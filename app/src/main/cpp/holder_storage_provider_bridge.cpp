// Bridges holder-core's storage-provider seam (holder_storage_provider_register) to a Kotlin
// AndroidStorageProvider implementation for a single named provider (e.g. "google-drive") --
// the same JNI-callback shape holder_keyring_bridge.cpp uses for the platform keyring seam
// and holder_git_signer.cpp uses for the SSH signer. See holder/holder.h for the C ABI's
// exact ownership/threading contract this implements against.

#include "holder/holder.h"

#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>

namespace {

// Owns everything the put/get/exists/remove callbacks below need for as long as
// holder-core keeps this provider installed under its registered name -- which may span
// many storage operations, on different threads, over the app's lifetime. Constructed in
// nativeRegisterProvider; destroyed exactly once by storage_provider_destroy, per
// holder_storage_provider_register's ownership contract.
struct StorageProviderBridgeContext {
  JavaVM* jvm = nullptr;
  jobject provider_global_ref = nullptr;
  jmethodID put_method = nullptr;
  jmethodID get_method = nullptr;
  jmethodID exists_method = nullptr;
  jmethodID remove_method = nullptr;
};

// See holder_keyring_bridge.cpp's identical helper for why this dance is needed: JNIEnv* is
// thread-local, these callbacks can run on any thread over the provider's lifetime, and a
// thread must only be detached by whoever attached it.
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

// Kotlin's put/get/remove return null on success, or "<code>:<message>" on failure -- see
// AndroidStorageProvider's doc comment (StorageProvider.kt) for why this plain-string
// convention was chosen over a custom exception type. Splits that back out for the C
// callback's out_error_code/out_error pair; falls back to a generic code if the string
// doesn't parse, rather than failing the whole call over a malformed error report.
void report_failure(const std::string& encoded, int* out_error_code, char** out_error) {
  const auto separator = encoded.find(':');
  if (separator == std::string::npos) {
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup(encoded);
    return;
  }
  int code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
  try {
    code = std::stoi(encoded.substr(0, separator));
  } catch (...) {
  }
  *out_error_code = code;
  *out_error = malloc_dup(encoded.substr(separator + 1));
}

int storage_put(
    void* user_data,
    const char* object_key,
    const char* staged_file_path,
    long long stored_size,
    const char* stored_sha256,
    int* out_error_code,
    char** out_error
) {
  auto* ctx = static_cast<StorageProviderBridgeContext*>(user_data);
  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) {
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("could not attach to JVM");
    return -1;
  }

  int result = -1;
  jstring j_object_key = env->NewStringUTF(object_key != nullptr ? object_key : "");
  jstring j_staged_file_path = env->NewStringUTF(staged_file_path != nullptr ? staged_file_path : "");
  jstring j_sha256 = env->NewStringUTF(stored_sha256 != nullptr ? stored_sha256 : "");

  auto j_failure = static_cast<jstring>(env->CallObjectMethod(
      ctx->provider_global_ref, ctx->put_method, j_object_key, j_staged_file_path,
      static_cast<jlong>(stored_size), j_sha256
  ));

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("put threw an unexpected exception");
  } else if (j_failure != nullptr) {
    const char* chars = env->GetStringUTFChars(j_failure, nullptr);
    report_failure(std::string(chars), out_error_code, out_error);
    env->ReleaseStringUTFChars(j_failure, chars);
  } else {
    result = 0;
  }

  if (j_failure != nullptr) env->DeleteLocalRef(j_failure);
  env->DeleteLocalRef(j_sha256);
  env->DeleteLocalRef(j_staged_file_path);
  env->DeleteLocalRef(j_object_key);

  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

int storage_get(
    void* user_data,
    const char* object_key,
    const char* destination_file_path,
    int* out_error_code,
    char** out_error
) {
  auto* ctx = static_cast<StorageProviderBridgeContext*>(user_data);
  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) {
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("could not attach to JVM");
    return -1;
  }

  int result = -1;
  jstring j_object_key = env->NewStringUTF(object_key != nullptr ? object_key : "");
  jstring j_destination = env->NewStringUTF(destination_file_path != nullptr ? destination_file_path : "");

  auto j_failure = static_cast<jstring>(
      env->CallObjectMethod(ctx->provider_global_ref, ctx->get_method, j_object_key, j_destination)
  );

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("get threw an unexpected exception");
  } else if (j_failure != nullptr) {
    const char* chars = env->GetStringUTFChars(j_failure, nullptr);
    report_failure(std::string(chars), out_error_code, out_error);
    env->ReleaseStringUTFChars(j_failure, chars);
  } else {
    result = 0;
  }

  if (j_failure != nullptr) env->DeleteLocalRef(j_failure);
  env->DeleteLocalRef(j_destination);
  env->DeleteLocalRef(j_object_key);

  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

