package com.paulrj.cumulusia

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 🖥️ Panel de Vista Previa (estilo Canvas / Artifacts / Web Preview).
 *
 *  • Vista previa web: junta el HTML + CSS + JavaScript que escribió la IA en el chat y lo
 *    ejecuta de verdad en una ventana (puedes tocar botones, escribir, jugar…).
 *  • Maqueta de la app: convierte los diseños XML de una app Android (activity_main.xml, etc.)
 *    en una maqueta visual navegable. La lógica Kotlin solo funciona al compilar la app.
 *
 * Todo ocurre dentro del teléfono: no se sube nada a internet.
 */
object PreviewPanel {

    private data class CodeBlock(val lang: String, val code: String, val fileName: String?)

    // ------------------------------------------------------------------ bloques de código del chat
    private fun codeBlocks(text: String): List<CodeBlock> {
        val out = mutableListOf<CodeBlock>()
        Regex("```([\\w+#.-]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val before = text.substring(maxOf(0, m.range.first - 300), m.range.first)
            val name = Regex("([\\w.-]+\\.(xml|html?|css|js|kt|java|gradle))", RegexOption.IGNORE_CASE)
                .findAll(before).lastOrNull()?.value
            out += CodeBlock(m.groupValues[1].lowercase(), m.groupValues[2], name)
        }
        return out
    }

    // ------------------------------------------------------------------ VISTA PREVIA WEB
    private fun isHtml(b: CodeBlock) = b.lang in setOf("html", "htm", "xhtml") ||
        b.fileName?.matches(Regex("(?i).*\\.html?$")) == true ||
        Regex("(?i)<!doctype html|<html[\\s>]|<body[\\s>]").containsMatchIn(b.code)
    private fun isCss(b: CodeBlock) = b.lang == "css" || b.fileName?.endsWith(".css", true) == true
    private fun isJs(b: CodeBlock) = b.lang in setOf("js", "javascript", "jsx") || b.fileName?.endsWith(".js", true) == true

    /** messagesNewestFirst: textos de las respuestas de la IA, de la más nueva a la más vieja. */
    fun buildWebPage(messagesNewestFirst: List<String>): Pair<String, String>? {
        val all = messagesNewestFirst.map { codeBlocks(it) }
        val htmlIdx = all.indexOfFirst { bl -> bl.any { isHtml(it) } }
        val pool: List<CodeBlock>
        var html: String
        if (htmlIdx >= 0) {
            // El HTML más nuevo + los CSS/JS de ese mensaje y de los mensajes más nuevos (cambios posteriores)
            html = all[htmlIdx].last { isHtml(it) }.code
            pool = all.take(htmlIdx + 1).flatten()
        } else {
            pool = all.firstOrNull { bl -> bl.any { isJs(it) || isCss(it) } } ?: return null
            html = "<!DOCTYPE html><html><head></head><body></body></html>"
        }
        // Solo la versión más nueva de cada archivo CSS/JS
        val css = pool.filter { isCss(it) }.distinctBy { it.fileName ?: it.code.hashCode().toString() }.joinToString("\n") { it.code }
        val js = pool.filter { isJs(it) }.distinctBy { it.fileName ?: it.code.hashCode().toString() }.joinToString("\n;\n") { it.code }
        // Los archivos locales enlazados (style.css, app.js) se reemplazan por el código del chat
        html = html.replace(Regex("(?i)<link[^>]+href=[\"'](?!https?:)[^\"']+\\.css[\"'][^>]*>"), "")
            .replace(Regex("(?i)<script[^>]+src=[\"'](?!https?:)[^\"']+\\.js[\"'][^>]*>\\s*</script>"), "")
        val meta = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        if (!Regex("(?i)<meta[^>]+viewport").containsMatchIn(html)) {
            html = insertAt(html, Regex("(?i)<head[^>]*>"), after = true, insert = meta) ?: (meta + html)
        }
        if (css.isNotBlank()) {
            val tag = "<style>\n$css\n</style>"
            html = insertAt(html, Regex("(?i)</head>"), after = false, insert = tag) ?: (tag + html)
        }
        if (js.isNotBlank()) {
            val tag = "<script>\n$js\n</script>"
            html = insertAt(html, Regex("(?i)</body>(?![\\s\\S]*</body>)"), after = false, insert = tag) ?: (html + tag)
        }
        return html to html
    }

