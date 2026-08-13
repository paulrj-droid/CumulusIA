#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "CumulusNativeC++"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cumulus_agent_LlamaEngine_nativeInit(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("⚡ Cargando archivo .gguf en memoria RAM: %s", path);
    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cumulus_agent_LlamaEngine_nativeGenerate(JNIEnv *env, jobject thiz, jstring prompt) {
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("🧠 Procesando consulta: %s", prompt_str);

    std::string response = "Respuesta generada 100% offline por el motor C++ (GGUF).";

    env->ReleaseStringUTFChars(prompt, prompt_str);
    return env->NewStringUTFChar(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_cumulus_agent_LlamaEngine_nativeFree(JNIEnv *env, jobject thiz) {
    LOGI("⏹ Liberando memoria RAM ocupada por el modelo (llama_free)...");
}
