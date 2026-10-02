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
#include <cstdlib>
#include "llama.h"

// ✅ PROTEGIDO: ggml-backend.h solo existe en tags nuevos de llama.cpp
#ifdef CUMULUS_HAS_GGML_BACKEND
#include "ggml-backend.h"
#endif

// 👁️ Visión: mtmd (multimodal) de llama.cpp
#ifdef CUMULUS_HAS_MTMD
#include "mtmd.h"
#include "mtmd-helper.h"
// Decodificador de imágenes (su código ya viene compilado dentro de mtmd)
#include "stb/stb_image.h"
// Decodificador de audio: copia privada solo para este archivo
#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_ENCODING
#define MA_NO_DEVICE_IO
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MA_NO_ENGINE
#define MA_NO_GENERATION
#define MA_API static
#define ma_atomic_global_lock cumulus_ma_atomic_global_lock
#define ma_android_sdk_version cumulus_ma_android_sdk_version
#define ma_log_callback_debug cumulus_ma_log_callback_debug
#define ma_device_enum_callback cumulus_ma_device_enum_callback
#include "miniaudio/miniaudio.h"
#endif

// GPU disponible si el build trae Vulkan u OpenCL
#if defined(CUMULUS_HAS_OPENCL)
#define CUMULUS_HAS_GPU 1
#define CUMULUS_GPU_LABEL "🎮 GPU (OpenCL Adreno)"
#elif defined(CUMULUS_HAS_VULKAN)
#define CUMULUS_HAS_GPU 1
#define CUMULUS_GPU_LABEL "🎮 GPU (Vulkan)"
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
static std::string g_mmprojPath;
// 🧠 Lo que el motor ya leyó de la conversación (para no releer todo en cada mensaje)
static std::vector<llama_token> g_cachedTokens;
// 🧭 Modo de chat (lo cambia la app: Solo chat, Ingeniero de código, etc.)
static std::string g_systemPrompt =
    "Eres Cumulus IA, un asistente útil, claro y conversacional. "
    "Responde en el idioma del usuario.";
static int g_maxGen = 256;
#ifdef CUMULUS_HAS_MTMD
static mtmd_context* g_mtmd = nullptr;
#endif

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

static void freeVision() {
#ifdef CUMULUS_HAS_MTMD
    if (g_mtmd) { mtmd_free(g_mtmd); g_mtmd = nullptr; LOGD("Visión liberada"); }
#endif
}

// Carga el mmproj elegido (si hay) para el modelo actual
static bool initVisionLocked() {
    freeVision();
#ifdef CUMULUS_HAS_MTMD
    if (!g_model || g_mmprojPath.empty()) return false;
    if (!validateGgufFile(g_mmprojPath)) {
        LOGE("👁️ mmproj inválido: %s", g_mmprojPath.c_str());
        return false;
    }
    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu = g_lastUsedGpu;
    p.print_timings = false;
    unsigned hw = std::thread::hardware_concurrency();
    p.n_threads = (int)std::min(8u, std::max(2u, hw > 1 ? hw - 1 : 2u));
    p.warmup = false;
    g_mtmd = mtmd_init_from_file(g_mmprojPath.c_str(), g_model, p);
    if (!g_mtmd && p.use_gpu) {
        LOGE("👁️ mmproj falló en GPU; reintentando en CPU");
        p.use_gpu = false;
        g_mtmd = mtmd_init_from_file(g_mmprojPath.c_str(), g_model, p);
    }
    if (!g_mtmd) {
        LOGE("👁️ No se pudo cargar el mmproj (¿no corresponde a este modelo?)");
        return false;
    }
    LOGI("👁️ Visión activada con: %s", g_mmprojPath.c_str());
    LOGI("👁️ Imágenes: %s | 🎧 Audio: %s",
         mtmd_support_vision(g_mtmd) ? "SÍ" : "NO",
         mtmd_support_audio(g_mtmd) ? "SÍ" : "NO");
    return true;
#else
    LOGI("👁️ Este build no incluye visión (mtmd)");
    return false;
#endif
}

