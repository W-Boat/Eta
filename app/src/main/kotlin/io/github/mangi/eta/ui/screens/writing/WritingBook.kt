package io.github.mangi.eta.ui.screens.writing

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal fun writingId(): String = UUID.randomUUID().toString()

@Serializable
internal data class WritingBook(
    val id: String = writingId(),
    val title: String,
    val cover: String = "",
    val notes: String = "",
    val outline: String = "",
    val aiMode: Boolean = false,
    val goal: Int = 2000,
    val width: Int = 640,
    val volumes: List<WritingVolume> = listOf(WritingVolume(title = "第一卷", chapters = listOf(WritingChapter(title = "开篇")))),
    val materials: List<WritingMaterial> = emptyList(),
    val context: WritingContext = WritingContext(),
    val candidate: WritingCandidate? = null,
    val candidateVolume: String? = null,
    val usage: List<WritingUsage> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
    val original: JsonObject? = null,
    val activeChapterId: String? = null,
) {
    val chapters get() = volumes.flatMap { it.chapters }
    fun activeChapter() = activeChapterId?.let(::chapter) ?: chapters.firstOrNull()
    fun chapter(id: String) = volumes.firstNotNullOfOrNull { volume -> volume.chapters.find { it.id == id } }
    fun changeChapter(id: String, edit: (WritingChapter) -> WritingChapter) = copy(
        volumes = volumes.map { volume ->
            if (volume.chapters.none { it.id == id }) volume
            else volume.copy(chapters = volume.chapters.map { if (it.id == id) edit(it) else it })
        },
    )
}

@Serializable
internal data class WritingVolume(
    val id: String = writingId(),
    val title: String,
    val chapters: List<WritingChapter> = emptyList(),
)

@Serializable
internal data class WritingChapter(
    val id: String = writingId(),
    val title: String,
    val text: String = "",
    val goal: Int? = null,
    val summary: String = "",
    val handoff: WritingHandoff? = null,
    val feedback: List<WritingFeedback> = emptyList(),
    val versions: List<WritingVersion> = emptyList(),
    val candidate: WritingCandidate? = null,
    val drafts: List<ParagraphDraft> = emptyList(),
    val position: WritingPosition = WritingPosition(),
)

@Serializable
internal data class WritingHandoff(
    val plot: String,
    val lessons: String,
    val carry: String,
    val directions: List<String>,
    val selected: String = "",
)

@Serializable
internal enum class ForeshadowStage(val label: String) { Planted("埋设"), Echo("呼应"), Resolved("回收") }

@Serializable
internal data class WritingAnchor(
    val chapterId: String, val offset: Int, val quote: String,
    val stage: ForeshadowStage? = null,
    val detached: Boolean = false,
) {
    fun locate(text: String): Int? {
        if (detached) return null
        if (offset in 0..text.length && text.startsWith(quote, offset)) return offset
        val first = text.indexOf(quote)
        return first.takeIf { it >= 0 && first == text.lastIndexOf(quote) }
    }
}

@Serializable
internal data class WritingFeedback(
    val anchor: WritingAnchor,
    val prompt: String = "",
    val annotation: String = "",
    val vote: Int = 0,
)

@Serializable
internal enum class WritingMaterialKind(val label: String) {
    Character("人物"), Setting("世界设定"), Event("剧情事件"), Cause("因果关系"),
    Foreshadow("伏笔"), Worldbook("世界书"), Reference("参考样本"), Skill("写作 Skill"),
}

@Serializable
internal data class WritingMaterial(
    val id: String = writingId(),
    val kind: WritingMaterialKind,
    val title: String,
    val content: String = "",
    val keywords: String = "",
    val enabled: Boolean = true,
    val anchors: List<WritingAnchor> = emptyList(),
    val sourceEvent: String? = null,
    val targetEvent: String? = null,
) {
    val resolved get() = anchors.any { it.stage == ForeshadowStage.Resolved }
}

@Serializable
internal data class WritingContext(
    val budget: Int = 12_000,
    val includeMemory: Boolean = true,
    val includeWorldbook: Boolean = true,
    val includeFeedback: Boolean = true,
    val includeMaterials: Boolean = true,
    val includeSkills: Boolean = true,
    val compressed: String = "",
)

@Serializable
internal data class WritingUsage(
    val model: String,
    val input: Int?,
    val output: Int?,
)

@Serializable
internal data class WritingVersion(
    val text: String,
    val label: String,
    val time: Long = System.currentTimeMillis(),
)

@Serializable
internal enum class WritingTask(val label: String) {
    Summary("章节摘要"), Draft("生成初稿"), Rewrite("改稿"), Outline("全书大纲"),
    Setup("作品设定"), Volume("卷名"), Handoff("定稿交接"),
    Extract("提取资料"), Analyze("分析样本"), Compress("压缩上下文"),
}

@Serializable
internal data class WritingCandidate(
    val task: WritingTask,
    val original: String,
    val text: String,
    val instruction: String = "",
    val targets: List<WritingAnchor> = emptyList(),
    val changes: List<String> = emptyList(),
)

internal fun WritingChapter.withText(replacement: String, label: String): WritingChapter {
    if (replacement == text) return this
    return copy(text = replacement, versions = (listOf(WritingVersion(text, label)) + versions).take(20))
}

internal val manuscriptLine = Regex("""[^\r\n]+""")
internal val sceneDivider = Regex("""(?:[*＊][\s　]*){3,}|[-‐‑‒–—―─━]{2,}|_{3,}|={3,}|[·•]{3,}|[✦❖※◇✧]{1,5}""")
private val codeFence = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
private val wordPattern = Regex("""\p{IsHan}|[\p{L}\p{N}&&[^\p{IsHan}]]+""")

internal fun writingWordCount(text: String): Int = wordPattern.findAll(text).count()

internal fun writingParagraphs(chapter: WritingChapter): List<WritingAnchor> = buildList {
    var fence: String? = null
    manuscriptLine.findAll(chapter.text).forEach { line ->
        val marker = codeFence.matchEntire(line.value)
        val open = fence
        if (open != null) {
            if (marker != null && marker.groupValues[1].startsWith(open) && marker.groupValues[2].isBlank()) fence = null
        } else if (marker != null) {
            fence = marker.groupValues[1]
        } else if (line.value.isNotBlank() && !sceneDivider.matches(line.value.trim())) {
            add(WritingAnchor(chapter.id, line.range.first, line.value))
        }
    }
}

internal fun applyWritingChanges(text: String, candidate: WritingCandidate): String {
    check(text == candidate.original) { "正文已变更，请重新生成候选。" }
    if (candidate.targets.isEmpty()) return candidate.text
    check(candidate.targets.size == candidate.changes.size) { "候选段落数量与选择不一致。" }
    val revised = StringBuilder(text)
    candidate.targets.zip(candidate.changes).sortedByDescending { it.first.offset }.forEach { (anchor, replacement) ->
        check(text.startsWith(anchor.quote, anchor.offset)) { "原段落已变更。" }
        revised.replace(anchor.offset, anchor.offset + anchor.quote.length, replacement)
    }
    return revised.toString()
}

internal fun WritingBook.moveChapter(chapterId: String, targetVolumeId: String): WritingBook {
    val chapter = chapter(chapterId) ?: return this
    val target = volumes.firstOrNull { it.id == targetVolumeId } ?: return this
    if (target.chapters.any { it.id == chapterId }) return this
    return copy(volumes = volumes.map { volume ->
        if (volume.id == targetVolumeId) volume.copy(chapters = volume.chapters + chapter)
        else volume.copy(chapters = volume.chapters.filterNot { it.id == chapterId })
    })
}
