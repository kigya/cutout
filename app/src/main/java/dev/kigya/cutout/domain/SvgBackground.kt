package dev.kigya.cutout.domain

import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource
import kotlin.math.abs

sealed interface SvgEdit {
    data class Stripped(val svg: String) : SvgEdit
    data object NoSeparateBackdrop : SvgEdit
    data object NotSvg : SvgEdit
}

private data class ViewBox(val x: Float, val y: Float, val width: Float, val height: Float)

fun stripSolidBackdrop(svg: String): SvgEdit {
    val document = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.newDocumentBuilder().parse(InputSource(StringReader(svg)))
    } catch (_: Exception) {
        return SvgEdit.NotSvg
    }
    val root = document.documentElement ?: return SvgEdit.NotSvg
    if (!localName(root).equals("svg", ignoreCase = true)) return SvgEdit.NotSvg
    val box = viewBox(root) ?: return SvgEdit.NoSeparateBackdrop
    val graphic = firstGraphic(root) ?: return SvgEdit.NoSeparateBackdrop
    if (!isFullBleedRect(graphic, box)) return SvgEdit.NoSeparateBackdrop
    graphic.parentNode?.removeChild(graphic)
    return SvgEdit.Stripped(serialize(document))
}

private val skipped = setOf(
    "defs", "metadata", "title", "desc", "style", "script", "clippath", "mask",
    "lineargradient", "radialgradient", "pattern", "symbol", "filter",
)
private val containers = setOf("svg", "g", "a", "switch")

private fun firstGraphic(node: Node): Node? {
    val children = node.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType != Node.ELEMENT_NODE) continue
        val name = localName(child).lowercase()
        if (name in skipped) continue
        if (name in containers) {
            val nested = firstGraphic(child)
            if (nested != null) return nested
            continue
        }
        return child
    }
    return null
}

private fun isFullBleedRect(node: Node, box: ViewBox): Boolean {
    if (!localName(node).equals("rect", ignoreCase = true)) return false
    val fill = node.attributes?.getNamedItem("fill")?.nodeValue?.trim()?.lowercase()
    if (fill == "none" || fill == "transparent" || fill?.startsWith("url(") == true) return false
    val width = length(node, "width", box.width) ?: return false
    val height = length(node, "height", box.height) ?: return false
    val x = length(node, "x", box.width) ?: 0f
    val y = length(node, "y", box.height) ?: 0f
    val coversWidth = width >= box.width * 0.98f
    val coversHeight = height >= box.height * 0.98f
    val nearOrigin = abs(x - box.x) <= box.width * 0.02f && abs(y - box.y) <= box.height * 0.02f
    return coversWidth && coversHeight && nearOrigin
}

private fun viewBox(root: Node): ViewBox? {
    val raw = root.attributes?.getNamedItem("viewBox")?.nodeValue
    if (raw != null) {
        val parts = raw.trim().split(Regex("[,\\s]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size == 4 && parts[2] > 0f && parts[3] > 0f) {
            return ViewBox(parts[0], parts[1], parts[2], parts[3])
        }
    }
    val width = length(root, "width", 0f)
    val height = length(root, "height", 0f)
    if (width != null && height != null && width > 0f && height > 0f) {
        return ViewBox(0f, 0f, width, height)
    }
    return null
}

private fun length(node: Node, attribute: String, reference: Float): Float? {
    val raw = node.attributes?.getNamedItem(attribute)?.nodeValue?.trim() ?: return null
    if (raw.endsWith("%")) {
        val percent = raw.removeSuffix("%").toFloatOrNull() ?: return null
        if (reference <= 0f) return null
        return reference * percent / 100f
    }
    return raw.removeSuffix("px").toFloatOrNull()
}

private fun localName(node: Node): String {
    val local = node.localName
    if (!local.isNullOrEmpty()) return local
    return node.nodeName.substringAfter(':')
}

private fun serialize(document: org.w3c.dom.Document): String {
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    val writer = StringWriter()
    transformer.transform(DOMSource(document), StreamResult(writer))
    return writer.toString()
}
