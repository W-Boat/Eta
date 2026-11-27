package io.github.mangi.eta.ui.screens.writing

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.PreferenceGroupItem
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class DeskPage(val label: String, val kind: WritingMaterialKind? = null) {
    Handoff("创作工作台"), Setup("作品设定"), Characters("人物", WritingMaterialKind.Character),
    World("世界设定", WritingMaterialKind.Setting), Worldbook("关键词世界书", WritingMaterialKind.Worldbook),
    Threads("伏笔档案", WritingMaterialKind.Foreshadow), Story("剧情时间线"),
    Graph("剧情因果图"), Context("上下文与记忆"), References("参考样本", WritingMaterialKind.Reference),
    Skills("写作 Skill", WritingMaterialKind.Skill), Usage("AI 用量"),
}

@Composable
internal fun WritingDeskScreen(state: WritingViewModel, bookId: String, navigate: (AppRoute) -> Unit, back: () -> Unit) {
    val book = state.book(bookId)
    var page by rememberSaveable { mutableStateOf(DeskPage.Handoff) }
    var openedMaterialId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingKind by rememberSaveable { mutableStateOf<WritingMaterialKind?>(null) }
    var handoffEdit by rememberSaveable { mutableStateOf(false) }
    var sampleEdit by rememberSaveable { mutableStateOf(false) }
    var sample by rememberSaveable { mutableStateOf("") }
    val skillPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) state.importSkill(bookId, it)
    }
    fun previousPage() {
        when {
            editingKind != null -> editingKind = null
            openedMaterialId != null -> openedMaterialId = null
            page != DeskPage.Handoff -> page = DeskPage.Handoff
            else -> back()
        }
    }
    BackHandler(enabled = page != DeskPage.Handoff || openedMaterialId != null || editingKind != null, onBack = ::previousPage)
    if (book == null) {
        ScaffoldPage("创作工作台", back) { item { if (state.loading) WritingLoading() else Text("找不到作品") } }
        return
    }

    fun jump(anchor: WritingAnchor) {
        val chapter = book.chapter(anchor.chapterId) ?: return
        val offset = anchor.locate(chapter.text) ?: return
        state.openChapter(book.id, chapter.id, offset)
        back()
    }
    val material = book.materials.find { it.id == openedMaterialId }
    val kind = editingKind
    if (kind != null) {
        WritingMaterialEditor(book, material, kind, cancel = ::previousPage, save = { saved ->
            state.edit(book.id) { current ->
                val items = if (current.materials.any { it.id == saved.id }) current.materials.map {
                    if (it.id == saved.id) saved else it
                } else current.materials + saved
                current.copy(materials = items)
            }
            openedMaterialId = saved.id
            editingKind = null
        })
        return
    }
    if (material != null) {
        WritingMaterialDetail(book, material, ::previousPage, edit = { editingKind = material.kind }, jump = ::jump,
            update = { revised -> state.edit(bookId) { current ->
                current.copy(materials = current.materials.map { if (it.id == revised.id) revised else it })
            } },
            delete = {
                state.edit(bookId) { current -> current.copy(materials = current.materials.filterNot {
                    it.id == material.id || it.sourceEvent == material.id || it.targetEvent == material.id
                }) }
                openedMaterialId = null
            },
        )
        return
    }
    val chapter = book.activeChapter()
    val events = book.materials.filter { it.kind == WritingMaterialKind.Event }
    val chapterOrder = book.chapters.mapIndexed { index, item -> item.id to index }.toMap()
    val sorted = events.sortedWith(compareBy<WritingMaterial> {
        it.anchors.minOfOrNull { anchor -> chapterOrder[anchor.chapterId] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
    }.thenBy { it.anchors.minOfOrNull(WritingAnchor::offset) ?: Int.MAX_VALUE })
    val entries = book.materials.filter { it.kind == page.kind }
    val addKind = page.kind ?: when (page) {
        DeskPage.Story -> WritingMaterialKind.Event
        DeskPage.Graph -> WritingMaterialKind.Cause.takeIf { events.size >= 2 }
        else -> null
    }
    ScaffoldPage(title = page.label, onBack = ::previousPage, actions = {
        if (addKind != null) TopBarActionButton(Icons.Rounded.Add, "新增${addKind.label}", onClick = { editingKind = addKind })
    }) {
        item { WritingNotice(state) }
        when (page) {
            DeskPage.Handoff -> {
                if (chapter != null) {
                    val before = book.chapters.getOrNull(book.chapters.indexOfFirst { it.id == chapter.id } - 1)?.handoff
                    val after = chapter.handoff
                    val threads = book.materials.filter {
                        it.kind == WritingMaterialKind.Foreshadow && !it.resolved &&
                            it.anchors.any { anchor -> anchor.chapterId == chapter.id || (chapterOrder[anchor.chapterId] ?: Int.MAX_VALUE) < (chapterOrder[chapter.id] ?: -1) }
                    }
                    item("chapter") {
                        DeskGroup("本章 · ${chapter.title}") {
                            if (before != null) {
                                DeskParagraph("上一章必须承接", before.carry)
                                if (before.selected.isNotBlank()) DeskParagraph("选定走向", before.selected)
                                HorizontalDivider()
                            }
                            DeskParagraph("已确认摘要", chapter.summary.ifBlank { "尚未确认摘要" })
                            val target = chapter.goal ?: book.goal
                            if (target > 0) DeskParagraph("写作目标", "$target 字")
                        }
                    }
                    item("threads") {
                        DeskGroup("相关未回收伏笔") {
                            if (threads.isEmpty()) DeskParagraph(null, "本章暂无相关的未回收伏笔")
                            threads.forEachIndexed { index, thread ->
                                WritingEntry(thread.title, thread.anchors.lastOrNull()?.let { book.chapter(it.chapterId)?.title }) {
                                    openedMaterialId = thread.id
                                }
                                if (index < threads.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                    item("handoff") {
                        DeskGroup("交给下一章") {
                            if (after != null) {
                                DeskParagraph("本章剧情", after.plot)
                                if (after.lessons.isNotBlank()) DeskParagraph("写作经验", after.lessons)
                                DeskParagraph("下一章必须承接", after.carry)
                                after.directions.forEach { direction ->
                                    WritingEntry(if (direction == after.selected) "✓ $direction" else direction) {
                                        state.editChapter(bookId, chapter.id) {
                                            it.copy(handoff = after.copy(selected = direction.takeUnless { direction == after.selected }.orEmpty()))
                                        }
                                    }
                                }
                            }
                            WritingEntry(if (after == null) "整理本章交接" else "编辑交接") { handoffEdit = true }
                        }
                    }
                }
                item("materials") { DeskLinks("故事资料", listOf(DeskPage.Characters, DeskPage.World, DeskPage.Worldbook,
                    DeskPage.Threads, DeskPage.Story, DeskPage.Graph)) { page = it } }
                item("settings") {
                    DeskGroup("写作设置") {
                        listOf(DeskPage.Setup, DeskPage.Context, DeskPage.References, DeskPage.Skills, DeskPage.Usage).forEach {
                            WritingEntry(it.label) { page = it }
                            HorizontalDivider()
                        }
                        WritingEntry("AI 模型") { navigate(AppRoute.ModelProviders) }
                        HorizontalDivider()
                        WritingEntry("角色扮演") { navigate(AppRoute.Characters) }
                    }
                }
            }
            DeskPage.Setup -> {
                item("setup") {
                    DeskGroup("创作方式") {
                        SwitchPreference(title = "AI 驱动", summary = "先确认摘要，再生成初稿", checked = book.aiMode,
                            onCheckedChange = { mode -> state.edit(bookId) { it.copy(aiMode = mode) } })
                    }
                    WritingSection {
                        SettingsTextField("作品设定", book.notes, singleLine = false) { notes -> state.edit(bookId) { it.copy(notes = notes) } }
                        SettingsTextField("全书大纲", book.outline, singleLine = false) { outline -> state.edit(bookId) { it.copy(outline = outline) } }
                        WritingEntry("生成设定候选") { state.generate(bookId, null, WritingTask.Setup, "") }
                        WritingEntry("生成大纲候选") { state.generate(bookId, null, WritingTask.Outline, "") }
                    }
                }
            }
            DeskPage.Story -> {
                val groups = sorted.groupBy { it.anchors.firstOrNull()?.chapterId }
                if (groups.isEmpty()) item { DeskGroup("剧情事件") { DeskParagraph(null, "暂无事件") } }
                groups.entries.forEach { (chapterId, chapters) ->
                    item { SmallTitle(chapterId?.let(book::chapter)?.title ?: "未关联章节") }
                    itemsIndexed(chapters, key = { _, event -> event.id }) { index, event ->
                        PreferenceGroupItem(isFirst = index == 0, isLast = index == chapters.lastIndex) {
                            WritingEntry(event.title, event.content) { openedMaterialId = event.id }
                        }
                    }
                }
                item { DeskGroup("已定稿章节") {
                    book.chapters.filter { it.handoff != null }.forEach { item ->
                        WritingEntry(item.title, item.handoff?.plot) { state.openChapter(bookId, item.id); back() }
                    }
                } }
            }
            DeskPage.Graph -> {
                val links = book.materials.filter { it.kind == WritingMaterialKind.Cause }
                item("causes") { WritingCauseGraph(sorted, links) { openedMaterialId = it } }
                item("edges") {
                    DeskGroup("因果关系") {
                        if (links.isEmpty()) DeskParagraph(null, "从两个剧情事件之间建立因果连接")
                        links.forEach { link ->
                            val from = events.find { it.id == link.sourceEvent }
                            val to = events.find { it.id == link.targetEvent }
                            WritingEntry("${from?.title.orEmpty()} → ${to?.title.orEmpty()}", link.title) { openedMaterialId = link.id }
                        }
                    }
                }
            }
            DeskPage.Context -> item("context") { WritingContextCard(state, book) }
            DeskPage.Usage -> book.usage.groupBy { it.model }.let { usage ->
                if (usage.isEmpty()) item { DeskGroup("模型用量") { DeskParagraph(null, "暂无生成记录") } }
                items(usage.entries.toList(), key = { it.key }) { (model, records) ->
                    DeskGroup(model) {
                        val input = records.mapNotNull { it.input }.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }?.toString() ?: "未知"
                        val output = records.mapNotNull { it.output }.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }?.toString() ?: "未知"
                        DeskParagraph(null, "${records.size} 次生成 · 输入 $input · 输出 $output")
                    }
                }
            }
            else -> {
                if (page == DeskPage.Skills) item {
                    DeskGroup("导入") { WritingEntry("导入 Markdown Skill") { skillPicker.launch(arrayOf("text/*", "application/octet-stream")) } }
                }
                if (page == DeskPage.References) item {
                    DeskGroup("样本分析") { WritingEntry("分析参考样本") { sampleEdit = true } }
                }
                if (entries.isEmpty()) item { DeskGroup(page.label) { DeskParagraph(null, "暂无记录") } }
                if (entries.isNotEmpty()) {
                    item("record-heading") { SmallTitle(page.label) }
                    itemsIndexed(entries, key = { _, item -> item.id }) { index, item ->
                        PreferenceGroupItem(isFirst = index == 0, isLast = index == entries.lastIndex) {
                            val status = when (item.kind) {
                                WritingMaterialKind.Foreshadow -> if (item.resolved) "已回收" else "未回收"
                                WritingMaterialKind.Skill, WritingMaterialKind.Worldbook -> if (item.enabled) "已启用" else "已停用"
                                else -> null
                            }
                            WritingEntry(item.title, listOfNotNull(status, item.content.take(80).takeIf(String::isNotBlank))
                                .joinToString(" · ").takeIf(String::isNotBlank)) { openedMaterialId = item.id }
                        }
                    }
                }
            }
        }
        if (page in setOf(DeskPage.Handoff, DeskPage.Setup, DeskPage.References, DeskPage.Context)) {
            writingGeneration(state)
            writingReview(book.candidate, state.generating == null, onAccept = { state.review(bookId, null, it) },
                onReject = { state.review(bookId, null, null) },
                onRetry = { state.generate(bookId, null, it.task, it.instruction, volumeId = book.candidateVolume) })
        }
    }
    if (handoffEdit && chapter != null) WritingHandoffDialog(chapter.handoff, { handoffEdit = false }) { value ->
        state.editChapter(bookId, chapter.id) { it.copy(handoff = value) }
        handoffEdit = false
    }
    if (sampleEdit) WritingDialog("分析参考样本", { sampleEdit = false }) {
        SettingsTextField("样本文本", sample, singleLine = false) { sample = it }
        DialogActions("生成分析", { sampleEdit = false }, {
            state.generate(bookId, null, WritingTask.Analyze, sample)
            sampleEdit = false
        }, confirmEnabled = sample.isNotBlank() && state.generating == null)
    }
}

@Composable
private fun DeskLinks(title: String, pages: List<DeskPage>, open: (DeskPage) -> Unit) {
    DeskGroup(title) {
        pages.forEachIndexed { index, page ->
            WritingEntry(page.label) { open(page) }
            if (index < pages.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
internal fun DeskGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SmallTitle(title)
    Card(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Column(content = content)
    }
}

@Composable
private fun DeskParagraph(label: String?, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) WritingCaption(label)
        Text(text)
    }
}

@Composable
private fun WritingContextCard(state: WritingViewModel, book: WritingBook) {
    val context = book.context
    var budget by rememberSaveable(book.id) { mutableStateOf(context.budget.toString()) }
    fun update(change: (WritingContext) -> WritingContext) = state.edit(book.id) { it.copy(context = change(it.context)) }
    DeskGroup("带入写作的资料") {
        SwitchPreference(title = "跨章记忆", checked = context.includeMemory, onCheckedChange = { b -> update { it.copy(includeMemory = b) } })
        SwitchPreference(title = "关键词世界书", checked = context.includeWorldbook, onCheckedChange = { b -> update { it.copy(includeWorldbook = b) } })
        SwitchPreference(title = "作者反馈", checked = context.includeFeedback, onCheckedChange = { b -> update { it.copy(includeFeedback = b) } })
        SwitchPreference(title = "资料库", checked = context.includeMaterials, onCheckedChange = { b -> update { it.copy(includeMaterials = b) } })
        SwitchPreference(title = "写作 Skill", checked = context.includeSkills, onCheckedChange = { b -> update { it.copy(includeSkills = b) } })
    }
    WritingSection {
        SettingsTextField("附加上下文字符预算", budget) { value ->
            budget = value
            value.toIntOrNull()?.takeIf { it in 0..100_000 }?.let { number -> update { it.copy(budget = number) } }
        }
        SettingsTextField("压缩后的记忆", context.compressed, singleLine = false) { value -> update { it.copy(compressed = value) } }
        WritingEntry("生成压缩候选") { state.generate(book.id, null, WritingTask.Compress, "") }
    }
}

@Composable
private fun WritingMaterialDetail(
    book: WritingBook, material: WritingMaterial, back: () -> Unit, edit: () -> Unit,
    jump: (WritingAnchor) -> Unit, update: (WritingMaterial) -> Unit, delete: () -> Unit,
) {
    var anchorIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    ScaffoldPage(material.title, back, actions = { TopBarActionButton(Icons.Rounded.Edit, "编辑资料", onClick = edit) }) {
        item("description") {
            DeskGroup(material.kind.label) {
                if (material.content.isNotBlank()) DeskParagraph(null, material.content)
                if (material.keywords.isNotBlank()) DeskParagraph("触发词", material.keywords)
                SwitchPreference(title = "用于创作上下文", checked = material.enabled,
                    onCheckedChange = { update(material.copy(enabled = it)) })
            }
        }
        item("anchors") {
            DeskGroup(if (material.kind == WritingMaterialKind.Foreshadow) "跨章锚点" else "引用原文") {
                if (material.anchors.isEmpty()) DeskParagraph(null, "暂无正文引用")
                material.anchors.forEachIndexed { index, anchor ->
                    val chapter = book.chapter(anchor.chapterId)
                    val valid = chapter?.let { anchor.locate(it.text) } != null
                    val status = if (material.kind == WritingMaterialKind.Foreshadow) (anchor.stage ?: ForeshadowStage.Planted).label else null
                    WritingEntry(listOfNotNull(chapter?.title ?: "缺失章节", status).joinToString(" · "),
                        (if (valid) "" else "原段待定位 · ") + anchor.quote.take(100)) {
                        if (material.kind == WritingMaterialKind.Foreshadow) anchorIndex = index
                        else if (valid) jump(anchor)
                    }
                }
            }
        }
        item { DeskGroup("管理") { WritingEntry("删除资料") { deleting = true } } }
    }
    anchorIndex?.let { index ->
        val anchor = material.anchors.getOrNull(index)
        if (anchor != null) WritingDialog("伏笔锚点", { anchorIndex = null }) {
            Text(anchor.quote)
            if (book.chapter(anchor.chapterId)?.let { anchor.locate(it.text) } != null) {
                WritingEntry("回到正文") { anchorIndex = null; jump(anchor) }
            }
            ForeshadowStage.entries.forEach { stage ->
                WritingEntry(if ((anchor.stage ?: ForeshadowStage.Planted) == stage) "✓ ${stage.label}" else stage.label) {
                    update(material.copy(anchors = material.anchors.mapIndexed { i, item -> if (i == index) item.copy(stage = stage) else item }))
                    anchorIndex = null
                }
            }
        }
    }
    if (deleting) WritingDialog("删除「${material.title}」", { deleting = false }) {
        if (material.kind == WritingMaterialKind.Event) WritingCaption("与该事件相连的因果关系也会删除。")
        DialogActions("删除", { deleting = false }, delete, destructive = true)
    }
}

@Composable
private fun WritingMaterialEditor(
    book: WritingBook, material: WritingMaterial?, kind: WritingMaterialKind, cancel: () -> Unit, save: (WritingMaterial) -> Unit,
) {
    var title by rememberSaveable(material?.id) { mutableStateOf(material?.title.orEmpty()) }
    var content by rememberSaveable(material?.id) { mutableStateOf(material?.content.orEmpty()) }
    var keywords by rememberSaveable(material?.id) { mutableStateOf(material?.keywords.orEmpty()) }
    var source by rememberSaveable(material?.id) { mutableStateOf(material?.sourceEvent) }
    var target by rememberSaveable(material?.id) { mutableStateOf(material?.targetEvent) }
    val valid = title.isNotBlank() && (kind != WritingMaterialKind.Cause || (source != null && target != null && source != target))
    ScaffoldPage(if (material == null) "新建${kind.label}" else "编辑${kind.label}", cancel, actions = {
        TextButton("保存", enabled = valid, onClick = {
            val item = material ?: WritingMaterial(kind = kind, title = title.trim())
            save(item.copy(title = title.trim(), content = content, keywords = keywords, sourceEvent = source, targetEvent = target))
        })
    }) {
        item {
            WritingSection {
                SettingsTextField("名称", title, inputTransformation = null) { title = it }
                SettingsTextField("内容", content, singleLine = false) { content = it }
                if (kind == WritingMaterialKind.Worldbook) {
                    SettingsTextField("触发词（逗号分隔）", keywords, inputTransformation = null) { keywords = it }
                }
            }
        }
        if (kind == WritingMaterialKind.Cause) {
            val events = book.materials.filter { it.kind == WritingMaterialKind.Event }
            item {
                DeskGroup("起因") { events.forEach { event -> WritingEntry(if (event.id == source) "✓ ${event.title}" else event.title) { source = event.id } } }
            }
            item {
                DeskGroup("结果") { events.forEach { event -> WritingEntry(if (event.id == target) "✓ ${event.title}" else event.title) { target = event.id } } }
            }
        }
    }
}

@Composable
private fun WritingCauseGraph(events: List<WritingMaterial>, links: List<WritingMaterial>, open: (String) -> Unit) {
    if (events.isEmpty()) return
    val color = MiuixTheme.colorScheme.primary
    val trackColor = MiuixTheme.colorScheme.dividerLine
    DeskGroup("事件关系") {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(14.dp)) {
            val horizontal = maxWidth
            val nodeWidth = (horizontal - 42.dp) / 2
            val cells = events.mapIndexed { index, event -> event.id to index }.toMap()
            Box(Modifier.fillMaxWidth().height(((events.size + 1) / 2 * 76).dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    for ((index, link) in links.withIndex()) {
                        val from = cells[link.sourceEvent] ?: continue
                        val to = cells[link.targetEvent] ?: continue
                        val step = 76.dp.toPx()
                        val gutter = 14.dp.toPx()
                        val fy = from / 2 * step + 27.dp.toPx()
                        val ty = to / 2 * step + 27.dp.toPx()
                        val fx = if (from % 2 == 0) nodeWidth.toPx() else size.width - nodeWidth.toPx()
                        val tx = if (to % 2 == 0) nodeWidth.toPx() else size.width - nodeWidth.toPx()
                        val lane = size.width / 2 + (index % 5 - 2) * 4.dp.toPx()
                        val path = Path().apply { moveTo(fx, fy); lineTo(lane, fy); lineTo(lane, ty); lineTo(tx, ty) }
                        drawPath(path, trackColor, style = Stroke(1.5.dp.toPx()))
                        val direction = if (tx > lane) -1 else 1
                        drawLine(color, Offset(tx, ty), Offset(tx + direction * gutter / 3, ty - gutter / 4), 1.5.dp.toPx())
                        drawLine(color, Offset(tx, ty), Offset(tx + direction * gutter / 3, ty + gutter / 4), 1.5.dp.toPx())
                    }
                }
                events.forEachIndexed { index, event ->
                    val x = if (index % 2 == 0) 0.dp else horizontal - nodeWidth
                    Card(Modifier.offset(x, (index / 2 * 76).dp).width(nodeWidth)) {
                        WritingEntry(event.title.take(24)) { open(event.id) }
                    }
                }
            }
        }
    }
}
