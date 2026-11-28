package io.github.mangi.eta.ui.screens.writing

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.editor.MarkwonEditor
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ManuscriptMarker(val offset: Int, val selected: Boolean = false)
internal class ManuscriptJump(val offset: Int)
internal data class ManuscriptParagraphBounds(val top: Int, val bottom: Int, val viewport: Int)

@Composable
internal fun ManuscriptEditor(
    chapter: WritingChapter,
    source: Boolean,
    visible: Boolean,
    markers: List<ManuscriptMarker>,
    activeParagraph: WritingAnchor?,
    selecting: Boolean,
    jump: ManuscriptJump?,
    onEdit: (String, ManuscriptEdit) -> Unit,
    onPosition: (WritingPosition) -> Unit,
    onMarker: (Int) -> Unit,
    onSelectionAction: (WritingPosition) -> Unit,
    onParagraphTap: (Int) -> Unit,
    onParagraphBounds: (ManuscriptParagraphBounds?) -> Unit,
    modifier: Modifier,
) {
    val edit by rememberUpdatedState(onEdit)
    val position by rememberUpdatedState(onPosition)
    val marker by rememberUpdatedState(onMarker)
    val select by rememberUpdatedState(onSelectionAction)
    val tap by rememberUpdatedState(onParagraphTap)
    val bounds by rememberUpdatedState(onParagraphBounds)
    val foreground = MiuixTheme.colorScheme.onSurface.toArgb()
    val accent = MiuixTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = modifier,
        onReset = null,
        factory = { context ->
            ManuscriptEditText(context).apply {
                replaceDocument(chapter.text, chapter.position)
                edited = { text, patch -> edit(text, patch) }
                moved = { position(it) }
                markerSelected = { marker(it) }
                selectionAction = { select(it) }
                paragraphTap = { offset -> tap(offset) }
                paragraphMoved = { bounds(it) }
            }
        },
        update = { view ->
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            view.setTextColor(foreground)
            view.updateAppearance(accent, markers, activeParagraph, selecting, source)
            if (view.text.toString() != chapter.text) view.replaceDocument(chapter.text, view.position())
            if (chapter.position.scroll < 0) view.scrollToOffset(chapter.position.start)
            if (jump != null && view.lastJump !== jump) {
                view.lastJump = jump
                view.scrollToOffset(jump.offset)
            }
        },
        onRelease = { it.release() },
    )
}

