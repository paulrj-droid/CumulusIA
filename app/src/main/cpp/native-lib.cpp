#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>
#include <cstdio>
#include <mutex>
#include <chrono>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/system_properties.h>
#include "llama.h"

// ✅ PROTEGIDO: ggml-backend.h solo existe en tags nuevos de llama.cpp
#ifdef CUMULUS_HAS_GGML_BACKEND
#include "ggml-backend.h"
#endif

#define LOG_TAG "CumulusNativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static llama_sampler* g_sampler = nullptr;
static bool g_modelLoaded  = false;
static bool g_backendInited = false;
static bool g_lastUsedGpu  = false;
static bool g_npuRequested = false;
static std::string g_backend = "💻 CPU (NEON)";
static int   g_nCtx  = 1024;
static float g_temp  = 0.7f;
static std::mutex g_mutex;

static std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* c = env->GetStringUTFChars(jstr, nullptr);
    if (c == nullptr) return "";
    std::string result = c;
    env->ReleaseStringUTFChars(jstr, c);
    return result;
}

// ============================================================================
// 🔍 DETECCIÓN REAL DE NPU HEXAGON (Qualcomm)
// ============================================================================
struct NpuStatus {
    bool hardware = false;
    bool runtime  = false;
    std::string soc;
};

static std::string getSocModel() {
    char val[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.soc.model", val) > 0 && val[0] != '\0') return std::string(val);
    char val2[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.board.platform", val2) > 0 && val2[0] != '\0') return std::string(val2);
    return "";
}

static bool isSnapdragonNpuSoc(const std::string& soc) {
    static const char* KNOWN[] = {
        "SM8750", "SM8650", "SM8550", "SM8450", "SM8350",
        "SM7675", "SM7550", "SM7450",
        "SDM8", "Snapdragon", "snapdragon", "Qualcomm", "qualcomm"
    };
    for (const char* k : KNOWN) {
        if (soc.find(k) != std::string::npos) return true;
    }
    return false;
}

static bool hasFastRpcDevice() {
    return access("/dev/adsprpc-smd", F_OK) == 0 ||
           access("/dev/adsprpc0", F_OK) == 0 ||
           access("/dev/adsprpc1", F_OK) == 0 ||
           access("/dev/adsprpc2", F_OK) == 0;
}

static bool hasQnnRuntime() {
    static const char* LIBS[] = {
        "libQnnHtp.so", "libQnnDsp.so", "libQnnSystem.so",
        "/vendor/lib64/libQnnHtp.so", "/vendor/lib64/libQnnSystem.so"
    };
    for (const char* l : LIBS) {
        void* h = dlopen(l, RTLD_NOW | RTLD_LOCAL);
        if (h) { dlclose(h); return true; }
    }
    return false;
}

static NpuStatus detectNpu() {
    NpuStatus s;
    s.soc = getSocModel();
    bool rpc = hasFastRpcDevice();
    s.hardware = rpc && (s.soc.empty() || isSnapdragonNpuSoc(s.soc));
    s.runtime = hasQnnRuntime();
    LOGI("🔍 Detección NPU | SoC=%s | FastRPC=%s | QNN=%s",
         s.soc.empty() ? "?" : s.soc.c_str(),
         rpc ? "SÍ" : "NO",
         s.runtime ? "SÍ" : "NO");
    return s;
}

// ✅ PROTEGIDO: solo si ggml-backend.h está disponible
static void logAvailableBackends() {
#ifdef CUMULUS_HAS_GGML_BACKEND
    size_t n = ggml_backend_dev_count();
    LOGI("Backends ggml registrados: %zu", n);
    for (size_t i = 0; i < n; i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        LOGI("   [%zu] %s | tipo=%d | %s", i,
             ggml_backend_dev_name(d),
             (int)ggml_backend_dev_type(d),
             ggml_backend_dev_description(d));
    }
#else
    LOGI("Backends ggml: ggml-backend.h no disponible en este build (tag viejo)");
#endif
}

static bool validateGgufFile(const std::string& path) {
    if (path.empty()) return false;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) { LOGE("No se pudo abrir: %s", path.c_str()); return false; }
    char magic[4] = {0,0,0,0};
    size_t r = fread(magic, 1, 4, f);
    fclose(f);
    if (r != 4) return false;
    return magic[0]=='G' && magic[1]=='G' && magic[2]=='U' && magic[3]=='F';
}

static void freeEngine() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model = nullptr; }
    g_modelLoaded = false;
    LOGD("Motor liberado");
}

