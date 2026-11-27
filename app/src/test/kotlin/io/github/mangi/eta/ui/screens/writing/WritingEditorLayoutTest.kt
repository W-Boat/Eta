package io.github.mangi.eta.ui.screens.writing

import android.app.Application
import android.os.Looper
import android.os.SystemClock
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
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WritingEditorLayoutTest {
    @Test
    fun recentChapterOpensInTheSameEditorThroughSidebarsOnPhoneAndTablet() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        val first = WritingChapter(title = "第一章", text = "灯亮了。")
        val recent = WritingChapter(title = "第二章", text = "城门开了。", position = WritingPosition(2, 2))
        val book = WritingBook(title = "城门", width = 1100, aiMode = true, activeChapterId = recent.id,
            volumes = listOf(WritingVolume(title = "上卷", chapters = listOf(first, recent))))
        WritingFiles(File(activity.filesDir, "writing")).save(book)
        val model = ViewModelProvider(activity)[WritingViewModel::class.java]
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!model.ready && System.nanoTime() < deadline) {
            looper.idle()
            Thread.sleep(10)
        }
        assertTrue(model.ready)

        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        var theme by mutableStateOf(AppearanceThemeMode.LIGHT)
        val view = ComposeView(activity)
        val density = activity.resources.displayMetrics.density
        fun pump(wide: Boolean) {
            val width = ((if (wide) 950 else 390) * density).toInt()
            val height = (720 * density).toInt()
            repeat(6) {
                Snapshot.sendApplyNotifications()
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
        fun editor() = allViews(activity.window.decorView).filterIsInstance<ManuscriptEditText>().single()
        try {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(appearance = AppearanceSettings(themeMode = theme), provideGlassHost = true) {
                    WritingChapterScreen(model, book.id, null, null, {}, {})
                }
            }
            activity.setContentView(view)
            for (wide in listOf(false, true)) {
                for (appearance in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
                    theme = appearance
                    pump(wide)
                    val mounted = editor()
                    val closedWidth = mounted.width
                    assertEquals(recent.text, mounted.text.toString())
                    assertEquals(2, mounted.selectionStart)
                    click(view, "目录")
                    pump(wide)
                    assertSame(mounted, editor())
                    assertEquals(2, editor().selectionStart)
                    if (wide) assertTrue(editor().width < closedWidth) else assertEquals(closedWidth, editor().width)
                    click(view, "关闭侧栏")
                    pump(wide)
                    click(view, "AI 辅助")
                    pump(wide)
                    assertSame(mounted, editor())
                    click(view, "关闭侧栏")
                    pump(wide)
                }
            }
            click(view, "目录")
            pump(true)
            click(view, "第一章")
            pump(true)
            assertEquals(first.text, editor().text.toString())
            assertEquals(first.id, model.book(book.id)?.activeChapterId)
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

    private fun click(view: ComposeView, label: String) {
        val host = view.getChildAt(0)
        val owner = host.javaClass.getMethod("getSemanticsOwner").invoke(host)
        val root = owner.javaClass.getMethod("getRootSemanticsNode").invoke(owner) as SemanticsNode
        fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(nodes(child))
        }
        val action = nodes(root).first { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) ||
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label })
        }.config[SemanticsActions.OnClick].action
        assertEquals(true, action?.invoke())
    }
}
