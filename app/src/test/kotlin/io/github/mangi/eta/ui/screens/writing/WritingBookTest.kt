package io.github.mangi.eta.ui.screens.writing

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingBookTest {
    @Test
    fun nonAdjacentCorrectionsPreserveIntermediateTextAndRejectStaleSource() {
        val chapter = WritingChapter(title = "第一章", text = "灯亮了。\r\n\r\n---\r\n\r\n她停在门口。\r\n\r\n潮水退去。")
        val parts = writingParagraphs(chapter)
        val candidate = WritingCandidate(WritingTask.Rewrite, chapter.text, "", targets = listOf(parts[0], parts[2]),
            changes = listOf("灯灭了。", "潮水涌来。"))
        assertEquals("灯灭了。\r\n\r\n---\r\n\r\n她停在门口。\r\n\r\n潮水涌来。", applyWritingChanges(chapter.text, candidate))
        assertTrue(runCatching { applyWritingChanges(chapter.text + "作者补笔", candidate) }.isFailure)
    }

    @Test
    fun previousChapterHandoffIsIncludedWithAnIndependentTarget() {
        val first = WritingChapter(title = "第一章", handoff = WritingHandoff("丢了钥匙", "固定视角", "找锁匠", listOf("锁匠失踪"), "锁匠失踪"))
        val next = WritingChapter(title = "第二章", text = "她找遍了镇上。")
        val book = WritingBook(title = "小镇", volumes = listOf(WritingVolume(title = "第一卷", chapters = listOf(first, next))))
        val prompt = buildWritingPrompt(book, next, WritingTask.Rewrite, "保留第三人称", emptyList())
        assertTrue(prompt.contains("锁匠失踪"))
        assertTrue(prompt.contains(next.text))
        val withoutMemory = book.copy(context = book.context.copy(budget = 0))
        val reduced = buildWritingPrompt(withoutMemory, next, WritingTask.Rewrite, "保留第三人称", emptyList())
        assertFalse(reduced.contains("锁匠失踪"))
        assertTrue(reduced.contains("保留第三人称"))
        assertTrue(reduced.contains(next.text))
    }

    @Test
    fun importsLegacyBackupAndRoundTripsNativeHistory() {
        val original = readWritingBackup("""{"title":"小镇","volume":"开卷","chapters":[{"id":"first","title":"开篇","text":"钥匙不见了。"}],"unrecognized":{"keep":true}}""")
        val edited = original.book.changeChapter("first") { it.withText("门开了。", "采用 AI 改稿前") }
        val stream = ByteArrayOutputStream()
        exportWritingBook(edited, WritingExport.Backup, stream)
        val exported = stream.toByteArray().decodeToString()
        assertTrue(exported.contains("unrecognized"))
        val imported = readWritingBackup(exported).book
        assertEquals("门开了。", imported.chapters.single().text)
        assertEquals("钥匙不见了。", imported.chapters.single().versions.single().text)
    }

    @Test
    fun epubStartsWithStoredMimetypeAndPreservesChapterOrder() {
        val book = WritingBook(title = "风 & 雨")
        val stream = ByteArrayOutputStream()
        exportWritingBook(book, WritingExport.Epub, stream)
        ZipInputStream(stream.toByteArray().inputStream()).use { zip ->
            val mimetype = zip.nextEntry
            assertEquals("mimetype", mimetype.name)
            assertEquals(java.util.zip.ZipEntry.STORED, mimetype.method)
            assertEquals("application/epub+zip", zip.readBytes().decodeToString())
            zip.closeEntry()
            assertEquals("META-INF/container.xml", zip.nextEntry.name)
            zip.closeEntry()
            assertEquals("EPUB/nav.xhtml", zip.nextEntry.name)
            assertTrue(zip.readBytes().decodeToString().contains("chapter0.xhtml"))
            zip.closeEntry()
            assertEquals("EPUB/book.opf", zip.nextEntry.name)
            zip.closeEntry()
            assertEquals("EPUB/chapter0.xhtml", zip.nextEntry.name)
        }
    }

}
