package io.github.mangi.eta.ui.screens.writing

import android.util.AtomicFile
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal val writingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal class WritingFiles(private val directory: File) {
    fun readAll(): List<WritingBook> {
        val names = directory.list().orEmpty().map { it.removeSuffix(".bak") }.distinct()
        return names.filter { it.matches(Regex("[A-Fa-f0-9-]{36}\\.json")) }.map { name ->
            val file = AtomicFile(File(directory, name))
            writingJson.decodeFromString<WritingBook>(file.readFully().decodeToString()).also {
                require(name == "${it.id}.json")
            }
        }.sortedByDescending { it.updated }
    }

    fun save(book: WritingBook) {
        directory.mkdirs()
        val file = file(book.id)
        val stream = file.startWrite()
        try {
            stream.write(writingJson.encodeToString(book).encodeToByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    fun delete(id: String) = file(id).delete()

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[A-Fa-f0-9-]{36}")))
        return AtomicFile(File(directory, "$id.json"))
    }
}

internal data class WritingImport(val book: WritingBook, val annotationCount: Int, val foreshadowCount: Int)

internal enum class WritingExport(val extension: String, val mime: String) {
    Backup("json", "application/json"),
    Markdown("md", "text/markdown"),
    Text("txt", "text/plain"),
    Epub("epub", "application/epub+zip"),
}

internal fun readWritingBackup(input: String): WritingImport {
    val root = writingJson.parseToJsonElement(input.removePrefix("\uFEFF")) as? JsonObject
        ?: error("备份必须是全书 JSON。")
    val title = root.string("title")
    require(title.isNotBlank()) { "备份缺少书名。" }
    val native = root["_etaWriter"] as? JsonObject
    if (native != null) {
        val imported = writingJson.decodeFromJsonElement(WritingBook.serializer(), native)
        validateWritingVolumes(imported.volumes)
        return WritingImport(
            imported.copy(id = writingId(), title = title, updated = System.currentTimeMillis()),
            imported.chapters.sumOf { c -> c.feedback.count { it.annotation.isNotBlank() } },
            imported.materials.count { it.kind == WritingMaterialKind.Foreshadow },
        )
    }
    val volumeRecords = root.objects("volumes").ifEmpty {
        listOf(buildJsonObject {
            put("id", writingId())
            put("title", root.string("volume").ifBlank { "第一卷" })
            put("chapters", root["chapters"] ?: JsonArray(emptyList()))
        })
    }
    val chapterRecords = volumeRecords.flatMap { it.objects("chapters") }
    val chapters = chapterRecords.associateBy { it.string("id") }
    fun anchor(record: JsonObject?): WritingAnchor? {
        val chapterId = record?.string("chapterId") ?: return null
        val paragraphId = record.string("paragraphId")
        val raw = chapters[chapterId] ?: return null
        val quote = raw.objects("paragraphs").firstOrNull { it.string("id") == paragraphId }?.string("text")
            ?.takeIf { it.isNotBlank() } ?: return null
        return WritingAnchor(chapterId, raw.string("text").indexOf(quote), quote)
    }
    val research = root["research"] as? JsonObject ?: JsonObject(emptyMap())
    val annotations = research.objects("annotations")
    val feedback = annotations.mapNotNull { note ->
        val location = anchor(note["anchor"] as? JsonObject) ?: return@mapNotNull null
        WritingFeedback(location, annotation = note.string("text").ifBlank { note.string("content") })
    }.groupBy { it.anchor.chapterId }
    val foreshadows = research.objects("foreshadows")
    val book = WritingBook(
        title = title,
        cover = root.string("cover"),
        outline = root.string("outline"),
        notes = root.string("notes"),
        goal = (root["goal"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 2000,
        volumes = volumeRecords.map { volume ->
            WritingVolume(volume.string("id"), volume.string("title"), volume.objects("chapters").map { chapter ->
                val id = chapter.string("id")
                WritingChapter(id, chapter.string("title"), chapter.string("text"), feedback = feedback[id].orEmpty())
            })
        },
        materials = foreshadows.map { record ->
            WritingMaterial(
                kind = WritingMaterialKind.Foreshadow,
                title = record.string("title").ifBlank { "伏笔" },
                content = record.string("note").ifBlank { record.string("text") },
                anchors = record.objects("anchors").mapNotNull { anchor(it["anchor"] as? JsonObject) },
            )
        },
        original = root,
    )
    validateWritingVolumes(book.volumes)
    return WritingImport(book, annotations.size, foreshadows.size)
}

private fun validateWritingVolumes(volumes: List<WritingVolume>) {
    val chapters = volumes.flatMap { it.chapters }
    require(volumes.isNotEmpty() && chapters.isNotEmpty()) { "备份缺少有效的卷和章节。" }
    for (ids in listOf(volumes.map { it.id }, chapters.map { it.id })) {
        require(ids.none { it.isBlank() } && ids.distinct().size == ids.size) { "备份包含空白或重复的卷章编号。" }
    }
}

internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().map {
    it as? JsonObject ?: error("无效的 $key 条目。")
}

internal fun exportWritingBook(book: WritingBook, format: WritingExport, stream: OutputStream) {
    when (format) {
        WritingExport.Backup -> stream.write(writingBackup(book).encodeToByteArray())
        WritingExport.Markdown, WritingExport.Text -> stream.write(writingDocument(book, format == WritingExport.Markdown).encodeToByteArray())
        WritingExport.Epub -> writeEpub(book, stream)
    }
}

private fun writingBackup(book: WritingBook): String {
    val root = book.original.orEmpty()
    val oldVolumes = book.original?.objects("volumes").orEmpty().associateBy { it.string("id") }
    val oldChapters = oldVolumes.values.flatMap { it.objects("chapters") }.associateBy { it.string("id") }
    val exported = buildJsonObject {
        root.forEach { (key, value) -> put(key, value) }
        put("id", book.id)
        put("title", book.title)
        put("cover", book.cover)
        put("notes", book.notes)
        put("outline", book.outline)
        put("goal", book.goal)
        put("aiMode", book.aiMode)
        put("activeId", book.chapters.firstOrNull()?.id.orEmpty())
        put("volumes", JsonArray(book.volumes.map { volume ->
            buildJsonObject {
                oldVolumes[volume.id].orEmpty().forEach { (key, value) -> put(key, value) }
                put("id", volume.id)
                put("title", volume.title)
                put("chapters", JsonArray(volume.chapters.map { chapter ->
                    buildJsonObject {
                        oldChapters[chapter.id].orEmpty().forEach { (key, value) -> put(key, value) }
                        put("id", chapter.id)
                        put("title", chapter.title)
                        put("text", chapter.text)
                        put("complete", chapter.handoff != null || (oldChapters[chapter.id]?.get("complete") == JsonPrimitive(true)))
                    }
                }))
            }
        }))
        put("_etaWriter", writingJson.encodeToJsonElement(WritingBook.serializer(), book))
    }
    return writingJson.encodeToString(JsonElement.serializer(), exported)
}

private fun writingDocument(book: WritingBook, markdown: Boolean): String = buildString {
    appendLine((if (markdown) "# " else "") + book.title)
    book.volumes.forEach { volume ->
        appendLine().appendLine((if (markdown) "## " else "") + volume.title)
        volume.chapters.forEach { chapter ->
            appendLine().appendLine((if (markdown) "### " else "") + chapter.title)
            appendLine().appendLine(if (markdown) chapter.text else plainWritingText(chapter.text))
        }
    }
}

private fun plainWritingText(source: String): String = source
    .replace(Regex("(?m)^ {0,3}#{1,6} +"), "")
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("(\\*\\*|__|~~|\u0060)(.+?)\\1"), "$2")

private fun String.xml(): String = replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    .filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }

private fun writeEpub(book: WritingBook, stream: OutputStream) {
    val title = book.title.xml()
    val chapters = book.volumes.flatMap { volume -> volume.chapters.map { volume.title to it } }
    val updated = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    val header = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    ZipOutputStream(stream).use { zip ->
        fun entry(path: String, content: String, stored: Boolean = false) {
            val bytes = content.encodeToByteArray()
            val record = ZipEntry(path)
            if (stored) {
                record.method = ZipEntry.STORED
                record.size = bytes.size.toLong()
                record.crc = CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(record)
            zip.write(bytes)
            zip.closeEntry()
        }
        entry("mimetype", "application/epub+zip", stored = true)
        entry("META-INF/container.xml", """$header
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
            <rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/>
            </rootfiles></container>""".trimIndent())
        val nav = chapters.mapIndexed { index, (volume, chapter) ->
            """<li><a href="chapter$index.xhtml">${volume.xml()} · ${chapter.title.xml()}</a></li>"""
        }.joinToString("\n")
        entry("EPUB/nav.xhtml", """$header
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh">
            <head><title>$title</title></head><body><nav epub:type="toc"><h1>目录</h1><ol>$nav</ol></nav></body></html>
        """.trimIndent())
        val manifest = chapters.indices.joinToString("\n") { """<item id="c$it" href="chapter$it.xhtml" media-type="application/xhtml+xml"/>""" }
        val spine = chapters.indices.joinToString("\n") { """<itemref idref="c$it"/>""" }
        entry("EPUB/book.opf", """$header
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="zh">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:${book.id}</dc:identifier>
            <dc:title>$title</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">$updated</meta></metadata>
            <manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>$manifest</manifest>
            <spine>$spine</spine></package>
        """.trimIndent())
        val renderer = org.commonmark.renderer.html.HtmlRenderer.builder().escapeHtml(true).build()
        val parser = org.commonmark.parser.Parser.builder().build()
        chapters.forEachIndexed { index, (_, chapter) ->
            val name = chapter.title.xml()
            val document = org.jsoup.Jsoup.parseBodyFragment(renderer.render(parser.parse(chapter.text)))
            document.select("img, a").forEach { element ->
                if (element.tagName() == "img") element.replaceWith(org.jsoup.nodes.TextNode(element.attr("alt")))
                else element.unwrap()
            }
            document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
            val html = document.body().html()
            entry("EPUB/chapter$index.xhtml", """$header
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh"><head><title>$name</title></head>
                <body><h1>$name</h1>$html</body></html>
            """.trimIndent())
        }
    }
}
