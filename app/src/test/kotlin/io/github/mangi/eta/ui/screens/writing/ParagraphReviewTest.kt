package io.github.mangi.eta.ui.screens.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphReviewTest {
    @Test
    fun editsBeforeDuplicateParagraphsShiftByIdentityAndAffectOnlyOverlap() {
        val chapter = WritingChapter(title = "开篇", text = "一样。\n一样。")
        val targets = writingParagraphs(chapter)
        val start = chapter.copy(drafts = targets.map { ParagraphDraft(anchor = it, text = "新候选。") })
        val prefix = "新开头。\n"
        val shifted = start.withManuscript(prefix + start.text, ManuscriptEdit(0, 0, prefix.length))
        assertEquals(targets.map { it.offset + prefix.length }, shifted.drafts.map { it.anchor.offset })
        assertTrue(shifted.drafts.all { it.decision == ParagraphDecision.Pending })
        val second = shifted.drafts[1].anchor.offset
        val overlap = shifted.withManuscript(shifted.text.replaceRange(second, second + 1, "另"),
            ManuscriptEdit(second, second + 1, second + 1))
        assertEquals(listOf(ParagraphDecision.Pending, ParagraphDecision.Stale), overlap.drafts.map { it.decision })
    }

    @Test
    fun adoptSelectedPreservesOtherPendingDraftsAndSceneMarkers() {
        val chapter = WritingChapter(title = "开篇", text = "　　第一段\r\n＊　＊　＊\r\n第二段\r\n第三段")
        val drafts = writingParagraphs(chapter).mapIndexed { index, anchor ->
            ParagraphDraft(anchor = anchor, text = "新候选 ${index + 1}", selected = index != 1)
        }
        val changed = chapter.copy(drafts = drafts).acceptParagraphs(setOf(drafts[0].id, drafts[2].id))
        assertEquals("　　新候选 1\r\n＊　＊　＊\r\n第二段\r\n新候选 3", changed.text)
        assertEquals(chapter.text, changed.versions.single().text)
        assertEquals(ParagraphDecision.Pending, changed.drafts[1].decision)
        assertEquals(changed.text.indexOf("第二段"), changed.drafts[1].anchor.offset)
    }

    @Test
    fun legacyCandidatesAndRecentPositionSurviveBackupAndLoad() {
        val first = WritingChapter(title = "开篇")
        val chapter = WritingChapter(title = "海边", text = "夜潮高。", position = WritingPosition(2, 3, 44))
        val legacy = chapter.copy(candidate = WritingCandidate(WritingTask.Rewrite, chapter.text, "",
            targets = writingParagraphs(chapter), changes = listOf("潮退了。")))
        val book = WritingBook(title = "海边", volumes = listOf(WritingVolume(title = "上卷", chapters = listOf(first, legacy))),
            activeChapterId = chapter.id)
        val fromDisk = writingJson.decodeFromString<WritingBook>(writingJson.encodeToString(WritingBook.serializer(), book))
        assertEquals(chapter.id, fromDisk.activeChapter()?.id)
        assertEquals(chapter.position, fromDisk.activeChapter()?.position)
        val source = java.io.ByteArrayOutputStream()
        exportWritingBook(fromDisk, WritingExport.Backup, source)
        val imported = readWritingBackup(source.toByteArray().decodeToString()).book
        val active = requireNotNull(imported.activeChapter())
        assertEquals(chapter.id, active.id)
        assertEquals("潮退了。", active.drafts.single().text)
        assertEquals(null, active.candidate)
        assertEquals(first.id, fromDisk.copy(activeChapterId = "removed").activeChapter()?.id)
    }

    @Test
    fun unsafeSectionRestructureStaysInWholeChapterReview() {
        val chapter = WritingChapter(title = "开篇", text = "标题\n第一段\n---\n第二段")
        val changed = chapter.receiveDrafts(
            WritingCandidate(WritingTask.Rewrite, chapter.text, "新标题\n第一段\n第二段"),
            chapter,
            replacing = null,
        )
        assertNotNull(changed.candidate)
        assertTrue(changed.drafts.isEmpty())
        val aligned = chapter.receiveDrafts(
            WritingCandidate(WritingTask.Rewrite, chapter.text, "标题\n新段落\n---\n第二段"),
            chapter,
            replacing = null,
        )
        assertFalse(aligned.drafts.isEmpty())
        assertEquals("第一段", aligned.drafts.single().anchor.quote)
    }
}
