#include "holder/holder.h"

#include <jni.h>

#include <cstdint>
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

// Handles are opaque holder_context* values owned by HolderNative for the
// lifetime of the app; see nativeContextOpen/nativeContextClose.
holder_context* context_from_handle(JNIEnv* env, jlong context_handle) {
  if (context_handle == 0) {
    throw_runtime(env, "holder context is not open");
    return nullptr;
  }
  return reinterpret_cast<holder_context*>(static_cast<intptr_t>(context_handle));
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeVersion(JNIEnv* env, jobject /* thiz */) {
  return env->NewStringUTF(holder_version_string());
}

extern "C" JNIEXPORT jlong JNICALL
Java_team_holder_android_HolderNative_nativeContextOpen(
    JNIEnv* env,
    jobject /* thiz */,
    jstring data_dir,
    jstring schema_sql
) {
  UtfChars data_dir_chars(env, data_dir);
  UtfChars schema_sql_chars(env, schema_sql);
  if (data_dir_chars.get() == nullptr || schema_sql_chars.get() == nullptr) {
    throw_runtime(env, "data_dir and schema_sql must not be null");
    return 0;
  }

  holder_context* context = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_context_open(data_dir_chars.get(), schema_sql_chars.get(), &context, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return 0;
  }
  return static_cast<jlong>(reinterpret_cast<intptr_t>(context));
}

extern "C" JNIEXPORT void JNICALL
Java_team_holder_android_HolderNative_nativeContextClose(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong context_handle
) {
  if (context_handle == 0) {
    return;
  }
  holder_context_destroy(reinterpret_cast<holder_context*>(static_cast<intptr_t>(context_handle)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeProjectList(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_project_list(context, &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeProjectCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring name,
    jstring root_path,
    jstring privacy_mode
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars name_chars(env, name);
  UtfChars root_path_chars(env, root_path);
  UtfChars privacy_mode_chars(env, privacy_mode);
  if (name_chars.get() == nullptr) {
    throw_runtime(env, "name must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_project_create(
      context,
      name_chars.get(),
      root_path_chars.get(),
      privacy_mode_chars.get(),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring title,
    jstring content,
    jstring parent_card_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars title_chars(env, title);
  UtfChars content_chars(env, content);
  UtfChars parent_card_id_chars(env, parent_card_id);
  if (project_id_chars.get() == nullptr || title_chars.get() == nullptr) {
    throw_runtime(env, "project_id and title must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_create(
      context,
      project_id_chars.get(),
      title_chars.get(),
      content_chars.get(),
      parent_card_id_chars.get(),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeEnsureDefaultProject(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring name,
    jstring welcome_title,
    jstring welcome_content
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars name_chars(env, name);
  UtfChars welcome_title_chars(env, welcome_title);
  UtfChars welcome_content_chars(env, welcome_content);
  if (name_chars.get() == nullptr || welcome_title_chars.get() == nullptr) {
    throw_runtime(env, "name and welcome_title must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_ensure_default_project(
      context,
      name_chars.get(),
      welcome_title_chars.get(),
      welcome_content_chars.get(),
      &json,
      &error
  );
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
    jlong context_handle,
    jstring project_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_list(context, project_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardGetContent(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring card_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars card_id_chars(env, card_id);
  if (card_id_chars.get() == nullptr) {
    throw_runtime(env, "card_id must not be null");
    return nullptr;
  }

  char* content = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_get_content(context, card_id_chars.get(), &content, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, content);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeProjectRename(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring name
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars name_chars(env, name);
  if (project_id_chars.get() == nullptr || name_chars.get() == nullptr) {
    throw_runtime(env, "project_id and name must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_project_rename(context, project_id_chars.get(), name_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_team_holder_android_HolderNative_nativeProjectDelete(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return;
  }

  UtfChars project_id_chars(env, project_id);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return;
  }

  holder_error* error = nullptr;
  const int rc = holder_project_delete(context, project_id_chars.get(), &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardUpdateContent(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring card_id,
    jstring content,
    jstring title
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars card_id_chars(env, card_id);
  UtfChars content_chars(env, content);
  UtfChars title_chars(env, title);
  if (card_id_chars.get() == nullptr || content_chars.get() == nullptr) {
    throw_runtime(env, "card_id and content must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_update_content(
      context,
      card_id_chars.get(),
      content_chars.get(),
      title_chars.get(),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_team_holder_android_HolderNative_nativeCardDelete(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring card_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return;
  }

  UtfChars card_id_chars(env, card_id);
  if (card_id_chars.get() == nullptr) {
    throw_runtime(env, "card_id must not be null");
    return;
  }

  holder_error* error = nullptr;
  const int rc = holder_card_delete(context, card_id_chars.get(), &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardListTrashed(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_list_trashed(context, project_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardRestore(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring card_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars card_id_chars(env, card_id);
  if (card_id_chars.get() == nullptr) {
    throw_runtime(env, "card_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_restore(context, card_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_team_holder_android_HolderNative_nativeCardPurge(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring card_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return;
  }

  UtfChars card_id_chars(env, card_id);
  if (card_id_chars.get() == nullptr) {
    throw_runtime(env, "card_id must not be null");
    return;
  }

  holder_error* error = nullptr;
  const int rc = holder_card_purge(context, card_id_chars.get(), &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardListLinks(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring card_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars card_id_chars(env, card_id);
  if (card_id_chars.get() == nullptr) {
    throw_runtime(env, "card_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_list_links(context, card_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardLinkAdd(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring from_card_id,
    jstring to_card_id,
    jstring kind,
    jstring label
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars from_card_id_chars(env, from_card_id);
  UtfChars to_card_id_chars(env, to_card_id);
  UtfChars kind_chars(env, kind);
  UtfChars label_chars(env, label);
  if (from_card_id_chars.get() == nullptr || to_card_id_chars.get() == nullptr ||
      kind_chars.get() == nullptr) {
    throw_runtime(env, "from_card_id, to_card_id, and kind must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_link_add(
      context,
      from_card_id_chars.get(),
      to_card_id_chars.get(),
      kind_chars.get(),
      label_chars.get(),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_team_holder_android_HolderNative_nativeCardLinkRemove(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring from_card_id,
    jstring to_card_id,
    jstring kind
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return;
  }

  UtfChars from_card_id_chars(env, from_card_id);
  UtfChars to_card_id_chars(env, to_card_id);
  UtfChars kind_chars(env, kind);
  if (from_card_id_chars.get() == nullptr || to_card_id_chars.get() == nullptr ||
      kind_chars.get() == nullptr) {
    throw_runtime(env, "from_card_id, to_card_id, and kind must not be null");
    return;
  }

  holder_error* error = nullptr;
  const int rc = holder_card_link_remove(
      context, from_card_id_chars.get(), to_card_id_chars.get(), kind_chars.get(), &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeCardSearch(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring query,
    jint limit,
    jint offset
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars query_chars(env, query);
  if (project_id_chars.get() == nullptr || query_chars.get() == nullptr) {
    throw_runtime(env, "project_id and query must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_card_search(
      context,
      project_id_chars.get(),
      query_chars.get(),
      static_cast<int>(limit),
      static_cast<int>(offset),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeProjectUpdateGitRemote(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring remote_url
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars remote_url_chars(env, remote_url);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_project_update_git_remote(
      context,
      project_id_chars.get(),
      remote_url_chars.get(),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeGitTestRemote(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring branch
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars branch_chars(env, branch);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc =
      holder_git_test_remote(context, project_id_chars.get(), branch_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeGitPush(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring branch,
    jboolean set_upstream
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars branch_chars(env, branch);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_git_push(
      context,
      project_id_chars.get(),
      branch_chars.get(),
      set_upstream == JNI_TRUE ? 1 : 0,
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeGitPull(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_git_pull(context, project_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeGitSyncStatus(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_git_sync_status(context, project_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeGitSyncIfDue(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jint push_interval_seconds,
    jint pull_interval_seconds
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_git_sync_if_due(
      context,
      project_id_chars.get(),
      static_cast<int>(push_interval_seconds),
      static_cast<int>(pull_interval_seconds),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeEncryptionCheck(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  if (project_id_chars.get() == nullptr) {
    throw_runtime(env, "project_id must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_encryption_check(context, project_id_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeRecoveryTokenExport(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring pin
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars pin_chars(env, pin);
  if (project_id_chars.get() == nullptr || pin_chars.get() == nullptr) {
    throw_runtime(env, "project_id and pin must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc =
      holder_recovery_token_export(context, project_id_chars.get(), pin_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeRecoveryTokenImport(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring project_id,
    jstring pin,
    jstring recovery_token
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars project_id_chars(env, project_id);
  UtfChars pin_chars(env, pin);
  UtfChars recovery_token_chars(env, recovery_token);
  if (project_id_chars.get() == nullptr || pin_chars.get() == nullptr ||
      recovery_token_chars.get() == nullptr) {
    throw_runtime(env, "project_id, pin, and recovery_token must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_recovery_token_import(
      context,
      project_id_chars.get(),
      pin_chars.get(),
      recovery_token_chars.get(),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeRecoveryTokenInspect(
    JNIEnv* env,
    jobject /* thiz */,
    jstring pin,
    jstring recovery_token
) {
  UtfChars pin_chars(env, pin);
  UtfChars recovery_token_chars(env, recovery_token);
  if (pin_chars.get() == nullptr || recovery_token_chars.get() == nullptr) {
    throw_runtime(env, "pin and recovery_token must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc =
      holder_recovery_token_inspect(pin_chars.get(), recovery_token_chars.get(), &json, &error);
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeRecoveryTokenImportGlobal(
    JNIEnv* env,
    jobject /* thiz */,
    jlong context_handle,
    jstring pin,
    jstring recovery_token
) {
  holder_context* context = context_from_handle(env, context_handle);
  if (context == nullptr) {
    return nullptr;
  }

  UtfChars pin_chars(env, pin);
  UtfChars recovery_token_chars(env, recovery_token);
  if (pin_chars.get() == nullptr || recovery_token_chars.get() == nullptr) {
    throw_runtime(env, "pin and recovery_token must not be null");
    return nullptr;
  }

  char* json = nullptr;
  holder_error* error = nullptr;
  const int rc = holder_recovery_token_import_global(
      context,
      pin_chars.get(),
      recovery_token_chars.get(),
      &json,
      &error
  );
  if (rc != HOLDER_OK) {
    throw_runtime(env, error_message(error));
    return nullptr;
  }
  return string_result(env, json);
}
