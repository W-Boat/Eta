package io.github.mangi.eta.ui.screens.writing

import android.graphics.Typeface
import android.text.Spanned
import android.text.SpannedString
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.PageScaffold
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.navigation.AppRoute
import io.noties.markwon.Markwon
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WritingChapterScreen(
    state: WritingViewModel,
    bookId: String,
    initialChapterId: String?,
    paragraphOffset: Int?,
    navigate: (AppRoute) -> Unit,
    back: () -> Unit,
) {
    val book = state.book(bookId)
    var initialized by rememberSaveable { mutableStateOf(false) }
    val chapter = if (initialized) book?.activeChapter() else initialChapterId?.let { book?.chapter(it) } ?: book?.activeChapter()
    LaunchedEffect(book?.id, chapter?.id) {
        if (chapter != null) {
            state.openChapter(bookId, chapter.id, paragraphOffset.takeUnless { initialized })
            initialized = true
        }
    }
    if (book == null || chapter == null) {
        LaunchedEffect(state.ready, book == null) { if (state.ready && book == null) back() }
        PageScaffold(title = stringResource(R.string.conversation_dock_writing), onBack = back, compactTitle = true) { padding, _, _ ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) WritingLoading() else Text("找不到章节")
            }
        }
        return
    }

    var drawer by rememberSaveable { mutableStateOf<String?>(null) }
    var fullReview by rememberSaveable(chapter.id) { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var versions by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var handoff by rememberSaveable { mutableStateOf(false) }
    var move by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var volumeNew by rememberSaveable { mutableStateOf(false) }
    var chapterNew by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var livePreview by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable(chapter.id) { mutableStateOf(false) }
    var picked by remember(chapter.id) { mutableStateOf(emptySet<WritingAnchor>()) }
    var pickedDraft by rememberSaveable(chapter.id) { mutableStateOf<String?>(null) }
    var replacingDraft by rememberSaveable(chapter.id) { mutableStateOf<String?>(null) }
    var feedback by remember(chapter.id) { mutableStateOf<WritingAnchor?>(null) }
    var jump by remember(chapter.id) { mutableStateOf<ManuscriptJump?>(null) }
    var position by remember(chapter.id) { mutableStateOf(chapter.position) }
    var words by remember(chapter.id) { mutableStateOf<Int?>(null) }
    var instruction by rememberSaveable(chapter.id) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val history = remember(chapter.id) { ManuscriptHistory() }
    var paragraphs by remember(chapter.id) { mutableStateOf(emptyList<WritingAnchor>()) }
    val pending = chapter.drafts.filter { it.decision in setOf(ParagraphDecision.Pending, ParagraphDecision.Stale) }
    val selectedIds = pending.filter { it.selected && it.decision == ParagraphDecision.Pending }.mapTo(hashSetOf()) { it.id }
    val bookTitle = book.title
    val chapterId = chapter.id
    val goal = chapter.goal ?: book.goal
    val wordSubtitle = words?.let { if (goal > 0) "$it / $goal 字" else "$it 字" }.orEmpty()

    LaunchedEffect(chapter.text) {
        delay(80)
        val measured = withContext(Dispatchers.Default) { writingWordCount(chapter.text) to writingParagraphs(chapter) }
        words = measured.first
        paragraphs = measured.second
    }
    fun closeLayer() = when {
        fullReview -> { fullReview = false; drawer = "assist"; true }
        selecting -> { selecting = false; picked = emptySet(); true }
        drawer != null -> { drawer = null; true }
        preview -> { preview = false; true }
        else -> false
    }
    BackHandler(enabled = fullReview || selecting || drawer != null || preview) { closeLayer() }
    fun selectChapter(id: String) { state.openChapter(bookId, id); drawer = null; preview = false }
    fun paragraphAt(offset: Int) = paragraphs.firstOrNull { offset in it.offset..(it.offset + it.quote.length) }
    fun retryDraft(draft: ParagraphDraft) {
        if (draft.decision == ParagraphDecision.Pending) {
            val target = writingParagraphs(chapter).firstOrNull { it == draft.anchor } ?: return
            state.generate(bookId, chapterId, WritingTask.Rewrite, draft.instruction, listOf(target), replacingDraft = draft.id)
        } else {
            fullReview = false
            drawer = null
            selecting = true
            picked = emptySet()
            replacingDraft = draft.id
            instruction = draft.instruction
            keyboard?.hide()
        }
    }
    fun reviewAt(offset: Int) {
        val draft = pending.firstOrNull { it.anchor.offset == offset }
        if (draft != null) { pickedDraft = draft.id; drawer = "assist"; keyboard?.hide() }
    }
    fun selectRange(cursor: WritingPosition) {
        val start = minOf(cursor.start, cursor.end)
        val end = maxOf(cursor.start, cursor.end)
        picked = if (start == end) setOfNotNull(paragraphAt(start)) else paragraphs.filterTo(linkedSetOf()) { anchor ->
            anchor.offset < end && anchor.offset + anchor.quote.length > start
        }
        drawer = "assist"
        keyboard?.hide()
    }
    fun applyDrafts(ids: Set<String>) {
        val before = state.book(bookId)?.chapter(chapterId) ?: return
        if (state.adoptParagraphs(bookId, chapterId, ids)) {
            history.record(before, false)
            before.drafts.filter { it.id in ids }.minOfOrNull { it.anchor.offset }?.let {
                jump = ManuscriptJump(it)
            }
        }
    }
    fun restore(snapshot: ManuscriptFrame) {
        state.editChapter(bookId, chapterId) { current -> snapshot.restore(current) }
    }
    fun saveParagraph(note: WritingFeedback, existing: String?, title: String) {
        state.edit(bookId) { current ->
            val updated = current.changeChapter(chapterId) { currentChapter ->
                currentChapter.copy(feedback = currentChapter.feedback.filterNot { old ->
                    old.anchor.locate(currentChapter.text) == note.anchor.offset
                } + note)
            }
            if (title.isNotBlank()) updated.copy(materials = updated.materials + WritingMaterial(
                kind = WritingMaterialKind.Foreshadow, title = title.trim(),
                anchors = listOf(note.anchor.copy(stage = ForeshadowStage.Planted)),
            )) else updated.copy(materials = updated.materials.map {
                if (it.id == existing && it.anchors.none { anchor ->
                        anchor.chapterId == note.anchor.chapterId && anchor.offset == note.anchor.offset
                    }) it.copy(anchors = it.anchors + note.anchor.copy(
                        stage = ForeshadowStage.Echo.takeIf { _ -> it.kind == WritingMaterialKind.Foreshadow },
                    )) else it
            })
        }
        feedback = null
    }
    fun cycleReview(id: String) {
        pickedDraft = id
        pending.find { it.id == id }?.takeIf { it.decision == ParagraphDecision.Pending }
            ?.let { jump = ManuscriptJump(it.anchor.offset) }
    }

    PageScaffold(
        title = chapter.title,
        subtitle = wordSubtitle,
        compactTitle = true,
        onBack = { if (!closeLayer()) back() },
        modifier = Modifier.imePadding(),
        isContentScrolled = position.scroll > 0,
        actions = {
            TopBarActionButton(Icons.Rounded.FormatListBulleted, "目录", onClick = {
                drawer = if (drawer == "contents") null else "contents"
            })
            TopBarActionButton(Icons.Rounded.AutoAwesome, if (pending.isEmpty()) "AI 辅助" else "AI 辅助，${pending.size} 段待处理",
                onClick = { drawer = if (drawer == "assist") null else "assist" })
            TopBarActionButton(Icons.Rounded.MoreVert, "写作选项", onClick = { menu = true })
        },
    ) { padding, _, _ ->
        val colors = MiuixTheme.colorScheme
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(colors.surface)) {
            val viewportWidth = maxWidth
            val wide = viewportWidth >= 840.dp
            val paneWidth = if (wide) 310.dp else (viewportWidth * 0.86f).coerceAtMost(340.dp)
            val edge = if (drawer == "contents") Alignment.CenterStart else Alignment.CenterEnd
            val startInset = if (wide && drawer == "contents") paneWidth else 0.dp
            val endInset = if (wide && drawer == "assist") paneWidth else 0.dp
            Box(Modifier.fillMaxSize().padding(start = startInset, end = endInset), contentAlignment = Alignment.TopCenter) {
                val editorWidth = if (livePreview && !preview) (book.width * 2).dp else book.width.dp
                Row(Modifier.widthIn(max = editorWidth).fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "正文编辑器" }) {
                        key(chapterId) {
                            val markers = if (selecting) paragraphs.map { ManuscriptMarker(it.offset, it in picked) }
                            else pending.filter { it.decision == ParagraphDecision.Pending }.distinctBy { it.anchor.offset }
                                .map { ManuscriptMarker(it.anchor.offset, it.id == pickedDraft) }
                            ManuscriptEditor(
                                chapter = chapter,
                                source = source,
                                markers = markers,
                                jump = jump,
                                onEdit = { text, patch ->
                                    state.writeManuscript(bookId, chapterId, text, patch) { history.record(it, true) }
                                },
                                onPosition = { moved ->
                                    position = moved
                                    state.editChapter(bookId, chapterId) { it.copy(position = moved) }
                                },
                                onMarker = ::reviewAt,
                                onSelectionAction = ::selectRange,
                                onParagraphTap = if (selecting) { offset ->
                                    paragraphAt(offset)?.let { picked = if (it in picked) picked - it else picked + it }
                                } else null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (preview) {
                            Box(Modifier.fillMaxSize().background(colors.surface).clickable(enabled = false) {}) {
                                WritingMarkdown(chapter.text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp))
                            }
                        }
                    }
                    if (livePreview && !preview && viewportWidth - startInset - endInset >= 620.dp) {
                        WritingMarkdown(chapter.text, Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp))
                    }
                }
                if (selecting) Card(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                    Row(Modifier.padding(horizontal = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton("取消", onClick = { selecting = false; picked = emptySet(); replacingDraft = null })
                        TextButton("改写所选 ${picked.size} 段", enabled = picked.isNotEmpty(), onClick = {
                            selecting = false
                            drawer = "assist"
                        })
                    }
                }
            }
            if (drawer != null && !fullReview) {
                if (!wide) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.27f)).clickable { drawer = null })
                Column(Modifier.align(edge).width(paneWidth).fillMaxHeight().background(colors.surface)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (drawer == "contents") "目录" else "AI 辅助", Modifier.weight(1f), style = MiuixTheme.textStyles.title3)
                        TopBarActionButton(Icons.Rounded.Close, "关闭侧栏", onClick = { drawer = null })
                    }
                    HorizontalDivider()
                    if (drawer == "contents") {
                        LazyColumn {
                            item("book") {
                                val facts = "${book.volumes.size} 卷 · ${book.chapters.size} 章"
                                WritingEntry(bookTitle, if (state.saving) "$facts · 正在保存" else "$facts · 书籍设置与导出") {
                                    navigate(AppRoute.WritingBookSettings(bookId))
                                }
                            }
                            book.volumes.forEach { volume ->
                                item("vol-${volume.id}") { SmallTitle(volume.title) }
                                items(volume.chapters, key = { it.id }) { entry ->
                                    WritingEntry(
                                        if (entry.id == chapterId) "✓ ${entry.title}" else entry.title,
                                        if (entry.handoff != null) "已定稿" else null,
                                    ) { selectChapter(entry.id) }
                                }
                                item("add-${volume.id}") { WritingEntry("新建章节") { chapterNew = volume.id } }
                            }
                            item("add-volume") { WritingEntry("新建卷") { volumeNew = true } }
                        }
                    } else {
                        LazyColumn {
                            item { WritingNotice(state) }
                            item("workspace") {
                                WritingEntry("创作工作台", "本章剧情接力") { navigate(AppRoute.WritingDesk(bookId)) }
                            }
                            writingGeneration(state)
                            if (pending.isNotEmpty() || chapter.candidate != null) item("review") {
                                WritingEntry("全屏对照", "待处理 ${pending.size + if (chapter.candidate != null) 1 else 0} 项") {
                                    fullReview = true
                                    keyboard?.hide()
                                }
                            }
                            paragraphReviews(pending, pickedDraft, selectedIds, ::cycleReview,
                                change = { id, action -> state.changeDraft(bookId, chapterId, id, action) },
                                accept = ::applyDrafts,
                                retry = ::retryDraft,
                                busy = state.generating != null,
                            )
                            chapterCandidate(state, bookId, chapter, history)
                            item("writing-actions") {
                                WritingSection {
                                    SettingsTextField("作者要求", instruction, singleLine = false) { instruction = it }
                                    SettingsTextField("已确认摘要", chapter.summary, singleLine = false) { value ->
                                        state.editChapter(bookId, chapterId) { it.copy(summary = value) }
                                    }
                                    val available = state.generating == null
                                    val range = picked.filter { it in paragraphs }.sortedBy { it.offset }
                                    val target = paragraphAt(position.start)
                                    if (range.isNotEmpty() || target != null) WritingEntry(
                                        if (range.isNotEmpty()) "改写所选 ${range.size} 段" else "改写光标所在段",
                                    ) {
                                        if (available) {
                                            val valid = writingParagraphs(chapter).toSet()
                                            val targets = range.ifEmpty { listOfNotNull(target) }.filter { it in valid }
                                            if (targets.isNotEmpty()) {
                                                state.generate(bookId, chapterId, WritingTask.Rewrite, instruction, targets,
                                                    replacingDraft = replacingDraft)
                                                replacingDraft = null
                                            }
                                        }
                                    }
                                    WritingEntry("在正文多选段落") { drawer = null; selecting = true; keyboard?.hide() }
                                    if (target != null) WritingEntry("本段批注与伏笔") { feedback = target }
                                    if (available) {
                                        WritingEntry("生成摘要") { state.generate(bookId, chapterId, WritingTask.Summary, instruction) }
                                        if (chapter.summary.isNotBlank()) WritingEntry("根据摘要生成初稿") {
                                            state.generate(bookId, chapterId, WritingTask.Draft, instruction)
                                        }
                                        if (chapter.text.isNotBlank()) {
                                            WritingEntry("整章改稿") { state.generate(bookId, chapterId, WritingTask.Rewrite, instruction) }
                                            WritingEntry("生成定稿交接") { state.generate(bookId, chapterId, WritingTask.Handoff, instruction) }
                                            WritingEntry("从章内提取资料") { state.generate(bookId, chapterId, WritingTask.Extract, instruction) }
                                        }
                                    }
                                }
                            }
                            item("handoff") {
                                WritingHandoffCard(book, chapter, { handoff = true }) { value ->
                                    state.editChapter(bookId, chapterId) { it.copy(handoff = value) }
                                }
                            }
                        }
                    }
                }
            }
            if (fullReview) {
                Column(Modifier.fillMaxSize().background(colors.surface)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全屏对照", Modifier.weight(1f), style = MiuixTheme.textStyles.title3)
                        TopBarActionButton(Icons.Rounded.Close, "返回正文", onClick = { closeLayer() })
                    }
                    HorizontalDivider()
                    val list = rememberLazyListState()
                    LaunchedEffect(Unit) {
                        val index = pending.indexOfFirst { it.id == pickedDraft }
                        if (index >= 0) list.scrollToItem(index + 2)
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        item("notice") { WritingNotice(state) }
                        paragraphReviews(pending, pickedDraft, selectedIds,
                            focus = ::cycleReview,
                            change = { id, action -> state.changeDraft(bookId, chapterId, id, action) },
                            accept = ::applyDrafts,
                            retry = ::retryDraft,
                            busy = state.generating != null,
                            showAll = true,
                        )
                        chapterCandidate(state, bookId, chapter, history, compare = true)
                    }
                }
            }
        }
    }

    if (menu) WritingDialog("写作选项", { menu = false }) {
        WritingEntry(if (preview) "返回编辑" else "Markdown 预览") { menu = false; preview = !preview; keyboard?.hide() }
        WritingEntry(if (livePreview) "关闭同屏预览" else "同屏预览（宽屏）") { menu = false; livePreview = !livePreview; preview = false }
        WritingEntry(if (source) "Markdown 高亮" else "显示源码") { menu = false; source = !source }
        if (history.canUndo) WritingEntry("撤销") { menu = false; history.undo(chapter)?.let(::restore) }
        if (history.canRedo) WritingEntry("重做") { menu = false; history.redo(chapter)?.let(::restore) }
        WritingEntry("版本历史") { menu = false; versions = true }
        WritingEntry("章节与排版设置") { menu = false; settings = true }
        WritingEntry("插入场景分隔线") {
            menu = false
            val start = minOf(position.start, position.end).coerceIn(0, chapter.text.length)
            val end = maxOf(position.start, position.end).coerceIn(start, chapter.text.length)
            val line = "\n\n---\n\n"
            history.record(chapter, false)
            state.writeManuscript(bookId, chapterId, chapter.text.replaceRange(start, end, line),
                ManuscriptEdit(start, end, start + line.length))
            jump = ManuscriptJump(start + line.length)
        }
    }
    if (versions) WritingVersionsScreen(chapter, { versions = false }, save = {
        state.editChapter(bookId, chapterId) { it.copy(versions = (listOf(WritingVersion(it.text, "手动快照")) + it.versions).take(20)) }
    }, restore = { version ->
        history.record(chapter, false)
        state.editChapter(bookId, chapterId) { it.withManuscript(version.text).copy(
            versions = (listOf(WritingVersion(it.text, "恢复历史版本前")) + it.versions).take(20),
        ) }
        versions = false
    })
    if (settings) WritingChapterSettings(book, chapter, { settings = false },
        save = { name, target, width ->
            state.edit(bookId) { it.changeChapter(chapterId) { ch -> ch.copy(title = name, goal = target) }.copy(width = width) }
            settings = false
        },
        openDesk = { settings = false; navigate(AppRoute.WritingDesk(bookId)) },
        move = { settings = false; move = true },
        delete = { settings = false; delete = true },
    )
    if (move) WritingDialog("移动章节", { move = false }) {
        book.volumes.filter { volume -> volume.chapters.none { it.id == chapterId } }.forEach { volume ->
            WritingEntry(volume.title) { state.edit(bookId) { it.moveChapter(chapterId, volume.id) }; move = false }
        }
    }
    if (delete) WritingDialog("删除「${chapter.title}」", { delete = false }) {
        WritingCaption("正文、版本和章内批注会一同删除。")
        DialogActions("删除", { delete = false }, {
            state.edit(bookId) { current ->
                if (current.chapters.size > 1) current.copy(activeChapterId = null, volumes = current.volumes.map { volume ->
                    volume.copy(chapters = volume.chapters.filterNot { it.id == chapterId })
                }) else current
            }
            delete = false
        }, destructive = true, confirmEnabled = book.chapters.size > 1)
    }
    if (volumeNew) WritingNameDialog("新建卷", "卷名", "第 ${book.volumes.size + 1} 卷", { volumeNew = false }) { name ->
        state.edit(bookId) { it.copy(volumes = it.volumes + WritingVolume(title = name)) }
        volumeNew = false
    }
    chapterNew?.let { volumeId ->
        WritingNameDialog("新建章节", "章名", "第 ${book.chapters.size + 1} 章", { chapterNew = null }) { name ->
            val created = WritingChapter(title = name)
            state.edit(bookId) { current -> current.copy(activeChapterId = created.id, volumes = current.volumes.map {
                if (it.id == volumeId) it.copy(chapters = it.chapters + created) else it
            }) }
            drawer = null
            chapterNew = null
            preview = false
        }
    }
    feedback?.let { WritingFeedbackDialog(book, chapter, it, { feedback = null }, ::saveParagraph) }
    if (handoff) WritingHandoffDialog(chapter.handoff, { handoff = false }) { value ->
        state.editChapter(bookId, chapterId) { it.copy(handoff = value) }
        handoff = false
    }
}

