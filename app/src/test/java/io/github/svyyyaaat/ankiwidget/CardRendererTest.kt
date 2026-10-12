package io.github.svyyyaaat.ankiwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CardRendererTest {
    private val css = """
        .card { font-family: sans-serif; font-size: 20px; text-align: center; color: black; background: white; }
        .sentence { font-size: 48px; }
        .word { font-size: 44px; }
        .pos { font-size: 14px; }
        #gloss { display: none; }
        .nightMode .card { color: white; }
        @media (max-width: 400px) { .sentence { font-size: 10px; } }
    """.trimIndent()

    private val question = """<div class="word">主流</div>"""

    private val answer = """
        <div class="sentence"><ruby><rb>高校</rb><rt>こうこう</rt></ruby>では<ruby><rb>制服</rb><rt>せいふく</rt></ruby>が<ruby><rb>主流</rb><rt>しゅりゅう</rt></ruby>だ。</div>
        <div>In high schools, school uniform is mainstream.</div>
        <br>
        <div>Word:</div>
        <div class="word">主流</div>
        <div class="pos">《 n. 》</div>
        <div>mainstream</div>
        [sound:mainstream.mp3]
        <br><a href="#" onclick="toggle()">Sentence Gloss</a>
        <div id="gloss">hidden gloss text</div>
        <script>function toggle() {}</script>
    """.trimIndent()

    @Test
    fun rendersExampleCard() {
        val density = 2.75f
        val w = (320 * density).toInt()
        val h = (250 * density).toInt()
        val q = CardRenderer.render(question, css, w, h, density)
        val a = CardRenderer.render(answer, css, w, h, density)
        assertTrue("question drew something", q.hasInk())
        assertTrue("answer drew something", a.hasInk())

        // Preview as it would look tinted on a dark and a light widget.
        val out = File(System.getProperty("preview.dir") ?: "build/preview").apply { mkdirs() }
        preview(listOf(q, a), Color.BLACK, Color.WHITE).save(File(out, "card_dark.png"))
        preview(listOf(q, a), Color.rgb(250, 250, 250), Color.BLACK).save(File(out, "card_light.png"))
    }

    @Test
    fun longCardShrinksToFit() {
        val long = (1..40).joinToString("") { "<div>Line $it with <ruby>漢字<rt>かんじ</rt></ruby></div>" }
        val bmp = CardRenderer.render(long, "", 600, 400, 2f)
        assertTrue(bmp.hasInk())
    }

    private fun Bitmap.hasInk(): Boolean {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        return px.any { Color.alpha(it) > 0 }
    }

    private fun preview(bitmaps: List<Bitmap>, bg: Int, fg: Int): Bitmap {
        val gap = 24
        val out = Bitmap.createBitmap(bitmaps.sumOf { it.width } + gap * (bitmaps.size + 1), bitmaps.maxOf { it.height } + gap * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        val tint = Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN) }
        var x = gap.toFloat()
        for (b in bitmaps) {
            c.drawBitmap(b, x, gap.toFloat(), tint)
            x += b.width + gap
        }
        return out
    }

    private fun Bitmap.save(f: File) = f.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
}
