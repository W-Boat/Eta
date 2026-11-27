package io.github.mangi.eta.ui.screens.writing

import android.app.Activity
import android.app.Application
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManuscriptEditorTest {
    @Test
    fun compositionRemainsOnTheNativeEditableAndRegularInputReportsAnExactPatch() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val editor = ManuscriptEditText(activity)
        activity.setContentView(editor)
        editor.requestFocus()
        editor.replaceDocument("海上\n来信", WritingPosition(2, 2))
        val editable = editor.text
        val edits = mutableListOf<Pair<String, ManuscriptEdit>>()
        editor.edited = { source, patch -> edits += source to patch }
        val input = requireNotNull(editor.onCreateInputConnection(EditorInfo()))

        assertTrue(input.setComposingText("ni", 1))
        assertTrue(BaseInputConnection.getComposingSpanStart(editable) >= 0)
        assertTrue(input.setComposingText("你", 1))
        assertTrue(input.finishComposingText())
        assertEquals("海上你\n来信", editor.text.toString())
        assertTrue(editor.text === editable)
        assertEquals("海上你\n来信" to ManuscriptEdit(2, 4, 3), edits.last())
        editor.release()
    }
}