private fun LazyListScope.chapterCandidate(state: WritingViewModel, bookId: String, chapter: WritingChapter, history: ManuscriptHistory,
    compare: Boolean = false,
) {
    writingReview(chapter.candidate, state.generating == null, onAccept = { candidate ->
        val before = state.book(bookId)?.chapter(chapter.id) ?: return@writingReview
        state.review(bookId, chapter.id, candidate)
        if (state.book(bookId)?.chapter(chapter.id)?.text != before.text) history.record(before, false)
    }, onReject = { state.review(bookId, chapter.id, null) }, onRetry = {
        state.generate(bookId, chapter.id, it.task, it.instruction, it.targets)
    }, compareInitially = compare)
}

private fun LazyListScope.paragraphReviews(
    drafts: List<ParagraphDraft>, focused: String?, selected: Set<String>, focus: (String) -> Unit,
    change: (String, (ParagraphDraft) -> ParagraphDraft) -> Unit, accept: (Set<String>) -> Unit,
    retry: (ParagraphDraft) -> Unit, busy: Boolean, showAll: Boolean = false,
) {
    if (drafts.isEmpty()) return
    item("batch-heading") {
        SmallTitle("逐段审批")
        if (selected.isNotEmpty()) TextButton("采用所选 ${selected.size} 段", onClick = { accept(selected) })
    }
    items(drafts, key = { it.id }) { draft ->
        val live = draft.decision == ParagraphDecision.Pending
        WritingSection {
            WritingEntry(if (live) draft.anchor.quote.take(44) else "原段已变化", if (live) null else draft.anchor.quote.take(60)) {
                focus(draft.id)
            }
            if (showAll || focused == draft.id || drafts.size == 1) {
                WritingCaption("原文")
                val changed = remember(draft.anchor.quote, draft.text) { manuscriptEdit(draft.anchor.quote, draft.text) }
                ReviewExcerpt(draft.anchor.quote, changed.start, changed.beforeEnd, MiuixTheme.colorScheme.error.copy(alpha = 0.13f))
                if (showAll) {
                    WritingCaption("改后")
                    ReviewExcerpt(draft.text, changed.start, changed.afterEnd, MiuixTheme.colorScheme.primary.copy(alpha = 0.17f))
                }
                SettingsTextField("候选稿", draft.text, singleLine = false) { value -> change(draft.id) { it.copy(text = value) } }
                if (live) CheckboxPreference(title = "选入本次采用", checked = draft.selected,
                    onCheckedChange = { value -> change(draft.id) { it.copy(selected = value) } })
                WritingActions {
                    if (live) TextButton("采用本段", enabled = draft.text.isNotBlank(), onClick = { accept(setOf(draft.id)) })
                    TextButton("拒绝", onClick = { change(draft.id) { it.copy(decision = ParagraphDecision.Rejected, selected = false) } })
                    TextButton(if (live) "重生成" else "重新选择原段", enabled = !busy, onClick = { retry(draft) })
                }
            }
        }
    }
}

