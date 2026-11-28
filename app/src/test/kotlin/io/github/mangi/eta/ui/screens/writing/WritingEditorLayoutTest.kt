package io.github.mangi.eta.ui.screens.writing

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.navigation.AppRoute
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WritingEditorLayoutTest {
    @Test
    fun recentChapterOpensInTheSameEditorThroughSidebarsOnPhoneAndTablet() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        val first = WritingChapter(title = "第一章", text = "灯亮了。")
        val manuscript = WritingChapter(title = "第二章", text = "城门开了。\n风吹进来。", position = WritingPosition(2, 2))
        val anchors = writingParagraphs(manuscript)
        val recent = manuscript.copy(drafts = listOf(ParagraphDraft(anchor = anchors[0], text = "城门缓缓打开。"),
            ParagraphDraft(anchor = anchors[1], text = "晚风穿过城门。")))
        val book = WritingBook(title = "城门", width = 1100, aiMode = true, activeChapterId = recent.id,
            volumes = listOf(WritingVolume(title = "上卷", chapters = listOf(first, recent))))
        val model = ViewModelProvider(activity)[WritingViewModel::class.java]
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!model.ready && System.nanoTime() < deadline) {
            looper.idle()
            Thread.sleep(10)
        }
        assertTrue(model.ready)
        val bookId = requireNotNull(model.create(book.title))
        model.edit(bookId) { book.copy(id = it.id) }
        assertEquals(recent.id, model.book(bookId)?.activeChapter()?.id)

        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        var theme by mutableStateOf(AppearanceThemeMode.LIGHT)
        var destination: AppRoute? = null
        val view = ComposeView(activity)
        val density = activity.resources.displayMetrics.density
        fun pump(wide: Boolean) {
            val width = ((if (wide) 950 else 390) * density).toInt()
            val height = (720 * density).toInt()
            repeat(8) {
                Snapshot.sendApplyNotifications()
                looper.idle()
                looper.idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                allViews(activity.window.decorView).forEach(View::forceLayout)
                activity.window.decorView.run {
                    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    layout(0, 0, width, height)
                }
                looper.idle()
            }
        }
        fun editor() = nodes(view).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("正文编辑器")
        } ?: error("正文编辑器未渲染：${labels(view)}")
        try {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(
                    appearance = AppearanceSettings(themeMode = theme),
                    provideGlassHost = true,
                ) {
                    WritingChapterScreen(model, bookId, null, null, { destination = it }, {})
                }
            }
            activity.setContentView(view)
            for (wide in listOf(false, true)) {
                for (appearance in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
                    theme = appearance
                    pump(wide)
                    val mounted = editor()
                    val closedWidth = mounted.boundsInRoot.width
                    assertTrue(labels(view).contains(recent.title))
                    assertEquals(2, model.book(bookId)?.activeChapter()?.position?.start)
                    click(view, "目录")
                    pump(wide)
                    assertEquals(mounted.id, editor().id)
                    assertEquals(2, model.book(bookId)?.activeChapter()?.position?.start)
                    val openWidth = editor().boundsInRoot.width
                    if (wide) assertTrue(openWidth < closedWidth) else assertEquals(closedWidth, openWidth, 0.5f)
                    click(view, "逐段审批，2 段待审")
                    pump(wide)
                    assertEquals(mounted.id, editor().id)
                    assertEquals(wide, labels(view).contains("关闭目录"))
                    assertTrue(labels(view).contains("城门缓缓打开。"))
                    click(view, "关闭逐段审批")
                    if (wide) click(view, "关闭目录")
                    pump(wide)
                    click(view, "快捷插入")
                    pump(wide)
                    assertTrue(labels(view).contains("场景分隔"))
                    click(view, "关闭快捷插入")
                    pump(wide)
                }
            }
            click(view, "创作工作台")
            assertEquals(AppRoute.WritingDesk(bookId), destination)
            val nativeEditor = allViews(view).filterIsInstance<ManuscriptEditText>().single()
            click(view, "写作选项")
            pump(true)
            click(view, "Markdown 预览")
            pump(true)
            assertEquals(View.INVISIBLE, nativeEditor.visibility)
            click(view, "写作选项")
            pump(true)
            click(view, "返回编辑")
            pump(true)
            assertTrue(nativeEditor === allViews(view).filterIsInstance<ManuscriptEditText>().single())
            assertEquals(View.VISIBLE, nativeEditor.visibility)
            assertEquals(2, nativeEditor.selectionStart)
            val now = SystemClock.uptimeMillis()
            val x = nativeEditor.totalPaddingLeft + 8 * density
            val y = nativeEditor.totalPaddingTop + 8 * density
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, x, y, 0)
                nativeEditor.onTouchEvent(event)
                event.recycle()
            }
            pump(true)
            assertTrue(labels(view).containsAll(listOf("编辑本段", "本段改稿提示", "本段批注", "本段伏笔", "本段相关")))
            click(view, "逐段审批，2 段待审")
            pump(true)
            click(view, "采用本段")
            pump(true)
            val accepted = requireNotNull(model.book(bookId)?.activeChapter())
            assertEquals("城门缓缓打开。\n风吹进来。", accepted.text)
            assertEquals(1, accepted.drafts.count { it.decision == ParagraphDecision.Pending })
            click(view, "关闭逐段审批")
            click(view, "目录")
            pump(true)
            click(view, "第一章")
            pump(true)
            assertTrue(labels(view).contains(first.title))
            assertEquals(first.id, model.book(bookId)?.activeChapterId)
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun allViews(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(allViews(root.getChildAt(i)))
    }

    private fun nodes(view: ComposeView): Sequence<SemanticsNode> {
        val host = view.getChildAt(0)
        val owner = host.javaClass.getMethod("getSemanticsOwner").invoke(host)
        val root = owner.javaClass.getMethod("getRootSemanticsNode").invoke(owner) as SemanticsNode
        fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(nodes(child))
        }
        return nodes(root)
    }

    private fun labels(view: ComposeView): List<String> = nodes(view).flatMap { node ->
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()).asSequence()
    }.toList()

    private fun click(view: ComposeView, label: String) {
        val action = nodes(view).firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) ||
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label })
        }?.config?.get(SemanticsActions.OnClick)?.action ?: error("无法点击 $label：${labels(view)}")
        assertEquals(true, action.invoke())
    }
}