internal class ManuscriptEditText(context: Context) : EditText(context) {
    private val density = resources.displayMetrics.density
    private val gutter = (27 * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val executor = Executors.newSingleThreadExecutor()
    private val markdown = MarkwonEditor.create(Markwon.create(context))
    private var renderRevision = 0
    private var renderTask: Runnable? = null
    private var highlighting = false
    private var updating = false
    private var markerDown: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var textTap = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var markers = emptyList<ManuscriptMarker>()
    private var activeParagraph: WritingAnchor? = null
    private var reportedBounds: ManuscriptParagraphBounds? = null
    private var accent = Color.BLACK
    private var multiSelect = false
    private var lastTyping = 0L
    private var customCursor = false
    private val normalCursor = textCursorDrawable
    private val hiddenCursor = ColorDrawable(Color.TRANSPARENT)
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    var edited: ((String, ManuscriptEdit) -> Unit)? = null
    var moved: ((WritingPosition) -> Unit)? = null
    var markerSelected: ((Int) -> Unit)? = null
    var selectionAction: ((WritingPosition) -> Unit)? = null
    var paragraphTap: ((Int) -> Unit)? = null
    var paragraphMoved: ((ManuscriptParagraphBounds?) -> Unit)? = null
    var lastJump: ManuscriptJump? = null

    init {
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(gutter, (21 * density).toInt(), (25 * density).toInt(), (26 * density).toInt())
        setLineSpacing(4 * density, 1.22f)
        textSize = 18f
        typeface = Typeface.SERIF
        isSaveEnabled = false
        addTextChangedListener(object : TextWatcher {
            private var patch = ManuscriptEdit(0, 0, 0)
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                patch = ManuscriptEdit(start, start + count, start + after)
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                scheduleHighlight()
                if (updating || s == null) return
                lastTyping = SystemClock.uptimeMillis()
                edited?.invoke(s.toString(), patch)
            }
        })
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, SELECTION_ACTION, Menu.NONE, "青简改稿")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != SELECTION_ACTION) return false
                selectionAction?.invoke(position())
                mode.finish()
                return true
            }
        }
    }

    fun position() = WritingPosition(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0), scrollY)

    fun replaceDocument(document: String, position: WritingPosition) {
        updating = true
        val old = text.toString()
        val patch = manuscriptEdit(old, document)
        if (old != document) text.replace(patch.start, patch.beforeEnd, document.substring(patch.start, patch.afterEnd))
        setSelection(position.start.coerceIn(0, length()), position.end.coerceIn(0, length()))
        updating = false
        if (position.scroll < 0) scrollToOffset(position.start)
        else doAfterLayout { scrollTo(0, position.scroll.coerceIn(0, maxScroll())) }
    }

    private fun maxScroll() = ((layout?.height ?: 0) + totalPaddingTop + totalPaddingBottom - height).coerceAtLeast(0)

    private fun doAfterLayout(action: () -> Unit) {
        if (!isLayoutRequested && layout != null) {
            action()
        } else {
            addOnLayoutChangeListener(object : OnLayoutChangeListener {
                override fun onLayoutChange(v: android.view.View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    removeOnLayoutChangeListener(this)
                    action()
                }
            })
        }
    }

    fun scrollToOffset(rawOffset: Int) {
        val offset = rawOffset.coerceIn(0, length())
        setSelection(offset)
        doAfterLayout {
            val content = layout ?: return@doAfterLayout
            val top = content.getLineTop(content.getLineForOffset(offset))
            scrollTo(0, (top - height / 4).coerceIn(0, maxScroll()))
            moved?.invoke(position())
        }
    }

    fun updateAppearance(color: Int, marks: List<ManuscriptMarker>, active: WritingAnchor?, selecting: Boolean, source: Boolean) {
        accent = color
        markers = marks
        activeParagraph = active
        reportParagraphBounds()
        multiSelect = selecting
        if (highlighting == source) {
            highlighting = !source
            if (highlighting) {
                scheduleHighlight()
            } else {
                renderRevision++
                renderTask?.let(::removeCallbacks)
                text.getSpans(0, length(), Any::class.java).forEach { span ->
                    if (span.javaClass.name.startsWith("io.noties.markwon.editor.")) text.removeSpan(span)
                }
            }
        }
        invalidate()
    }

    private fun scheduleHighlight() {
        if (!highlighting || executor.isShutdown) return
        val revision = ++renderRevision
        renderTask?.let(::removeCallbacks)
        renderTask = Runnable {
            val snapshot = text.toString()
            executor.execute {
                markdown.preRender(SpannableStringBuilder(snapshot)) { result ->
                    post {
                        if (highlighting && revision == renderRevision && text.toString() == snapshot) {
                            result.dispatchTo(text)
                        }
                    }
                }
            }
        }.also { postDelayed(it, 125) }
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if (!updating && start >= 0 && end >= 0) moved?.invoke(position())
        invalidate()
    }

    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(x, y, oldX, oldY)
        if (!updating && y != oldY) moved?.invoke(position())
        reportParagraphBounds()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reportParagraphBounds()
    }

    private fun reportParagraphBounds() {
        val content = layout
        val active = activeParagraph
        val bounds = if (content != null && active != null && active.offset < length()) {
            val first = content.getLineForOffset(active.offset)
            val last = content.getLineForOffset((active.offset + active.quote.length - 1).coerceIn(active.offset, length()))
            ManuscriptParagraphBounds(
                totalPaddingTop + content.getLineTop(first) - scrollY,
                totalPaddingTop + content.getLineBottom(last) - scrollY,
                height,
            )
        } else null
        if (bounds != reportedBounds) {
            reportedBounds = bounds
            paragraphMoved?.invoke(bounds)
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val content = layout
        val showBreath = content != null && hasFocus() && hasWindowFocus() && isCursorVisible &&
            selectionStart >= 0 && selectionStart == selectionEnd && !multiSelect && ValueAnimator.areAnimatorsEnabled() &&
            accessibility?.isTouchExplorationEnabled != true && BaseInputConnection.getComposingSpanStart(text) < 0
        if (customCursor != showBreath) {
            customCursor = showBreath
            textCursorDrawable = if (showBreath) hiddenCursor else normalCursor
        }
        val active = activeParagraph
        if (content != null && active != null && active.offset < length()) {
            val first = content.getLineForOffset(active.offset)
            val last = content.getLineForOffset((active.offset + active.quote.length - 1).coerceIn(active.offset, length()))
            val top = totalPaddingTop + content.getLineTop(first).toFloat()
            val bottom = totalPaddingTop + content.getLineBottom(last).toFloat()
            paint.color = accent
            paint.alpha = 25
            canvas.drawRoundRect(
                scrollX + gutter * 0.78f, top,
                scrollX + width - 12 * density, bottom, 9 * density, 9 * density, paint,
            )
        }
        super.onDraw(canvas)
        if (content == null) return
        paint.color = accent
        for (marker in markers) {
            val line = content.getLineForOffset(marker.offset.coerceIn(0, length()))
            val y = totalPaddingTop + (content.getLineTop(line) + content.getLineBottom(line)) / 2f
            if (y !in scrollY.toFloat()..(scrollY + height).toFloat()) continue
            paint.alpha = if (marker.selected) 255 else 160
            canvas.drawCircle(scrollX + gutter * 0.50f, y, density * if (marker.selected) 4.5f else 3.4f, paint)
        }
        if (!showBreath) return
        val line = content.getLineForOffset(selectionStart)
        val time = SystemClock.uptimeMillis() - lastTyping
        val opacity = if (time < 600L) 1.0 else 0.66 + 0.34 * kotlin.math.cos((time - 600L) * 2 * Math.PI / 1700)
        paint.alpha = (255 * opacity).toInt()
        val x = totalPaddingLeft + content.getPrimaryHorizontal(selectionStart)
        val top = totalPaddingTop + content.getLineTop(line) + 3 * density
        val bottom = totalPaddingTop + content.getLineBottom(line) - 4 * density
        canvas.drawRoundRect(x, top, x + 2 * density, bottom, density, density, paint)
        postInvalidateDelayed(32)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val content = layout ?: return super.onTouchEvent(event)
        val line = content.getLineForVertical(event.y.toInt() + scrollY - totalPaddingTop)
        val lineOffset = content.getLineStart(line)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            downTime = event.eventTime
            val y = event.y + scrollY - totalPaddingTop
            textTap = event.x >= gutter && y >= 0f && y < content.height
            markerDown = if (multiSelect || event.x < gutter) {
                markers.firstOrNull { content.getLineForOffset(it.offset.coerceIn(0, length())) == line }?.offset
                    ?: lineOffset.takeIf { multiSelect }
            } else null
            return super.onTouchEvent(event)
        }
        val movedFar = kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop
        if (movedFar || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) textTap = false
        val hit = markerDown
        if (hit == null) {
            val tapped = textTap && event.actionMasked == MotionEvent.ACTION_UP &&
                event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
            val offset = if (tapped) getOffsetForPosition(event.x, event.y) else -1
            val handled = super.onTouchEvent(event)
            if (tapped && offset >= 0 && selectionStart == selectionEnd) paragraphTap?.invoke(offset)
            return handled
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            markerDown = null
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
            cancel.recycle()
            if (multiSelect) paragraphTap?.invoke(hit) else markerSelected?.invoke(hit)
            performClick()
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            markerDown = null
            return super.onTouchEvent(event)
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && movedFar) {
            markerDown = null
            return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick() = super.performClick()

    fun release() {
        renderRevision++
        renderTask?.let(::removeCallbacks)
        executor.shutdownNow()
    }

    private companion object {
        const val SELECTION_ACTION = 19901
    }
}
