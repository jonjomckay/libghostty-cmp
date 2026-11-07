package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.FontLoadResult
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.shaper.ShapingOptions
import kotlin.math.ceil
import org.jetbrains.skia.FontStyle as SkFontStyle

internal fun skiaGlyphPainter(
    fontFamily: FontFamily,
    fontSizePx: Float,
    resolver: FontFamily.Resolver,
): GlyphPainter {
    fun resolve(weight: FontWeight, style: FontStyle): Typeface? =
        (resolver.resolve(fontFamily, weight, style).value as? FontLoadResult)?.typeface

    val regular = resolve(FontWeight.Normal, FontStyle.Normal)
        ?: FontMgr.default.matchFamilyStyle(null, SkFontStyle.NORMAL)!!
    return SkiaGlyphPainter(
        regular = regular,
        bold = resolve(FontWeight.Bold, FontStyle.Normal),
        italic = resolve(FontWeight.Normal, FontStyle.Italic),
        boldItalic = resolve(FontWeight.Bold, FontStyle.Italic),
        sizePx = fontSizePx,
        fallbackTypefaces = (fontFamily as? FontListFontFamily)?.fonts.orEmpty().mapNotNull { font ->
            (resolver.resolve(FontFamily(font), font.weight, font.style).value as? FontLoadResult)?.typeface
        }.distinct(),
    )
}

private const val SHAPED_CACHE_SIZE = 256

/**
 * Draws with Skia directly. Skia fonts do not fall back to other typefaces for missing glyphs, so cells whose
 * first character the terminal font lacks use the system's choice for that character.
 *
 * `drawString` maps each codepoint to a glyph on its own, which breaks emoji ZWJ sequences, flags and combining
 * marks apart; cells holding more than one codepoint are shaped instead, and the shaped lines are cached.
 */
internal class SkiaGlyphPainter(
    regular: Typeface,
    bold: Typeface?,
    italic: Typeface?,
    boldItalic: Typeface?,
    private val sizePx: Float,
    private val fallbackTypefaces: List<Typeface> = emptyList(),
) : GlyphPainter {
    private val asciiStrings = arrayOfNulls<String>(128)
    private val regularFont = font(regular)

    // A family without a bold or italic face gets synthesized ones.
    private val boldFont = font(
        typeface = bold.takeUnless { it == regular } ?: regular,
        synthesizeBold = bold == null || bold == regular,
    )

    private val italicFont = font(
        typeface = italic.takeUnless { it == regular } ?: regular,
        synthesizeItalic = italic == null || italic == regular,
    )

    private val boldItalicFont = font(
        typeface = boldItalic.takeUnless { it == regular } ?: regular,
        synthesizeBold = boldItalic == null || boldItalic == regular,
        synthesizeItalic = boldItalic == null || boldItalic == regular,
    )
    private val fallbacks = HashMap<Int, Font?>()
    private val shapingOptions = ShapingOptions.DEFAULT.withFontMgr(FontMgr.default)
    private val shaped = LinkedHashMap<ShapedKey, TextLine>()
    private val paint = Paint().apply { isAntiAlias = true }

    override val cellWidth: Float = regularFont.measureTextWidth("M")
    override val cellHeight: Int = ceil(regularFont.metrics.descent - regularFont.metrics.ascent).toInt()
    override val baseline: Float = -regularFont.metrics.ascent

    override fun measure(text: String): Float = regularFont.measureTextWidth(text)

    override fun draw(
        canvas: Canvas,
        text: CharArray,
        offset: Int,
        length: Int,
        x: Float,
        baselineY: Float,
        color: Int,
        bold: Boolean,
        italic: Boolean,
    ) {
        val styled = when {
            bold && italic -> boldItalicFont
            bold -> boldFont
            italic -> italicFont
            else -> regularFont
        }
        val first = text[offset]
        val codepoint = if (first.isHighSurrogate() && length > 1 && text[offset + 1].isLowSurrogate()) {
            0x10000 + ((first.code - 0xd800) shl 10) + text[offset + 1].code - 0xdc00
        } else {
            first.code
        }
        val emojiPresentation = codepoint in 0x1f000..0x1faff ||
                (offset until offset + length).any { text[it] == '\ufe0f' || text[it] == '\u20e3' }
        val emoji = if (emojiPresentation) {
            fallbackTypefaces.lastOrNull {
                it.familyName.contains("emoji", ignoreCase = true) && it.getUTF32Glyph(codepoint) != 0.toShort()
            }?.let { face -> fallbacks.getOrPut(codepoint) { font(face) } }
        } else {
            null
        }
        val font = emoji ?: if (styled.getUTF32Glyph(codepoint) != 0.toShort()) {
            styled
        } else {
            fallback(codepoint) ?: styled
        }
        paint.color = color
        val string = textString(text, offset, length)
        if ((if (codepoint > 0xffff) 2 else 1) == length) {
            canvas.skiaCanvas.drawString(string, x, baselineY, font, paint)
        } else {
            val key = ShapedKey(string, font)
            val line = shaped.remove(key) ?: TextLine.make(string, font, shapingOptions)
            shaped[key] = line
            if (shaped.size > SHAPED_CACHE_SIZE) {
                val eldest = shaped.entries.first()
                eldest.value.close()
                shaped.remove(eldest.key)
            }
            canvas.skiaCanvas.drawTextLine(line, x, baselineY, paint)
        }
    }

    private data class ShapedKey(val text: String, val font: Font)

    private fun fallback(codepoint: Int): Font? = fallbacks.getOrPut(codepoint) {
        (fallbackTypefaces.firstOrNull { it.getUTF32Glyph(codepoint) != 0.toShort() }
            ?: FontMgr.default.matchFamilyStyleCharacter(null, SkFontStyle.NORMAL, null, codepoint))?.let { font(it) }
    }

    private fun textString(text: CharArray, offset: Int, length: Int): String {
        if (length == 1) {
            val character = text[offset].code
            if (character < asciiStrings.size) {
                return asciiStrings[character] ?: text.concatToString(offset, offset + 1).also {
                    asciiStrings[character] = it
                }
            }
        }
        return text.concatToString(offset, offset + length)
    }

    private fun font(typeface: Typeface, synthesizeBold: Boolean = false, synthesizeItalic: Boolean = false) =
        Font(typeface, sizePx).apply {
            isSubpixel = true
            edging = FontEdging.ANTI_ALIAS
            isEmboldened = synthesizeBold
            if (synthesizeItalic) {
                skewX = -0.25f
            }
        }
}

internal fun skiaImageBitmapOf(width: Int, height: Int, argb: IntArray): ImageBitmap {
    // An ARGB int in little-endian memory is B, G, R, A.
    val bytes = ByteArray(argb.size * 4) { i -> (argb[i / 4] ushr ((i % 4) * 8)).toByte() }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}
