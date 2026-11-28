package io.github.mangi.eta.ui.screens.writing

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceGroupItem
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.BookOutline
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.Import
import top.yukonga.miuix.kmp.icon.glass.ListView
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WritingShelfScreen(state: WritingViewModel, navigate: (AppRoute) -> Unit, back: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(state::readImport) }
    val books = state.books.filter { it.title.contains(query.trim(), ignoreCase = true) }.sortedByDescending { it.updated }
    fun openBook(id: String) = navigate(AppRoute.WritingBook(id))

    ScaffoldPage(
        title = stringResource(R.string.conversation_dock_writing),
        onBack = back,
        actions = {
            TopBarActionButton(
                icon = if (state.cards) MiuixIcons.Glass.ListView else MiuixIcons.Glass.GridView,
                contentDescription = if (state.cards) "横排书架" else "卡片书架",
                onClick = state::toggleCards,
            )
            TopBarActionButton(MiuixIcons.Glass.Import, "导入全书备份", enabled = state.ready,
                onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            TopBarActionButton(MiuixIcons.Glass.Add, "新建作品", enabled = state.ready, onClick = { creating = true })
        },
    ) {
        item { WritingNotice(state) }
        if (state.loading) item { WritingLoading() }
        if (state.books.isNotEmpty()) item("search") {
            SearchBar(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                insideMargin = DpSize(0.dp, 0.dp),
                expanded = false,
                onExpandedChange = {},
                inputField = { InputField(query = query, onQueryChange = { query = it }, onSearch = { query = it },
                    expanded = false, onExpandedChange = {}, label = "搜索作品") },
                content = {},
            )
        }
        if (books.isNotEmpty()) item("shelf-heading") { SmallTitle("我的作品") }
        if (state.ready && books.isEmpty()) item {
            ListEmptyState(
                title = if (query.isBlank()) "书架为空" else "没有匹配的作品",
                summary = if (query.isBlank()) "新建小说，或导入青简的全书备份。" else "试试其他书名。",
                icon = MiuixIcons.Glass.BookOutline,
                action = { if (query.isBlank()) TextButton("新建作品", onClick = { creating = true }) },
            )
        }
        if (state.cards) {
            items(books.chunked(2), key = { it.first().id }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { book ->
                        WritingBookTile(book, Modifier.weight(1f)) { openBook(book.id) }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        } else {
            itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                PreferenceGroupItem(isFirst = index == 0, isLast = index == books.lastIndex) {
                    Row(Modifier.fillMaxWidth().clickable { openBook(book.id) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        WritingCover(book, Modifier.width(48.dp).aspectRatio(0.78f).squircleClip(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(book.title)
                            WritingCaption(book.activeChapter()?.title.orEmpty())
                        }
                    }
                    if (index < books.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp, end = 14.dp))
                }
            }
        }
    }
    if (creating) WritingNameDialog("新建作品", "书名", "", { creating = false }) {
        state.create(it)?.let(::openBook)
        creating = false
    }
    val incoming = state.pendingImport
    if (incoming != null) {
        val duplicate = state.books.any { it.title.equals(incoming.book.title, ignoreCase = true) }
        WritingDialog("导入《${incoming.book.title}》", state::dismissImport) {
            WritingCaption("${incoming.book.chapters.size} 章 · ${incoming.annotationCount} 条批注 · ${incoming.foreshadowCount} 条伏笔")
            if (duplicate) {
                Text("书架已有同名作品。覆盖会替换现有书稿。")
                TextButton("覆盖同名书", onClick = { state.confirmImport(true)?.let(::openBook) })
            }
            DialogActions(
                confirmText = if (duplicate) "保留副本" else "导入",
                onCancel = state::dismissImport,
                onConfirm = { state.confirmImport(false)?.let(::openBook) },
            )
        }
    }
}

@Composable
private fun WritingBookTile(book: WritingBook, modifier: Modifier, open: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val hasCover = book.cover.startsWith("data:image/")
    Box(modifier.aspectRatio(0.83f).squircleClip(22.dp).background(colors.surfaceContainerHigh).clickable(onClick = open)) {
        WritingCover(book, Modifier.matchParentSize())
        if (hasCover) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0.43f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.76f),
            )))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold,
                color = if (hasCover) Color.White else colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.activeChapter()?.title?.let { title ->
                Text(title, style = MiuixTheme.textStyles.footnote1,
                    color = if (hasCover) Color.White.copy(alpha = 0.87f) else colors.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun WritingCover(book: WritingBook, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, book.cover) {
        value = withContext(Dispatchers.IO) {
            if (!book.cover.startsWith("data:image/") || book.cover.length > 12_000_000) return@withContext null
            runCatching {
                val data = Base64.decode(book.cover.substringAfter(','), Base64.DEFAULT)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 600).coerceAtLeast(1)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val cover = image
    if (cover != null) {
        Image(cover, "《${book.title}》封面", modifier, contentScale = ContentScale.Crop)
    } else {
        val colors = MiuixTheme.colorScheme
        Box(modifier.background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.13f), colors.surfaceContainerHigh))),
            contentAlignment = Alignment.Center) {
            Icon(MiuixIcons.Glass.BookOutline, null, tint = colors.primary)
        }
    }
}

@Composable
internal fun WritingCaption(text: String) {
    Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun WritingSection(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun WritingDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlassDialog(show = true, title = title, backdrop = currentGlassOverlayBackdrop(), onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun WritingNameDialog(title: String, label: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    WritingDialog(title, dismiss) {
        SettingsTextField(label, value, inputTransformation = null) { value = it }
        DialogActions("保存", dismiss, { save(value.trim()) }, confirmEnabled = value.isNotBlank())
    }
}

@Composable
internal fun WritingNotice(state: WritingViewModel) {
    val message = state.notice ?: return
    WritingSection {
        Text(message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.startsWith("保存失败")) TextButton("重试", onClick = state::retrySave)
            TextButton("关闭", onClick = state::clearNotice)
        }
    }
}

@Composable
internal fun WritingLoading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        InfiniteProgressIndicator(size = 28.dp)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WritingActions(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun WritingEntry(title: String, summary: String? = null, open: () -> Unit) {
    ArrowPreference(title = title, summary = summary, onClick = open)
}
