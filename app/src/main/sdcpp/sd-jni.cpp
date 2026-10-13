// ============================================================================
// 🎨 IA DE IMÁGENES DE CUMULUS (stable-diffusion.cpp, licencia MIT)
//    Crea una "versión nueva" de una foto (img2img) dentro del teléfono, en la CPU.
//    Va en su propia librería (libcumulus_sd.so) con su propio ggml escondido:
//    así no choca con el llama.cpp del chat (libcumulus_native.so) ni con GenieX.
// ============================================================================
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

#include "stable-diffusion.h"
#include "ggml.h"

#ifdef __ANDROID__
#include <android/log.h>
#include <android/set_abort_message.h>
#define SD_LOG(prio, ...) __android_log_print(prio, "CumulusSD", __VA_ARGS__)
#else
#include <cstdio>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
#define SD_LOG(prio, ...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
static void android_set_abort_message(const char*) {}
#endif

static std::mutex g_genMutex;                      // una sola carga / creación a la vez
static std::atomic<sd_ctx_t*> g_ctx{nullptr};
static std::string g_modelPath;
static int g_threads = 0;
static std::atomic<bool> g_cancel{false};
static std::atomic<int> g_step{0};
static std::atomic<int> g_steps{0};

static std::mutex g_logMutex;
static std::string g_lastError;
static std::vector<std::string> g_lastLogs;
static std::string g_crashDir;

