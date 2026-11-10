// ============================================================================
// 🎨⚡ IA DE IMÁGENES EN LA NPU (Snapdragon / Hexagon) — motor propio de Cumulus
//    Crea una imagen DESDE CERO (solo con la descripción) usando el Stable Diffusion 1.5 OFICIAL
//    de Qualcomm AI Hub (licencia CreativeML OpenRAIL-M), ya compilado para cada chip
//    (QNN context binaries, w8a16, QAIRT 2.50):
//      · text_encoder: tokens [1,77] int32  → text_embedding [1,77,768]
//      · unet:         latent [1,4,64,64] (en la NPU suele ir NHWC) + timestep [1,1] + text_emb → ruido
//      · vae:          latent → imagen [1,512,512,3] NHWC en 0..1 (ya divide por 0,18215 por dentro)
//    Pasos: DPM++ 2M con sigmas de Karras (20 pasos, guía 7,5 = como el ejemplo de Qualcomm).
//
//    Las librerías de Qualcomm (libQnnHtp.so, libQnnSystem.so, libQnnHtpV**Stub/Skel.so) NO se enlazan:
//    se abren en tiempo de ejecución (dlopen) desde el APK, las mismas que trae GenieX (QAIRT 2.50.40).
//    Los headers de QNN vienen del repositorio oficial de Qualcomm qualcomm/geniex-qairt-plugin (BSD-3).
//    El tokenizador CLIP es el de stable-diffusion.cpp (MIT), que ya va en esta misma librería.
//    Nada de este archivo viene de Local Dream.
// ============================================================================
#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <random>
#include <string>
#include <vector>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "System/QnnSystemInterface.h"

#include "tokenizers/clip_tokenizer.h"

#ifdef __ANDROID__
#include <android/log.h>
#define NPU_LOG(prio, ...) __android_log_print(prio, "CumulusNPU", __VA_ARGS__)
#else
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define NPU_LOG(prio, ...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

// ---------------------------------------------------------------------------
// Estado
// ---------------------------------------------------------------------------
std::mutex g_mutex;                       // una carga / una imagen a la vez
std::mutex g_errMutex;
std::string g_err;
std::atomic<bool> g_cancel{false};
std::atomic<int> g_step{0};
std::atomic<int> g_steps{0};

void setErr(const std::string& e) {
    std::lock_guard<std::mutex> lk(g_errMutex);
    g_err = e;
    if (!e.empty()) NPU_LOG(ANDROID_LOG_ERROR, "%s", e.c_str());
}

typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t***, uint32_t*);
typedef Qnn_ErrorHandle_t (*GetSysProvidersFn)(const QnnSystemInterface_t***, uint32_t*);

void* g_htpLib = nullptr;
void* g_sysLib = nullptr;
QNN_INTERFACE_VER_TYPE g_qnn{};
QNN_SYSTEM_INTERFACE_VER_TYPE g_sys{};
bool g_qnnReady = false;
Qnn_LogHandle_t g_log = nullptr;
Qnn_BackendHandle_t g_backend = nullptr;
Qnn_DeviceHandle_t g_device = nullptr;

/** Una parte del modelo (text_encoder, unet o vae): su contexto en la NPU y cómo son sus entradas y salidas. */
struct Part {
    std::string name;
    QnnSystemContext_Handle_t sysCtx = nullptr;   // dueño de la descripción de los tensores
    Qnn_ContextHandle_t ctx = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    std::vector<Qnn_Tensor_t> inputs;
    std::vector<Qnn_Tensor_t> outputs;
    std::vector<std::vector<uint8_t>> inBufs;
    std::vector<std::vector<uint8_t>> outBufs;
};
Part g_text, g_unet, g_vae;
bool g_loaded = false;

// ---------------------------------------------------------------------------
// Tensores (v1 y v2 comparten los mismos primeros campos)
// ---------------------------------------------------------------------------
Qnn_TensorV1_t& tv(Qnn_Tensor_t& t) { return t.v1; }
const Qnn_TensorV1_t& tv(const Qnn_Tensor_t& t) { return t.v1; }

size_t elemCount(const Qnn_Tensor_t& t) {
    size_t n = 1;
    for (uint32_t i = 0; i < tv(t).rank; ++i) n *= tv(t).dimensions[i];
    return n;
}

size_t elemBytes(Qnn_DataType_t d) {
    switch (d) {
        case QNN_DATATYPE_INT_8: case QNN_DATATYPE_UINT_8: case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8: case QNN_DATATYPE_BOOL_8: return 1;
        case QNN_DATATYPE_INT_16: case QNN_DATATYPE_UINT_16: case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16: case QNN_DATATYPE_FLOAT_16: case QNN_DATATYPE_BFLOAT_16: return 2;
        case QNN_DATATYPE_INT_32: case QNN_DATATYPE_UINT_32: case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32: case QNN_DATATYPE_FLOAT_32: return 4;
        case QNN_DATATYPE_INT_64: case QNN_DATATYPE_UINT_64: case QNN_DATATYPE_FLOAT_64: return 8;
        default: return 0;
    }
}