@Composable
private fun ReviewExcerpt(text: String, start: Int, end: Int, highlight: Color) {
    androidx.compose.foundation.text.BasicText(buildAnnotatedString {
        append(text.substring(0, start))
        withStyle(SpanStyle(background = highlight)) { append(text.substring(start, end)) }
        append(text.substring(end))
    }, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
}

@Composable
private fun WritingVersionsScreen(chapter: WritingChapter, back: () -> Unit, save: () -> Unit, restore: (WritingVersion) -> Unit) {
    BackHandler(onBack = back)
    var open by remember { mutableStateOf<WritingVersion?>(null) }
    ScaffoldPage(title = "版本历史", onBack = back, actions = { TextButton("保存快照", onClick = save) }) {
        items(chapter.versions) { version ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                WritingEntry(version.label, DateFormat.getDateTimeInstance().format(Date(version.time))) { open = version }
            }
        }
        if (chapter.versions.isEmpty()) item { WritingSection { WritingCaption("还没有保存的版本") } }
    }
    open?.let { version ->
        WritingDialog(version.label, { open = null }) {
            Text(version.text.ifBlank { "空白章节" })
            DialogActions("恢复此版本", { open = null }, { restore(version) })
        }
    }
}

private data class ManuscriptFrame(
    val text: String, val drafts: List<ParagraphDraft>, val candidate: WritingCandidate?, val feedback: List<WritingFeedback>,
) {
    constructor(chapter: WritingChapter) : this(chapter.text, chapter.drafts, chapter.candidate, chapter.feedback)
    fun restore(chapter: WritingChapter): WritingChapter {
        val priorIds = drafts.mapTo(hashSetOf()) { it.id }
        val lateDrafts = chapter.withManuscript(text).drafts.filterNot { it.id in priorIds }
        val latest = chapter.drafts.associateBy { it.id }
        return chapter.copy(text = text, drafts = drafts.map { draft ->
            draft.copy(text = latest[draft.id]?.text ?: draft.text)
        } + lateDrafts, candidate = chapter.candidate ?: candidate, feedback = feedback)
    }
}

