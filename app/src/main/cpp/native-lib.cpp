#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_cumulusia_app_ai_CumulusEngine_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Cumulus IA Engine - Native C++ Online";
    return env->NewStringUTF(hello.c_str());
}