std::string tensorName(const Qnn_Tensor_t& t) { return tv(t).name ? tv(t).name : ""; }

std::string dimsText(const Qnn_Tensor_t& t) {
    std::string s = "[";
    for (uint32_t i = 0; i < tv(t).rank; ++i) { if (i) s += ","; s += std::to_string(tv(t).dimensions[i]); }
    return s + "] tipo 0x" + [&] { char b[16]; snprintf(b, sizeof b, "%04x", (unsigned)tv(t).dataType); return std::string(b); }();
}

float halfToFloat(uint16_t h) {
    const uint32_t s = (h & 0x8000u) << 16, e = (h >> 10) & 0x1F, m = h & 0x3FF;
    uint32_t f;
    if (e == 0) {
        if (m == 0) f = s;
        else { int ee = -1; uint32_t mm = m; do { ++ee; mm <<= 1; } while (!(mm & 0x400)); f = s | ((127 - 15 - ee) << 23) | ((mm & 0x3FF) << 13); }
    } else if (e == 31) f = s | 0x7F800000u | (m << 13);
    else f = s | ((e + 112) << 23) | (m << 13);
    float out; memcpy(&out, &f, 4); return out;
}

uint16_t floatToHalf(float v) {
    uint32_t f; memcpy(&f, &v, 4);
    const uint32_t s = (f >> 16) & 0x8000u;
    int e = (int)((f >> 23) & 0xFF) - 127 + 15;
    uint32_t m = f & 0x7FFFFF;
    if (e <= 0) return (uint16_t)s;
    if (e >= 31) return (uint16_t)(s | 0x7C00);
    return (uint16_t)(s | (e << 10) | ((m + 0x1000) >> 13));
}

/** Escala y desfase de un tensor cuantizado (float = (q + offset) * scale). */
void scaleOffset(const Qnn_Tensor_t& t, float& scale, int32_t& offset) {
    scale = 1.f; offset = 0;
    const Qnn_QuantizeParams_t& q = tv(t).quantizeParams;
    if (q.encodingDefinition == QNN_DEFINITION_DEFINED && q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        scale = q.scaleOffsetEncoding.scale > 0.f ? q.scaleOffsetEncoding.scale : 1.f;
        offset = q.scaleOffsetEncoding.offset;
    }
}

/** Escribe valores float en el búfer del tensor, en su tipo (float, half o cuantizado). */
bool writeFloats(const Qnn_Tensor_t& t, const float* src, size_t n, std::vector<uint8_t>& buf) {
    const Qnn_DataType_t d = tv(t).dataType;
    float sc; int32_t off; scaleOffset(t, sc, off);
    switch (d) {
        case QNN_DATATYPE_FLOAT_32: memcpy(buf.data(), src, n * 4); return true;
        case QNN_DATATYPE_FLOAT_16: { auto* o = reinterpret_cast<uint16_t*>(buf.data()); for (size_t i = 0; i < n; ++i) o[i] = floatToHalf(src[i]); return true; }
        case QNN_DATATYPE_UFIXED_POINT_16: { auto* o = reinterpret_cast<uint16_t*>(buf.data());
            for (size_t i = 0; i < n; ++i) { long q = lroundf(src[i] / sc) - off; o[i] = (uint16_t)(q < 0 ? 0 : q > 65535 ? 65535 : q); } return true; }
        case QNN_DATATYPE_SFIXED_POINT_16: { auto* o = reinterpret_cast<int16_t*>(buf.data());
            for (size_t i = 0; i < n; ++i) { long q = lroundf(src[i] / sc) - off; o[i] = (int16_t)(q < -32768 ? -32768 : q > 32767 ? 32767 : q); } return true; }
        case QNN_DATATYPE_UFIXED_POINT_8: { auto* o = buf.data();
            for (size_t i = 0; i < n; ++i) { long q = lroundf(src[i] / sc) - off; o[i] = (uint8_t)(q < 0 ? 0 : q > 255 ? 255 : q); } return true; }
        case QNN_DATATYPE_SFIXED_POINT_8: { auto* o = reinterpret_cast<int8_t*>(buf.data());
            for (size_t i = 0; i < n; ++i) { long q = lroundf(src[i] / sc) - off; o[i] = (int8_t)(q < -128 ? -128 : q > 127 ? 127 : q); } return true; }
        default: return false;
    }
}