static void freeEngine() {
    freeVision();
    g_cachedTokens.clear();
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

// ✅ COMPATIBILIDAD DE API: llama_kv_cache_clear fue reemplazada por el nuevo
//    sistema de memoria (llama_get_memory + llama_memory_clear) en tags nuevos
//    de llama.cpp (ej. b9222). Tags viejos siguen usando llama_kv_cache_clear.

// Borra la memoria del motor desde la posición p0 en adelante. Devuelve false si no se puede.
static bool removeKvFrom(llama_context* ctx, int p0) {
#ifdef CUMULUS_USE_MEMORY_API
    return llama_memory_seq_rm(llama_get_memory(ctx), 0, p0, -1);
#else
    return llama_kv_cache_seq_rm(ctx, 0, p0, -1);
#endif
}

static void clearKvCache(llama_context* ctx) {
    g_cachedTokens.clear();
#ifdef CUMULUS_USE_MEMORY_API
    llama_memory_clear(llama_get_memory(ctx), true);
#else
    llama_kv_cache_clear(ctx);
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
#ifdef CUMULUS_HAS_GPU
            gpuLayers = 999;
            LOGI("⚡→ Fallback: NPU se ejecuta en %s", CUMULUS_GPU_LABEL);
#else
            LOGI("⚡→💻 Fallback: NPU se ejecuta en CPU");
#endif
        }
    } else if (gpuReq) {
#ifdef CUMULUS_HAS_GPU
        gpuLayers = 999;
        LOGI("🎮 Offloading completo a %s", CUMULUS_GPU_LABEL);
#else
        LOGE("⚠️ Usuario pidió GPU pero no hay GPU compilada, usando CPU");
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
    cparams.n_ctx = (uint32_t)std::max(256, std::min(32768, g_nCtx));   // hasta 32K tokens

    unsigned hw = std::thread::hardware_concurrency();
    unsigned availableThreads = hw > 1 ? hw - 1 : 2;
    int threads = (int)std::min(8u, std::max(2u, availableThreads));
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    LOGI("Usando %d threads", threads);

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx && usingGpu) {
        LOGE("⚠️ Falló el contexto GPU; reintentando modelo y contexto en CPU");
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
    initVisionLocked();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();

    LOGI("═══════════════════════════════════════════════════");
    LOGI("✅ MODELO CARGADO en %ld ms", (long)ms);
    LOGI("Hardware real: %s",
         g_npuRequested ? (g_lastUsedGpu ? "⚡ NPU/HTP (o fallback GPU)" : "⚡→💻 fallback CPU")
                        : (g_lastUsedGpu ? "🎮 GPU" : "💻 CPU (NEON)"));
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

// ============================================================================
// 💬 FORMATO DE CHAT
// Usa la plantilla de chat oficial que trae el propio modelo (dentro del GGUF),
// así el modelo sabe exactamente dónde termina su turno. Si el modelo no trae
// plantilla, se usa el formato genérico anterior como respaldo.
// ============================================================================
// Arma la conversación completa (sistema + historial + mensaje nuevo) con la plantilla del modelo
static std::string buildChatPromptMulti(const std::string& system,
                                        const std::vector<std::string>& roles,
                                        const std::vector<std::string>& contents) {
#ifdef CUMULUS_HAS_CHAT_TEMPLATE
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl != nullptr) {
        std::vector<llama_chat_message> msgs;
        msgs.push_back({ "system", system.c_str() });
        size_t total = system.size();
        for (size_t i = 0; i < roles.size() && i < contents.size(); i++) {
            msgs.push_back({ roles[i].c_str(), contents[i].c_str() });
            total += contents[i].size();
        }
        std::vector<char> buf(total + 4096);
        int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
        if (len > (int32_t)buf.size()) {
            buf.resize((size_t)len + 1);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t)buf.size());
        }
        if (len > 0) return std::string(buf.data(), (size_t)len);
        LOGE("⚠️ Plantilla del modelo no soportada; usando formato genérico");
    }
#endif
    std::string out = "### Sistema:\n" + system + "\n\n";
    for (size_t i = 0; i < roles.size() && i < contents.size(); i++) {
        out += (roles[i] == "assistant" ? "### Asistente:\n" : "### Usuario:\n") + contents[i] + "\n\n";
    }
    return out + "### Asistente:\n";
}