// ✅ COMPATIBILIDAD DE API: llama_load_model_from_file fue renombrada a
//    llama_model_load_from_file en el PR #11063 de llama.cpp.
//    Tags nuevos (ej. b9222) usan la nueva API; tags viejos (ej. b4618) usan la vieja.
static llama_model* loadModelFile(const char* path, const llama_model_params& params) {
#ifdef CUMULUS_USE_NEW_LOAD_API
    return llama_model_load_from_file(path, params);
#else
    return llama_load_model_from_file(path, params);
#endif
}

// ============================================================================
// Carga del modelo con enrutado CPU / GPU / NPU
// ============================================================================
static jboolean loadModelLocked(const std::string& path) {
    auto t0 = std::chrono::steady_clock::now();

    LOGI("═══════════════════════════════════════════════════");
    LOGI("Cargando modelo: %s", path.c_str());
    LOGI("Backend seleccionado: %s", g_backend.c_str());
    LOGI("Parámetros: ctx=%d, temp=%.2f", g_nCtx, g_temp);
    LOGI("═══════════════════════════════════════════════════");

    if (!validateGgufFile(path)) {
        LOGE("❌ Archivo GGUF inválido");
        return JNI_FALSE;
    }

    freeEngine();

    if (!g_backendInited) {
        llama_backend_init();
        g_backendInited = true;
        logAvailableBackends();
    }

    llama_model_params mparams = llama_model_default_params();

    const bool npuReq = (g_backend.find("NPU") != std::string::npos);
    const bool gpuReq = (g_backend.find("GPU") != std::string::npos);
    g_npuRequested = npuReq;

    int gpuLayers = 0;

    if (npuReq) {
        NpuStatus st = detectNpu();
#ifdef CUMULUS_HAS_HEXAGON
        if (st.hardware) {
            gpuLayers = 999;
            LOGI("⚡ Enrutando TODAS las capas al Hexagon NPU (HTP0)");
        } else {
            LOGE("⚡ NPU solicitada pero este SoC no expone Hexagon → fallback");
        }
#else
        LOGI("⚡ NPU solicitada, pero este build aún no incluye GGML_HEXAGON");
#endif
        if (gpuLayers == 0) {
#ifdef CUMULUS_HAS_VULKAN
            gpuLayers = 999;
            LOGI("⚡→ Fallback: NPU se ejecuta en GPU (Vulkan)");
#else
            LOGI("⚡→💻 Fallback: NPU se ejecuta en CPU");
#endif
        }
    } else if (gpuReq) {
#ifdef CUMULUS_HAS_VULKAN
        gpuLayers = 999;
        LOGI("🎮 Offloading completo al GPU (Vulkan)");
#else
        LOGE("⚠️ Usuario pidió GPU pero Vulkan NO está compilado, usando CPU");
#endif
    } else {
        LOGI("💻 Configurando para CPU solamente");
    }

    mparams.n_gpu_layers = gpuLayers;
    bool usingGpu = (gpuLayers > 0);

    LOGI("Cargando modelo en memoria...");
    // ✅ Usa el adaptador que detecta automáticamente la API correcta del tag
    g_model = loadModelFile(path.c_str(), mparams);
    if (!g_model && usingGpu) {
        LOGE("⚠️ Falló la carga GPU; reintentando el mismo GGUF en CPU");
        mparams.n_gpu_layers = 0;
        usingGpu = false;
        g_model = loadModelFile(path.c_str(), mparams);
    }
    if (!g_model) {
        LOGE("❌ Error al cargar modelo: arquitectura no soportada o archivo corrupto");
        return JNI_FALSE;
    }
    LOGI("✅ Modelo cargado en memoria");

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)std::max(256, std::min(8192, g_nCtx));

    unsigned hw = std::thread::hardware_concurrency();
    unsigned availableThreads = hw > 1 ? hw - 1 : 2;
    int threads = (int)std::min(8u, std::max(2u, availableThreads));
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    LOGI("Usando %d threads", threads);

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx && usingGpu) {
        LOGE("⚠️ Falló el contexto Vulkan; reintentando modelo y contexto en CPU");
        llama_model_free(g_model);
        g_model = nullptr;
        mparams.n_gpu_layers = 0;
        usingGpu = false;
        g_model = loadModelFile(path.c_str(), mparams);
        if (g_model) g_ctx = llama_init_from_model(g_model, cparams);
    }
    if (!g_ctx) {
        LOGE("❌ No se pudo crear el contexto");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    g_lastUsedGpu = usingGpu;

    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_temp));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g_modelLoaded = true;
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();

    LOGI("═══════════════════════════════════════════════════");
    LOGI("✅ MODELO CARGADO en %ld ms", (long)ms);
    LOGI("Hardware real: %s",
         g_npuRequested ? (g_lastUsedGpu ? "⚡ NPU/HTP (o fallback GPU)" : "⚡→💻 fallback CPU")
                        : (g_lastUsedGpu ? "🎮 GPU (Vulkan)" : "💻 CPU (NEON)"));
    LOGI("═══════════════════════════════════════════════════");
    return JNI_TRUE;
}