/** Lee el búfer del tensor como float. */
bool readFloats(const Qnn_Tensor_t& t, const std::vector<uint8_t>& buf, float* dst, size_t n) {
    const Qnn_DataType_t d = tv(t).dataType;
    float sc; int32_t off; scaleOffset(t, sc, off);
    switch (d) {
        case QNN_DATATYPE_FLOAT_32: memcpy(dst, buf.data(), n * 4); return true;
        case QNN_DATATYPE_FLOAT_16: { auto* s = reinterpret_cast<const uint16_t*>(buf.data()); for (size_t i = 0; i < n; ++i) dst[i] = halfToFloat(s[i]); return true; }
        case QNN_DATATYPE_UFIXED_POINT_16: { auto* s = reinterpret_cast<const uint16_t*>(buf.data()); for (size_t i = 0; i < n; ++i) dst[i] = ((float)s[i] + off) * sc; return true; }
        case QNN_DATATYPE_SFIXED_POINT_16: { auto* s = reinterpret_cast<const int16_t*>(buf.data()); for (size_t i = 0; i < n; ++i) dst[i] = ((float)s[i] + off) * sc; return true; }
        case QNN_DATATYPE_UFIXED_POINT_8: { auto* s = buf.data(); for (size_t i = 0; i < n; ++i) dst[i] = ((float)s[i] + off) * sc; return true; }
        case QNN_DATATYPE_SFIXED_POINT_8: { auto* s = reinterpret_cast<const int8_t*>(buf.data()); for (size_t i = 0; i < n; ++i) dst[i] = ((float)s[i] + off) * sc; return true; }
        default: return false;
    }
}

// ---------------------------------------------------------------------------
// Librerías de Qualcomm
// ---------------------------------------------------------------------------
void qnnLog(const char* fmt, QnnLog_Level_t level, uint64_t, va_list args) {
    char buf[1024];
    vsnprintf(buf, sizeof buf, fmt, args);
    NPU_LOG(level <= QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : level == QNN_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "QNN: %s", buf);
    if (level <= QNN_LOG_LEVEL_ERROR) setErr(std::string("QNN: ") + buf);
}