// ---------------------------------------------------------------------------
// 🩺 Diagnóstico: los mismos archivos que usa el chat («¿por qué se cerró la app?»)
// ---------------------------------------------------------------------------
static void writeNote(const char* name, const std::string& text) {
    if (g_crashDir.empty()) return;
    int fd = open((g_crashDir + "/" + name).c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    if (!text.empty()) { ssize_t w = write(fd, text.data(), text.size()); (void)w; }
    close(fd);
}

static void phase(const std::string& what) { writeNote("native_phase.txt", "▶ IA de imágenes: " + what + "\n"); }
static void phaseEnd() { writeNote("native_phase.txt", ""); }

static void sdLogCallback(enum sd_log_level_t level, const char* text, void*) {
    if (!text || !*text || level == SD_LOG_DEBUG) return;
    const int prio = level == SD_LOG_ERROR ? ANDROID_LOG_ERROR : level == SD_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    SD_LOG(prio, "%s", text);
    std::lock_guard<std::mutex> lk(g_logMutex);
    g_lastLogs.emplace_back(text);
    if (g_lastLogs.size() > 20) g_lastLogs.erase(g_lastLogs.begin());
    if (level == SD_LOG_ERROR) g_lastError = text;
}

static void sdProgressCallback(int step, int steps, float, void*) {
    g_step = step;
    g_steps = steps;
}

// ggml (el de esta librería) se detiene por un error: se anota el motivo exacto antes de cerrar
static void sdAbortCallback(const char* msg) {
    if (!msg) msg = "(sin mensaje)";
    SD_LOG(ANDROID_LOG_FATAL, "💥 %s", msg);
    android_set_abort_message(msg);
    std::string out = std::string("IA de imágenes (stable-diffusion.cpp): ") + msg + "\n";
    if (g_logMutex.try_lock()) {
        for (const auto& l : g_lastLogs) out += l;
        g_logMutex.unlock();
    }
    writeNote("native_abort.txt", out);
}

static void setError(const std::string& e) {
    std::lock_guard<std::mutex> lk(g_logMutex);
    g_lastError = e;
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return "";
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static void freeCtxLocked() {
    sd_ctx_t* ctx = g_ctx.exchange(nullptr);
    if (ctx) free_sd_ctx(ctx);
    g_modelPath.clear();
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    sd_set_log_callback(sdLogCallback, nullptr);
    sd_set_progress_callback(sdProgressCallback, nullptr);
    ggml_set_abort_callback(sdAbortCallback);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeInit(JNIEnv* env, jobject, jstring crashDir) {
    g_crashDir = jstr(env, crashDir);
}

/** Carga el modelo (si ya está cargado el mismo, no hace nada). */
JNIEXPORT jboolean JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeLoad(JNIEnv* env, jobject, jstring modelPath, jint threads) {
    std::lock_guard<std::mutex> lk(g_genMutex);
    const std::string path = jstr(env, modelPath);
    if (path.empty()) { setError("Falta el archivo del modelo"); return JNI_FALSE; }
    if (g_ctx.load() && g_modelPath == path && g_threads == threads) return JNI_TRUE;
    freeCtxLocked();
    setError("");
    phase("cargando el modelo " + path.substr(path.find_last_of('/') + 1));
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path.c_str();
    if (threads > 0) p.n_threads = threads;
    p.enable_mmap = true;              // menos RAM: el modelo se lee del archivo
    p.diffusion_flash_attn = true;     // menos memoria en la atención
    sd_ctx_t* ctx = nullptr;
    try {
        ctx = new_sd_ctx(&p);
    } catch (const std::exception& e) {
        setError(std::string("Error al cargar: ") + e.what());
        ctx = nullptr;
    } catch (...) {
        setError("Error desconocido al cargar el modelo");
        ctx = nullptr;
    }
    phaseEnd();
    if (!ctx) {
        std::lock_guard<std::mutex> lk2(g_logMutex);
        if (g_lastError.empty()) g_lastError = "No se pudo cargar el modelo de imágenes";
        return JNI_FALSE;
    }
    if (!sd_ctx_supports_image_generation(ctx)) {
        free_sd_ctx(ctx);
        setError("Ese archivo no es un modelo para crear imágenes");
        return JNI_FALSE;
    }
    g_ctx = ctx;
    g_modelPath = path;
    g_threads = threads;
    return JNI_TRUE;
}

/** Nombre del tipo de modelo cargado (por ejemplo «SDXS (512-DS)»). */
JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeModelVersion(JNIEnv* env, jobject) {
    sd_ctx_t* ctx = g_ctx.load();
    const char* name = ctx ? sd_get_model_version_name(ctx) : "";
    return env->NewStringUTF(name ? name : "");
}

/**
 * Versión nueva de una imagen (img2img).
 * rgb: w*h*3 bytes (RGB). Devuelve la imagen nueva (w*h*3 RGB) o null si falló o se detuvo.
 * oneStep = modelos de un solo paso (SDXS / Turbo): la fuerza se usa como nivel de ruido.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeImg2Img(JNIEnv* env, jobject, jbyteArray rgb, jint w, jint h,
                                                          jstring prompt, jstring negative, jfloat strength,
                                                          jint steps, jfloat cfg, jlong seed, jboolean oneStep) {
    std::lock_guard<std::mutex> lk(g_genMutex);
    sd_ctx_t* ctx = g_ctx.load();
    if (!ctx) { setError("El modelo de imágenes no está cargado"); return nullptr; }
    if (!rgb || w <= 0 || h <= 0) { setError("Imagen vacía"); return nullptr; }
    const jsize n = env->GetArrayLength(rgb);
    if (n < (jsize)((size_t)w * (size_t)h * 3)) { setError("Imagen incompleta"); return nullptr; }

    std::vector<uint8_t> input((size_t)w * (size_t)h * 3);
    env->GetByteArrayRegion(rgb, 0, (jsize)input.size(), reinterpret_cast<jbyte*>(input.data()));
    const std::string p = jstr(env, prompt);
    const std::string np = jstr(env, negative);

    g_cancel = false;
    g_step = 0;
    g_steps = 0;
    setError("");
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    phase("creando una versión nueva (" + std::to_string(w) + "x" + std::to_string(h) + ")");

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt = p.c_str();
    gp.negative_prompt = np.c_str();
    gp.init_image.width = (uint32_t)w;
    gp.init_image.height = (uint32_t)h;
    gp.init_image.channel = 3;
    gp.init_image.data = input.data();
    gp.width = w;
    gp.height = h;
    gp.strength = strength;
    gp.seed = (int64_t)seed;
    gp.batch_count = 1;
    gp.sample_params.sample_steps = steps > 0 ? steps : 1;
    gp.sample_params.guidance.txt_cfg = cfg;
    if (oneStep) gp.sample_params.extra_sample_args = "strength_as_noise_level=true,force_first_sigma=true";

    sd_image_t* out = nullptr;
    int count = 0;
    bool ok = false;
    try {
        ok = generate_image(ctx, &gp, &out, &count);
    } catch (const std::exception& e) {
        setError(std::string("Error al crear la imagen: ") + e.what());
        ok = false;
    } catch (...) {
        setError("Error desconocido al crear la imagen");
        ok = false;
    }
    phaseEnd();

    jbyteArray result = nullptr;
    if (ok && out && count > 0 && out[0].data && !g_cancel.load()) {
        const sd_image_t& img = out[0];
        const size_t pixels = (size_t)img.width * img.height;
        std::vector<uint8_t> rgbOut(pixels * 3);
        const uint32_t ch = img.channel == 0 ? 3 : img.channel;
        for (size_t i = 0; i < pixels; ++i) {
            const uint8_t* s = img.data + i * ch;
            rgbOut[i * 3 + 0] = s[0];
            rgbOut[i * 3 + 1] = ch >= 3 ? s[1] : s[0];
            rgbOut[i * 3 + 2] = ch >= 3 ? s[2] : s[0];
        }
        if ((int)img.width == w && (int)img.height == h) {
            result = env->NewByteArray((jsize)rgbOut.size());
            if (result) env->SetByteArrayRegion(result, 0, (jsize)rgbOut.size(), reinterpret_cast<const jbyte*>(rgbOut.data()));
        } else {
            setError("La imagen salió de otro tamaño (" + std::to_string(img.width) + "x" + std::to_string(img.height) + ")");
        }
    } else if (!g_cancel.load()) {
        std::lock_guard<std::mutex> lk2(g_logMutex);
        if (g_lastError.empty()) g_lastError = "La IA no devolvió ninguna imagen";
    }
    if (out) free_sd_images(out, count);
    return result;
}

/** ⏹️ Detener (se llama desde otro hilo). */
JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeCancel(JNIEnv*, jobject) {
    g_cancel = true;
    sd_ctx_t* ctx = g_ctx.load();
    if (ctx) sd_cancel_generation(ctx, SD_CANCEL_ALL);
}

/** Libera la memoria del modelo de imágenes. */
JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeFree(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_genMutex);
    freeCtxLocked();
}

JNIEXPORT jint JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeProgress(JNIEnv*, jobject) {
    const int s = g_steps.load();
    return s > 0 ? (jint)(g_step.load() * 100 / s) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNative_nativeLastError(JNIEnv* env, jobject) {
    std::string e;
    {
        std::lock_guard<std::mutex> lk(g_logMutex);
        e = g_lastError;
    }
    // NewStringUTF exige UTF-8 modificado válido: se dejan las letras con tilde (2-3 bytes) y se
    // quitan los bytes rotos y los emoji (4 bytes), así nunca se cae la app por un texto raro
    std::string safe;
    const size_t n = e.size();
    for (size_t i = 0; i < n;) {
        const unsigned char c = (unsigned char)e[i];
        if (c == '\n' || c == '\t' || (c >= 0x20 && c < 0x7F)) { safe += (char)c; ++i; continue; }
        size_t len = (c >= 0xC2 && c <= 0xDF) ? 2 : (c >= 0xE0 && c <= 0xEF) ? 3 : 0;
        bool ok = len > 0 && i + len <= n;
        for (size_t k = 1; ok && k < len; ++k) ok = (((unsigned char)e[i + k]) & 0xC0) == 0x80;
        if (ok && len == 3) {
            const unsigned char c1 = (unsigned char)e[i + 1];
            if ((c == 0xE0 && c1 < 0xA0) || (c == 0xED && c1 >= 0xA0)) ok = false;   // largas o mitades de emoji
        }
        if (ok) { safe.append(e, i, len); i += len; } else { ++i; }
    }
    while (!safe.empty() && (safe.back() == '\n' || safe.back() == ' ')) safe.pop_back();
    return env->NewStringUTF(safe.c_str());
}

}  // extern "C"
