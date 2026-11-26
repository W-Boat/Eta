package io.github.mangi.eta.ui.screens.writing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

private enum class DeskPane(val label: String) { Setup("设定"), Library("资料库"), Story("剧情"), Context("上下文") }

@Composable
internal fun WritingDeskScreen(state: WritingViewModel, bookId: String, navigate: (AppRoute) -> Unit, back: () -> Unit) {
    val book = state.book(bookId)
    var pane by rememberSaveable { mutableStateOf(DeskPane.Setup) }
    var materialId by rememberSaveable { mutableStateOf<String?>(null) }
    var newKind by rememberSaveable { mutableStateOf<WritingMaterialKind?>(null) }
    var showTypes by rememberSaveable { mutableStateOf(false) }
    var showSample by rememberSaveable { mutableStateOf(false) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<WritingMaterialKind?>(null) }
    val skillImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) state.importSkill(bookId, it)
    }

    ScaffoldPage(title = "创作工作台", onBack = back) {
        item { WritingNotice(state) }
        if (book == null) {
            item { if (state.loading) WritingLoading() else WritingSection { Text("找不到作品。") } }
            return@ScaffoldPage
        }
        item("tabs") {
            WritingActions {
                DeskPane.entries.forEach { tab ->
                    TextButton(
                        tab.label, onClick = { pane = tab },
                        colors = if (pane == tab) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                }
            }
        }
        writingGeneration(state)
        writingReview(book.candidate, state.generating == null,
            onAccept = { state.review(bookId, null, it) },
            onReject = { state.review(bookId, null, null) },
            onRetry = { state.generate(bookId, null, it.task, it.instruction, volumeId = book.candidateVolume) },
        )
        when (pane) {
            DeskPane.Setup -> {
                item("mode") {
                    WritingSection {
                        Text("创作模式")
                        WritingActions {
                            TextButton(if (book.aiMode) "切换为人类主笔" else "切换为 AI 驱动", onClick = {
                                state.edit(bookId) { it.copy(aiMode = !it.aiMode) }
                            })
                            TextButton("AI 用量", onClick = { showUsage = true })
                        }
                        WritingCaption(if (book.aiMode) "当前为 AI 驱动 · 先确认摘要，再生成初稿。" else "当前为人类主笔 · 可随时生成和审阅改稿候选。")
                    }
                }
                item("outline") {
                    WritingSection {
                        SettingsTextField("作品设定", book.notes, singleLine = false) { notes ->
                            state.edit(bookId) { it.copy(notes = notes) }
                        }
                        SettingsTextField("全书大纲", book.outline, singleLine = false) { outline ->
                            state.edit(bookId) { it.copy(outline = outline) }
                        }
                        WritingActions {
                            TextButton("生成设定", enabled = state.generating == null, onClick = {
                                state.generate(bookId, null, WritingTask.Setup, "")
                            })
                            TextButton("生成大纲", enabled = state.generating == null, onClick = {
                                state.generate(bookId, null, WritingTask.Outline, "")
                            })
                        }
                    }
                }
            }
            DeskPane.Library -> {
                item("materials") {
                    WritingSection {
                        WritingActions {
                            TextButton("新建资料", onClick = { showTypes = true })
                            TextButton("导入 Skill", onClick = {
                                skillImporter.launch(arrayOf("text/*", "application/octet-stream"))
                            })
                            TextButton("分析样本", enabled = state.generating == null, onClick = { showSample = true })
                        }
                        WritingActions {
                            TextButton(if (filter == null) "✓ 全部" else "全部", onClick = { filter = null })
                            book.materials.map { it.kind }.distinct().forEach { kind ->
                                TextButton(if (filter == kind) "✓ ${kind.label}" else kind.label, onClick = { filter = kind })
                            }
                        }
                    }
                }
                items(book.materials.filter { filter == null || it.kind == filter }, key = { it.id }) { material ->
                    WritingSection {
                        WritingEntry(
                            material.title,
                            listOfNotNull(material.kind.label, "已停用".takeUnless { material.enabled },
                                "${material.anchors.size} 个锚点".takeIf { material.anchors.isNotEmpty() }).joinToString(" · "),
                        ) { materialId = material.id }
                    }
                }
            }
            DeskPane.Story -> {
                val events = book.materials.filter { it.kind == WritingMaterialKind.Event }
                val order = book.chapters.mapIndexed { i, chapter -> chapter.id to i }.toMap()
                item("timeline-heading") {
                    WritingSection {
                        Text("剧情时间线")
                        TextButton("添加事件", onClick = { newKind = WritingMaterialKind.Event })
                    }
                }
                items(events.sortedBy { it.anchors.minOfOrNull { anchor -> order[anchor.chapterId] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE },
                    key = { "event:${it.id}" }) { event ->
                    WritingSection { WritingEntry(event.title, event.content) { materialId = event.id } }
                }
                item("graph") {
                    WritingSection {
                        Text("因果图")
                        TextButton("连接事件", enabled = events.size > 1, onClick = { newKind = WritingMaterialKind.Cause })
                        book.materials.filter { it.kind == WritingMaterialKind.Cause }.forEach { cause ->
                            val source = events.find { it.id == cause.sourceEvent }
                            val target = events.find { it.id == cause.targetEvent }
                            WritingEntry(
                                "${source?.title ?: "缺失事件"} → ${target?.title ?: "缺失事件"}",
                                cause.title,
                            ) { materialId = cause.id }
                        }
                    }
                }
                item("handoffs") {
                    WritingSection {
                        Text("已定稿章节")
                        book.chapters.filter { it.handoff != null }.forEach { chapter ->
                            WritingEntry(chapter.title, chapter.handoff?.plot) {
                                navigate(AppRoute.WritingChapter(bookId, chapter.id))
                            }
                        }
                    }
                }
            }
            DeskPane.Context -> item("context") { WritingContextCard(state, book) }
        }
    }

    if (book == null) return
    if (showTypes) WritingDialog("新建资料", { showTypes = false }) {
        WritingMaterialKind.entries.filter { it != WritingMaterialKind.Cause }.forEach { kind ->
            WritingEntry(kind.label) { showTypes = false; newKind = kind }
        }
    }
    if (showSample) {
        var sample by rememberSaveable { mutableStateOf("") }
        WritingDialog("分析参考样本", { showSample = false }) {
            SettingsTextField("参考样本文本", sample, singleLine = false) { sample = it }
            DialogActions("生成分析", { showSample = false }, {
                state.generate(bookId, null, WritingTask.Analyze, sample)
                showSample = false
            }, confirmEnabled = sample.isNotBlank())
        }
    }
    if (showUsage) WritingDialog("AI 用量", { showUsage = false }) {
        val usage = book.usage.groupBy { it.model }
        if (usage.isEmpty()) WritingCaption("暂无生成记录。")
        usage.forEach { (model, records) ->
            Text(model)
            val input = records.mapNotNull { it.input }.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }?.toString() ?: "未知"
            val output = records.mapNotNull { it.output }.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }?.toString() ?: "未知"
            WritingCaption("${records.size} 次生成 · 输入 $input tokens · 输出 $output tokens")
        }
    }
    val material = book.materials.find { it.id == materialId }
    val kind = material?.kind ?: newKind
    if (kind != null) WritingMaterialDialog(book, material, kind, dismiss = { materialId = null; newKind = null },
        save = { item ->
            state.edit(bookId) {
                val index = it.materials.indexOfFirst { existing -> existing.id == item.id }
                it.copy(materials = if (index < 0) it.materials + item else it.materials.map { old -> if (old.id == item.id) item else old })
            }
            materialId = null
            newKind = null
        }, remove = {
            if (material != null) state.edit(bookId) {
                it.copy(materials = it.materials.filterNot { existing ->
                    existing.id == material.id || existing.sourceEvent == material.id || existing.targetEvent == material.id
                })
            }
            materialId = null
        }, jump = {
            materialId = null
            navigate(AppRoute.WritingChapter(bookId, it.chapterId))
        })
}

