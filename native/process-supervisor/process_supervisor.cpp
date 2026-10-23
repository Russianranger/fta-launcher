#include "process_owner.h"
#include <jni.h>
#include <exception>
#include <stdexcept>

namespace {
std::string string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value,nullptr);
    if (!chars) throw std::runtime_error("Cannot read process argument");
    std::string result(chars); env->ReleaseStringUTFChars(value,chars); return result;
}
std::vector<std::string> strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (!values || env->GetArrayLength(values) > 128) throw std::runtime_error("Invalid process argument list");
    for (jsize i=0;i<env->GetArrayLength(values);++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values,i));
        result.push_back(string(env,value)); env->DeleteLocalRef(value);
    }
    return result;
}
void error(JNIEnv* env, const std::exception& problem) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),problem.what());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_russianranger_fta_runtime_NativeProcessBridge_start(JNIEnv* env,jobject,jstring executable,jobjectArray args,jobjectArray environment,jstring cwd,jstring input,jstring output,jstring errors) {
    try { return fta::start({string(env,executable),strings(env,args),strings(env,environment),string(env,cwd),string(env,input),string(env,output),string(env,errors)}); }
    catch (const std::exception& problem) { error(env,problem); return 0; }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_russianranger_fta_runtime_NativeProcessBridge_processId(JNIEnv* env,jobject,jlong handle) {
    try { return fta::process_id(handle); }
    catch (const std::exception& problem) { error(env,problem); return -1; }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_russianranger_fta_runtime_NativeProcessBridge_waitFor(JNIEnv* env,jobject,jlong handle,jlong timeout) {
    try { return fta::wait_for(handle,timeout); }
    catch (const std::exception& problem) { error(env,problem); return fta::running; }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_russianranger_fta_runtime_NativeProcessBridge_stop(JNIEnv* env,jobject,jlong handle,jlong grace) {
    try { return fta::stop(handle,grace); }
    catch (const std::exception& problem) { error(env,problem); return fta::running; }
}