    /** Inserta [insert] antes o después de la primera coincidencia de [rx]; null si no la encuentra. */
    private fun insertAt(text: String, rx: Regex, after: Boolean, insert: String): String? {
        val m = rx.find(text) ?: return null
        val pos = if (after) m.range.last + 1 else m.range.first
        return text.substring(0, pos) + insert + text.substring(pos)
    }

    // ------------------------------------------------------------------ MAQUETA DE APP ANDROID
    private val viewRoots = Regex("(?i)<(LinearLayout|androidx\\.[\\w.]*Layout|[\\w.]*ConstraintLayout|RelativeLayout|FrameLayout|ScrollView|" +
        "[\\w.]*NestedScrollView|[\\w.]*CoordinatorLayout|[\\w.]*CardView|[\\w.]*DrawerLayout|TableLayout|GridLayout|merge)[\\s>]")

    fun buildAppMockup(messagesNewestFirst: List<String>): Pair<String, String>? {
        val layouts = LinkedHashMap<String, String>()
        val strings = HashMap<String, String>()
        val colors = HashMap<String, String>()
        val shapes = HashMap<String, String>()   // fondos redondeados (res/drawable/*.xml con <shape>)
        var n = 1
        // Del más viejo al más nuevo, para que la versión nueva de un archivo reemplace a la vieja
        for (msg in messagesNewestFirst.asReversed()) for (b in codeBlocks(msg)) {
            val c = b.code
            if (!(b.lang == "xml" || c.trimStart().startsWith("<"))) continue
            when {
                c.contains("<shape") && b.fileName != null -> shapes[b.fileName.substringBeforeLast('.')] = c
                c.contains("<resources") -> {
                    Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(c)
                        .forEach { strings[it.groupValues[1]] = it.groupValues[2].replace("\\'", "'").replace("\\n", " ").trim() }
                    Regex("<color\\s+name=\"([^\"]+)\"[^>]*>(.*?)</color>").findAll(c)
                        .forEach { colors[it.groupValues[1]] = it.groupValues[2].trim() }
                }
                viewRoots.containsMatchIn(c) && !c.contains("<manifest") -> {
                    val name = b.fileName?.substringBeforeLast('.') ?: "pantalla_${n++}"
                    layouts.remove(name); layouts[name] = c
                }
            }
        }
        if (layouts.isEmpty()) return null
        // Pantallas principales primero
        val ordered = layouts.entries.sortedBy { if (it.key.startsWith("activity")) 0 else if (it.key.startsWith("fragment")) 1 else 2 }
        val tabs = StringBuilder(); val screens = StringBuilder()
        ordered.forEachIndexed { i, (name, xml) ->
            tabs.append("<button class='tab${if (i == 0) " on" else ""}' onclick='show($i)'>${esc(name)}</button>")
            val body = runCatching { XmlToHtml(strings, colors, layouts, shapes).convert(xml, 0) }
                .getOrElse { "<div class='err'>No pude dibujar este diseño (el XML tiene un error):<br>${esc(it.message ?: "")}</div>" }
            screens.append("<div class='screen' id='s$i' style='display:${if (i == 0) "flex" else "none"}'>$body</div>")
        }
        val page = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
 body{margin:0;background:#0b0b0d;font-family:Roboto,'Segoe UI',sans-serif;color:#e8e8ea}
 .tabs{display:flex;gap:6px;overflow-x:auto;padding:10px;background:#141417;position:sticky;top:0;z-index:5}
 .tab{border:1px solid #3a3a3e;background:#1d1d21;color:#ddd;border-radius:16px;padding:6px 12px;font-size:13px;white-space:nowrap}
 .tab.on{background:#3D5AFE;border-color:#3D5AFE;color:#fff}
 .phone{margin:12px auto;width:min(380px,94vw);min-height:640px;background:#121212;border:8px solid #222;border-radius:28px;overflow:hidden;position:relative}
 .screen{flex-direction:column;min-height:624px;position:relative}
 .v{box-sizing:border-box;min-width:0}
 .txt{padding:2px 0;font-size:14px}
 .btn{background:#3D5AFE;color:#fff;border:none;border-radius:20px;padding:10px 16px;font-size:14px;margin:4px 0}
 .inp{background:#1f1f23;border:1px solid #444;border-radius:8px;color:#fff;padding:10px;font-size:14px;margin:4px 0}
 .img{background:#2a2a30;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#888;min-height:48px;min-width:48px}
 .card{background:#1e1e22;border-radius:14px;padding:12px;margin:6px 0;box-shadow:0 2px 6px #0006}
 .bar{background:#3D5AFE;color:#fff;padding:14px 16px;font-size:18px;font-weight:600}
 .row{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #2a2a2e}
 .fab{position:absolute;right:16px;bottom:16px;width:56px;height:56px;border-radius:16px;background:#3D5AFE;color:#fff;border:none;font-size:24px}
 .err{color:#ff8a80;padding:16px}
 .ibtn{background:transparent;border:none;color:#e8e8ea;width:44px;height:44px;min-width:44px;border-radius:50%;font-size:20px;display:flex;align-items:center;justify-content:center;flex:none;padding:0}
 .dl{flex:1;min-height:624px}
 .drawer{position:absolute;top:0;bottom:0;max-width:86%;z-index:6;transition:transform .25s;box-shadow:0 0 24px #000a;overflow:auto;align-self:stretch!important;height:auto!important;display:flex;flex-direction:column}
 .dl-left{left:0;transform:translateX(-105%)} .dl-right{right:0;transform:translateX(105%)}
 .dl.ol>.dl-left,.dl.or>.dl-right{transform:none}
 .scrim{position:absolute;inset:0;background:#0008;z-index:5;display:none} .dl.ol>.scrim,.dl.or>.scrim{display:block}
 .note{font-size:12px;color:#8e8e93;text-align:center;padding:4px 12px 16px}
</style></head><body>
<div class="tabs">$tabs</div>
<div class="phone">$screens</div>
<div class="note">📱 Maqueta del diseño (XML). Los botones se ven pero la lógica Kotlin solo funciona al compilar la app.</div>
<script>
function dr(e,s){var d=e.closest('.dl');if(!d)return;d.classList.remove('ol','or');if(s)d.classList.add(s);}
function show(i){document.querySelectorAll('.screen').forEach(function(s,k){s.style.display=k==i?'flex':'none'});
 document.querySelectorAll('.tab').forEach(function(t,k){t.classList.toggle('on',k==i)});}
</script></body></html>"""
        val code = ordered.joinToString("\n\n") { "<!-- ${it.key}.xml -->\n${it.value}" }
        return page to code
    }

    /**
     * ✂️ Un diseño XML que llegó cortado (al modelo se le acabó el espacio): se quita la etiqueta a medias
     * del final y se cierran las que quedaron abiertas, para que se pueda dibujar igual.
     */
    fun repairXml(xml: String): String {
        var s = xml.trimEnd()
        val lt = s.lastIndexOf('<'); val gt = s.lastIndexOf('>')
        if (lt > gt) s = s.substring(0, lt).trimEnd()
        val stack = ArrayDeque<String>()
        Regex("<!--.*?-->|<\\?.*?\\?>|<!\\[CDATA\\[.*?]]>|<(/?)([A-Za-z_][\\w.:-]*)((?:[^<>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>",
            RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            val name = m.groupValues[2]
            if (name.isEmpty()) return@forEach
            if (m.groupValues[1] == "/") {
                val idx = stack.lastIndexOf(name)
                if (idx >= 0) while (stack.size > idx) stack.removeLast()
            } else if (m.groupValues[4] != "/") stack.addLast(name)
        }
        val sb = StringBuilder(s)
        while (stack.isNotEmpty()) sb.append("\n</").append(stack.removeLast()).append(">")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "&#39;").replace("\"", "&quot;")

    /** Convierte un diseño XML de Android en HTML aproximado. */
    private class XmlToHtml(val strings: Map<String, String>, val colors: Map<String, String>, val layouts: Map<String, String>,
                            val shapes: Map<String, String> = emptyMap()) {

        fun convert(xml: String, depth: Int): String = try { convertRaw(xml, depth) } catch (e: Exception) {
            // Diseño cortado: se completa y se dibuja lo que alcanzó a escribir la IA
            val fixed = repairXml(xml.trim().removePrefix("\uFEFF"))
            if (fixed == xml.trim()) throw e
            convertRaw(fixed, depth) + (if (depth == 0) "<div class='note'>✂️ Este diseño llegó cortado: completé lo que faltaba para poder mostrarlo.</div>" else "")
        }

        private fun convertRaw(xml: String, depth: Int): String {
            if (depth > 4) return ""
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml.trim().removePrefix("﻿")))
            val sb = StringBuilder()
            val closers = ArrayDeque<String>()
            val parents = ArrayDeque<String>()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val a = HashMap<String, String>()
                        for (i in 0 until p.attributeCount) a[p.getAttributeName(i).substringAfter(':')] = p.getAttributeValue(i)
                        val tag = p.name.substringAfterLast('.')
                        val (open, close) = element(tag, a, depth, parents.lastOrNull())
                        sb.append(open); closers.addLast(close); parents.addLast(tag)
                    }
                    XmlPullParser.END_TAG -> {
                        if (closers.isNotEmpty()) sb.append(closers.removeLast())
                        if (parents.isNotEmpty()) parents.removeLast()
                    }
                }
                ev = p.next()
            }
            return sb.toString()
        }

        private fun res(v: String?): String? {
            if (v == null) return null
            return when {
                v.startsWith("@string/") -> (strings[v.removePrefix("@string/")] ?: v.removePrefix("@string/")).replace("\\'", "'").replace("\\\"", "\"")
                v.startsWith("@android:string/") -> v.substringAfter('/')
                v.startsWith("@color/") -> colors[v.removePrefix("@color/")]?.let { res(it) } ?: guessColor(v.removePrefix("@color/"))
                v.startsWith("@android:color/") -> when (v.substringAfter('/')) { "white" -> "#FFFFFF"; "black" -> "#000000"; "transparent" -> "transparent"; else -> "#888888" }
                v.startsWith("?") || v.startsWith("@") -> null
                else -> v.replace("\\'", "'").replace("\\@", "@").replace("\\?", "?")
            }
        }

        /** Fondo de un @drawable con <shape>: color, degradado, esquinas, borde y forma de círculo. */
        private fun shapeCss(ref: String?): String? {
            if (ref == null || !ref.startsWith("@drawable/")) return null
            val xml = shapes[ref.removePrefix("@drawable/")] ?: return null
            val s = StringBuilder()
            fun attr(tag: String, name: String) = Regex("<$tag\\b[^>]*?android:$name=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            val g1 = color(attr("gradient", "startColor")); val g2 = color(attr("gradient", "endColor"))
            if (g1 != null && g2 != null) s.append("background:linear-gradient(135deg,$g1,$g2);")
            else color(attr("solid", "color"))?.let { s.append("background:$it;") }
            dim(attr("corners", "radius"))?.let { s.append("border-radius:$it;") }
            val sw = dim(attr("stroke", "width")); val sc = color(attr("stroke", "color"))
            if (sw != null && sc != null) s.append("border:$sw solid $sc;")
            if (Regex("android:shape=\"oval\"").containsMatchIn(xml)) s.append("border-radius:50%;")
            return s.toString()
        }

        /**
         * 🎨 Un color que el diseño usa pero no está definido: se adivina por su nombre.
         * Antes TODO lo desconocido salía azul, y si era el fondo la maqueta quedaba entera azul.
         */
        private fun guessColor(name: String): String {
            val n = name.lowercase()
            return when {
                listOf("background", "fondo", "bg", "surface", "superficie", "window", "dark", "oscuro", "black", "negro").any { it in n } -> "#121218"
                listOf("card", "tarjeta", "container", "contenedor").any { it in n } -> "#1E1E26"
                listOf("text", "texto", "onprimary", "on_primary", "white", "blanco", "titulo", "title").any { it in n } -> "#FFFFFF"
                listOf("hint", "secondary_text", "gris", "gray", "grey").any { it in n } -> "#9A9AA5"
                listOf("red", "rojo", "error", "danger").any { it in n } -> "#E53935"
                listOf("green", "verde", "success").any { it in n } -> "#43A047"
                listOf("orange", "naranja", "yellow", "amarillo", "warning").any { it in n } -> "#FFB300"
                listOf("purple", "morado", "violet").any { it in n } -> "#7E57C2"
                listOf("primary", "accent", "blue", "azul", "button", "boton", "botón").any { it in n } -> "#3D5AFE"
                else -> "#2A2A32"
            }
        }

        private fun color(v: String?): String? {
            val c = res(v) ?: return null
            if (!c.startsWith("#")) return null
            val h = c.removePrefix("#")
            return when (h.length) {
                8 -> { val a = h.substring(0, 2).toInt(16) / 255.0; "rgba(${h.substring(2, 4).toInt(16)},${h.substring(4, 6).toInt(16)},${h.substring(6, 8).toInt(16)},$a)" }
                6, 3 -> "#$h"
                else -> null
            }
        }

        private fun dim(v: String?): String? = when {
            v == null -> null
            v == "match_parent" || v == "fill_parent" -> "100%"
            v == "wrap_content" -> null
            v.endsWith("dp") || v.endsWith("dip") || v.endsWith("sp") || v.endsWith("px") ->
                v.replace(Regex("(dp|dip|sp|px)$"), "").toFloatOrNull()?.let { "${it}px" }
            else -> null
        }

        private fun style(tag: String, a: Map<String, String>): String {
            val s = StringBuilder()
            val w = a["layout_width"]; val h = a["layout_height"]
            dim(w)?.let { if (w == "match_parent" || w == "fill_parent") s.append("align-self:stretch;width:auto;") else s.append("width:$it;") }
            dim(h)?.let { if (h == "match_parent" || h == "fill_parent") s.append("flex:1;") else s.append("height:$it;") }
            a["layout_weight"]?.toFloatOrNull()?.let { s.append("flex:$it 1 0;") }
            dim(a["padding"])?.let { s.append("padding:$it;") }
            dim(a["paddingHorizontal"])?.let { s.append("padding-left:$it;padding-right:$it;") }
            dim(a["paddingVertical"])?.let { s.append("padding-top:$it;padding-bottom:$it;") }
            (dim(a["paddingStart"]) ?: dim(a["paddingLeft"]))?.let { s.append("padding-left:$it;") }
            (dim(a["paddingEnd"]) ?: dim(a["paddingRight"]))?.let { s.append("padding-right:$it;") }
            dim(a["paddingTop"])?.let { s.append("padding-top:$it;") }
            dim(a["paddingBottom"])?.let { s.append("padding-bottom:$it;") }
            dim(a["layout_margin"])?.let { s.append("margin:$it;") }
            dim(a["layout_marginHorizontal"])?.let { s.append("margin-left:$it;margin-right:$it;") }
            dim(a["layout_marginVertical"])?.let { s.append("margin-top:$it;margin-bottom:$it;") }
            (dim(a["layout_marginStart"]) ?: dim(a["layout_marginLeft"]))?.let { s.append("margin-left:$it;") }
            (dim(a["layout_marginEnd"]) ?: dim(a["layout_marginRight"]))?.let { s.append("margin-right:$it;") }
            dim(a["layout_marginTop"])?.let { s.append("margin-top:$it;") }
            dim(a["layout_marginBottom"])?.let { s.append("margin-bottom:$it;") }
            val shapeBg = shapeCss(a["background"])
            if (shapeBg != null) s.append(shapeBg)
            else (color(a["background"]) ?: color(a["backgroundTint"]) ?: color(a["cardBackgroundColor"]))?.let { s.append("background:$it;") }
            color(a["textColor"])?.let { s.append("color:$it;") }
            dim(a["textSize"])?.let { s.append("font-size:$it;") }
            if (a["textStyle"]?.contains("bold") == true) s.append("font-weight:700;")
            if (a["textStyle"]?.contains("italic") == true) s.append("font-style:italic;")
            // gravity: «center» centra todo; «center_vertical» solo en vertical (el texto sigue a la izquierda)
            val gv = a["gravity"].orEmpty()
            val row = a["orientation"] == "horizontal"
            if (Regex("(^|\\|)center(\\||$)").containsMatchIn(gv) || a["layout_gravity"]?.let { Regex("(^|\\|)center(\\||$)").containsMatchIn(it) } == true && gv.isEmpty() && tag.endsWith("TextView")) {
                s.append("text-align:center;justify-content:center;align-items:center;")
            } else {
                if (gv.contains("center_vertical")) s.append(if (row) "align-items:center;" else "justify-content:center;")
                if (gv.contains("center_horizontal")) s.append(if (row) "justify-content:center;" else "align-items:center;text-align:center;")
            }
            if (a["layout_gravity"]?.contains("center") == true) s.append("align-self:center;")
            if (a["layout_gravity"]?.contains("end") == true || a["layout_gravity"]?.contains("right") == true) s.append("align-self:flex-end;")
            if (a["visibility"] == "gone") s.append("display:none;")
            if (a["visibility"] == "invisible") s.append("visibility:hidden;")
            dim(a["cardCornerRadius"])?.let { s.append("border-radius:$it;") }
            // 🎨 Detalles de diseño moderno (Material 3) para que la maqueta se vea como la app
            dim(a["cornerRadius"])?.let { s.append("border-radius:$it;") }
            (dim(a["cardElevation"]) ?: dim(a["elevation"]))?.let { s.append("box-shadow:0 ${it} calc($it * 3) #0008;") }
            val stroke = dim(a["strokeWidth"]); val strokeColor = color(a["strokeColor"])
            if (stroke != null && strokeColor != null) s.append("border:$stroke solid $strokeColor;")
            a["fontFamily"]?.let { f -> if (f.contains("medium")) s.append("font-weight:500;") else if (f.contains("bold") || f.contains("black")) s.append("font-weight:700;") }
            a["letterSpacing"]?.toFloatOrNull()?.let { s.append("letter-spacing:${it}em;") }
            a["alpha"]?.toFloatOrNull()?.let { s.append("opacity:$it;") }
            return s.toString()
        }

        /** Ícono de un ImageButton según su imagen, id o descripción (☰ menú, ⚙ ajustes, 🎤, ➤ enviar…). */
        private fun iconFor(a: Map<String, String>): String {
            val k = (a["src"].orEmpty() + " " + a["srcCompat"].orEmpty() + " " + a["id"].orEmpty() + " " + a["contentDescription"].orEmpty()).lowercase()
            return when {
                Regex("menu|hamburg|drawer").containsMatchIn(k) -> "☰"
                Regex("setting|ajuste|config|tune|gear|engran").containsMatchIn(k) -> "⚙"
                Regex("mic|voz|voice|hablar|speak").containsMatchIn(k) -> "🎤"
                Regex("send|enviar").containsMatchIn(k) -> "➤"
                Regex("close|cerrar|clear").containsMatchIn(k) -> "✕"
                Regex("search|buscar|lupa").containsMatchIn(k) -> "🔍"
                Regex("back|atras|atrás|volver|arrow_left").containsMatchIn(k) -> "←"
                Regex("add|agregar|plus|nuevo|new").containsMatchIn(k) -> "＋"
                Regex("more|mas|más|overflow|dots").containsMatchIn(k) -> "⋮"
                Regex("camera|camara|cámara|photo|foto").containsMatchIn(k) -> "📷"
                Regex("share|compartir").containsMatchIn(k) -> "↗"
                Regex("delete|borrar|eliminar|trash").containsMatchIn(k) -> "🗑"
                Regex("play").containsMatchIn(k) -> "▶"
                else -> "●"
            }
        }

        private fun element(tag: String, a: Map<String, String>, depth: Int, parent: String? = null): Pair<String, String> {
            val st = style(tag, a)
            val text = esc(res(a["text"]) ?: "")
            val hint = esc(res(a["hint"]) ?: "")
            val id = a["id"]?.substringAfter('/')?.let { " title='${esc(it)}'" } ?: ""
            val lg = a["layout_gravity"].orEmpty()
            return when {
                // ☰ Paneles laterales (DrawerLayout): ocultos; se abren con los botones de menú y ajustes
                tag == "DrawerLayout" ->
                    "<div class='v dl'$id style='display:flex;flex-direction:column;position:relative;overflow:hidden;$st'>" to
                        "<div class='scrim' onclick=\"dr(this,0)\"></div></div>"
                parent == "DrawerLayout" && (lg.contains("start") || lg.contains("left")) ->
                    "<div class='v drawer dl-left'$id style='$st'>" to "</div>"
                parent == "DrawerLayout" && (lg.contains("end") || lg.contains("right")) ->
                    "<div class='v drawer dl-right'$id style='$st'>" to "</div>"
                tag == "ImageButton" -> {
                    val icon = iconFor(a)
                    val click = when (icon) { "☰" -> " onclick=\"dr(this,'ol')\""; "⚙" -> " onclick=\"dr(this,'or')\""; else -> "" }
                    val tint = color(a["tint"])?.let { "color:$it;" } ?: ""
                    "<button class='ibtn v'$id$click style='$tint$st'>$icon</button>" to ""
                }
                tag == "HorizontalScrollView" -> "<div class='v'$id style='display:flex;flex-direction:row;overflow-x:auto;$st'>" to "</div>"
                tag == "LinearLayout" -> {
                    val dir = if (a["orientation"] == "horizontal") "row" else "column"
                    "<div class='v'$id style='display:flex;flex-direction:$dir;${if (dir == "row") "align-items:center;gap:6px;" else ""}$st'>" to "</div>"
                }
                tag.endsWith("Toolbar") || tag == "ActionBar" ->
                    "<div class='v bar'$id style='$st'>${esc(res(a["title"]) ?: "Mi app")}" to "</div>"
                tag.endsWith("CardView") -> "<div class='v card'$id style='display:flex;flex-direction:column;$st'>" to "</div>"
                tag.endsWith("ScrollView") -> "<div class='v'$id style='display:flex;flex-direction:column;overflow:auto;$st'>" to "</div>"
                tag.endsWith("Layout") || tag == "merge" || tag == "RadioGroup" ->
                    "<div class='v'$id style='display:flex;flex-direction:column;position:relative;$st'>" to "</div>"
                tag.contains("FloatingActionButton") ->
                    "<button class='fab'$id style='$st'>${if (text.isNotEmpty()) text else "+"}</button>" to ""
                tag.endsWith("Button") && !tag.endsWith("RadioButton") && !tag.endsWith("ToggleButton") ->
                    "<button class='btn v'$id style='$st'>${if (text.isNotEmpty()) text else if (tag == "ImageButton") "⬤" else "Botón"}</button>" to ""
                tag.endsWith("EditText") || tag == "AutoCompleteTextView" -> {
                    val flat = if (a["background"] == "@null" || a["background"] == "@android:color/transparent") "background:transparent;border:none;" else ""
                    "<input class='inp v'$id placeholder='${if (hint.isNotEmpty()) hint else "Escribe aquí"}' value='$text' style='$flat$st'>" to ""
                }
                tag.endsWith("TextView") || tag == "Chip" ->
                    "<div class='txt v'$id style='$st'>${if (text.isNotEmpty()) text else "Texto"}</div>" to ""
                tag.endsWith("ImageView") -> "<div class='img v'$id style='$st'>🖼️</div>" to ""
                tag == "RecyclerView" || tag == "ListView" || tag == "GridView" -> {
                    // Si el diseño indica su fila (tools:listitem), se dibuja 3 veces; si no, filas de ejemplo
                    val item = a["listitem"]?.substringAfter("/")?.let { layouts[it] }
                    val rows = (1..3).joinToString("") { i ->
                        if (item != null) runCatching { convert(item, depth + 1) }.getOrDefault("")
                        else "<div class='row'><div class='img' style='width:36px;height:36px;min-height:36px'>📄</div><div>Elemento $i</div></div>"
                    }
                    "<div class='v'$id style='display:flex;flex-direction:column;$st'>$rows" to "</div>"
                }
                tag.endsWith("CheckBox") || tag.contains("Switch") || tag == "ToggleButton" ->
                    "<label class='v txt'$id style='display:flex;align-items:center;gap:8px;$st'><input type='checkbox'${if (a["checked"] == "true") " checked" else ""}>$text</label>" to ""
                tag.endsWith("RadioButton") ->
                    "<label class='v txt'$id style='display:flex;align-items:center;gap:8px;$st'><input type='radio' name='r'>$text</label>" to ""
                tag.endsWith("SeekBar") || tag == "Slider" -> "<input type='range' class='v'$id style='$st'>" to ""
                tag.endsWith("ProgressBar") -> "<progress class='v'$id style='$st'></progress>" to ""
                tag.endsWith("Spinner") -> "<select class='inp v'$id style='$st'><option>Opción 1</option><option>Opción 2</option></select>" to ""
                tag == "include" -> {
                    val inc = a["layout"]?.substringAfter("/")?.let { layouts[it] }
                    (if (inc != null) runCatching { convert(inc, depth + 1) }.getOrDefault("") else "") to ""
                }
                tag == "View" || tag == "Space" -> "<div class='v'$id style='min-height:1px;$st'></div>" to ""
                else -> "<div class='v'$id style='display:flex;flex-direction:column;$st'>" to "</div>"
            }
        }
    }

    // ------------------------------------------------------------------ VENTANA
    /**
     * Abre la ventana. [build] se vuelve a llamar con 🔄 para tomar la versión más nueva del chat.
     * [live]: mientras devuelva true (la IA está escribiendo el código), la vista se actualiza sola
     * cada pocos segundos para ver cómo se va armando la app.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun open(activity: Activity, title: String, emptyMessage: String, live: (() -> Boolean)? = null, build: () -> Pair<String, String>?) {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0B0D")) }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(10), px(8), px(10)); setBackgroundColor(Color.parseColor("#141417"))
        }
        val titleView = TextView(activity).apply {
            text = title; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun pill(label: String, onClick: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(px(12), px(7), px(12), px(7))
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.parseColor("#2C2C30")) }
            setOnClickListener { onClick() }
        }
        val content = FrameLayout(activity)
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()   // permite alert(), confirm(), etc.
            setBackgroundColor(Color.WHITE)
        }
        val codeView = TextView(activity).apply {
            setTextColor(Color.parseColor("#E6E6E6")); textSize = 12f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(px(12), px(12), px(12), px(12))
        }
        val codeScroll = ScrollView(activity).apply {
            visibility = View.GONE
            addView(HorizontalScrollView(activity).apply { addView(codeView) })
        }
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        content.addView(codeScroll, FrameLayout.LayoutParams(-1, -1))

        lateinit var tabView: TextView
        lateinit var tabCode: TextView
        fun select(preview: Boolean) {
            web.visibility = if (preview) View.VISIBLE else View.GONE
            codeScroll.visibility = if (preview) View.GONE else View.VISIBLE
            (tabView.background as GradientDrawable).setColor(Color.parseColor(if (preview) "#3D5AFE" else "#2C2C30"))
            (tabCode.background as GradientDrawable).setColor(Color.parseColor(if (preview) "#2C2C30" else "#3D5AFE"))
        }
        var shown: String? = "\u0000"   // lo último que se mostró (para no recargar si no cambió)
        fun isLive() = live != null && runCatching { live() }.getOrDefault(false)
        fun reload(force: Boolean = true) {
            val result = runCatching { build() }.getOrNull()
            val key = result?.first
            if (!force && key == shown) return
            shown = key
            if (result == null) {
                val msg = if (isLive()) "✦ La IA está escribiendo el código de tu app…<br><br>Aquí vas a ver cómo se va armando; " +
                    "esta ventana se actualiza sola." else emptyMessage
                web.loadDataWithBaseURL(null, "<html><body style='background:#0b0b0d;color:#ccc;font-family:sans-serif;padding:24px;font-size:16px'>" +
                    msg + "</body></html>", "text/html", "UTF-8", null)
                codeView.text = "(sin código todavía)"
            } else {
                web.loadDataWithBaseURL("https://preview.cumulus.local/", result.first, "text/html", "UTF-8", null)
                codeView.text = result.second
            }
        }
        tabView = pill("Vista") { select(true) }
        tabCode = pill("Código") { select(false) }
        val lp = LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) }
        bar.addView(tabView, lp); bar.addView(tabCode, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        bar.addView(pill("🔄") { reload() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        bar.addView(pill("✕") { dialog.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })

        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        // ✦ Se está armando: se actualiza sola cada 2,5 s hasta que la IA termina (y una vez más al final)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val still = isLive()
                titleView.text = if (still) "$title · ✦ armándose…" else title
                reload(force = false)
                if (still) handler.postDelayed(this, 2500)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick); runCatching { web.destroy() } }
        select(true)
        reload()
        if (isLive()) {
            titleView.text = "$title · ✦ armándose…"
            handler.postDelayed(tick, 2500)
        }
        dialog.show()
    }
}
