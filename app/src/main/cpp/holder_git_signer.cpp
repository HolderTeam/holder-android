// Bridges holder-core's git credential seam (holder_git_set_ssh_signer) to
// GitIdentity.signForNative on the Kotlin side, so a project's SSH auth can
// be backed by a non-exportable Android Keystore key instead of a file on
// disk. See holder/holder.h for the C ABI's exact ownership/threading
// contract this implements against.

#include "holder/holder.h"

#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

// Owns everything the sign/destroy callbacks below need for as long as
// holder-core keeps this signer installed -- which may span many git
// operations, on different threads, over the app's lifetime. Constructed in
// nativeRegisterSigner; destroyed exactly once by git_signer_destroy, per
// holder_git_set_ssh_signer's ownership contract.
struct GitSignerContext {
  JavaVM* jvm = nullptr;
  jobject git_identity_global_ref = nullptr;
  jmethodID sign_method = nullptr;
  std::string key_alias;
};

// JNIEnv* is thread-local and only valid on the thread that obtained it.
// sign_fn/destroy_user_data can run on any thread the host app performs a
// git operation on, so every call must fetch its own JNIEnv* -- attaching
// the thread if it isn't already attached to the JVM, and detaching again
// only if this call is the one that attached it (never detach a thread we
// didn't attach: it may belong to the JVM already).
JNIEnv* get_env_attaching_if_needed(JavaVM* jvm, bool* out_attached_here) {
  *out_attached_here = false;
  JNIEnv* env = nullptr;
  const jint rc = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (rc == JNI_OK) {
    return env;
  }
  if (rc == JNI_EDETACHED) {
    if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
      *out_attached_here = true;
      return env;
    }
  }
  return nullptr;
}

void detach_if_attached_here(JavaVM* jvm, bool attached_here) {
  if (attached_here) {
    jvm->DetachCurrentThread();
  }
}

int git_sign_fn(
    void* user_data,
    const unsigned char* data,
    size_t data_len,
    unsigned char** out_der_sig,
    size_t* out_der_sig_len
) {
  auto* ctx = static_cast<GitSignerContext*>(user_data);

  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) return -1;

  int result = -1;
  jbyteArray input = env->NewByteArray(static_cast<jsize>(data_len));
  if (input != nullptr) {
    env->SetByteArrayRegion(input, 0, static_cast<jsize>(data_len), reinterpret_cast<const jbyte*>(data));

    jstring alias = env->NewStringUTF(ctx->key_alias.c_str());
    if (alias != nullptr) {
      auto der_sig = static_cast<jbyteArray>(
          env->CallObjectMethod(ctx->git_identity_global_ref, ctx->sign_method, alias, input)
      );

      if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
      } else if (der_sig != nullptr) {
        const jsize len = env->GetArrayLength(der_sig);
        auto* buf = static_cast<unsigned char*>(malloc(static_cast<size_t>(len)));
        if (buf != nullptr) {
          env->GetByteArrayRegion(der_sig, 0, len, reinterpret_cast<jbyte*>(buf));
          *out_der_sig = buf;
          *out_der_sig_len = static_cast<size_t>(len);
          result = 0;
        }
      }
      if (der_sig != nullptr) env->DeleteLocalRef(der_sig);
      env->DeleteLocalRef(alias);
    }
    env->DeleteLocalRef(input);
  }

  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

void git_signer_destroy(void* user_data) {
  auto* ctx = static_cast<GitSignerContext*>(user_data);

  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env != nullptr) {
    env->DeleteGlobalRef(ctx->git_identity_global_ref);
    detach_if_attached_here(ctx->jvm, attached_here);
  }
  // If we couldn't even attach, the JVM is likely shutting down; leaking one
  // global ref in that case is preferable to crashing.

  delete ctx;
}

} // namespace

// Installs GitIdentity (the `this` receiving this call) as context's SSH
// signer: subsequent git operations on context will sign via
// GitIdentity.signForNative instead of the default ssh-agent/~/.ssh lookup.
// Also points libgit2 at homedir for ~/.ssh/known_hosts (see
// holder_git_set_homedir). Returns a HOLDER_* status code.
extern "C" JNIEXPORT jint JNICALL
Java_team_holder_android_git_GitIdentity_nativeRegisterSigner(
    JNIEnv* env,
    jobject thiz,
    jlong context_handle,
    jstring j_key_alias,
    jbyteArray j_public_key_blob,
    jstring j_homedir
) {
  if (context_handle == 0 || j_key_alias == nullptr || j_public_key_blob == nullptr ||
      j_homedir == nullptr) {
    return HOLDER_ERROR_INVALID_ARGUMENT;
  }
  auto* context = reinterpret_cast<holder_context*>(static_cast<intptr_t>(context_handle));

  {
    const char* homedir_chars = env->GetStringUTFChars(j_homedir, nullptr);
    holder_error* error = nullptr;
    const int rc = holder_git_set_homedir(homedir_chars, &error);
    env->ReleaseStringUTFChars(j_homedir, homedir_chars);
    if (error != nullptr) holder_error_destroy(error);
    if (rc != HOLDER_OK) return rc;
  }

  auto* ctx = new GitSignerContext();
  env->GetJavaVM(&ctx->jvm);
  ctx->git_identity_global_ref = env->NewGlobalRef(thiz);
  ctx->sign_method = env->GetMethodID(
      env->GetObjectClass(thiz),
      "signForNative",
      "(Ljava/lang/String;[B)[B"
  );
  if (ctx->sign_method == nullptr) {
    env->ExceptionClear();
    env->DeleteGlobalRef(ctx->git_identity_global_ref);
    delete ctx;
    return HOLDER_ERROR_RUNTIME;
  }

  const char* alias_chars = env->GetStringUTFChars(j_key_alias, nullptr);
  ctx->key_alias = alias_chars;
  env->ReleaseStringUTFChars(j_key_alias, alias_chars);

  const jsize pubkey_len = env->GetArrayLength(j_public_key_blob);
  std::vector<unsigned char> pubkey(static_cast<size_t>(pubkey_len));
  env->GetByteArrayRegion(j_public_key_blob, 0, pubkey_len, reinterpret_cast<jbyte*>(pubkey.data()));

  holder_error* error = nullptr;
  // Per holder_git_set_ssh_signer's contract, ctx is always released exactly
  // once from this point on -- by holder-core, regardless of the return
  // code -- so there is nothing to clean up here on failure.
  const int rc = holder_git_set_ssh_signer(
      context,
      "git",
      pubkey.data(),
      pubkey.size(),
      &git_sign_fn,
      ctx,
      &git_signer_destroy,
      &error
  );
  if (error != nullptr) holder_error_destroy(error);
  return rc;
}