bool openQnn(const std::string& libDir) {
    if (g_qnnReady) return true;
    // La NPU busca sus librerías (libQnnHtpV**Skel.so) en ADSP_LIBRARY_PATH: primero la carpeta de la app
    const char* prev = getenv("ADSP_LIBRARY_PATH");
    std::string adsp = libDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp;/dsp";
    if (prev && *prev && std::string(prev).find(libDir) == std::string::npos) adsp += std::string(";") + prev;
    if (!prev || std::string(prev).find(libDir) == std::string::npos) setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    g_htpLib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_htpLib) g_htpLib = dlopen((libDir + "/libQnnHtp.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!g_htpLib) { setErr(std::string("No se pudo abrir libQnnHtp.so: ") + (dlerror() ? dlerror() : "")); return false; }
    g_sysLib = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_sysLib) g_sysLib = dlopen((libDir + "/libQnnSystem.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!g_sysLib) { setErr(std::string("No se pudo abrir libQnnSystem.so: ") + (dlerror() ? dlerror() : "")); return false; }

    auto getProviders = reinterpret_cast<GetProvidersFn>(dlsym(g_htpLib, "QnnInterface_getProviders"));
    auto getSysProviders = reinterpret_cast<GetSysProvidersFn>(dlsym(g_sysLib, "QnnSystemInterface_getProviders"));
    if (!getProviders || !getSysProviders) { setErr("Las librerías QNN no tienen las funciones esperadas"); return false; }

    const QnnInterface_t** prov = nullptr; uint32_t np = 0;
    if (getProviders(&prov, &np) != QNN_SUCCESS || !prov || np == 0) { setErr("QNN no entregó su interfaz"); return false; }
    bool found = false;
    for (uint32_t i = 0; i < np; ++i) {
        if (prov[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            prov[i]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            g_qnn = prov[i]->QNN_INTERFACE_VER_NAME; found = true; break;
        }
    }
    if (!found) { setErr("La versión de QNN del teléfono no coincide con la del motor"); return false; }

    const QnnSystemInterface_t** sprov = nullptr; uint32_t ns = 0;
    if (getSysProviders(&sprov, &ns) != QNN_SUCCESS || !sprov || ns == 0) { setErr("QNN System no entregó su interfaz"); return false; }
    found = false;
    for (uint32_t i = 0; i < ns; ++i) {
        if (sprov[i]->systemApiVersion.major == QNN_SYSTEM_API_VERSION_MAJOR &&
            sprov[i]->systemApiVersion.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
            g_sys = sprov[i]->QNN_SYSTEM_INTERFACE_VER_NAME; found = true; break;
        }
    }
    if (!found) { setErr("La versión de QNN System no coincide"); return false; }

    if (g_qnn.logCreate) g_qnn.logCreate(qnnLog, QNN_LOG_LEVEL_WARN, &g_log);
    if (!g_qnn.backendCreate || g_qnn.backendCreate(g_log, nullptr, &g_backend) != QNN_SUCCESS || !g_backend) {
        setErr("No se pudo iniciar la NPU (QnnBackend_create)"); return false;
    }
    if (g_qnn.deviceCreate && g_qnn.deviceCreate(g_log, nullptr, &g_device) != QNN_SUCCESS) g_device = nullptr;
    g_qnnReady = true;
    return true;
}

void freePart(Part& p) {
    if (p.ctx && g_qnn.contextFree) g_qnn.contextFree(p.ctx, nullptr);
    if (p.sysCtx && g_sys.systemContextFree) g_sys.systemContextFree(p.sysCtx);
    p = Part{};
}

void closeQnn() {
    freePart(g_text); freePart(g_unet); freePart(g_vae);
    g_loaded = false;
    if (g_device && g_qnn.deviceFree) g_qnn.deviceFree(g_device);
    if (g_backend && g_qnn.backendFree) g_qnn.backendFree(g_backend);
    if (g_log && g_qnn.logFree) g_qnn.logFree(g_log);
    g_device = nullptr; g_backend = nullptr; g_log = nullptr;
    g_qnnReady = false;
    // (las librerías quedan abiertas: GenieX usa las mismas y cerrarlas podría molestarle)
}

/** Carga una parte desde su archivo .bin (contexto ya compilado para este chip). */
bool loadPart(Part& p, const std::string& name, const std::string& path) {
    p.name = name;
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) { setErr("No se encontró " + name + " (" + path + ")"); return false; }
    struct stat st{};
    fstat(fd, &st);
    const size_t size = (size_t)st.st_size;
    void* map = size ? mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0) : MAP_FAILED;
    close(fd);
    if (map == MAP_FAILED) { setErr("No se pudo leer " + name); return false; }

    bool ok = false;
    do {
        if (g_sys.systemContextCreate(&p.sysCtx) != QNN_SUCCESS) { setErr("QnnSystemContext_create falló"); break; }
        const QnnSystemContext_BinaryInfo_t* info = nullptr;
        Qnn_ContextBinarySize_t infoSize = 0;
        if (g_sys.systemContextGetBinaryInfo(p.sysCtx, map, size, &info, &infoSize) != QNN_SUCCESS || !info) {
            setErr("El archivo " + name + " no es un modelo de NPU válido para esta versión"); break;
        }
        uint32_t numGraphs = 0; QnnSystemContext_GraphInfo_t* graphs = nullptr;
        if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) { numGraphs = info->contextBinaryInfoV1.numGraphs; graphs = info->contextBinaryInfoV1.graphs; }
        else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) { numGraphs = info->contextBinaryInfoV2.numGraphs; graphs = info->contextBinaryInfoV2.graphs; }
        else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) { numGraphs = info->contextBinaryInfoV3.numGraphs; graphs = info->contextBinaryInfoV3.graphs; }
        if (numGraphs < 1 || !graphs) { setErr(name + ": el modelo no trae ningún grafo"); break; }
        const QnnSystemContext_GraphInfo_t& g = graphs[0];
        const char* gname = nullptr; uint32_t nin = 0, nout = 0; Qnn_Tensor_t* tin = nullptr; Qnn_Tensor_t* tout = nullptr;
        if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) { gname = g.graphInfoV1.graphName; nin = g.graphInfoV1.numGraphInputs; tin = g.graphInfoV1.graphInputs; nout = g.graphInfoV1.numGraphOutputs; tout = g.graphInfoV1.graphOutputs; }
        else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) { gname = g.graphInfoV2.graphName; nin = g.graphInfoV2.numGraphInputs; tin = g.graphInfoV2.graphInputs; nout = g.graphInfoV2.numGraphOutputs; tout = g.graphInfoV2.graphOutputs; }
        else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) { gname = g.graphInfoV3.graphName; nin = g.graphInfoV3.numGraphInputs; tin = g.graphInfoV3.graphInputs; nout = g.graphInfoV3.numGraphOutputs; tout = g.graphInfoV3.graphOutputs; }
        if (!gname || nin == 0 || nout == 0) { setErr(name + ": el grafo no tiene entradas o salidas"); break; }

        if (g_qnn.contextCreateFromBinary(g_backend, g_device, nullptr, map, size, &p.ctx, nullptr) != QNN_SUCCESS || !p.ctx) {
            std::lock_guard<std::mutex> lk(g_errMutex);
            g_err = "La NPU no pudo abrir " + name + (g_err.empty() ? "" : " (" + g_err + ")");
            break;
        }
        if (g_qnn.graphRetrieve(p.ctx, gname, &p.graph) != QNN_SUCCESS || !p.graph) { setErr(name + ": no se encontró el grafo " + gname); break; }

        for (uint32_t i = 0; i < nin; ++i) p.inputs.push_back(tin[i]);
        for (uint32_t i = 0; i < nout; ++i) p.outputs.push_back(tout[i]);
        auto prep = [&](std::vector<Qnn_Tensor_t>& ts, std::vector<std::vector<uint8_t>>& bufs) -> bool {
            for (auto& t : ts) {
                const size_t bytes = elemCount(t) * elemBytes(tv(t).dataType);
                if (bytes == 0) { setErr(name + ": tipo de tensor no soportado " + dimsText(t)); return false; }
                bufs.emplace_back(bytes);
                tv(t).memType = QNN_TENSORMEMTYPE_RAW;
                tv(t).clientBuf.data = bufs.back().data();
                tv(t).clientBuf.dataSize = (uint32_t)bytes;
            }
            return true;
        };
        if (!prep(p.inputs, p.inBufs) || !prep(p.outputs, p.outBufs)) break;
        // (los búferes no se mueven: se reservaron todos antes de apuntar a ellos)
        for (size_t i = 0; i < p.inputs.size(); ++i) tv(p.inputs[i]).clientBuf.data = p.inBufs[i].data();
        for (size_t i = 0; i < p.outputs.size(); ++i) tv(p.outputs[i]).clientBuf.data = p.outBufs[i].data();
        std::string desc = name + ": grafo " + gname;
        for (auto& t : p.inputs) desc += " | entrada " + tensorName(t) + " " + dimsText(t);
        for (auto& t : p.outputs) desc += " | salida " + tensorName(t) + " " + dimsText(t);
        NPU_LOG(ANDROID_LOG_INFO, "%s", desc.c_str());
        ok = true;
    } while (false);
    munmap(map, size);   // (la NPU ya copió el modelo a su memoria)
    if (!ok) freePart(p);
    return ok;
}

