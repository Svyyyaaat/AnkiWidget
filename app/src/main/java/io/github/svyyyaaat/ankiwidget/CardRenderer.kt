package io.github.svyyyaaat.ankiwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Renders card HTML into a white-on-transparent bitmap.
 *
 * Widgets can't host a WebView, so this understands just enough HTML/CSS for typical cards:
 * block structure, line breaks, font size/weight/style, text alignment, hidden elements and
 * `<ruby>` furigana. Colours are deliberately ignored: the widget tints the bitmap with the
 * theme's text colour, so the card follows light/dark mode and system colours.
 */
object CardRenderer {
    private const val ROOT_FONT_PX = 16f
    private const val CARD_FONT_PX = 20f

    /** Cards are designed for a full screen; scale CSS px down a little for a widget. */
    private const val CSS_PX_TO_DP = 0.8f
    private const val MIN_SHRINK = 0.4f
    private const val RUBY_SCALE = 0.5f
    private const val RUBY_ALPHA = 215

    fun render(html: String, css: String, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (widthPx <= 0 || heightPx <= 0) return bitmap
        val paragraphs = parse(html, Css.parse(css))
        val japanese = JAPANESE_KANA.containsMatchIn(html)

        var shrink = 1f
        var layout: Layout
        while (true) {
            layout = Layout(paragraphs, widthPx.toFloat(), density * CSS_PX_TO_DP * shrink, japanese)
            if (layout.height <= heightPx || shrink <= MIN_SHRINK) break
            shrink = max(MIN_SHRINK, shrink * sqrt(heightPx / layout.height).coerceAtMost(0.95f))
        }
        layout.draw(Canvas(bitmap), widthPx.toFloat(), heightPx.toFloat())
        return bitmap
    }

    // ---------------------------------------------------------------------------------------
    // Content model

    private enum class Align { LEFT, CENTER, RIGHT }

    private data class Style(val sizePx: Float, val bold: Boolean, val italic: Boolean, val align: Align)

    private sealed interface Piece { val style: Style }

    private class TextPiece(val text: String, override val style: Style) : Piece

    private class RubyPiece(val base: String, val ruby: String, override val style: Style) : Piece

    private class Paragraph(val pieces: MutableList<Piece> = mutableListOf(), val rule: Boolean = false)

    // ---------------------------------------------------------------------------------------
    // HTML -> paragraphs

    private val SKIPPED_TAGS = setOf(
        "script", "style", "audio", "video", "img", "svg", "a", "button", "input", "select",
        "textarea", "object", "iframe", "noscript", "template", "head", "title", "canvas",
    )
    private val BLOCK_TAGS = setOf(
        "div", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "table", "tr", "thead",
        "tbody", "tfoot", "section", "article", "header", "footer", "blockquote", "center", "pre",
        "dl", "dt", "dd", "figure", "figcaption", "details", "summary", "main", "nav", "form",
    )
    private val ANKI_TAGS = Regex("""\[sound:[^\]]*]|\[anki:[^\]]*]|\[\[type:[^\]]*]]""")
    private val JAPANESE_KANA = Regex("[\\u3040-\\u30ff]")

    /** Anki's plain furigana syntax, e.g. "高校[こうこう]", for templates that don't use the furigana filter. */
    private val BRACKET_FURIGANA = Regex(""" ?([^\s>\[\]]+?)\[([^\]:]+)]""")

    private fun parse(html: String, css: Css): List<Paragraph> {
        val doc = Jsoup.parseBodyFragment(html)
        val root = Style(CARD_FONT_PX, bold = false, italic = false, align = Align.CENTER)
        val cardStyle = css.cardDecls.fold(root) { s, d -> d.applyTo(s, s.sizePx) }
        return ParagraphBuilder(css).apply { walkChildren(doc.body(), cardStyle) }.finish()
    }

    private class ParagraphBuilder(private val css: Css) {
        private val paragraphs = mutableListOf<Paragraph>()
        private var current: Paragraph? = null

        fun finish(): List<Paragraph> {
            boundary()
            while (paragraphs.lastOrNull()?.let { it.pieces.isEmpty() && !it.rule } == true) {
                paragraphs.removeAt(paragraphs.lastIndex)
            }
            while (paragraphs.firstOrNull()?.let { it.pieces.isEmpty() && !it.rule } == true) {
                paragraphs.removeAt(0)
            }
            return paragraphs
        }

