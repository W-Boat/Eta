package io.github.mangi.eta.ui.screens.writing

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.SpannedString
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.PageScaffold
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.navigation.AppRoute
import io.noties.markwon.Markwon
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ChapterPane(val label: String) { Text("正文"), AI("AI 写作"), Paragraphs("逐段"), Versions("版本") }

@Composable
internal fun WritingChapterScreen(
    state: WritingViewModel,
    bookId: String,
    chapterId: String,
    paragraphOffset: Int?,
    navigate: (AppRoute) -> Unit,
    back: () -> Unit,
) {
    val book = state.book(bookId)
    val chapter = book?.chapter(chapterId)
    var pane by rememberSaveable(chapterId) { mutableStateOf<ChapterPane?>(null) }
    val activePane = pane ?: when {
        paragraphOffset != null -> ChapterPane.Paragraphs
        book?.aiMode == true && chapter?.text.isNullOrBlank() -> ChapterPane.AI
        else -> ChapterPane.Text
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var instruction by rememberSaveable(chapterId) { mutableStateOf("") }
    var selected by remember(chapterId) { mutableStateOf(emptySet<WritingAnchor>()) }
    var feedback by remember(chapterId) { mutableStateOf<WritingAnchor?>(null) }
    var handoffEditor by rememberSaveable { mutableStateOf(false) }
    var selectedVersion by remember { mutableStateOf<WritingVersion?>(null) }
    var source by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var livePreview by rememberSaveable { mutableStateOf(false) }
    val paragraphList = rememberLazyListState()
    val paragraphs = remember(chapterId, chapter?.text) { chapter?.let(::writingParagraphs).orEmpty() }
    val otherList = rememberLazyListState()
    val history = remember(chapterId) { WritingUndo() }
    var selection by remember(chapterId) { mutableStateOf(IntRange.EMPTY) }
    var requestedCursor by remember(chapterId) { mutableStateOf<Int?>(null) }
    val original = chapter?.text.orEmpty()
    fun editText(text: String) {
        state.editChapter(bookId, chapterId) { it.copy(text = text) }
    }
    fun userEdit(text: String, group: Boolean = false) {
        if (original == text) return
        history.record(original, group)
        editText(text)
    }
    // Explicit edits outside the text field remain undoable even when another tab is open.
    var externalText by remember(chapterId) { mutableStateOf(original) }
    LaunchedEffect(original) { externalText = original }

    PageScaffold(
        title = chapter?.title ?: "章节",
        onBack = back,
        modifier = Modifier.imePadding(),
        actions = {
            TopBarActionButton(Icons.Rounded.Settings, "章节设置", enabled = chapter != null,
                onClick = { settings = true })
        },
    ) { padding, _, side ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = side).navigationBarsPadding()) {
            if (book == null || chapter == null) {
                if (state.loading) WritingLoading() else WritingSection { Text("找不到章节。") }
                return@Column
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ChapterPane.entries.forEach { tab ->
                    TextButton(
                        text = tab.label,
                        onClick = { pane = tab },
                        colors = if (activePane == tab) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                }
            }
            if (activePane == ChapterPane.Text) {
                WritingNotice(state)
                val words = remember(original) { writingWordCount(original) }
                val goal = chapter.goal ?: book.goal
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    WritingCaption(if (goal > 0) "$words / $goal 字" else "$words 字")
                    WritingCaption(if (state.saving) "保存中…" else "自动保存")
                }
                WritingActions {
                    TextButton(if (preview) "返回编辑" else "预览", onClick = { preview = !preview })
                    TextButton(if (livePreview) "关闭同屏" else "同屏预览", enabled = !preview, onClick = { livePreview = !livePreview })
                    TextButton(if (source) "高亮" else "源码", enabled = !preview, onClick = { source = !source })
                    TextButton("场景线", enabled = !preview, onClick = {
                        val insertion = "\n\n---\n\n"
                        val start = selection.first.coerceIn(0, original.length)
                        val end = (selection.last + 1).coerceIn(start, original.length)
                        requestedCursor = start + insertion.length
                        userEdit(original.replaceRange(start, end, insertion))
                    })
                    TextButton("撤销", enabled = history.canUndo, onClick = { history.undo(original)?.let(::editText) })
                    TextButton("重做", enabled = history.canRedo, onClick = { history.redo(original)?.let(::editText) })
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.TopCenter) {
                    Card(Modifier.widthIn(max = if (livePreview && !preview) (book.width * 2).dp else book.width.dp)
                        .fillMaxWidth().fillMaxSize()) {
                        val editor: @Composable (Modifier) -> Unit = { editorModifier ->
                            WritingMarkdownEditor(
                                text = original,
                                source = source,
                                cursor = requestedCursor,
                                onCursorApplied = { requestedCursor = null },
                                onSelection = { selection = it },
                                onEdit = { previous, next ->
                                    history.record(previous, group = true)
                                    editText(next)
                                },
                                modifier = editorModifier.padding(horizontal = 12.dp),
                            )
                        }
                        val rendered: @Composable (Modifier) -> Unit = { previewModifier ->
                            WritingMarkdown(original, previewModifier.verticalScroll(rememberScrollState()).padding(14.dp))
                        }
                        if (preview) {
                            rendered(Modifier.fillMaxSize())
                        } else if (livePreview) {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                if (maxWidth >= 620.dp) {
                                    Row(Modifier.fillMaxSize()) {
                                        editor(Modifier.weight(1f).fillMaxSize())
                                        rendered(Modifier.weight(1f).fillMaxSize())
                                    }
                                } else {
                                    Column(Modifier.fillMaxSize()) {
                                        editor(Modifier.weight(1.6f).fillMaxWidth())
                                        HorizontalDivider()
                                        rendered(Modifier.weight(1f).fillMaxWidth())
                                    }
                                }
                            }
                        } else {
                            editor(Modifier.fillMaxSize())
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    state = if (activePane == ChapterPane.Paragraphs) paragraphList else otherList,
                ) {
                    item { WritingNotice(state) }
                    writingGeneration(state)
                    when (activePane) {
                        ChapterPane.AI -> {
                            item("plan") {
                                WritingSection {
                                    SettingsTextField("作者要求", instruction, singleLine = false) { instruction = it }
                                    SettingsTextField("已确认摘要", chapter.summary, singleLine = false) { summary ->
                                        state.editChapter(bookId, chapterId) { it.copy(summary = summary) }
                                    }
                                    val available = state.generating == null
                                    WritingActions {
                                        TextButton("生成摘要", enabled = available, onClick = {
                                            state.generate(bookId, chapterId, WritingTask.Summary, instruction)
                                        })
                                        TextButton("生成初稿", enabled = available && chapter.summary.isNotBlank(), onClick = {
                                            state.generate(bookId, chapterId, WritingTask.Draft, instruction)
                                        })
                                        TextButton("整章改稿", enabled = available && original.isNotBlank(), onClick = {
                                            state.generate(bookId, chapterId, WritingTask.Rewrite, instruction)
                                        })
                                        TextButton("定稿交接", enabled = available && original.isNotBlank(), onClick = {
                                            state.generate(bookId, chapterId, WritingTask.Handoff, instruction)
                                        })
                                        TextButton("提取资料", enabled = available && original.isNotBlank(), onClick = {
                                            state.generate(bookId, chapterId, WritingTask.Extract, instruction)
                                        })
                                    }
                                }
                            }
                            writingReview(chapter.candidate, state.generating == null, onAccept = { candidate ->
                                state.review(bookId, chapterId, candidate)
                                val updated = state.book(bookId)?.chapter(chapterId)?.text
                                if (updated != null && updated != externalText) {
                                    history.record(externalText, group = false)
                                    externalText = updated
                                }
                                if (candidate.task == WritingTask.Draft && updated != original) pane = ChapterPane.Text
                            }, onReject = { state.review(bookId, chapterId, null) }, onRetry = {
                                state.generate(bookId, chapterId, it.task, it.instruction, it.targets)
                            })
                            item("handoff") {
                                WritingHandoffCard(book, chapter, edit = { handoffEditor = true }, write = { next ->
                                    state.editChapter(bookId, chapterId) { it.copy(handoff = next) }
                                })
                            }
                        }
                        ChapterPane.Paragraphs -> {
                            item("paragraph-prompt") {
                                WritingSection {
                                    SettingsTextField("本次改稿要求", instruction, singleLine = false) { instruction = it }
                                    TextButton("生成所选段落候选 · ${selected.size}", enabled = selected.isNotEmpty() && state.generating == null,
                                        onClick = {
                                            val valid = selected.filter { it.locate(original) == it.offset }.sortedBy { it.offset }
                                            if (valid.isNotEmpty()) {
                                                state.generate(bookId, chapterId, WritingTask.Rewrite, instruction, valid)
                                                pane = ChapterPane.AI
                                            }
                                            selected = emptySet()
                                        })
                                }
                            }
                            items(paragraphs, key = { it.offset }) { paragraph ->
                                WritingSection {
                                    Text(paragraph.quote, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                    val note = chapter.feedback.firstOrNull { it.anchor.locate(original) == paragraph.offset }
                                    if (!note?.annotation.isNullOrBlank()) WritingCaption(note?.annotation.orEmpty())
                                    WritingActions {
                                        TextButton(if (paragraph in selected) "取消选中" else "选中改稿", onClick = {
                                            selected = if (paragraph in selected) selected - paragraph else selected + paragraph
                                        })
                                        TextButton("段落反馈", onClick = { feedback = paragraph })
                                    }
                                }
                            }
                        }
                        ChapterPane.Versions -> {
                            item("snapshot") {
                                WritingSection {
                                    TextButton("保存当前版本", enabled = original.isNotBlank(), onClick = {
                                        state.editChapter(bookId, chapterId) {
                                            it.copy(versions = (listOf(WritingVersion(it.text, "手动快照")) + it.versions).take(20))
                                        }
                                    })
                                }
                            }
                            items(chapter.versions) { version ->
                                WritingSection {
                                    WritingEntry(version.label, DateFormat.getDateTimeInstance().format(Date(version.time))) {
                                        selectedVersion = version
                                    }
                                }
                            }
                        }
                        ChapterPane.Text -> Unit
                    }
                }
                LaunchedEffect(paragraphOffset, activePane, paragraphs) {
                    if (activePane == ChapterPane.Paragraphs && paragraphOffset != null) {
                        val index = paragraphs.indexOfFirst { paragraphOffset in it.offset until it.offset + it.quote.length }
                        if (index >= 0) paragraphList.scrollToItem(index + if (state.generating == null) 2 else 3)
                    }
                }
            }
        }
    }
    if (book == null || chapter == null) return
    if (settings) WritingChapterSettings(book, chapter, dismiss = { settings = false }, save = { name, goal, width ->
        state.edit(bookId) { current ->
            current.changeChapter(chapterId) { it.copy(title = name, goal = goal) }.copy(width = width)
        }
        settings = false
    }, openDesk = {
        settings = false
        navigate(AppRoute.WritingDesk(bookId))
    }, move = {
        settings = false
        moving = true
    }, delete = {
        settings = false
        deleting = true
    })
    if (moving) WritingDialog("移动章节", { moving = false }) {
        book.volumes.filter { volume -> volume.chapters.none { it.id == chapterId } }.forEach { volume ->
            WritingEntry(volume.title) {
                state.edit(bookId) { it.moveChapter(chapterId, volume.id) }
                moving = false
            }
        }
    }
    if (deleting) WritingDialog("删除「${chapter.title}」", { deleting = false }) {
        WritingCaption("正文、历史版本和章内批注将一同删除。资料库会保留相关伏笔。")
        DialogActions("删除", { deleting = false }, {
            state.edit(bookId) { current ->
                if (current.chapters.size <= 1) current else current.copy(volumes = current.volumes.map { volume ->
                    volume.copy(chapters = volume.chapters.filterNot { it.id == chapterId })
                })
            }
            deleting = false
            back()
        }, destructive = true, confirmEnabled = book.chapters.size > 1)
    }
    feedback?.let { anchor ->
        WritingFeedbackDialog(book, chapter, anchor, dismiss = { feedback = null }, save = { note, foreshadowId, newTitle ->
            state.edit(bookId) { current ->
                val notes = current.changeChapter(chapterId) {
                    it.copy(feedback = it.feedback.filterNot { old -> old.anchor.locate(original) == anchor.offset } + note)
                }
                when {
                    newTitle.isNotBlank() -> notes.copy(materials = notes.materials + WritingMaterial(
                        kind = WritingMaterialKind.Foreshadow, title = newTitle.trim(), anchors = listOf(anchor),
                    ))
                    foreshadowId != null -> notes.copy(materials = notes.materials.map {
                        if (it.id == foreshadowId) it.copy(anchors = (it.anchors + anchor).distinct()) else it
                    })
                    else -> notes
                }
            }
            feedback = null
        })
    }
    if (handoffEditor) WritingHandoffDialog(chapter.handoff, dismiss = { handoffEditor = false }) { handoff ->
        state.editChapter(bookId, chapterId) { it.copy(handoff = handoff) }
        handoffEditor = false
    }
    selectedVersion?.let { version ->
        WritingDialog(version.label, { selectedVersion = null }) {
            Text(version.text.ifBlank { "空白章节" })
            DialogActions("恢复此版本", { selectedVersion = null }, {
                history.record(original, group = false)
                state.editChapter(bookId, chapterId) { it.withText(version.text, "恢复历史版本前") }
                selectedVersion = null
                pane = ChapterPane.Text
            })
        }
    }
}

@Composable
private fun WritingMarkdownEditor(
    text: String,
    source: Boolean,
    cursor: Int?,
    onCursorApplied: () -> Unit,
    onSelection: (IntRange) -> Unit,
    onEdit: (String, String) -> Unit,
    modifier: Modifier,
) {
    val currentEdit by rememberUpdatedState(onEdit)
    val currentSelection by rememberUpdatedState(onSelection)
    val color = MiuixTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier,
        onReset = null,
        factory = { context ->
            ManuscriptEditText(context).apply {
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setPadding(6, 16, 6, 16)
                setLineSpacing(0f, 1.35f)
                textSize = 17f
                typeface = Typeface.SERIF
                selectionChanged = { start, end -> currentSelection(minOf(start, end) until maxOf(start, end)) }
                addTextChangedListener(object : TextWatcher {
                    private var previous = ""
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { previous = s.toString() }
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (!updating && s != null) currentEdit(previous, s.toString())
                    }
                })
            }
        },
        update = { editor ->
            editor.setTextColor(color)
            editor.setHighlighting(!source)
            if (editor.text.toString() != text) {
                val position = (cursor ?: editor.selectionStart).coerceIn(0, text.length)
                editor.updating = true
                editor.setText(text)
                editor.setSelection(position)
                editor.updating = false
            }
            if (cursor != null) {
                editor.setSelection(cursor.coerceIn(0, text.length))
                onCursorApplied()
            }
        },
        onRelease = { it.executor.shutdownNow() },
    )
}