static jboolean loadModelCommon(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return loadModelLocked(path);
}

static jboolean configureAndLoadModelCommon(
        const std::string& path,
        const std::string& backend,
        int ctx,
        float temp) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_backend = backend;
    g_nCtx = ctx;
    g_temp = temp;
    LOGI("Configuración atómica: backend=%s, ctx=%d, temp=%.2f",
         g_backend.c_str(), g_nCtx, g_temp);
    return loadModelLocked(path);
}

static jstring runInferenceCommon(JNIEnv* env, const std::string& prompt) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_modelLoaded || !g_ctx || !g_model || !g_sampler) {
        LOGE("Intento de inferencia sin modelo cargado");
        return env->NewStringUTF("⚠️ Motor sin modelo cargado todavía.");
    }

    auto t0 = std::chrono::steady_clock::now();
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t nCtx = llama_n_ctx(g_ctx);
    const int maxGen = 256;

    const std::string formattedPrompt =
        "### Sistema:\n"
        "Eres Cumulus IA, un asistente útil, claro y conversacional. "
        "Responde en el idioma del usuario.\n\n"
        "### Usuario:\n" + prompt + "\n\n### Asistente:\n";
    std::vector<llama_token> tokens((size_t)nCtx);
    int n = llama_tokenize(vocab, formattedPrompt.c_str(), (int32_t)formattedPrompt.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
    if (n <= 0) {
        LOGE("Error al tokenizar prompt");
        return env->NewStringUTF("⚠️ No se pudo procesar el mensaje.");
    }
    if ((uint32_t)n + 1 >= nCtx) {
        LOGE("Prompt demasiado largo: %d tokens", n);
        return env->NewStringUTF("⚠️ Mensaje demasiado largo para el contexto.");
    }
    const int generationLimit = std::min(maxGen, (int)nCtx - n - 1);
    tokens.resize(n);

    llama_kv_cache_clear(g_ctx);

    llama_batch batch = llama_batch_get_one(tokens.data(), n);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Error en llama_decode");
        return env->NewStringUTF("⚠️ Error del motor al leer el mensaje.");
    }

    std::string result;
    char buf[128];
    for (int i = 0; i < generationLimit; i++) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (tok < 0) break;
        if (llama_vocab_is_eog(vocab, tok)) break;
        int32_t written = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (written <= 0) break;
        result.append(buf, (size_t)written);

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, next) != 0) break;
    }

    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    LOGI("Inferencia en %ld ms: '%s'", (long)ms, result.c_str());

    if (result.empty()) result = "(el modelo no generó respuesta)";
    return env->NewStringUTF(result.c_str());
}

// ============================================================================
// Helpers comunes usados por MainActivity
// ============================================================================
static void setBackendCommon(const std::string& backend) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_backend = backend;
    LOGI("═══════════════════════════════════════════════════");
    LOGI("🔧 Backend cambiado a: %s", g_backend.c_str());
    LOGI("═══════════════════════════════════════════════════");
}

