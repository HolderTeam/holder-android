#include "holder/holder.h"

#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_team_holder_android_HolderNative_nativeVersion(JNIEnv* env, jobject /* thiz */) {
  return env->NewStringUTF(holder_version_string());
}
