#include "holder/holder.h"

#include <jni.h>

#include <memory>
#include <string>

namespace {

class UtfChars {
 public:
  UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
    if (value_ != nullptr) {
      chars_ = env_->GetStringUTFChars(value_, nullptr);
    }
  }

  UtfChars(const UtfChars&) = delete;
  UtfChars& operator=(const UtfChars&) = delete;

  ~UtfChars() {
    if (chars_ != nullptr) {
      env_->ReleaseStringUTFChars(value_, chars_);
    }
  }

  const char* get() const {
    return chars_;
  }

 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_ = nullptr;
};

void throw_runtime(JNIEnv* env, const std::string& message) {
  jclass exception_class = env->FindClass("java/lang/RuntimeException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message.c_str());
  }
}

std::string error_message(holder_error* error) {
  std::string message = holder_error_message(error);
  holder_error_destroy(error);
  return message;
}

jstring string_result(JNIEnv* env, char* value) {
  std::unique_ptr<char, decltype(&holder_string_free)> holder(value, holder_string_free);
  return env->NewStringUTF(holder.get());
}

holder_context* open_context_or_throw(JNIEnv* env, const char* data_dir, const char* schema_sql) {
  holder_context* context = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_context_open(data_dir, schema_sql, &context, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return context;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeVersion(JNIEnv* env, jobject /* thiz */) {
  return env->NewStringUTF(holder_version_string());
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeProjectList(
    JNIEnv* env,
    jobject /* thiz */,
    jstring data_dir,
    jstring schema_sql
) {
  UtfChars data_dir_chars(env, data_dir);
  UtfChars schema_sql_chars(env, schema_sql);
  if (data_dir_chars.get() == nullptr || schema_sql_chars.get() == nullptr) {
    throw_runtime(env, "data_dir and schema_sql must not be null");
    return nullptr;
  }

  std::unique_ptr<holder_context, decltype(&holder_context_destroy)> context(
      open_context_or_throw(env, data_dir_chars.get(), schema_sql_chars.get()),
      holder_context_destroy
  );
  if (context == nullptr) {
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_project_list(context.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardList(
    JNIEnv* env,
    jobject /* thiz */,
    jstring data_dir,
    jstring schema_sql,
    jstring project_id
) {
  UtfChars data_dir_chars(env, data_dir);
  UtfChars schema_sql_chars(env, schema_sql);
  UtfChars project_id_chars(env, project_id);
  if (data_dir_chars.get() == nullptr || schema_sql_chars.get() == nullptr ||
      project_id_chars.get() == nullptr) {
    throw_runtime(env, "data_dir, schema_sql, and project_id must not be null");
    return nullptr;
  }

  std::unique_ptr<holder_context, decltype(&holder_context_destroy)> context(
      open_context_or_throw(env, data_dir_chars.get(), schema_sql_chars.get()),
      holder_context_destroy
  );
  if (context == nullptr) {
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_list(context.get(), project_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}
