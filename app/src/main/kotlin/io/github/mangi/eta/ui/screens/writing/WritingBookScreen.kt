package io.github.mangi.eta.ui.screens.writing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun WritingBookScreen(state: WritingViewModel, bookId: String, navigate: (AppRoute) -> Unit, back: () -> Unit) {
    val book = state.book(bookId)
    var renameBook by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var addVolume by rememberSaveable { mutableStateOf(false) }
    var addChapterTo by rememberSaveable { mutableStateOf<String?>(null) }
    var volumeMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var renameVolume by rememberSaveable { mutableStateOf<String?>(null) }
    var exportMenu by rememberSaveable { mutableStateOf(false) }
    var pendingFormat by rememberSaveable { mutableStateOf<WritingExport?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) state.setCover(bookId, it)
    }
    // CreateDocument's MIME type is part of the registered contract. Keep a registration for each format.
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WritingExport.Backup.mime)) {
        if (it != null && book != null) state.export(it, book, WritingExport.Backup)
    }
    val markdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WritingExport.Markdown.mime)) {
        if (it != null && book != null) state.export(it, book, WritingExport.Markdown)
    }
    val plain = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WritingExport.Text.mime)) {
        if (it != null && book != null) state.export(it, book, WritingExport.Text)
    }
    val epub = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WritingExport.Epub.mime)) {
        if (it != null && book != null) state.export(it, book, WritingExport.Epub)
    }

    ScaffoldPage(
        title = book?.title ?: "作品",
        onBack = back,
        actions = {
            TopBarActionButton(Icons.Rounded.Add, "新建卷", enabled = book != null, onClick = { addVolume = true })
            TopBarActionButton(Icons.Rounded.Tune, "创作工作台", enabled = book != null,
                onClick = { navigate(AppRoute.WritingDesk(bookId)) })
        },
    ) {
        item { WritingNotice(state) }
        if (book == null) {
            item { if (state.loading) WritingLoading() else WritingSection { Text("找不到作品。") } }
            return@ScaffoldPage
        }
        item("book-actions") {
            WritingSection {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WritingCover(book, Modifier.width(76.dp).aspectRatio(0.8f))
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (book.aiMode) "AI 驱动" else "人类主笔")
                        WritingCaption("${book.volumes.size} 卷 · ${book.chapters.size} 章")
                        WritingCaption(if (state.saving) "正在保存…" else "自动保存")
                    }
                }
                WritingActions {
                    TextButton("改书名", onClick = { renameBook = true })
                    TextButton("设封面", onClick = { coverPicker.launch("image/*") })
                    TextButton("导出", onClick = { exportMenu = true })
                    TextButton("删除作品", onClick = { deleting = true })
                }
            }
        }
        writingGeneration(state)
        writingReview(book.candidate, state.generating == null, onAccept = { state.review(bookId, null, it) },
            onReject = { state.review(bookId, null, null) }, onRetry = {
                state.generate(bookId, null, it.task, it.instruction, volumeId = book.candidateVolume)
            })
        book.volumes.forEach { volume ->
            item("vol:${volume.id}") {
                SmallTitle(text = volume.title)
            }
            item("vol-menu:${volume.id}") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    WritingEntry("卷设置", "${volume.chapters.size} 章") { volumeMenu = volume.id }
                }
            }
            items(volume.chapters, key = { "ch:${it.id}" }) { chapter ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                    val status = when {
                        chapter.candidate != null -> "有候选待审阅"
                        chapter.handoff != null -> "已定稿"
                        chapter.summary.isNotBlank() -> "摘要已确认"
                        else -> "草稿"
                    }
                    WritingEntry(chapter.title, status) { navigate(AppRoute.WritingChapter(bookId, chapter.id)) }
                }
            }
            item("add:${volume.id}") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    WritingEntry("新建章节") { addChapterTo = volume.id }
                }
            }
        }
    }

    if (book == null) return
    if (renameBook) WritingNameDialog("编辑作品", "书名", book.title, { renameBook = false }) { name ->
        state.edit(bookId) { it.copy(title = name) }
        renameBook = false
    }
    if (addVolume) WritingNameDialog("新建卷", "卷名", "第 ${book.volumes.size + 1} 卷", { addVolume = false }) { name ->
        state.edit(bookId) { it.copy(volumes = it.volumes + WritingVolume(title = name)) }
        addVolume = false
    }
    addChapterTo?.let { volumeId ->
        WritingNameDialog("新建章节", "章名", "第 ${book.chapters.size + 1} 章", { addChapterTo = null }) { name ->
            val chapter = WritingChapter(title = name)
            state.edit(bookId) { current ->
                current.copy(volumes = current.volumes.map {
                    if (it.id == volumeId) it.copy(chapters = it.chapters + chapter) else it
                })
            }
            addChapterTo = null
            navigate(AppRoute.WritingChapter(bookId, chapter.id))
        }
    }
    val selectedVolume = book.volumes.find { it.id == volumeMenu }
    if (selectedVolume != null) {
        WritingDialog(selectedVolume.title, { volumeMenu = null }) {
            WritingEntry("编辑卷名") { renameVolume = selectedVolume.id; volumeMenu = null }
            WritingEntry("生成卷名候选") {
                state.generate(bookId, null, WritingTask.Volume, selectedVolume.title, volumeId = selectedVolume.id)
                volumeMenu = null
            }
        }
    }
    val editedVolume = book.volumes.find { it.id == renameVolume }
    if (editedVolume != null) {
        WritingNameDialog("编辑卷名", "卷名", editedVolume.title, { renameVolume = null }) { name ->
            state.edit(bookId) { current -> current.copy(volumes = current.volumes.map {
                if (it.id == editedVolume.id) it.copy(title = name) else it
            }) }
            renameVolume = null
        }
    }
    if (deleting) WritingDialog("删除《${book.title}》", { deleting = false }) {
        Text("此操作会删除本地整部作品及章节版本。")
        DialogActions("删除", { deleting = false }, { state.delete(bookId); deleting = false; back() }, destructive = true)
    }
    if (exportMenu) WritingDialog("导出作品", { exportMenu = false }) {
        WritingExport.entries.forEach { format ->
            val label = if (format == WritingExport.Backup) "JSON 全书备份" else format.name
            WritingEntry(label) {
                pendingFormat = format
                exportMenu = false
            }
        }
    }
    val format = pendingFormat
    if (format != null) androidx.compose.runtime.LaunchedEffect(format) {
        pendingFormat = null
        val fileName = book.title.replace(Regex("[/\\\\:*?\"<>|]"), "_") + "." + format.extension
        when (format) {
            WritingExport.Backup -> backup.launch(fileName)
            WritingExport.Markdown -> markdown.launch(fileName)
            WritingExport.Text -> plain.launch(fileName)
            WritingExport.Epub -> epub.launch(fileName)
        }
    }
}

internal fun LazyListScope.writingGeneration(state: WritingViewModel) {
    val task = state.generating ?: return
    item("ai-progress") {
        WritingSection {
            Text("正在生成 · $task")
            TextButton("取消生成", onClick = state::cancelGeneration)
        }
    }
}