bool execute(Part& p) {
    const Qnn_ErrorHandle_t e = g_qnn.graphExecute(p.graph, p.inputs.data(), (uint32_t)p.inputs.size(),
                                                   p.outputs.data(), (uint32_t)p.outputs.size(), nullptr, nullptr);
    if (e != QNN_SUCCESS) { setErr(p.name + ": la NPU falló al ejecutar (código " + std::to_string((unsigned long)e) + ")"); return false; }
    return true;
}

/** ¿Es NHWC? ([1,H,W,4] en vez de [1,4,H,W]) */
bool isNhwc(const Qnn_Tensor_t& t) { return tv(t).rank == 4 && tv(t).dimensions[3] == 4 && tv(t).dimensions[1] != 4; }

void toLayout(const std::vector<float>& nchw, int h, int w, bool nhwc, std::vector<float>& out) {
    out.resize(nchw.size());
    if (!nhwc) { out = nchw; return; }
    for (int c = 0; c < 4; ++c) for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x)
        out[((size_t)y * w + x) * 4 + c] = nchw[((size_t)c * h + y) * w + x];
}

void fromLayout(const std::vector<float>& in, int h, int w, bool nhwc, std::vector<float>& nchw) {
    nchw.resize(in.size());
    if (!nhwc) { nchw = in; return; }
    for (int c = 0; c < 4; ++c) for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x)
        nchw[((size_t)c * h + y) * w + x] = in[((size_t)y * w + x) * 4 + c];
}

// ---------------------------------------------------------------------------
// Texto → 77 tokens (CLIP) → embedding [77×768]
// ---------------------------------------------------------------------------
bool encodeText(const std::string& text, std::vector<float>& emb) {
    static CLIPTokenizer tok;   // (el mismo de stable-diffusion.cpp, con su vocabulario incluido)
    std::vector<int> ids;
    if (!tok.encode(text, ids)) ids.clear();
    std::vector<int32_t> t77;
    t77.push_back(49406);                                   // <|startoftext|>
    for (size_t i = 0; i < ids.size() && t77.size() < 76; ++i) t77.push_back(ids[i]);
    while (t77.size() < 77) t77.push_back(49407);           // <|endoftext|> (también de relleno, como SD 1.5)

    Qnn_Tensor_t& in = g_text.inputs[0];
    const size_t n = elemCount(in);
    if (n != 77) { setErr("text_encoder: esperaba 77 tokens y pide " + std::to_string(n)); return false; }
    const Qnn_DataType_t d = tv(in).dataType;
    if (d == QNN_DATATYPE_INT_32 || d == QNN_DATATYPE_UINT_32) memcpy(g_text.inBufs[0].data(), t77.data(), 77 * 4);
    else if (d == QNN_DATATYPE_INT_64 || d == QNN_DATATYPE_UINT_64) { auto* o = reinterpret_cast<int64_t*>(g_text.inBufs[0].data()); for (int i = 0; i < 77; ++i) o[i] = t77[i]; }
    else { std::vector<float> f(t77.begin(), t77.end()); if (!writeFloats(in, f.data(), 77, g_text.inBufs[0])) { setErr("text_encoder: tipo de entrada no soportado"); return false; } }
    if (!execute(g_text)) return false;
    const size_t m = elemCount(g_text.outputs[0]);
    emb.resize(m);
    if (!readFloats(g_text.outputs[0], g_text.outBufs[0], emb.data(), m)) { setErr("text_encoder: tipo de salida no soportado"); return false; }
    return true;
}