@Composable
private fun WritingContextCard(state: WritingViewModel, book: WritingBook) {
    val context = book.context
    var budget by rememberSaveable(book.id) { mutableStateOf(context.budget.toString()) }
    fun update(change: (WritingContext) -> WritingContext) = state.edit(book.id) { it.copy(context = change(it.context)) }
    WritingSection {
        SettingsTextField("附加上下文字符预算", budget) { value ->
            budget = value
            value.toIntOrNull()?.takeIf { it in 0..100_000 }?.let { number -> update { it.copy(budget = number) } }
        }
        WritingCaption("正文和明确的作者要求独立保留。当前附加资料上限为 ${context.budget} 字符。")
        WritingToggle("跨章记忆", context.includeMemory) { update { it.copy(includeMemory = !it.includeMemory) } }
        WritingToggle("关键词世界书", context.includeWorldbook) { update { it.copy(includeWorldbook = !it.includeWorldbook) } }
        WritingToggle("作者反馈", context.includeFeedback) { update { it.copy(includeFeedback = !it.includeFeedback) } }
        WritingToggle("资料库", context.includeMaterials) { update { it.copy(includeMaterials = !it.includeMaterials) } }
        WritingToggle("写作 Skill", context.includeSkills) { update { it.copy(includeSkills = !it.includeSkills) } }
        SettingsTextField("压缩后的记忆", context.compressed, singleLine = false) { value ->
            update { it.copy(compressed = value) }
        }
        TextButton("生成压缩候选", enabled = state.generating == null, onClick = {
            state.generate(book.id, null, WritingTask.Compress, "")
        })
    }
}

