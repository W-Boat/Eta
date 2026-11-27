package io.github.mangi.eta.ui.screens.writing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.TopBarActionButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

internal fun LazyListScope.writingReview(
    candidate: WritingCandidate?,
    enabled: Boolean,
    onAccept: (WritingCandidate) -> Unit,
    onReject: () -> Unit,
    onRetry: (WritingCandidate) -> Unit,
    compareInitially: Boolean = false,
) {
    if (candidate == null) return
    item("writing-review") {
        WritingCandidateCard(candidate, enabled, onAccept, onReject, onRetry, compareInitially)
    }
}

@Composable
private fun WritingCandidateCard(
    candidate: WritingCandidate,
    enabled: Boolean,
    onAccept: (WritingCandidate) -> Unit,
    onReject: () -> Unit,
    onRetry: (WritingCandidate) -> Unit,
    compareInitially: Boolean,
) {
    var revised by remember(candidate) { mutableStateOf(candidate.text) }
    var changes by remember(candidate) { mutableStateOf(candidate.changes) }
    var compare by rememberSaveable { mutableStateOf(compareInitially) }
    WritingSection {
        Text("审阅 · ${candidate.task.label}")
        if (candidate.targets.isEmpty()) {
            if (candidate.task == WritingTask.Extract) {
                val evidence = remember(revised) { runCatching { parseWritingJson(revised) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
                evidence?.let {
                    Text(it.string("title"))
                    WritingCaption("原文证据")
                    Text(it.string("quote"))
                }
            }
            if (compare && candidate.original.isNotBlank()) {
                WritingCaption("原文")
                WritingMarkdown(candidate.original)
            }
            if (candidate.task == WritingTask.Handoff) WritingCaption("可调整本章剧情、经验、承接和后续走向，再确认定稿。")
            SettingsTextField("候选", revised, singleLine = false) { revised = it }
        } else {
            candidate.targets.forEachIndexed { index, anchor ->
                if (compare) {
                    WritingCaption("原段 ${index + 1}")
                    WritingMarkdown(anchor.quote)
                }
                SettingsTextField("候选段 ${index + 1}", changes[index], singleLine = false) { text ->
                    changes = changes.mapIndexed { position, old -> if (index == position) text else old }
                }
            }
        }
        WritingActions {
            if (candidate.original.isNotBlank()) TextButton(if (compare) "收起原文" else "对比原文", onClick = { compare = !compare })
            TextButton("重生成", enabled = enabled, onClick = { onRetry(candidate) })
            TextButton("拒绝", enabled = enabled, onClick = onReject)
            TextButton(
                text = "接受候选",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = enabled && (revised.isNotBlank() || changes.isNotEmpty()),
                onClick = { onAccept(candidate.copy(text = revised, changes = changes)) },
            )
        }
    }
}

@Composable
internal fun WritingChapterSettings(
    book: WritingBook,
    chapter: WritingChapter,
    dismiss: () -> Unit,
    save: (String, Int?, Int) -> Unit,
    openDesk: () -> Unit,
    move: () -> Unit,
    delete: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(chapter.title) }
    var goal by rememberSaveable { mutableStateOf(chapter.goal?.toString().orEmpty()) }
    var width by rememberSaveable { mutableStateOf(book.width.toString()) }
    WritingDialog("章节设置", dismiss) {
        SettingsTextField("章名", title) { title = it }
        SettingsTextField("章节字数目标（留空使用全书默认值）", goal) { goal = it }
        SettingsTextField("正文最大宽度（280–1200 dp）", width) { width = it }
        WritingActions {
            TextButton("打开创作工作台", onClick = openDesk)
            if (book.volumes.size > 1) TextButton("移动章节", onClick = move)
            if (book.chapters.size > 1) TextButton("删除章节", onClick = delete)
        }
        val targetWidth = width.toIntOrNull()?.takeIf { it in 280..1200 }
        val targetGoal = goal.toIntOrNull()?.takeIf { it >= 0 }
        DialogActions(
            "保存", dismiss,
            { if (targetWidth != null) save(title.trim(), targetGoal, targetWidth) },
            confirmEnabled = title.isNotBlank() && targetWidth != null && (goal.isBlank() || targetGoal != null),
        )
    }
}

internal enum class ParagraphFeedbackField(val title: String) {
    Prompt("本段改稿提示"),
    Annotation("本段批注"),
    Foreshadow("本段伏笔"),
}

@Composable
internal fun WritingFeedbackDialog(
    book: WritingBook,
    chapter: WritingChapter,
    anchor: WritingAnchor,
    field: ParagraphFeedbackField,
    dismiss: () -> Unit,
    save: (WritingFeedback, String?, String) -> Unit,
    rewrite: ((String) -> Unit)?,
) {
    val latest = chapter.feedback.firstOrNull { it.anchor.locate(chapter.text) == anchor.offset }
    var prompt by rememberSaveable { mutableStateOf(latest?.prompt.orEmpty()) }
    var annotation by rememberSaveable { mutableStateOf(latest?.annotation.orEmpty()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var newTitle by rememberSaveable { mutableStateOf("") }
    WritingDialog(field.title, dismiss) {
        WritingCaption(anchor.quote.take(250))
        when (field) {
            ParagraphFeedbackField.Prompt -> {
                SettingsTextField("改稿提示", prompt, singleLine = false) { prompt = it }
                if (rewrite != null) TopBarActionButton(Icons.Rounded.AutoAwesome, "生成本段候选", onClick = {
                    save(WritingFeedback(anchor, prompt, annotation, latest?.vote ?: 0), null, "")
                    rewrite(prompt)
                })
            }
            ParagraphFeedbackField.Annotation -> SettingsTextField("批注", annotation, singleLine = false) { annotation = it }
            ParagraphFeedbackField.Foreshadow -> {
                SettingsTextField("新增伏笔", newTitle) {
                    newTitle = it
                    if (it.isNotBlank()) selected = null
                }
                val foreshadows = book.materials.filter { it.kind == WritingMaterialKind.Foreshadow }
                if (foreshadows.isNotEmpty()) {
                    WritingCaption("关联已有伏笔")
                    foreshadows.forEach { item ->
                        WritingEntry(item.title, if (selected == item.id) "已选择" else null) {
                            selected = item.id.takeUnless { it == selected }
                            newTitle = ""
                        }
                    }
                }
            }
        }
        DialogActions("保存", dismiss, { save(WritingFeedback(anchor, prompt, annotation, latest?.vote ?: 0), selected, newTitle) },
            confirmEnabled = field != ParagraphFeedbackField.Foreshadow || selected != null || newTitle.isNotBlank())
    }
}

@Composable
internal fun WritingHandoffCard(
    book: WritingBook,
    chapter: WritingChapter,
    edit: () -> Unit,
    write: (WritingHandoff) -> Unit,
) {
    val handoff = chapter.handoff
    val previous = book.chapters.getOrNull(book.chapters.indexOfFirst { it.id == chapter.id } - 1)?.handoff
    WritingSection {
        Text("剧情交接")
        if (previous != null) {
            WritingCaption("上一章必须承接")
            Text(previous.carry)
            if (previous.selected.isNotBlank()) WritingCaption("选定走向 · ${previous.selected}")
        }
        if (handoff != null) {
            WritingCaption("本章剧情")
            Text(handoff.plot)
            if (handoff.lessons.isNotBlank()) {
                WritingCaption("写作经验")
                Text(handoff.lessons)
            }
            WritingCaption("下一章必须承接")
            Text(handoff.carry)
            if (handoff.directions.isNotEmpty()) {
                WritingCaption("预选下一章走向")
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    handoff.directions.forEach { direction ->
                        WritingEntry(if (direction == handoff.selected) "✓ $direction" else direction) {
                            write(handoff.copy(selected = if (direction == handoff.selected) "" else direction))
                        }
                    }
                }
            }
        }
        TextButton(if (handoff == null) "手动定稿" else "编辑交接", onClick = edit)
    }
}

@Composable
internal fun WritingHandoffDialog(old: WritingHandoff?, dismiss: () -> Unit, save: (WritingHandoff) -> Unit) {
    var plot by rememberSaveable { mutableStateOf(old?.plot.orEmpty()) }
    var lessons by rememberSaveable { mutableStateOf(old?.lessons.orEmpty()) }
    var carry by rememberSaveable { mutableStateOf(old?.carry.orEmpty()) }
    var directions by rememberSaveable { mutableStateOf(old?.directions.orEmpty().joinToString("\n")) }
    WritingDialog("定稿交接", dismiss) {
        SettingsTextField("本章剧情", plot, singleLine = false) { plot = it }
        SettingsTextField("写作经验", lessons, singleLine = false) { lessons = it }
        SettingsTextField("下一章必须承接", carry, singleLine = false) { carry = it }
        SettingsTextField("后续走向（每行一条）", directions, singleLine = false) { directions = it }
        DialogActions(
            "确认定稿", dismiss,
            {
                val paths = directions.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
                save(WritingHandoff(plot, lessons, carry, paths, old?.selected.orEmpty().takeIf { it in paths }.orEmpty()))
            },
            confirmEnabled = plot.isNotBlank() && carry.isNotBlank(),
        )
    }
}
