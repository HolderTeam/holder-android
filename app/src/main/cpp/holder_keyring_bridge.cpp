// Bridges holder-core's platform keyring seam (holder_keyring_set_provider) to
// AndroidKeyringStore on the Kotlin side, backed by Keystore-wrapped
// EncryptedSharedPreferences -- Android's substitute for the desktop platform
// keyrings (libsecret/Keychain/Windows Credential Manager) holder-core otherwise
// relies on for encrypted_git project keys and other secrets. See holder/holder.h
// for the C ABI's exact ownership/threading contract this implements against; it
// is the same contract holder_git_signer.cpp implements for the SSH signer.

#include "holder/holder.h"

#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>

namespace {

// Owns everything the lookup/store/remove/destroy callbacks below need for as
// long as holder-core keeps this provider installed -- which may span many
// keyring operations, on different threads, over the app's lifetime.
// Constructed in nativeRegisterProvider; destroyed exactly once by
// keyring_destroy, per holder_keyring_set_provider's ownership contract.
struct KeyringBridgeContext {
  JavaVM* jvm = nullptr;
  jobject store_global_ref = nullptr;
  jmethodID lookup_method = nullptr;
  jmethodID store_method = nullptr;
  jmethodID remove_method = nullptr;
};

// See holder_git_signer.cpp's identical helper for why this dance is needed:
// JNIEnv* is thread-local, these callbacks can run on any thread over the
// provider's lifetime, and a thread must only be detached by whoever attached
// it.
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

char* malloc_dup(const std::string& value) {
  auto* buf = static_cast<char*>(malloc(value.size() + 1));
  if (buf == nullptr) return nullptr;
  memcpy(buf, value.c_str(), value.size() + 1);
  return buf;
}

int keyring_lookup(
    void* user_data,
    int kind,
    const char* service,
    const char* account,
    const char* project_id,
    int* out_found,
    char** out_secret,
    char** /*out_error*/
) {
  auto* ctx = static_cast<KeyringBridgeContext*>(user_data);
  *out_found = 0;

  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) return -1;

  int result = -1;
  jstring j_service = env->NewStringUTF(service != nullptr ? service : "");
  jstring j_account = env->NewStringUTF(account != nullptr ? account : "");
  jstring j_project_id = project_id != nullptr ? env->NewStringUTF(project_id) : nullptr;

  auto j_secret = static_cast<jstring>(env->CallObjectMethod(
      ctx->store_global_ref,
      ctx->lookup_method,
      static_cast<jint>(kind),
      j_service,
      j_account,
      j_project_id
  ));

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  } else {
    result = 0;
    if (j_secret != nullptr) {
      const char* chars = env->GetStringUTFChars(j_secret, nullptr);
      *out_secret = malloc_dup(std::string(chars));
      *out_found = 1;
      env->ReleaseStringUTFChars(j_secret, chars);
    }
  }

  if (j_secret != nullptr) env->DeleteLocalRef(j_secret);
  if (j_project_id != nullptr) env->DeleteLocalRef(j_project_id);
  env->DeleteLocalRef(j_account);
  env->DeleteLocalRef(j_service);

  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

int keyring_store(
    void* user_data,
    int kind,
    const char* service,
    const char* account,
    const char* project_id,
    const char* /*label*/,
    const char* secret,
    char** /*out_error*/
) {
  auto* ctx = static_cast<KeyringBridgeContext*>(user_data);

  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) return -1;

  int result = -1;
  jstring j_service = env->NewStringUTF(service != nullptr ? service : "");
  jstring j_account = env->NewStringUTF(account != nullptr ? account : "");
  jstring j_project_id = project_id != nullptr ? env->NewStringUTF(project_id) : nullptr;
  jstring j_secret = env->NewStringUTF(secret != nullptr ? secret : "");

  env->CallVoidMethod(
      ctx->store_global_ref,
      ctx->store_method,
      static_cast<jint>(kind),
      j_service,
      j_account,
      j_project_id,
      j_secret
  );

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  } else {
    result = 0;
  }

  env->DeleteLocalRef(j_secret);
  if (j_project_id != nullptr) env->DeleteLocalRef(j_project_id);
  env->DeleteLocalRef(j_account);
  env->DeleteLocalRef(j_service);

  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

int keyring_remove(
    void* user_data,
    int kind,
    const char* service,
    const char* account,
    const char* project_id,
    char** /*out_error*/
) {
  auto* ctx = static_cast<KeyringBridgeContext*>(user_data);

  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) return -1;

  int result = -1;
  jstring j_service = env->NewStringUTF(service != nullptr ? service : "");
  jstring j_account = env->NewStringUTF(account != nullptr ? account : "");
  jstring j_project_id = project_id != nullptr ? env->NewStringUTF(project_id) : nullptr;

  env->CallVoidMethod(
      ctx->store_global_ref,
      ctx->remove_method,
      static_cast<jint>(kind),
      j_service,
      j_account,
      j_project_id
  );

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  } else {
    result = 0;
  }

  if (j_project_id != nullptr) env->DeleteLocalRef(j_project_id);
  env->DeleteLocalRef(j_account);
  env->DeleteLocalRef(j_service);

  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

void keyring_destroy(void* user_data) {
  auto* ctx = static_cast<KeyringBridgeContext*>(user_data);

  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env != nullptr) {
    env->DeleteGlobalRef(ctx->store_global_ref);
    detach_if_attached_here(ctx->jvm, attached_here);
  }
  // If we couldn't even attach, the JVM is likely shutting down; leaking one
  // global ref in that case is preferable to crashing.

  delete ctx;
}

} // namespace

// Installs AndroidKeyringStore (the `this` receiving this call) as holder-core's
// process-wide platform keyring provider. Returns a HOLDER_* status code.
extern "C" JNIEXPORT jint JNICALL
Java_team_holder_android_keyring_AndroidKeyringStore_nativeRegisterProvider(
    JNIEnv* env,
    jobject thiz
) {
  auto* ctx = new KeyringBridgeContext();
  env->GetJavaVM(&ctx->jvm);
  ctx->store_global_ref = env->NewGlobalRef(thiz);

  jclass clazz = env->GetObjectClass(thiz);
  ctx->lookup_method = env->GetMethodID(
      clazz,
      "lookup",
      "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
  );
  ctx->store_method = env->GetMethodID(
      clazz,
      "store",
      "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
  );
  ctx->remove_method =
      env->GetMethodID(clazz, "remove", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

  if (ctx->lookup_method == nullptr || ctx->store_method == nullptr || ctx->remove_method == nullptr) {
    env->ExceptionClear();
    env->DeleteGlobalRef(ctx->store_global_ref);
    delete ctx;
    return HOLDER_ERROR_RUNTIME;
  }

  holder_error* error = nullptr;
  // Per holder_keyring_set_provider's contract, ctx is always released exactly
  // once from this point on -- by holder-core, regardless of the return code --
  // so there is nothing to clean up here on failure.
  const int rc =
      holder_keyring_set_provider(&keyring_lookup, &keyring_store, &keyring_remove, ctx, &keyring_destroy, &error);
  if (error != nullptr) holder_error_destroy(error);
  return rc;
}