static std::string buildChatPrompt(const std::string& system, const std::string& user) {
#ifdef CUMULUS_HAS_CHAT_TEMPLATE
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl != nullptr) {
        llama_chat_message msgs[2] = {
            { "system", system.c_str() },
            { "user",   user.c_str()   },
        };
        std::vector<char> buf(system.size() + user.size() + 2048);
        int32_t len = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t)buf.size());
        if (len > (int32_t)buf.size()) {
            buf.resize((size_t)len + 1);
            len = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t)buf.size());
        }
        if (len > 0) {
            LOGI("💬 Usando la plantilla de chat oficial del modelo");
            return std::string(buf.data(), (size_t)len);
        }
        LOGE("⚠️ Plantilla del modelo no soportada; usando formato genérico");
    } else {
        LOGI("ℹ️ El modelo no trae plantilla de chat; usando formato genérico");
    }
#endif
    return "### Sistema:\n" + system + "\n\n### Usuario:\n" + user + "\n\n### Asistente:\n";
}

// Corta la respuesta si el modelo empieza a escribir un turno nuevo por su cuenta
static bool cutAtStopSequence(std::string& text) {
    static const char* STOPS[] = {
        "### Usuario", "### Sistema", "### Asistente", "###Usuario",
        "\nUsuario:", "\nUser:", "\nTú:",
        "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|eot_id|>",
        "<|start_header_id|>", "<end_of_turn>", "<start_of_turn>"
    };
    size_t cut = std::string::npos;
    for (const char* s : STOPS) {
        size_t p = text.find(s);
        if (p != std::string::npos && p < cut) cut = p;
    }
    if (cut == std::string::npos) return false;
    text.erase(cut);
    return true;
}

static void trimSpaces(std::string& text) {
    const char* WS = " \t\r\n";
    size_t start = text.find_first_not_of(WS);
    if (start == std::string::npos) { text.clear(); return; }
    size_t end = text.find_last_not_of(WS);
    text = text.substr(start, end - start + 1);
}

// Genera la respuesta token por token (texto e imagen usan lo mismo)
// ⚡ Streaming: envía el texto parcial a Kotlin mientras se genera
static JNIEnv* g_streamEnv = nullptr;
static jobject g_streamObj = nullptr;
static jmethodID g_streamMethod = nullptr;

static void streamPartial(const std::string& text) {
    if (!g_streamEnv || !g_streamObj || !g_streamMethod) return;
    // No cortar un carácter UTF-8 a la mitad
    size_t len = text.size();
    size_t i = len;
    int back = 0;
    while (i > 0 && back < 4 && (((unsigned char)text[i - 1]) & 0xC0) == 0x80) { i--; back++; }
    if (i > 0) {
        unsigned char lead = (unsigned char)text[i - 1];
        int need = (lead >= 0xF0) ? 4 : (lead >= 0xE0) ? 3 : (lead >= 0xC0) ? 2 : 1;
        if (need > 1 && back + 1 < need) len = i - 1;
    }
    jbyteArray arr = g_streamEnv->NewByteArray((jsize)len);
    if (!arr) return;
    g_streamEnv->SetByteArrayRegion(arr, 0, (jsize)len, (const jbyte*)text.data());
    g_streamEnv->CallVoidMethod(g_streamObj, g_streamMethod, arr);
    if (g_streamEnv->ExceptionCheck()) g_streamEnv->ExceptionClear();
    g_streamEnv->DeleteLocalRef(arr);
}

static std::string generateReply(const llama_vocab* vocab, int generationLimit,
                                 std::vector<llama_token>* generated = nullptr) {
    std::string result;
    char buf[256];
    for (int i = 0; i < generationLimit; i++) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (tok < 0) break;
        if (llama_vocab_is_eog(vocab, tok)) break;
        // special=false: no mostrar tokens de control (<|im_end|>, etc.)
        int32_t written = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (written < 0) break;
        result.append(buf, (size_t)written);

        // ✋ Si el modelo empieza a inventar otro turno, se corta ahí
        if (cutAtStopSequence(result)) {
            LOGI("✋ Turno inventado detectado: respuesta cortada");
            break;
        }

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, next) != 0) break;
        if (generated) generated->push_back(tok);
        if (i % 3 == 2) streamPartial(result);
    }
    trimSpaces(result);
    return result;
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
    const int maxGen = g_maxGen;

    const std::string formattedPrompt = buildChatPrompt(g_systemPrompt, prompt);
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

    clearKvCache(g_ctx);

    // El mensaje se lee por partes (textos largos de internet o archivos
    // pueden superar el tamaño de lote del motor)
    const int nBatch = std::max(32, (int)llama_n_batch(g_ctx));
    for (int pos = 0; pos < n; pos += nBatch) {
        const int len = std::min(nBatch, n - pos);
        llama_batch batch = llama_batch_get_one(tokens.data() + pos, len);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Error en llama_decode (parte %d)", pos / nBatch);
            return env->NewStringUTF("⚠️ Error del motor al leer el mensaje.");
        }
    }

    std::string result = generateReply(vocab, generationLimit);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    LOGI("Inferencia en %ld ms: '%s'", (long)ms, result.c_str());

    if (result.empty()) result = "(el modelo no generó respuesta)";
    return env->NewStringUTF(result.c_str());
}