        private fun boundary() {
            current?.let { if (it.pieces.isNotEmpty()) paragraphs += it }
            current = null
        }

        private fun lineBreak() {
            val c = current
            if (c == null) paragraphs += Paragraph() else boundary()
        }

        private fun add(piece: Piece) {
            val c = current ?: Paragraph().also { current = it }
            c.pieces += piece
        }

        private fun addText(raw: String, style: Style) {
            val text = raw.replace(ANKI_TAGS, "").replace(WHITESPACE, " ")
            if (text.isEmpty()) return
            if (text.isBlank() && current == null) return
            var last = 0
            for (m in BRACKET_FURIGANA.findAll(text)) {
                if (m.range.first > last) add(TextPiece(text.substring(last, m.range.first), style))
                add(RubyPiece(m.groupValues[1], m.groupValues[2], style))
                last = m.range.last + 1
            }
            if (last < text.length) add(TextPiece(text.substring(last), style))
        }

        fun walkChildren(el: Element, style: Style) {
            for (child in el.childNodes()) walk(child, style)
        }

        private fun walk(node: Node, parent: Style) {
            when (node) {
                is TextNode -> addText(node.wholeText, parent)
                is Element -> walkElement(node, parent)
            }
        }

        private fun walkElement(el: Element, parent: Style) {
            val tag = el.normalName()
            if (tag in SKIPPED_TAGS || el.hasAttr("hidden")) return
            val decls = css.declsFor(el) + listOfNotNull(Decl.parseInline(el.attr("style")))
            if (decls.any { it.hidden }) return
            val style = decls.fold(tagDefaults(el, parent)) { s, d -> d.applyTo(s, parent.sizePx) }
            val display = decls.lastOrNull { it.display != null }?.display

            when (tag) {
                "br" -> lineBreak()
                "hr" -> {
                    boundary()
                    paragraphs += Paragraph(rule = true)
                }
                "ruby" -> addRuby(el, style)
                else -> {
                    val block = display in BLOCK_DISPLAYS || (display == null && tag in BLOCK_TAGS)
                    if (block) boundary()
                    if (tag == "li") addText("• ", style)
                    walkChildren(el, style)
                    if (tag == "td" || tag == "th") addText(" ", style)
                    if (block) boundary()
                }
            }
        }

        private fun addRuby(el: Element, style: Style) {
            val base = StringBuilder()
            for (child in el.childNodes()) {
                when {
                    child is TextNode -> base.append(child.wholeText)
                    child is Element && child.normalName() == "rt" -> {
                        val b = base.toString().replace(WHITESPACE, " ").trim()
                        val r = child.text().trim()
                        if (b.isNotEmpty()) add(RubyPiece(b, r, style)) else if (r.isNotEmpty()) add(TextPiece(r, style))
                        base.clear()
                    }
                    child is Element && child.normalName() == "rp" -> Unit
                    child is Element -> base.append(child.text())
                }
            }
            if (base.isNotBlank()) addText(base.toString(), style)
        }

        private fun tagDefaults(el: Element, parent: Style): Style =
            when (el.normalName()) {
                "h1" -> parent.copy(sizePx = parent.sizePx * 2f, bold = true)
                "h2" -> parent.copy(sizePx = parent.sizePx * 1.5f, bold = true)
                "h3" -> parent.copy(sizePx = parent.sizePx * 1.17f, bold = true)
                "h4", "b", "strong", "th", "dt", "summary" -> parent.copy(bold = true)
                "h5" -> parent.copy(sizePx = parent.sizePx * 0.83f, bold = true)
                "h6" -> parent.copy(sizePx = parent.sizePx * 0.67f, bold = true)
                "i", "em", "cite", "var", "dfn" -> parent.copy(italic = true)
                "big" -> parent.copy(sizePx = parent.sizePx * 1.2f)
                "small", "sub", "sup" -> parent.copy(sizePx = parent.sizePx * 0.83f)
                "center" -> parent.copy(align = Align.CENTER)
                "font" -> FONT_SIZES[el.attr("size").trim().toIntOrNull()]?.let { parent.copy(sizePx = it) } ?: parent
                else -> parent
            }
    }

    private val WHITESPACE = Regex("\\s+")
    private val BLOCK_DISPLAYS = setOf("block", "flex", "grid", "list-item", "table", "table-row")
    private val FONT_SIZES = mapOf(1 to 10f, 2 to 13f, 3 to 16f, 4 to 18f, 5 to 24f, 6 to 32f, 7 to 48f)