int storage_exists(
    void* user_data,
    const char* object_key,
    int* out_exists,
    int* out_error_code,
    char** out_error
) {
  auto* ctx = static_cast<StorageProviderBridgeContext*>(user_data);
  *out_exists = 0;
  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) {
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("could not attach to JVM");
    return -1;
  }

  int result = -1;
  jstring j_object_key = env->NewStringUTF(object_key != nullptr ? object_key : "");
  const jboolean j_exists = env->CallBooleanMethod(ctx->provider_global_ref, ctx->exists_method, j_object_key);

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("exists threw an unexpected exception");
  } else {
    *out_exists = j_exists ? 1 : 0;
    result = 0;
  }

  env->DeleteLocalRef(j_object_key);
  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

int storage_remove(void* user_data, const char* object_key, int* out_error_code, char** out_error) {
  auto* ctx = static_cast<StorageProviderBridgeContext*>(user_data);
  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env == nullptr) {
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("could not attach to JVM");
    return -1;
  }

  int result = -1;
  jstring j_object_key = env->NewStringUTF(object_key != nullptr ? object_key : "");
  auto j_failure =
      static_cast<jstring>(env->CallObjectMethod(ctx->provider_global_ref, ctx->remove_method, j_object_key));

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    *out_error_code = HOLDER_STORAGE_ERROR_UNAVAILABLE;
    *out_error = malloc_dup("remove threw an unexpected exception");
  } else if (j_failure != nullptr) {
    const char* chars = env->GetStringUTFChars(j_failure, nullptr);
    report_failure(std::string(chars), out_error_code, out_error);
    env->ReleaseStringUTFChars(j_failure, chars);
  } else {
    result = 0;
  }

  if (j_failure != nullptr) env->DeleteLocalRef(j_failure);
  env->DeleteLocalRef(j_object_key);

  detach_if_attached_here(ctx->jvm, attached_here);
  return result;
}

void storage_provider_destroy(void* user_data) {
  auto* ctx = static_cast<StorageProviderBridgeContext*>(user_data);

  bool attached_here = false;
  JNIEnv* env = get_env_attaching_if_needed(ctx->jvm, &attached_here);
  if (env != nullptr) {
    env->DeleteGlobalRef(ctx->provider_global_ref);
    detach_if_attached_here(ctx->jvm, attached_here);
  }
  // If we couldn't even attach, the JVM is likely shutting down; leaking one global ref in
  // that case is preferable to crashing.

  delete ctx;
}

} // namespace

// Installs `thiz` (an AndroidStorageProvider implementation) as holder-core's storage
// provider for `providerName` (e.g. "google-drive"). Returns a HOLDER_* status code.
extern "C" JNIEXPORT jint JNICALL
Java_team_holder_android_resource_AndroidStorageProviderBridge_nativeRegisterProvider(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring providerName,
    jobject provider
) {
  auto* ctx = new StorageProviderBridgeContext();
  env->GetJavaVM(&ctx->jvm);
  ctx->provider_global_ref = env->NewGlobalRef(provider);

  jclass clazz = env->GetObjectClass(provider);
  ctx->put_method = env->GetMethodID(
      clazz, "put", "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)Ljava/lang/String;"
  );
  ctx->get_method =
      env->GetMethodID(clazz, "get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
  ctx->exists_method = env->GetMethodID(clazz, "exists", "(Ljava/lang/String;)Z");
  ctx->remove_method = env->GetMethodID(clazz, "remove", "(Ljava/lang/String;)Ljava/lang/String;");

  if (ctx->put_method == nullptr || ctx->get_method == nullptr || ctx->exists_method == nullptr ||
      ctx->remove_method == nullptr) {
    env->ExceptionClear();
    env->DeleteGlobalRef(ctx->provider_global_ref);
    delete ctx;
    return HOLDER_ERROR_RUNTIME;
  }

  const char* provider_name_chars = env->GetStringUTFChars(providerName, nullptr);
  holder_error* error = nullptr;
  // Per holder_storage_provider_register's contract, ctx is always released exactly once
  // from this point on -- by holder-core, regardless of the return code -- so there is
  // nothing to clean up here on failure.
  const int rc = holder_storage_provider_register(
      provider_name_chars, &storage_put, &storage_get, &storage_exists, &storage_remove, ctx,
      &storage_provider_destroy, &error
  );
  env->ReleaseStringUTFChars(providerName, provider_name_chars);
  if (error != nullptr) holder_error_destroy(error);
  return rc;
}
