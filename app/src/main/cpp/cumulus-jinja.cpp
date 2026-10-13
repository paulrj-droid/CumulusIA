// 💬 Ejecuta la plantilla Jinja que trae cada GGUF (ver cumulus-jinja.h)
#include "cumulus-jinja.h"

// llama.cpp v0.6.0+ usa su propio JSON (common_json); las versiones anteriores, nlohmann::ordered_json
#ifdef CUMULUS_JINJA_COMMON_JSON
#include "json.h"
using cjson = common_json;
#else
#include <nlohmann/json.hpp>
using cjson = nlohmann::ordered_json;
#endif
#include "jinja/caps.h"
#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"
#include "jinja/value.h"

#include <exception>

namespace cumulus {

struct JinjaTemplate {
    jinja::program prog;
    std::string src;
    std::string bos;
    std::string eos;
    jinja::caps caps;
    bool usesThinking = false;
};

std::shared_ptr<JinjaTemplate> jinjaCompile(const std::string& source, const std::string& bosToken,
                                            const std::string& eosToken, std::string* err) {
    if (source.empty()) { if (err) *err = "el modelo no trae plantilla"; return nullptr; }
    try {
        auto t = std::make_shared<JinjaTemplate>();
        jinja::lexer lexer;
        auto lexed = lexer.tokenize(source);
        t->prog = jinja::parse_from_tokens(lexed);
        t->src = lexed.source;
        t->bos = bosToken;
        t->eos = eosToken;
        t->caps = jinja::caps_get(t->prog);
        t->usesThinking = source.find("enable_thinking") != std::string::npos;
        return t;
    } catch (const std::exception& e) {
        if (err) *err = e.what();
    } catch (...) {
        if (err) *err = "error desconocido al leer la plantilla";
    }
    return nullptr;
}

bool jinjaRender(const JinjaTemplate& t, const std::string& system, const std::vector<std::string>& roles,
                 const std::vector<std::string>& contents, bool addGenerationPrompt, bool enableThinking,
                 std::string& out, std::string* err) {
    try {
        // Contenido como texto, o como lista [{type:text}] si la plantilla solo acepta esa forma
        const bool typed = !t.caps.supports_string_content && t.caps.supports_typed_content;
        auto content = [&](const std::string& text) -> cjson {
            if (!typed) return cjson(text);
            cjson arr = cjson::array();
            arr.push_back(cjson{ {"type", "text"}, {"text", text} });
            return arr;
        };
        cjson messages = cjson::array();
        std::string pendingSystem;
        if (!system.empty()) {
            if (t.caps.supports_system_role) messages.push_back(cjson{ {"role", "system"}, {"content", content(system)} });
            else pendingSystem = system;   // sin rol «system» (Gemma…): va al comienzo del primer mensaje del usuario
        }
        for (size_t i = 0; i < roles.size() && i < contents.size(); i++) {
            const std::string role = roles[i] == "assistant" ? "assistant" : "user";
            std::string text = contents[i];
            if (!pendingSystem.empty() && role == "user") { text = pendingSystem + "\n\n" + text; pendingSystem.clear(); }
            messages.push_back(cjson{ {"role", role}, {"content", content(text)} });
        }

        cjson inp = cjson::object();
        inp["messages"] = messages;
        inp["bos_token"] = t.bos;
        inp["eos_token"] = t.eos;
        inp["add_generation_prompt"] = addGenerationPrompt;
        inp["enable_thinking"] = enableThinking;

        jinja::context ctx(t.src);
        jinja::global_from_json(ctx, inp, true);
        jinja::runtime runtime(ctx);
        const jinja::value results = runtime.execute(t.prog);
        auto parts = jinja::runtime::gather_string_parts(results);
        out = parts->as_string().str();
        if (out.empty()) { if (err) *err = "la plantilla no produjo texto"; return false; }
        return true;
    } catch (const std::exception& e) {
        if (err) *err = e.what();
    } catch (...) {
        if (err) *err = "error desconocido al usar la plantilla";
    }
    return false;
}

std::string jinjaInfo(const JinjaTemplate& t) {
    return std::string("sistema: ") + (t.caps.supports_system_role ? "sí" : "no") +
           " · texto: " + (t.caps.supports_string_content ? "string" : "lista") +
           " · pensamiento: " + (t.usesThinking ? "sí" : "no") +
           " · " + std::to_string(t.src.size()) + " letras";
}

}  // namespace cumulus