private class ManuscriptHistory {
    private val past = ArrayDeque<ManuscriptFrame>()
    private val future = ArrayDeque<ManuscriptFrame>()
    private var lastEdit = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun record(before: WritingChapter, group: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!group || lastEdit == 0L || now - lastEdit >= 600) {
            past.addLast(ManuscriptFrame(before))
            if (past.size > 30) past.removeFirst()
        }
        future.clear()
        lastEdit = if (group) now else 0
        refresh()
    }

    fun undo(current: WritingChapter): ManuscriptFrame? = past.removeLastOrNull()?.also {
        future.addLast(ManuscriptFrame(current))
        lastEdit = 0
        refresh()
    }

    fun redo(current: WritingChapter): ManuscriptFrame? = future.removeLastOrNull()?.also {
        past.addLast(ManuscriptFrame(current))
        lastEdit = 0
        refresh()
    }

    private fun refresh() {
        canUndo = past.isNotEmpty()
        canRedo = future.isNotEmpty()
    }
}

@Composable
internal fun WritingMarkdown(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val foreground = MiuixTheme.colorScheme.onSurface.toArgb()
    var rendered by remember { mutableStateOf<Spanned>(SpannedString("")) }
    LaunchedEffect(text, markwon) {
        rendered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { markwon.toMarkdown(text) }
    }
    AndroidView(
        modifier = modifier,
        factory = {
            TextView(it).apply {
                textSize = 17f
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.35f)
                setTextIsSelectable(true)
            }
        },
        update = { view ->
            view.setTextColor(foreground)
            if (view.tag !== rendered) {
                markwon.setParsedMarkdown(view, rendered)
                view.tag = rendered
            }
        },
    )
}