#ifdef CUMULUS_HAS_MTMD
// Carga una imagen o un audio sin depender de las funciones "helper" de mtmd,
// que cambian de forma entre versiones de llama.cpp
static mtmd_bitmap* loadMediaBitmap(mtmd_context* ctx, const std::string& path) {
    // 1) ¿Es audio? (miniaudio detecta wav/mp3/flac)
    if (mtmd_support_audio(ctx)) {
        const int sampleRate = mtmd_get_audio_sample_rate(ctx);
        if (sampleRate > 0) {
            ma_decoder_config cfg = ma_decoder_config_init(ma_format_f32, 1, (ma_uint32)sampleRate);
            ma_decoder dec;
            if (ma_decoder_init_file(path.c_str(), &cfg, &dec) == MA_SUCCESS) {
                std::vector<float> pcm;
                float chunk[4096];
                for (;;) {
                    ma_uint64 got = 0;
                    if (ma_decoder_read_pcm_frames(&dec, chunk, 4096, &got) != MA_SUCCESS || got == 0) break;
                    pcm.insert(pcm.end(), chunk, chunk + got);
                }
                ma_decoder_uninit(&dec);
                if (!pcm.empty()) {
                    LOGI("🎧 Audio decodificado: %zu muestras a %d Hz", pcm.size(), sampleRate);
                    return mtmd_bitmap_init_from_audio(pcm.size(), pcm.data());
                }
            }
        }
    }
    // 2) Imagen (stb_image), convertida a RGB
    int w = 0, h = 0, ch = 0;
    unsigned char* rgb = stbi_load(path.c_str(), &w, &h, &ch, 3);
    if (!rgb) return nullptr;
    LOGI("👁️ Imagen decodificada: %dx%d", w, h);
    mtmd_bitmap* bmp = mtmd_bitmap_init((uint32_t)w, (uint32_t)h, rgb);
    stbi_image_free(rgb);
    return bmp;
}
#endif

// ============================================================================
// 👁️ INFERENCIA CON IMAGEN(ES) (modelos de visión + mmproj)
// ============================================================================
// Acepta varias imágenes/audios a la vez (cada uno con su marcador)
static jstring runInferenceWithMediaListCommon(JNIEnv* env, const std::string& prompt,
                                               const std::vector<std::string>& paths) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_modelLoaded || !g_ctx || !g_model || !g_sampler) {
        return env->NewStringUTF("⚠️ Motor sin modelo cargado todavía.");
    }