private class ManuscriptEditText(context: android.content.Context) : EditText(context) {
    val executor = Executors.newSingleThreadExecutor()
    private val watcher by lazy {
        MarkwonEditorTextWatcher.withPreRender(MarkwonEditor.create(Markwon.create(context)), executor, this)
    }
    private var highlighting = false
    var updating = false
    var selectionChanged: ((Int, Int) -> Unit)? = null

    fun setHighlighting(enabled: Boolean) {
        if (highlighting == enabled) return
        highlighting = enabled
        if (enabled) {
            addTextChangedListener(watcher)
            watcher.afterTextChanged(text)
        } else {
            removeTextChangedListener(watcher)
            val start = selectionStart.coerceIn(0, length())
            val end = selectionEnd.coerceIn(0, length())
            updating = true
            setText(text.toString())
            setSelection(start, end)
            updating = false
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (selStart >= 0 && selEnd >= 0) selectionChanged?.invoke(selStart, selEnd)
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

private class WritingUndo {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private var lastEdit = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun record(before: String, group: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!group || lastEdit == 0L || now - lastEdit >= 600) {
            past.addLast(before)
            if (past.size > 30) past.removeFirst()
        }
        future.clear()
        lastEdit = if (group) now else 0
        refresh()
    }

    fun undo(current: String): String? = past.removeLastOrNull()?.also {
        future.addLast(current)
        lastEdit = 0
        refresh()
    }

    fun redo(current: String): String? = future.removeLastOrNull()?.also {
        past.addLast(current)
        lastEdit = 0
        refresh()
    }

    private fun refresh() {
        canUndo = past.isNotEmpty()
        canRedo = future.isNotEmpty()
    }
}