    // ---------------------------------------------------------------------------------------
    // A deliberately small CSS subset

    private class Decl(
        val size: String? = null,
        val weight: String? = null,
        val fontStyle: String? = null,
        val align: String? = null,
        val display: String? = null,
        val hidden: Boolean = false,
    ) {
        fun applyTo(style: Style, parentSize: Float): Style {
            var s = style
            size?.let { v -> fontSize(v, parentSize)?.let { s = s.copy(sizePx = it) } }
            weight?.let { v ->
                val n = v.toIntOrNull()
                s = s.copy(bold = if (n != null) n >= 600 else v == "bold" || v == "bolder")
            }
            fontStyle?.let { s = s.copy(italic = it == "italic" || it == "oblique") }
            align?.let { v ->
                s = when (v) {
                    "center", "-webkit-center" -> s.copy(align = Align.CENTER)
                    "right", "end" -> s.copy(align = Align.RIGHT)
                    "left", "start", "justify" -> s.copy(align = Align.LEFT)
                    else -> s
                }
            }
            return s
        }

        companion object {
            fun parse(body: String): Decl? {
                var size: String? = null
                var weight: String? = null
                var fontStyle: String? = null
                var align: String? = null
                var display: String? = null
                var hidden = false
                for (decl in body.split(';')) {
                    val i = decl.indexOf(':')
                    if (i < 0) continue
                    val name = decl.substring(0, i).trim().lowercase()
                    val value = decl.substring(i + 1).replace("!important", "").trim().lowercase()
                    when (name) {
                        "font-size" -> size = value
                        "font-weight" -> weight = value
                        "font-style" -> fontStyle = value
                        "text-align" -> align = value
                        "display" -> if (value == "none") hidden = true else display = value
                        "visibility" -> if (value == "hidden" || value == "collapse") hidden = true
                    }
                }
                if (size == null && weight == null && fontStyle == null && align == null && display == null && !hidden) {
                    return null
                }
                return Decl(size, weight, fontStyle, align, display, hidden)
            }

            fun parseInline(style: String): Decl? = if (style.isBlank()) null else parse(style)

            private val LENGTH = Regex("""^(-?[\d.]+)(px|pt|em|rem|%)?$""")
            private val KEYWORDS = mapOf(
                "xx-small" to 9f, "x-small" to 10f, "small" to 13f, "medium" to 16f,
                "large" to 18f, "x-large" to 24f, "xx-large" to 32f, "xxx-large" to 48f,
            )

            fun fontSize(value: String, parent: Float): Float? {
                KEYWORDS[value]?.let { return it }
                if (value == "smaller") return parent / 1.2f
                if (value == "larger") return parent * 1.2f
                val m = LENGTH.find(value) ?: return null
                val n = m.groupValues[1].toFloatOrNull() ?: return null
                val px = when (m.groupValues[2]) {
                    "px", "" -> n
                    "pt" -> n * 4f / 3f
                    "em" -> n * parent
                    "rem" -> n * ROOT_FONT_PX
                    "%" -> n / 100f * parent
                    else -> return null
                }
                return px.takeIf { it > 0f }
            }
        }
    }