#ifdef CUMULUS_HAS_MTMD
    if (!g_mtmd) {
        return env->NewStringUTF("⚠️ No hay mmproj cargado. Elige el mmproj de este modelo en Configuración.");
    }
    if (paths.empty()) return env->NewStringUTF("⚠️ No hay archivos adjuntos.");
    auto t0 = std::chrono::steady_clock::now();
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t nCtx = llama_n_ctx(g_ctx);

    std::vector<mtmd_bitmap*> bmps;
    int nAudio = 0, nImages = 0;
    auto freeAll = [&]() { for (auto* b : bmps) mtmd_bitmap_free(b); bmps.clear(); };
    for (const auto& path : paths) {
        // Sirve para imágenes (jpg/png/bmp/gif) y audio (wav/mp3/flac)
        mtmd_bitmap* bmp = loadMediaBitmap(g_mtmd, path);
        if (!bmp) {
            LOGE("📎 No se pudo leer el archivo: %s", path.c_str());
            continue;
        }
        const bool isAudio = mtmd_bitmap_is_audio(bmp);
        if ((isAudio && !mtmd_support_audio(g_mtmd)) || (!isAudio && !mtmd_support_vision(g_mtmd))) {
            LOGE("📎 Tipo no soportado por este mmproj: %s", path.c_str());
            mtmd_bitmap_free(bmp);
            continue;
        }
        if (isAudio) nAudio++; else nImages++;
        bmps.push_back(bmp);
    }
    if (bmps.empty()) {
        return env->NewStringUTF("⚠️ Este modelo/mmproj no pudo leer esos archivos (formato no soportado).");
    }
    LOGI("📎 Multimodal: %d imagen(es), %d audio(s)", nImages, nAudio);

    std::string markers;
    for (size_t m = 0; m < bmps.size(); m++) {
        if (bmps.size() > 1) markers += "Archivo " + std::to_string(m + 1) + ": ";
        markers += std::string(mtmd_default_marker()) + "\n";
    }
    const std::string defaultAsk = nAudio > 0 && nImages == 0
        ? std::string(nAudio > 1 ? "Transcribe y resume estos audios." : "Transcribe y resume este audio.")
        : std::string(nImages > 1 ? "Describe estas imágenes." : "Describe esta imagen.");
    const std::string userText = markers + (prompt.empty() ? defaultAsk : prompt);
    const std::string formatted = buildChatPrompt(g_systemPrompt, userText);

    mtmd_input_text txt;
    txt.text = formatted.c_str();
    txt.add_special = true;
    txt.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    std::vector<const mtmd_bitmap*> cbmps(bmps.begin(), bmps.end());
    int32_t res = mtmd_tokenize(g_mtmd, chunks, &txt, cbmps.data(), cbmps.size());
    freeAll();
    if (res != 0) {
        mtmd_input_chunks_free(chunks);
        LOGE("👁️ mtmd_tokenize falló: %d", res);
        return env->NewStringUTF("⚠️ No se pudo procesar la imagen.");
    }

    const size_t nTokens = mtmd_helper_get_n_tokens(chunks);
    if (nTokens + 16 >= nCtx) {
        mtmd_input_chunks_free(chunks);
        LOGE("👁️ Adjuntos + texto = %zu tokens, contexto = %u", nTokens, nCtx);
        return env->NewStringUTF("⚠️ Los adjuntos no caben en el contexto. Sube CONTEXTO (16384 o más para varias imágenes) o envía menos.");
    }

    clearKvCache(g_ctx);
    llama_pos newPast = 0;
    res = mtmd_helper_eval_chunks(g_mtmd, g_ctx, chunks, 0, 0,
                                  (int32_t)llama_n_batch(g_ctx), true, &newPast);
    mtmd_input_chunks_free(chunks);
    if (res != 0) {
        LOGE("👁️ mtmd_helper_eval_chunks falló: %d", res);
        return env->NewStringUTF("⚠️ Error del motor al leer la imagen.");
    }

    const int generationLimit = std::min(g_maxGen, (int)nCtx - (int)newPast - 1);
    std::string result = generateReply(vocab, generationLimit);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    LOGI("👁️ Inferencia con imagen en %ld ms: '%s'", (long)ms, result.c_str());
    if (result.empty()) result = "(el modelo no generó respuesta)";
    return env->NewStringUTF(result.c_str());
#else
    (void)prompt; (void)paths;
    return env->NewStringUTF("⚠️ Este build no incluye visión.");
#endif
}

static jstring runInferenceWithImageCommon(JNIEnv* env, const std::string& prompt,
                                           const std::string& imagePath) {
    return runInferenceWithMediaListCommon(env, prompt, std::vector<std::string>{ imagePath });
}