// ---------------------------------------------------------------------------
// Calendario de ruido de SD 1.5 y DPM++ 2M (Karras)
// ---------------------------------------------------------------------------
std::vector<float> g_trainSigmas;   // 1000 sigmas del entrenamiento
std::vector<float> g_logSigmas;

void initSchedule() {
    if (!g_trainSigmas.empty()) return;
    g_trainSigmas.resize(1000); g_logSigmas.resize(1000);
    const double b0 = std::sqrt(0.00085), b1 = std::sqrt(0.012);
    double ac = 1.0;
    for (int i = 0; i < 1000; ++i) {
        const double b = std::pow(b0 + (b1 - b0) * i / 999.0, 2.0);
        ac *= (1.0 - b);
        g_trainSigmas[i] = (float)std::sqrt((1.0 - ac) / ac);
        g_logSigmas[i] = std::log(g_trainSigmas[i]);
    }
}

/** sigma → timestep (0..999) interpolando en log(sigma). */
float sigmaToT(float sigma) {
    const float ls = std::log(std::max(sigma, 1e-10f));
    if (ls <= g_logSigmas.front()) return 0.f;
    if (ls >= g_logSigmas.back()) return 999.f;
    int lo = 0, hi = 999;
    while (hi - lo > 1) { const int mid = (lo + hi) / 2; if (g_logSigmas[mid] <= ls) lo = mid; else hi = mid; }
    const float w = (ls - g_logSigmas[lo]) / (g_logSigmas[hi] - g_logSigmas[lo]);
    return lo + w;
}

std::vector<float> karrasSigmas(int n) {
    const float smin = g_trainSigmas.front(), smax = g_trainSigmas.back(), rho = 7.f;
    const float a = std::pow(smax, 1.f / rho), b = std::pow(smin, 1.f / rho);
    std::vector<float> s(n + 1);
    for (int i = 0; i < n; ++i) s[i] = std::pow(a + (float)i / std::max(1, n - 1) * (b - a), rho);
    s[n] = 0.f;
    return s;
}

}  // namespace

// ============================================================================
// JNI (com.paulrj.cumulusia.ai.ImageGenNpu)
// ============================================================================
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNpu_nativeNpuAvailable(JNIEnv*, jobject) { return JNI_TRUE; }

/**
 * Carga las 3 partes. textEncoder/unet/vae = rutas de los .bin · libDir = carpeta de las librerías nativas de la app.
 */
JNIEXPORT jboolean JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNpu_nativeNpuLoad(JNIEnv* env, jobject, jstring jText, jstring jUnet, jstring jVae, jstring jLibDir) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto js = [&](jstring s) { std::string o; if (s) { const char* c = env->GetStringUTFChars(s, nullptr); if (c) { o = c; env->ReleaseStringUTFChars(s, c); } } return o; };
    const std::string text = js(jText), unet = js(jUnet), vae = js(jVae), libDir = js(jLibDir);
    setErr("");
    if (g_loaded) return JNI_TRUE;
    if (!openQnn(libDir)) { closeQnn(); return JNI_FALSE; }
    if (!loadPart(g_text, "text_encoder", text) || !loadPart(g_unet, "unet", unet) || !loadPart(g_vae, "vae", vae)) {
        closeQnn();
        return JNI_FALSE;
    }
    if (g_unet.inputs.size() < 3) { setErr("unet: esperaba 3 entradas y tiene " + std::to_string(g_unet.inputs.size())); closeQnn(); return JNI_FALSE; }
    g_loaded = true;
    return JNI_TRUE;
}

/**
 * Imagen nueva desde cero. Devuelve w*h*3 bytes RGB (w y h salen del modelo: 512×512) o null.
 * La primera fila del resultado lleva el ancho y alto: se devuelve [w(4 bytes), h(4 bytes), RGB…].
 */
