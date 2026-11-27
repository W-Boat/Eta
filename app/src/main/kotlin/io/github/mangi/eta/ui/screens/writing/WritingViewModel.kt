package io.github.mangi.eta.ui.screens.writing

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WritingViewModel(application: Application) : AndroidViewModel(application) {
    private val files = WritingFiles(File(application.filesDir, "writing"))
    private val resolver = application.contentResolver
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val preferences = application.getSharedPreferences("writing", 0)
    private var generation: Job? = null
    private var trackedManuscript: TrackedManuscript? = null

    var books by mutableStateOf<List<WritingBook>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var generating by mutableStateOf<String?>(null)
        private set
    var cards by mutableStateOf(preferences.getBoolean("cards", true))
        private set
    var pendingImport by mutableStateOf<WritingImport?>(null)
        private set

    init {
        viewModelScope.launch {
            val initial = try {
                withContext(Dispatchers.IO) { files.readAll() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notice = "无法打开本地青简书架。"
                loading = false
                return@launch
            }
            val saved = initial.associateBy { it.id }.toMutableMap()
            books = initial
            ready = true
            loading = false
            for (signal in changed) {
                delay(150)
                val snapshot = books
                try {
                    withContext(Dispatchers.IO) {
                        snapshot.filter { saved[it.id] !== it }.forEach { book ->
                            files.save(book)
                            saved[book.id] = book
                        }
                        val currentIds = snapshot.mapTo(hashSetOf()) { it.id }
                        saved.keys.filterNot(currentIds::contains).forEach { id ->
                            files.delete(id)
                            saved.remove(id)
                        }
                    }
                    if (books === snapshot) saving = false
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    saving = false
                    notice = "保存失败。请重试，或先导出备份。"
                }
            }
        }
    }

    fun book(id: String) = books.find { it.id == id }

    fun toggleCards() {
        cards = !cards
        preferences.edit().putBoolean("cards", cards).apply()
    }

    fun create(title: String): String? {
        if (!ready || title.isBlank()) return null
        val book = WritingBook(title = title.trim())
        replaceBooks(listOf(book) + books)
        return book.id
    }

    fun edit(id: String, transform: (WritingBook) -> WritingBook) {
        val book = book(id) ?: return
        val next = transform(book)
        if (next == book) return
        replaceBooks(books.map { if (it.id == id) next.copy(updated = System.currentTimeMillis()) else it })
    }

    fun editChapter(bookId: String, chapterId: String, transform: (WritingChapter) -> WritingChapter) =
        edit(bookId) { it.changeChapter(chapterId, transform) }

    fun openChapter(bookId: String, chapterId: String, offset: Int? = null) = edit(bookId) { book ->
        book.copy(activeChapterId = chapterId).changeChapter(chapterId) { chapter ->
            val next = chapter.migrateParagraphDrafts()
            if (offset == null) next else next.copy(position = WritingPosition(offset, offset, -1))
        }
    }

    fun changeDraft(bookId: String, chapterId: String, id: String, change: (ParagraphDraft) -> ParagraphDraft) =
        editChapter(bookId, chapterId) { chapter ->
            chapter.copy(drafts = chapter.drafts.map { if (it.id == id) change(it) else it })
        }

    fun writeManuscript(
        bookId: String, chapterId: String, text: String, patch: ManuscriptEdit,
        beforeChange: (WritingChapter) -> Unit = {},
    ) = edit(bookId) { book ->
        val before = book.chapter(chapterId) ?: return@edit book
        if (before.text == text) return@edit book
        beforeChange(before)
        trackedManuscript?.takeIf { it.bookId == bookId && it.chapterId == chapterId && it.text == before.text }?.let {
            it.edits.add(patch)
            it.text = text
        }
        book.changeChapter(chapterId) { it.withManuscript(text, patch) }.copy(materials = book.materials.map { item ->
            if (item.anchors.none { it.chapterId == chapterId }) item else item.copy(anchors = item.anchors.map {
                if (it.chapterId == chapterId) it.shifted(patch) else it
            })
        })
    }

    fun adoptParagraphs(bookId: String, chapterId: String, ids: Set<String>): Boolean = try {
        edit(bookId) { book ->
            val chapter = book.chapter(chapterId) ?: return@edit book
            val revised = chapter.acceptParagraphs(ids)
            val replacements = chapter.drafts.filter { it.id in ids }.sortedByDescending { it.anchor.offset }.map { draft ->
                val text = draft.replacement()
                val start = draft.anchor.offset
                ManuscriptEdit(start, start + draft.anchor.quote.length, start + text.length) to text
            }
            book.changeChapter(chapterId) { revised }.copy(materials = book.materials.map { item ->
                if (item.anchors.none { it.chapterId == chapterId }) item else item.copy(anchors = item.anchors.map { anchor ->
                    if (anchor.chapterId != chapterId) anchor else replacements.fold(anchor) { ref, (patch, text) ->
                        ref.afterParagraphReplacement(patch, text)
                    }
                })
            })
        }
        true
    } catch (failure: IllegalArgumentException) {
        notice = failure.message ?: "无法采用候选。"
        false
    }

    fun delete(bookId: String) {
        replaceBooks(books.filterNot { it.id == bookId })
    }

    private fun replaceBooks(next: List<WritingBook>) {
        if (!ready) return
        books = next
        retrySave()
    }

    fun retrySave() {
        if (ready) {
            saving = true
            changed.trySend(Unit)
        }
    }

    fun clearNotice() { notice = null }

    fun readImport(uri: Uri) = fileOperation("无法读取备份") {
        pendingImport = withContext(Dispatchers.IO) { readWritingBackup(readDocument(uri)) }
    }

    fun dismissImport() { pendingImport = null }

    fun confirmImport(overwrite: Boolean): String? {
        val pending = pendingImport ?: return null
        if (!ready) return null
        val namesake = books.firstOrNull { it.title.equals(pending.book.title, ignoreCase = true) }
        val imported = if (overwrite && namesake != null) pending.book.copy(id = namesake.id) else pending.book
        replaceBooks(listOf(imported) + books.filterNot { it.id == imported.id })
        dismissImport()
        return imported.id
    }

    fun export(uri: Uri, snapshot: WritingBook, format: WritingExport) = fileOperation("导出失败") {
        withContext(Dispatchers.IO) {
            val output = resolver.openOutputStream(uri, "wt") ?: error("无法打开导出文件。")
            output.use { exportWritingBook(snapshot, format, it) }
        }
        notice = "已导出《${snapshot.title}》。"
    }

    fun importSkill(bookId: String, uri: Uri) = fileOperation("无法导入 Skill") {
        val source = withContext(Dispatchers.IO) { readDocument(uri, 256 * 1024L) }
        require(source.isNotBlank()) { "Skill 内容为空。" }
        val title = source.lineSequence().firstOrNull { it.startsWith("name:") }
            ?.substringAfter(':')?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() }
            ?: source.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")
            ?: "写作 Skill"
        val item = WritingMaterial(kind = WritingMaterialKind.Skill, title = title, content = source, enabled = false)
        edit(bookId) { it.copy(materials = it.materials + item) }
    }

    fun setCover(bookId: String, uri: Uri) = fileOperation("无法设置封面") {
        val encoded = withContext(Dispatchers.IO) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                val longestEdge = maxOf(info.size.width, info.size.height)
                if (longestEdge > 640) {
                    decoder.setTargetSize(
                        (640L * info.size.width / longestEdge).toInt().coerceAtLeast(1),
                        (640L * info.size.height / longestEdge).toInt().coerceAtLeast(1),
                    )
                }
            }
            try {
                ByteArrayOutputStream().use { bytes ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes))
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
            } finally {
                bitmap.recycle()
            }
        }
        edit(bookId) { it.copy(cover = encoded) }
    }

    private fun readDocument(uri: Uri, limit: Long = 32 * 1024 * 1024L): String {
        val stream = resolver.openInputStream(uri) ?: error("无法打开文件。")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "文件超过导入大小限制。" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().decodeToString()
        }
    }

    private fun fileOperation(message: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                notice = failure.message?.let { "$message · $it" } ?: message
            }
        }
    }

    fun generate(
        bookId: String,
        chapterId: String?,
        task: WritingTask,
        instruction: String,
        targets: List<WritingAnchor> = emptyList(),
        volumeId: String? = null,
        replacingDraft: String? = null,
    ) {
        if (generation?.isCompleted == false) return
        val book = book(bookId) ?: return
        val chapter = chapterId?.let { book.chapter(it) ?: return }
        if (task == WritingTask.Draft && chapter?.summary.isNullOrBlank()) {
            notice = "请先确认本章摘要。"
            return
        }
        val volume = volumeId?.let { id -> book.volumes.find { it.id == id } }
        val tracker = chapter?.let { TrackedManuscript(bookId, it.id, it.text) }
        trackedManuscript = tracker
        generating = task.label
        generation = viewModelScope.launch {
            try {
                val (result, usage) = requestWritingCandidate(book, chapter, task, instruction, targets)
                if (chapterId != null) {
                    edit(bookId) {
                        it.changeChapter(chapterId) { current ->
                            current.receiveDrafts(result, requireNotNull(chapter), replacingDraft,
                                tracker?.takeIf { it.text == current.text }?.edits)
                        }
                            .copy(usage = it.usage + usage)
                    }
                } else {
                    edit(bookId) {
                        val candidate = if (volume == null) result else result.copy(original = volume.title)
                        it.copy(candidate = candidate, candidateVolume = volume?.id, usage = it.usage + usage)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                notice = "模型请求超时，请重试。"
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                notice = failure.message?.takeIf { it.startsWith("请") || it.startsWith("模型") || it.startsWith("生成") }
                    ?: "生成失败。请检查 ETA 的模型设置或缩小请求范围后重试。"
            } finally {
                trackedManuscript = null
                generating = null
            }
        }
    }

    fun cancelGeneration() { generation?.cancel() }

    fun review(bookId: String, chapterId: String?, edited: WritingCandidate?) {
        try {
            edit(bookId) { book ->
                if (chapterId == null) {
                    if (edited == null) return@edit book.copy(candidate = null, candidateVolume = null)
                    val next = when (edited.task) {
                        WritingTask.Setup -> {
                            check(book.notes == edited.original) { "作品设定已变更，请重新生成。" }
                            book.copy(notes = edited.text)
                        }
                        WritingTask.Outline -> {
                            check(book.outline == edited.original) { "大纲已变更，请重新生成。" }
                            book.copy(outline = edited.text)
                        }
                        WritingTask.Volume -> book.copy(volumes = book.volumes.map {
                            if (it.id != book.candidateVolume) it else {
                                check(it.title == edited.original) { "卷名已变更，请重新生成。" }
                                it.copy(title = edited.text.lineSequence().first().trim().ifBlank { it.title })
                            }
                        })
                        WritingTask.Compress -> book.copy(context = book.context.copy(compressed = edited.text))
                        WritingTask.Analyze -> book.copy(materials = book.materials + WritingMaterial(
                            kind = WritingMaterialKind.Reference, title = "样本分析", content = edited.text,
                        ))
                        else -> error("无法应用此候选。")
                    }
                    return@edit next.copy(candidate = null, candidateVolume = null)
                }
                val chapter = book.chapter(chapterId) ?: return@edit book
                if (edited?.task == WritingTask.Extract) {
                    check(chapter.text == edited.original) { "正文已变更，请重新提取。" }
                    val material = writingExtract(edited, chapter)
                    return@edit book.changeChapter(chapterId) { it.copy(candidate = null) }
                        .copy(materials = book.materials + material)
                }
                book.changeChapter(chapterId) { current ->
                    val next = when (edited?.task) {
                        null -> current
                        WritingTask.Summary -> {
                            check(current.summary == edited.original) { "摘要已变更，请重新生成。" }
                            current.copy(summary = edited.text.trim())
                        }
                        WritingTask.Rewrite, WritingTask.Draft ->
                            current.withText(applyWritingChanges(current.text, edited), "采用 AI ${edited.task.label}前").let {
                                current.withManuscript(it.text).copy(versions = it.versions)
                            }
                        WritingTask.Handoff -> {
                            check(current.text == edited.original) { "正文已变更，请重新整理定稿。" }
                            current.copy(handoff = writingHandoff(edited.text))
                        }
                        else -> error("无法应用此候选。")
                    }
                    next.copy(candidate = null)
                }
            }
        } catch (failure: Exception) {
            notice = failure.message ?: "无法应用候选。"
        }
    }
}

private class TrackedManuscript(val bookId: String, val chapterId: String, var text: String) {
    val edits = mutableListOf<ManuscriptEdit>()
}