// ============================================================================
// 🧠 CHAT CON MEMORIA: recibe el historial y reutiliza lo ya leído
// ============================================================================
static jstring runChatCommon(JNIEnv* env, jobject thiz, const std::vector<std::string>& roles,
                             const std::vector<std::string>& contents) {
    std::lock_guard<std::mutex> lock(g_mutex);
    struct StreamGuard {
        StreamGuard(JNIEnv* e, jobject o) {
            g_streamEnv = e; g_streamObj = o; g_streamMethod = nullptr;
            if (e && o) {
                jclass cls = e->GetObjectClass(o);
                g_streamMethod = e->GetMethodID(cls, "onNativeToken", "([B)V");
                if (e->ExceptionCheck()) { e->ExceptionClear(); g_streamMethod = nullptr; }
                e->DeleteLocalRef(cls);
            }
        }
        ~StreamGuard() { g_streamEnv = nullptr; g_streamObj = nullptr; g_streamMethod = nullptr; }
    } streamGuard(env, thiz);
    if (!g_modelLoaded || !g_ctx || !g_model || !g_sampler) {
        return env->NewStringUTF("⚠️ Motor sin modelo cargado todavía.");
    }
    auto t0 = std::chrono::steady_clock::now();
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t nCtx = llama_n_ctx(g_ctx);

    const std::string formatted = buildChatPromptMulti(g_systemPrompt, roles, contents);
    std::vector<llama_token> tokens(formatted.size() + 16);
    int n = llama_tokenize(vocab, formatted.c_str(), (int32_t)formatted.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
    if (n < 0) {
        tokens.resize((size_t)(-n) + 16);
        n = llama_tokenize(vocab, formatted.c_str(), (int32_t)formatted.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (n <= 0) return env->NewStringUTF("⚠️ No se pudo procesar el mensaje.");
    tokens.resize(n);
    if ((uint32_t)n + 16 >= nCtx) {
        LOGE("🧠 Conversación de %d tokens no cabe en %u", n, nCtx);
        return env->NewStringUTF("⚠️ La conversación es demasiado larga para el contexto. Sube CONTEXTO o empieza un chat nuevo.");
    }

    // ¿Cuánto de esta conversación ya está leído en la memoria del motor?
    size_t keep = 0;
    while (keep < g_cachedTokens.size() && keep < tokens.size() && g_cachedTokens[keep] == tokens[keep]) keep++;
    if (keep >= (size_t)n) keep = (size_t)n - 1;   // siempre se lee al menos 1 token
    if (keep < g_cachedTokens.size() || keep == 0) {
        if (keep == 0 || !removeKvFrom(g_ctx, (int)keep)) {
            // Algunos modelos (ej. híbridos) no permiten borrar solo una parte: se relee todo
            clearKvCache(g_ctx);
            keep = 0;
        }
    }
    LOGI("🧠 Conversación: %d tokens (reutiliza %zu, lee %zu nuevos)", n, keep, (size_t)n - keep);

    const int nBatch = std::max(32, (int)llama_n_batch(g_ctx));
    for (int pos = (int)keep; pos < n; pos += nBatch) {
        const int len = std::min(nBatch, n - pos);
        llama_batch batch = llama_batch_get_one(tokens.data() + pos, len);
        if (llama_decode(g_ctx, batch) != 0) {
            clearKvCache(g_ctx);
            return env->NewStringUTF("⚠️ Error del motor al leer la conversación.");
        }
    }

    std::vector<llama_token> generated;
    const int generationLimit = std::min(g_maxGen, (int)nCtx - n - 1);
    std::string result = generateReply(vocab, generationLimit, &generated);
    g_cachedTokens = tokens;
    g_cachedTokens.insert(g_cachedTokens.end(), generated.begin(), generated.end());

    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    LOGI("🧠 Respuesta en %ld ms", (long)ms);
    if (result.empty()) result = "(el modelo no generó respuesta)";
    return env->NewStringUTF(result.c_str());
}

static void setMmprojCommon(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_mmprojPath = path;
    LOGI("👁️ mmproj seleccionado: %s", path.empty() ? "(ninguno)" : path.c_str());
    if (g_modelLoaded) initVisionLocked(); else freeVision();
}

// "vision", "audio", "vision,audio" o "" según lo que entienda el mmproj
static std::string mediaCapsCommon() {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string caps;
#ifdef CUMULUS_HAS_MTMD
    if (g_mtmd) {
        if (mtmd_support_vision(g_mtmd)) caps += "vision";
        if (mtmd_support_audio(g_mtmd)) { if (!caps.empty()) caps += ","; caps += "audio"; }
    }
#endif
    return caps;
}

static bool hasVisionCommon() {
    std::lock_guard<std::mutex> lock(g_mutex);
#ifdef CUMULUS_HAS_MTMD
    return g_mtmd != nullptr;
#else
    return false;
#endif
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
#ifdef CUMULUS_HAS_GPU
    if (g_lastUsedGpu) return CUMULUS_GPU_LABEL;
#endif
    return "💻 CPU (NEON)";
}

static std::string deviceCapsString() {
    NpuStatus npu = detectNpu();
    std::string caps = "💻 CPU: ✅ | ";
#ifdef CUMULUS_HAS_GPU
    caps += std::string(CUMULUS_GPU_LABEL) + ": ✅ | ";
#else
    caps += "🎮 GPU: ❌ | ";
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
// 🎮 AJUSTES VULKAN PARA GPUs MÓVILES (Adreno / Mali)
// Se ejecuta al cargar la librería, antes de que ggml inicialice Vulkan.
// Desactiva funciones que en drivers móviles suelen producir texto basura.
// ============================================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
#ifdef CUMULUS_HAS_VULKAN
    setenv("GGML_VK_DISABLE_F16", "1", 1);
    setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
    setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
    setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 1);
    setenv("GGML_VK_DISABLE_BFLOAT16", "1", 1);
    LOGI("🎮 Vulkan: modo compatible para GPU móvil activado");
#endif
    return JNI_VERSION_1_6;
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
#elif defined(CUMULUS_HAS_OPENCL)
    return env->NewStringUTF("Cumulus Native Engine v9 (CPU+OpenCL)");
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
// ============================================================================
// 👁️ JNI — Visión (mmproj)
// ============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeSetMmproj(JNIEnv* env, jobject, jstring path) {
    setMmprojCommon(jstringToStd(env, path));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeHasVision(JNIEnv*, jobject) {
    return hasVisionCommon() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeRunInferenceWithImage(
        JNIEnv* env, jobject, jstring prompt, jstring imagePath) {
    return runInferenceWithImageCommon(env, jstringToStd(env, prompt), jstringToStd(env, imagePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeGetMediaCaps(JNIEnv* env, jobject) {
    return env->NewStringUTF(mediaCapsCommon().c_str());
}

// Igual que nativeRunInferenceWithImage, pero acepta imagen o audio
extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeRunInferenceWithMedia(
        JNIEnv* env, jobject, jstring prompt, jstring mediaPath) {
    return runInferenceWithImageCommon(env, jstringToStd(env, prompt), jstringToStd(env, mediaPath));
}

// ============================================================================
// 🧭 JNI — Modo de chat
// ============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeSetChatMode(
        JNIEnv* env, jobject, jstring systemPrompt, jint maxTokens) {
    std::string sp = jstringToStd(env, systemPrompt);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!sp.empty()) g_systemPrompt = sp;
    g_maxGen = std::max(32, std::min(4096, (int)maxTokens));
    LOGI("🧭 Modo de chat actualizado (máx %d tokens)", g_maxGen);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeRunInferenceWithMediaList(
        JNIEnv* env, jobject, jstring prompt, jobjectArray mediaPaths) {
    std::vector<std::string> paths;
    const jsize n = mediaPaths ? env->GetArrayLength(mediaPaths) : 0;
    for (jsize i = 0; i < n; i++) {
        auto js = (jstring)env->GetObjectArrayElement(mediaPaths, i);
        paths.push_back(jstringToStd(env, js));
        env->DeleteLocalRef(js);
    }
    return runInferenceWithMediaListCommon(env, jstringToStd(env, prompt), paths);
}

static std::vector<std::string> jarrayToVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    const jsize n = arr ? env->GetArrayLength(arr) : 0;
    for (jsize i = 0; i < n; i++) {
        auto js = (jstring)env->GetObjectArrayElement(arr, i);
        out.push_back(jstringToStd(env, js));
        env->DeleteLocalRef(js);
    }
    return out;
}

// roles: "user"/"assistant"; el último debe ser el mensaje nuevo del usuario
extern "C" JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeRunChat(
        JNIEnv* env, jobject thiz, jobjectArray roles, jobjectArray contents) {
    return runChatCommon(env, thiz, jarrayToVector(env, roles), jarrayToVector(env, contents));
}

extern "C" JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_MainActivity_nativeResetChatMemory(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) clearKvCache(g_ctx);
}