    private class Css(
        val cardDecls: List<Decl>,
        private val byTag: Map<String, List<Decl>>,
        private val byClass: Map<String, List<Decl>>,
        private val byId: Map<String, List<Decl>>,
    ) {
        /** Applies in rough specificity order: tag, class, id. Ancestor context is ignored. */
        fun declsFor(el: Element): List<Decl> {
            val out = mutableListOf<Decl>()
            byTag[el.normalName()]?.let { out += it }
            for (c in el.classNames()) byClass[c]?.let { out += it }
            if (el.id().isNotEmpty()) byId[el.id()]?.let { out += it }
            return out
        }

        companion object {
            private val COMMENTS = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            private val COMPOUND = Regex("""^([a-zA-Z][\w-]*)?((?:[.#][\w-]+)*)$""")

            fun parse(css: String): Css {
                val card = mutableListOf<Decl>()
                val byTag = mutableMapOf<String, MutableList<Decl>>()
                val byClass = mutableMapOf<String, MutableList<Decl>>()
                val byId = mutableMapOf<String, MutableList<Decl>>()
                val text = css.replace(COMMENTS, "")
                var i = 0
                while (i < text.length) {
                    val open = text.indexOf('{', i)
                    if (open < 0) break
                    val selector = text.substring(i, open).trim()
                    if (selector.startsWith("@")) {
                        // Skip @media / @font-face / ... blocks entirely (nested braces included).
                        var depth = 0
                        var j = open
                        while (j < text.length) {
                            if (text[j] == '{') depth++
                            if (text[j] == '}' && --depth == 0) break
                            j++
                        }
                        i = j + 1
                        continue
                    }
                    val close = text.indexOf('}', open).let { if (it < 0) text.length else it }
                    val decl = Decl.parse(text.substring(open + 1, close))
                    i = close + 1
                    if (decl == null) continue
                    for (part in selector.split(',')) {
                        val sel = part.trim()
                        if (sel.isEmpty() || ':' in sel || '[' in sel || "night" in sel.lowercase()) continue
                        val last = sel.split(Regex("[\\s>+~]+")).last()
                        val m = COMPOUND.find(last) ?: continue
                        val tag = m.groupValues[1].lowercase()
                        val rest = m.groupValues[2]
                        val ids = Regex("#([\\w-]+)").findAll(rest).map { it.groupValues[1] }.toList()
                        val classes = Regex("\\.([\\w-]+)").findAll(rest).map { it.groupValues[1] }.toList()
                        when {
                            "card" in classes || tag == "body" || tag == "html" -> card += decl
                            ids.isNotEmpty() -> byId.getOrPut(ids.last()) { mutableListOf() } += decl
                            classes.isNotEmpty() -> byClass.getOrPut(classes.last()) { mutableListOf() } += decl
                            tag.isNotEmpty() -> byTag.getOrPut(tag) { mutableListOf() } += decl
                        }
                    }
                }
                return Css(card, byTag, byClass, byId)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Layout & drawing

    /** Characters that must not start a line (Japanese kinsoku shori, simplified). */
    private const val NO_LINE_START = "、。，．,.・：；？！!?)）」』】〉》〕］}｝ーぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ…‥"
    private val TOKEN = Regex("""\s+|[\p{IsHan}\p{IsHiragana}\p{IsKatakana}　-〿＀-￯]|[^\s\p{IsHan}\p{IsHiragana}\p{IsKatakana}　-〿＀-￯]+""")

    private class Atom(val text: String, val ruby: String?, val style: Style) {
        val isSpace get() = ruby == null && text.isBlank()
    }

    private class Placed(val atom: Atom, val width: Float)

    private class Line(
        val placed: List<Placed>,
        val width: Float,
        val align: Align,
        val rubyHeight: Float,
        val ascent: Float,
        val descent: Float,
        val rule: Boolean = false,
    ) {
        val height get() = rubyHeight + ascent + descent
    }

    private class Layout(
        paragraphs: List<Paragraph>,
        private val maxWidth: Float,
        private val scale: Float,
        japanese: Boolean,
    ) {
        private val locale = if (japanese) Locale.JAPAN else Locale.getDefault()
        private val paints = mutableMapOf<Triple<Float, Boolean, Boolean>, Paint>()
        private val lines = mutableListOf<Line>()
        val height: Float

        init {
            val baseSize = CARD_FONT_PX * scale
            for (p in paragraphs) {
                when {
                    p.rule -> lines += Line(emptyList(), 0f, Align.CENTER, 0f, baseSize * 0.4f, baseSize * 0.4f, rule = true)
                    p.pieces.isEmpty() -> {
                        val fm = paint(Style(CARD_FONT_PX, bold = false, italic = false, align = Align.CENTER)).fontMetrics
                        lines += Line(emptyList(), 0f, Align.CENTER, 0f, -fm.ascent, fm.descent)
                    }
                    else -> wrap(atomsOf(p), p.pieces.first().style.align)
                }
            }
            height = lines.sumOf { it.height.toDouble() }.toFloat()
        }

        fun paint(style: Style, ruby: Boolean = false): Paint {
            val size = style.sizePx * scale * (if (ruby) RUBY_SCALE else 1f)
            return paints.getOrPut(Triple(size, style.bold, style.italic)) {
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    textSize = size
                    color = android.graphics.Color.WHITE
                    textLocale = locale
                    val tfStyle = when {
                        style.bold && style.italic -> Typeface.BOLD_ITALIC
                        style.bold -> Typeface.BOLD
                        style.italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                    typeface = Typeface.create(Typeface.DEFAULT, tfStyle)
                }
            }
        }

        private fun atomsOf(p: Paragraph): List<Atom> {
            val atoms = mutableListOf<Atom>()
            for (piece in p.pieces) {
                when (piece) {
                    is RubyPiece -> atoms += Atom(piece.base, piece.ruby, piece.style)
                    is TextPiece -> for (m in TOKEN.findAll(piece.text)) {
                        val t = m.value
                        val prev = atoms.lastOrNull()
                        if (t.length == 1 && t[0] in NO_LINE_START && prev != null && !prev.isSpace && prev.ruby == null) {
                            atoms[atoms.lastIndex] = Atom(prev.text + t, null, prev.style)
                        } else {
                            atoms += Atom(t, null, piece.style)
                        }
                    }
                }
            }
            return atoms
        }

        private fun widthOf(a: Atom): Float {
            val base = paint(a.style).measureText(a.text)
            return if (a.ruby == null) base else max(base, paint(a.style, ruby = true).measureText(a.ruby))
        }

        private fun wrap(atoms: List<Atom>, align: Align) {
            val queue = ArrayDeque(atoms)
            var current = mutableListOf<Placed>()
            var x = 0f
            fun flush() {
                while (current.lastOrNull()?.atom?.isSpace == true) current.removeAt(current.lastIndex)
                if (current.isNotEmpty()) lines += lineOf(current, align)
                current = mutableListOf()
                x = 0f
            }
            while (queue.isNotEmpty()) {
                val atom = queue.removeFirst()
                if (atom.isSpace && current.isEmpty()) continue
                val w = widthOf(atom)
                if (w > maxWidth && atom.ruby == null && atom.text.length > 1) {
                    // A single word wider than the widget: break it per character.
                    atom.text.reversed().forEach { queue.addFirst(Atom(it.toString(), null, atom.style)) }
                    continue
                }
                if (x + w > maxWidth && current.isNotEmpty()) {
                    flush()
                    if (atom.isSpace) continue
                }
                current += Placed(atom, w)
                x += w
            }
            flush()
        }

        private fun lineOf(placed: List<Placed>, align: Align): Line {
            var ascent = 0f
            var descent = 0f
            var rubyHeight = 0f
            for (p in placed) {
                val fm = paint(p.atom.style).fontMetrics
                ascent = max(ascent, -fm.ascent)
                descent = max(descent, fm.descent)
                if (p.atom.ruby != null) {
                    val rfm = paint(p.atom.style, ruby = true).fontMetrics
                    rubyHeight = max(rubyHeight, (rfm.descent - rfm.ascent) * 0.85f)
                }
            }
            return Line(placed, placed.sumOf { it.width.toDouble() }.toFloat(), align, rubyHeight, ascent, descent)
        }

        fun draw(canvas: Canvas, width: Float, height: Float) {
            var y = if (this.height < height) (height - this.height) / 2f else 0f
            for (line in lines) {
                if (y > height) break
                if (line.rule) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        alpha = 70
                        strokeWidth = max(1f, scale)
                    }
                    val mid = y + line.height / 2f
                    canvas.drawLine(width * 0.1f, mid, width * 0.9f, mid, p)
                    y += line.height
                    continue
                }
                var x = when (line.align) {
                    Align.LEFT -> 0f
                    Align.CENTER -> (width - line.width) / 2f
                    Align.RIGHT -> width - line.width
                }
                val baseline = y + line.rubyHeight + line.ascent
                for (p in line.placed) {
                    val basePaint = paint(p.atom.style)
                    val baseWidth = basePaint.measureText(p.atom.text)
                    canvas.drawText(p.atom.text, x + (p.width - baseWidth) / 2f, baseline, basePaint)
                    p.atom.ruby?.let { ruby ->
                        val rubyPaint = paint(p.atom.style, ruby = true)
                        val rubyWidth = rubyPaint.measureText(ruby)
                        // CJK glyphs reach ~0.88em above the baseline; keep a small gap above that.
                        val rubyBaseline = baseline - basePaint.textSize * 0.98f
                        val alpha = rubyPaint.alpha
                        rubyPaint.alpha = RUBY_ALPHA
                        canvas.drawText(ruby, x + (p.width - rubyWidth) / 2f, rubyBaseline, rubyPaint)
                        rubyPaint.alpha = alpha
                    }
                    x += p.width
                }
                y += line.height
            }
        }
    }
}
