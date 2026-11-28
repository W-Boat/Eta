package io.github.mangi.eta.ui.screens.writing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.Title
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import io.github.mangi.eta.ui.components.TopBarActionButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.glass.GlassFloatingToolbar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.ListView
import top.yukonga.miuix.kmp.icon.glass.QuotationMarks
import top.yukonga.miuix.kmp.icon.glass.Remove
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WritingEditorDock(
    expanded: Boolean,
    onToggleInsert: () -> Unit,
    onInsert: (WritingInsert) -> Unit,
    onOpenDesk: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (expanded) Card {
            FlowRow(
                Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                WritingInsert.entries.forEach { action ->
                    TopBarActionButton(action.icon, action.label, onClick = { onInsert(action) })
                }
                TopBarActionButton(MiuixIcons.Glass.Close, "关闭快捷插入", onClick = onToggleInsert)
            }
        }
        GlassFloatingToolbar(backdrop = null, outSidePadding = PaddingValues(0.dp)) {
            Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                TopBarActionButton(
                    MiuixIcons.Glass.Add, "快捷插入", onClick = onToggleInsert,
                    modifier = Modifier.semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "已展开" else "已收起"
                    },
                    tint = if (expanded) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                )
                VerticalDivider(Modifier.padding(horizontal = 4.dp).height(20.dp))
                TopBarActionButton(MiuixIcons.Glass.GridView, "创作工作台", onClick = onOpenDesk)
            }
        }
    }
}

@Composable
internal fun WritingParagraphMenu(
    bounds: ManuscriptParagraphBounds,
    modifier: Modifier = Modifier,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    if (bounds.bottom < 0 || bounds.top >= bounds.viewport) return
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    var height by remember { mutableIntStateOf(0) }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Card(
            Modifier.padding(horizontal = 4.dp).offset {
                val below = bounds.bottom + gap
                val above = bounds.top - height - gap
                val max = (bounds.viewport - height).coerceAtLeast(0)
                IntOffset(0, if (below in 0..max) below else above.coerceIn(0, max))
            }.onSizeChanged { height = it.height },
        ) {
            FlowRow(
                Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                content = actions,
            )
        }
    }
}

internal enum class WritingInsert(val label: String, val icon: ImageVector) {
    Heading("标题", Icons.Rounded.Title),
    Bold("加粗", Icons.Rounded.FormatBold),
    Italic("斜体", Icons.Rounded.FormatItalic),
    Quote("引用", MiuixIcons.Glass.QuotationMarks),
    ListItem("列表", MiuixIcons.Glass.ListView),
    Scene("场景分隔", MiuixIcons.Glass.Remove),
}

internal data class WritingInsertion(val start: Int, val end: Int, val replacement: String, val caret: Int)

internal fun WritingInsert.at(text: String, position: WritingPosition): WritingInsertion {
    fun boundary(offset: Int): Int {
        val index = offset.coerceIn(0, text.length)
        return if (index > 0 && text.getOrNull(index) == '\n' && text[index - 1] == '\r') index - 1 else index
    }
    val cursor = boundary(minOf(position.start, position.end))
    val extent = boundary(maxOf(position.start, position.end))
    val rangeStart = minOf(cursor, text.lastIndexOfAny(charArrayOf('\r', '\n'), cursor - 1) + 1)
    val rangeEnd = text.indexOfAny(charArrayOf('\r', '\n'), extent).takeIf { it >= 0 } ?: text.length
    if (this == WritingInsert.Scene) {
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val prefix = if (rangeEnd == rangeStart) "" else newline.repeat(2)
        val following = when {
            text.startsWith(newline.repeat(2), rangeEnd) -> 2
            text.startsWith(newline, rangeEnd) -> 1
            else -> 0
        }
        val separator = prefix + "---" + newline.repeat(2 - following)
        return WritingInsertion(rangeEnd, rangeEnd, separator, rangeEnd + separator.length + following * newline.length)
    }
    val inline = this == WritingInsert.Bold || this == WritingInsert.Italic
    val start = if (inline && cursor != extent) cursor else rangeStart
    val end = if (inline && cursor != extent) extent else rangeEnd
    val selected = text.substring(start, end)
    val (prefix, suffix) = when (this) {
        WritingInsert.Heading -> "# " to ""
        WritingInsert.Bold -> "**" to "**"
        WritingInsert.Italic -> "_" to "_"
        WritingInsert.Quote -> "> " to ""
        WritingInsert.ListItem -> "- " to ""
        WritingInsert.Scene -> error("Scene inserts between paragraphs")
    }
    val formatted = selected.startsWith(prefix) && selected.endsWith(suffix) && selected.length >= prefix.length + suffix.length
    val replacement = if (formatted) selected.substring(prefix.length, selected.length - suffix.length) else prefix + selected + suffix
    val caret = start + if (selected.isEmpty()) prefix.length else replacement.length
    return WritingInsertion(start, end, replacement, caret)
}
