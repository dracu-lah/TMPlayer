package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import platform.Foundation.NSBundle
import java.io.File
import org.jetbrains.skia.Image

/**
 * Android's resource ids on iOS: :app's R is generated in :ios, so the shell hands over the lookup
 * from an id to the drawable's file name when it starts, as App.kt hands SetupImages its ids.
 */
object DrawableNames {
    var lookup: (Int) -> String? = { null }
}

/**
 * Android's vector drawables (app/src/main/res/drawable, copied into the app bundle as drawable/)
 * as Compose ImageVectors: the paths with their fill, stroke and alpha, which is all :app's icons use.
 */
@Composable
fun ImageVector.Companion.vectorResource(id: Int): ImageVector = remember(id) { loadVector(id) }

/**
 * A drawable as a painter: a picture from drawable-nodpi/ (the setup and tour shots, WebP) when the
 * bundle has one by that name, otherwise the vector of the same name.
 */
@Composable
fun painterResource(id: Int): Painter {
    val picture = remember(id) { loadPicture(id) }
    val vector = rememberVectorPainter(ImageVector.vectorResource(id))
    return picture ?: vector
}

private val empty: ImageVector by lazy { ImageVector.Builder("empty", 24.dp, 24.dp, 24f, 24f).build() }

private fun loadPicture(id: Int): Painter? {
    val name = DrawableNames.lookup(id) ?: return null
    val folder = NSBundle.mainBundle.resourcePath ?: return null
    val file = File("$folder/drawable-nodpi/$name.webp")
    if (!file.exists()) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return runCatching { BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap()) }.getOrNull()
}

private fun loadVector(id: Int): ImageVector {
    val name = DrawableNames.lookup(id) ?: return empty
    val folder = NSBundle.mainBundle.resourcePath ?: return empty
    val xml = runCatching { File("$folder/drawable/$name.xml").readText() }.getOrNull() ?: return empty
    return runCatching { parse(name, xml) }.getOrDefault(empty)
}

private fun attribute(element: String, name: String): String? =
    Regex("""android:$name\s*=\s*"([^"]*)"""").find(element)?.groupValues?.get(1)

private fun dimension(text: String?): Float = text?.removeSuffix("dp")?.removeSuffix("dip")?.toFloatOrNull() ?: 24f

private fun color(text: String?): Color? {
    val value = text?.trim() ?: return null
    if (!value.startsWith("#")) return if (value.isEmpty()) null else Color.White
    val hex = value.drop(1)
    val argb = when (hex.length) {
        3 -> "FF" + hex.map { "$it$it" }.joinToString("")
        4 -> hex.map { "$it$it" }.joinToString("")
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)?.let { Color(it.toInt()) }
}

private fun parse(name: String, xml: String): ImageVector {
    val root = Regex("<vector[^>]*>", RegexOption.DOT_MATCHES_ALL).find(xml)?.value ?: return empty
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = dimension(attribute(root, "width")).dp,
        defaultHeight = dimension(attribute(root, "height")).dp,
        viewportWidth = attribute(root, "viewportWidth")?.toFloatOrNull() ?: 24f,
        viewportHeight = attribute(root, "viewportHeight")?.toFloatOrNull() ?: 24f,
    )
    for (match in Regex("<path\\b[^>]*?/?>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
        val path = match.value
        val data = attribute(path, "pathData") ?: continue
        val fill = color(attribute(path, "fillColor"))
        val stroke = color(attribute(path, "strokeColor"))
        builder.addPath(
            pathData = addPathNodes(data),
            pathFillType = if (attribute(path, "fillType") == "evenOdd") PathFillType.EvenOdd else PathFillType.NonZero,
            fill = fill?.let(::SolidColor),
            fillAlpha = attribute(path, "fillAlpha")?.toFloatOrNull() ?: 1f,
            stroke = stroke?.let(::SolidColor),
            strokeAlpha = attribute(path, "strokeAlpha")?.toFloatOrNull() ?: 1f,
            strokeLineWidth = attribute(path, "strokeWidth")?.toFloatOrNull() ?: 0f,
            strokeLineCap = when (attribute(path, "strokeLineCap")) {
                "round" -> StrokeCap.Round
                "square" -> StrokeCap.Square
                else -> StrokeCap.Butt
            },
            strokeLineJoin = when (attribute(path, "strokeLineJoin")) {
                "round" -> StrokeJoin.Round
                "bevel" -> StrokeJoin.Bevel
                else -> StrokeJoin.Miter
            },
        )
    }
    return builder.build()
}