static void setParamsCommon(int ctx, float temp) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_nCtx = ctx;
    g_temp = temp;
    LOGI("Parámetros actualizados: ctx=%d, temp=%.2f", g_nCtx, g_temp);
}

static jboolean isModelLoadedCommon() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_modelLoaded && g_model && g_ctx && g_sampler) ? JNI_TRUE : JNI_FALSE;
}

static std::string engineInfoString() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_npuRequested) {
        if (g_lastUsedGpu) {
#ifdef CUMULUS_HAS_HEXAGON
            return "⚡ NPU (Hexagon)";
#else
            return "⚡ NPU→🎮 GPU (fallback)";
#endif
        }
        return "⚡ NPU→💻 CPU (fallback)";
    }
    if (g_lastUsedGpu) return "🎮 GPU (Vulkan)";
    return "💻 CPU (NEON)";
}

static std::string deviceCapsString() {
    NpuStatus npu = detectNpu();
    std::string caps = "💻 CPU: ✅ | ";
#ifdef CUMULUS_HAS_VULKAN
    caps += "🎮 GPU (Vulkan): ✅ | ";
#else
    caps += "🎮 GPU (Vulkan): ❌ | ";
#endif
#ifdef CUMULUS_HAS_HEXAGON
    caps += "⚡ NPU (Hexagon): ✅ build con ggml-hexagon";
#else
    if (npu.hardware) {
        caps += "⚡ NPU (Hexagon): ✅ " + npu.soc + " (build NPU pendiente: Ronda 3)";
    } else {
        caps += "⚡ NPU (Hexagon): ❌ no detectada";
    }
#endif
    return caps;
}

static std::string npuInfoString() {
    NpuStatus s = detectNpu();
    std::string out;
    out += "SoC: " + (s.soc.empty() ? std::string("desconocido") : s.soc) + "\n";
    out += "Hexagon (FastRPC): "; out += s.hardware ? "✅ presente" : "❌ ausente"; out += "\n";
    out += "Runtime QNN: ";       out += s.runtime  ? "✅ disponible" : "❌ no en el sistema"; out += "\n";
#ifdef CUMULUS_HAS_HEXAGON
    out += "Build ggml-hexagon: ✅ activo (HTP0)";
#else
    out += "Build ggml-hexagon: 🔜 se activa en la Ronda 3 (Docker de Qualcomm)";
#endif
    return out;
}

// ============================================================================
// JNI — CumulusEngine
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_ai_CumulusEngine_runNativeInference(
        JNIEnv* env, jobject, jstring prompt) {
    return runInferenceCommon(env, jstringToStd(env, prompt));
}

// ============================================================================
// JNI — MainActivity
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
#ifdef CUMULUS_HAS_HEXAGON
    return env->NewStringUTF("Cumulus Native Engine v9 (CPU+Vulkan+Hexagon)");
#elif defined(CUMULUS_HAS_VULKAN)
    return env->NewStringUTF("Cumulus Native Engine v9 (CPU+Vulkan)");
#else
    return env->NewStringUTF("Cumulus Native Engine v9 (CPU)");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeEngineInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(engineInfoString().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeGetDeviceCapabilities(JNIEnv* env, jobject) {
    return env->NewStringUTF(deviceCapsString().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeGetNpuInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(npuInfoString().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeSetBackend(JNIEnv* env, jobject, jstring backend) {
    setBackendCommon(jstringToStd(env, backend));
}

extern "C" JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeSetParams(JNIEnv* env, jobject, jint ctx, jfloat temp) {
    setParamsCommon((int)ctx, temp);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeLoadModel(JNIEnv* env, jobject, jstring modelPath) {
    return loadModelCommon(jstringToStd(env, modelPath));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeConfigureAndLoadModel(
        JNIEnv* env, jobject, jstring modelPath, jstring backend, jint ctx, jfloat temp) {
    return configureAndLoadModelCommon(
        jstringToStd(env, modelPath), jstringToStd(env, backend), (int)ctx, temp);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeRunInference(JNIEnv* env, jobject, jstring prompt) {
    return runInferenceCommon(env, jstringToStd(env, prompt));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeIsModelLoaded(JNIEnv*, jobject) {
    return isModelLoadedCommon();
}

extern "C" JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeReleaseModel(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeEngine();
}