JNIEXPORT jbyteArray JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNpu_nativeNpuTxt2Img(JNIEnv* env, jobject, jstring jPrompt, jstring jNegative,
                                                          jint steps, jfloat cfg, jlong seed) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_loaded) { setErr("La IA de imágenes de la NPU no está cargada"); return nullptr; }
    auto js = [&](jstring s) { std::string o; if (s) { const char* c = env->GetStringUTFChars(s, nullptr); if (c) { o = c; env->ReleaseStringUTFChars(s, c); } } return o; };
    const std::string prompt = js(jPrompt), negative = js(jNegative);
    g_cancel = false;
    setErr("");
    initSchedule();
    const int nSteps = steps > 0 ? steps : 20;
    g_step = 0; g_steps = nSteps + 2;

    // 1) Texto → embeddings (con la descripción y con lo que NO se quiere)
    std::vector<float> cond, uncond;
    if (!encodeText(prompt, cond) || !encodeText(negative, uncond)) return nullptr;
    g_step = 1;

    // 2) Entradas de la UNet: cuál es cuál (por nombre; si no, por forma)
    int iLat = -1, iT = -1, iEmb = -1;
    for (int i = 0; i < (int)g_unet.inputs.size(); ++i) {
        const std::string n = tensorName(g_unet.inputs[i]);
        const size_t c = elemCount(g_unet.inputs[i]);
        if (n.find("latent") != std::string::npos || (iLat < 0 && tv(g_unet.inputs[i]).rank == 4)) { if (iLat < 0) iLat = i; continue; }
        if (n.find("time") != std::string::npos || c == 1) { if (iT < 0) iT = i; continue; }
        if (iEmb < 0) iEmb = i;
    }
    if (iLat < 0 || iT < 0 || iEmb < 0) { setErr("unet: no reconozco sus entradas"); return nullptr; }
    const Qnn_Tensor_t& latT = g_unet.inputs[iLat];
    const bool nhwc = isNhwc(latT);
    const int H = (int)(nhwc ? tv(latT).dimensions[1] : tv(latT).dimensions[2]);
    const int W = (int)(nhwc ? tv(latT).dimensions[2] : tv(latT).dimensions[3]);
    const size_t L = (size_t)4 * H * W;
    if (elemCount(latT) != L || elemCount(g_unet.inputs[iEmb]) != cond.size() || elemCount(g_unet.outputs[0]) != L) {
        setErr("unet: tamaños inesperados (latente " + dimsText(latT) + ", texto " + dimsText(g_unet.inputs[iEmb]) + ")");
        return nullptr;
    }
    const bool outNhwc = isNhwc(g_unet.outputs[0]) || (tv(g_unet.outputs[0]).rank == 4 && nhwc && tv(g_unet.outputs[0]).dimensions[3] == 4);

    // 3) Ruido inicial y pasos (DPM++ 2M Karras)
    std::mt19937_64 rng((uint64_t)seed);
    std::normal_distribution<float> nd(0.f, 1.f);
    const std::vector<float> sig = karrasSigmas(nSteps);
    std::vector<float> x(L);
    for (auto& v : x) v = nd(rng) * sig[0];
    std::vector<float> old, denoised(L), lin(L), lay, ec(L), eu(L), raw(L);

    auto runUnet = [&](const std::vector<float>& emb, std::vector<float>& eps) -> bool {
        if (!writeFloats(g_unet.inputs[iEmb], emb.data(), emb.size(), g_unet.inBufs[iEmb])) { setErr("unet: tipo de entrada de texto no soportado"); return false; }
        if (!execute(g_unet)) return false;
        if (!readFloats(g_unet.outputs[0], g_unet.outBufs[0], raw.data(), L)) { setErr("unet: tipo de salida no soportado"); return false; }
        fromLayout(raw, H, W, outNhwc, eps);
        return true;
    };

    for (int i = 0; i < nSteps; ++i) {
        if (g_cancel.load()) { setErr(""); return nullptr; }
        const float s = sig[i], sn = sig[i + 1];
        const float cin = 1.f / std::sqrt(s * s + 1.f);
        for (size_t k = 0; k < L; ++k) lin[k] = x[k] * cin;
        toLayout(lin, H, W, nhwc, lay);
        if (!writeFloats(latT, lay.data(), L, g_unet.inBufs[iLat])) { setErr("unet: tipo de entrada latente no soportado"); return nullptr; }
        const float t = sigmaToT(s);
        if (!writeFloats(g_unet.inputs[iT], &t, 1, g_unet.inBufs[iT])) { setErr("unet: tipo de entrada de tiempo no soportado"); return nullptr; }
        if (!runUnet(cond, ec)) return nullptr;
        if (cfg > 1.f) { if (!runUnet(uncond, eu)) return nullptr; }
        for (size_t k = 0; k < L; ++k) {
            const float e = cfg > 1.f ? eu[k] + cfg * (ec[k] - eu[k]) : ec[k];
            denoised[k] = x[k] - s * e;
        }
        if (sn <= 0.f) {
            x = denoised;
        } else {
            const float tc = -std::log(s), tn = -std::log(sn), h = tn - tc;
            const float a = sn / s, b = -std::expm1(-h);
            if (old.empty()) {
                for (size_t k = 0; k < L; ++k) x[k] = a * x[k] + b * denoised[k];
            } else {
                const float hLast = tc - (-std::log(sig[i - 1]));
                const float r = hLast / h;
                const float c1 = 1.f + 1.f / (2.f * r), c2 = 1.f / (2.f * r);
                for (size_t k = 0; k < L; ++k) x[k] = a * x[k] + b * (c1 * denoised[k] - c2 * old[k]);
            }
        }
        old = denoised;
        g_step = i + 2;
    }
    if (g_cancel.load()) return nullptr;

    // 4) VAE: latente → imagen
    Part& v = g_vae;
    const Qnn_Tensor_t& vin = v.inputs[0];
    if (elemCount(vin) != L) { setErr("vae: tamaño de entrada inesperado " + dimsText(vin)); return nullptr; }
    toLayout(x, H, W, isNhwc(vin), lay);
    if (!writeFloats(vin, lay.data(), L, v.inBufs[0])) { setErr("vae: tipo de entrada no soportado"); return nullptr; }
    if (!execute(v)) return nullptr;
    const Qnn_Tensor_t& vout = v.outputs[0];
    const size_t pn = elemCount(vout);
    std::vector<float> img(pn);
    if (!readFloats(vout, v.outBufs[0], img.data(), pn)) { setErr("vae: tipo de salida no soportado"); return nullptr; }
    // Forma de la imagen: [1,H,W,3] (NHWC, la de Qualcomm) o [1,3,H,W]
    int ih = 0, iw = 0; bool chw = false;
    if (tv(vout).rank == 4 && tv(vout).dimensions[3] == 3) { ih = tv(vout).dimensions[1]; iw = tv(vout).dimensions[2]; }
    else if (tv(vout).rank == 4 && tv(vout).dimensions[1] == 3) { ih = tv(vout).dimensions[2]; iw = tv(vout).dimensions[3]; chw = true; }
    else { setErr("vae: forma de salida inesperada " + dimsText(vout)); return nullptr; }
    // (si la salida viene en -1..1 en vez de 0..1, se corrige)
    float mn = 1e9f, mx = -1e9f;
    for (size_t k = 0; k < pn; k += 97) { mn = std::min(mn, img[k]); mx = std::max(mx, img[k]); }
    const bool minus1 = mn < -0.2f;
    std::vector<uint8_t> rgb(8 + (size_t)ih * iw * 3);
    memcpy(rgb.data(), &iw, 4); memcpy(rgb.data() + 4, &ih, 4);
    for (int y = 0; y < ih; ++y) for (int xx = 0; xx < iw; ++xx) for (int c = 0; c < 3; ++c) {
        float f = chw ? img[((size_t)c * ih + y) * iw + xx] : img[((size_t)y * iw + xx) * 3 + c];
        if (minus1) f = f * 0.5f + 0.5f;
        const int q = (int)lroundf(f * 255.f);
        rgb[8 + ((size_t)y * iw + xx) * 3 + c] = (uint8_t)(q < 0 ? 0 : q > 255 ? 255 : q);
    }
    g_step = g_steps.load();
    jbyteArray out = env->NewByteArray((jsize)rgb.size());
    if (out) env->SetByteArrayRegion(out, 0, (jsize)rgb.size(), reinterpret_cast<const jbyte*>(rgb.data()));
    return out;
}

JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNpu_nativeNpuCancel(JNIEnv*, jobject) { g_cancel = true; }

/** Libera la NPU (toda la memoria del modelo de imágenes). */
JNIEXPORT void JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNpu_nativeNpuFree(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_mutex);
    closeQnn();
}

JNIEXPORT jint JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNpu_nativeNpuProgress(JNIEnv*, jobject) {
    const int s = g_steps.load();
    return s > 0 ? (jint)(g_step.load() * 100 / s) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_paulrj_cumulusia_ai_ImageGenNpu_nativeNpuLastError(JNIEnv* env, jobject) {
    std::string e;
    { std::lock_guard<std::mutex> lk(g_errMutex); e = g_err; }
    // (solo ASCII y letras con tilde de 2 bytes: NewStringUTF nunca se cae)
    std::string safe;
    for (size_t i = 0; i < e.size();) {
        const unsigned char c = (unsigned char)e[i];
        if (c >= 0x20 && c < 0x7F) { safe += (char)c; ++i; continue; }
        if (c >= 0xC2 && c <= 0xDF && i + 1 < e.size() && (((unsigned char)e[i + 1]) & 0xC0) == 0x80) { safe.append(e, i, 2); i += 2; continue; }
        ++i;
    }
    return env->NewStringUTF(safe.c_str());
}

}  // extern "C"