@Composable
private fun WritingToggle(label: String, enabled: Boolean, toggle: () -> Unit) {
    WritingEntry(label, if (enabled) "已启用" else "已停用", toggle)
}

@Composable
private fun WritingMaterialDialog(
    book: WritingBook,
    current: WritingMaterial?,
    kind: WritingMaterialKind,
    dismiss: () -> Unit,
    save: (WritingMaterial) -> Unit,
    remove: () -> Unit,
    jump: (WritingAnchor) -> Unit,
) {
    var title by rememberSaveable(current?.id) { mutableStateOf(current?.title.orEmpty()) }
    var content by rememberSaveable(current?.id) { mutableStateOf(current?.content.orEmpty()) }
    var keywords by rememberSaveable(current?.id) { mutableStateOf(current?.keywords.orEmpty()) }
    var enabled by rememberSaveable(current?.id) { mutableStateOf(current?.enabled ?: true) }
    var source by rememberSaveable(current?.id) { mutableStateOf(current?.sourceEvent) }
    var target by rememberSaveable(current?.id) { mutableStateOf(current?.targetEvent) }
    var removeConfirm by rememberSaveable { mutableStateOf(false) }
    WritingDialog(kind.label, dismiss) {
        SettingsTextField("名称", title) { title = it }
        SettingsTextField("内容", content, singleLine = false) { content = it }
        if (kind == WritingMaterialKind.Worldbook) {
            SettingsTextField("触发词（逗号分隔）", keywords) { keywords = it }
        }
        WritingToggle("用于创作上下文", enabled) { enabled = !enabled }
        if (kind == WritingMaterialKind.Cause) {
            val events = book.materials.filter { it.kind == WritingMaterialKind.Event }
            WritingCaption("起因")
            WritingActions {
                events.forEach { event ->
                    TextButton(if (source == event.id) "✓ ${event.title}" else event.title, onClick = { source = event.id })
                }
            }
            WritingCaption("结果")
            WritingActions {
                events.forEach { event ->
                    TextButton(if (target == event.id) "✓ ${event.title}" else event.title, onClick = { target = event.id })
                }
            }
        }
        current?.anchors.orEmpty().forEach { anchor ->
            val chapter = book.chapter(anchor.chapterId)
            val missing = chapter == null || anchor.locate(chapter.text) == null
            WritingEntry(chapter?.title ?: "缺失章节", (if (missing) "原段待定位 · " else "") + anchor.quote.take(100)) {
                if (chapter != null) jump(anchor)
            }
        }
        if (current != null) {
            if (removeConfirm) {
                WritingCaption(if (kind == WritingMaterialKind.Event) "删除事件也会移除与其相连的因果关系。" else "删除后会从资料库移除此条目。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("确认删除", onClick = remove)
                    TextButton("取消删除", onClick = { removeConfirm = false })
                }
            } else TextButton("删除资料", onClick = { removeConfirm = true })
        }
        DialogActions(
            "保存", dismiss, {
                val item = current ?: WritingMaterial(kind = kind, title = title.trim())
                save(item.copy(title = title.trim(), content = content, keywords = keywords,
                    enabled = enabled, sourceEvent = source, targetEvent = target))
            },
            confirmEnabled = title.isNotBlank() && (kind != WritingMaterialKind.Cause || (source != null && target != null && source != target)),
        )
    }
}
