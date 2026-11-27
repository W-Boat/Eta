package io.github.mangi.eta.ui.screens.writing

import kotlinx.serialization.Serializable

@Serializable
internal data class WritingPosition(val start: Int = 0, val end: Int = 0, val scroll: Int = 0)

@Serializable
internal enum class ParagraphDecision { Pending, Stale, Accepted, Rejected }

@Serializable
internal data class ParagraphDraft(
    val id: String = writingId(),
    val anchor: WritingAnchor,
    val text: String,
    val instruction: String = "",
    val decision: ParagraphDecision = ParagraphDecision.Pending,
    val selected: Boolean = false,
)

internal data class ManuscriptEdit(val start: Int, val beforeEnd: Int, val afterEnd: Int) {
    val shift get() = afterEnd - beforeEnd
}

internal fun WritingAnchor.shifted(edit: ManuscriptEdit): WritingAnchor {
    if (detached) return this
    return when {
        edit.beforeEnd <= offset -> copy(offset = offset + edit.shift)
        edit.start >= offset + quote.length -> this
        else -> copy(offset = edit.start.coerceAtMost(offset), detached = true)
    }
}

internal fun WritingAnchor.afterParagraphReplacement(edit: ManuscriptEdit, replacement: String): WritingAnchor =
    if (!detached && offset == edit.start && offset + quote.length == edit.beforeEnd) copy(quote = replacement)
    else shifted(edit)

internal fun ParagraphDraft.replacement(): String =
    anchor.quote.takeWhile { it == ' ' || it == '\t' || it == '　' } + text.trim()

internal fun manuscriptEdit(before: String, after: String): ManuscriptEdit {
    var start = 0
    while (start < minOf(before.length, after.length) && before[start] == after[start]) start++
    var oldEnd = before.length
    var newEnd = after.length
    while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return ManuscriptEdit(start, oldEnd, newEnd)
}

internal fun WritingChapter.migrateParagraphDrafts(): WritingChapter {
    val legacy = candidate ?: return this
    if (legacy.targets.isEmpty() || legacy.targets.size != legacy.changes.size) return this
    val valid = legacy.original == text && writingParagraphs(this).toSet().containsAll(legacy.targets)
    if (!valid) return this
    return copy(candidate = null, drafts = drafts + legacy.targets.zip(legacy.changes).map { (anchor, prose) ->
        ParagraphDraft(anchor = anchor, text = prose, instruction = legacy.instruction)
    })
}

internal fun WritingChapter.withManuscript(newText: String, edit: ManuscriptEdit = manuscriptEdit(text, newText)): WritingChapter {
    if (newText == text) return this
    val base = migrateParagraphDrafts()
    val next = base.copy(text = newText)
        .copy(feedback = base.feedback.map { it.copy(anchor = it.anchor.shifted(edit)) })
    if (base.drafts.none { it.decision == ParagraphDecision.Pending }) return next
    val current = writingParagraphs(next).associateBy { it.offset }
    return next.copy(drafts = base.drafts.map { draft ->
        if (draft.decision != ParagraphDecision.Pending) return@map draft
        val anchor = draft.anchor.shifted(edit)
        if (current[anchor.offset] == anchor) {
            draft.copy(anchor = anchor)
        } else {
            draft.copy(anchor = anchor, decision = ParagraphDecision.Stale, selected = false)
        }
    })
}

internal fun WritingChapter.receiveDrafts(
    result: WritingCandidate,
    captured: WritingChapter,
    replacing: String?,
    trackedEdits: List<ManuscriptEdit>? = null,
): WritingChapter {
    val targets = if (result.targets.isNotEmpty()) result.targets else {
        if (result.task != WritingTask.Rewrite) return copy(candidate = result)
        val before = writingParagraphs(captured)
        val proposed = captured.copy(text = result.text)
        val after = writingParagraphs(proposed)
        fun WritingChapter.separators(anchors: List<WritingAnchor>): List<String> = buildList {
            var start = 0
            for (anchor in anchors) {
                add(text.substring(start, anchor.offset))
                start = anchor.offset + anchor.quote.length
            }
            add(text.substring(start))
        }
        fun markup(line: String) = line.trimStart().takeWhile { it in "#>|*-+`~[]" }
        if (before.size != after.size || before.isEmpty() || captured.separators(before) != proposed.separators(after) ||
            before.zip(after).any { (old, new) -> markup(old.quote) != markup(new.quote) } ||
            (before.size > 1 && before.zip(after).none { (old, new) -> old.quote == new.quote })
        ) {
            return copy(candidate = result)
        }
        val pairs = before.zip(after).filter { (old, new) -> old.quote != new.quote }
        if (pairs.isEmpty()) return this
        return receiveDrafts(result.copy(targets = pairs.map { it.first }, changes = pairs.map { it.second.quote }),
            captured, replacing, trackedEdits)
    }
    val current = writingParagraphs(this).associateBy(WritingAnchor::offset)
    val incoming = targets.zip(result.changes).map { (anchor, prose) ->
        val shifted = trackedEdits?.fold(anchor) { value, edit -> value.shifted(edit) } ?: anchor
        ParagraphDraft(
            anchor = shifted,
            text = prose,
            instruction = result.instruction,
            decision = if ((text == captured.text || trackedEdits != null) && current[shifted.offset] == shifted)
                ParagraphDecision.Pending else ParagraphDecision.Stale,
        )
    }
    // Preserve prior drafts unless the author explicitly regenerated that draft successfully.
    return copy(drafts = drafts.filterNot { it.id == replacing } + incoming)
}

internal fun WritingChapter.acceptParagraphs(ids: Set<String>): WritingChapter {
    val matching = drafts.filter { it.id in ids }
    require(ids.isNotEmpty() && matching.size == ids.size)
    val byOffset = writingParagraphs(this).associateBy { it.offset }
    val ordered = matching.sortedByDescending { it.anchor.offset }
    require(ordered.map { it.anchor.offset }.distinct().size == ordered.size) { "同一段只能采用一份候选。" }
    require(ordered.all { draft ->
        draft.decision == ParagraphDecision.Pending && byOffset[draft.anchor.offset] == draft.anchor &&
            draft.text.isNotBlank() && manuscriptLine.matches(draft.text) && !sceneDivider.matches(draft.text.trim())
    }) { "候选或原段已变化。请检查该段后再采用。" }

    var next = copy(drafts = drafts.map {
        if (it.id in ids) it.copy(decision = ParagraphDecision.Accepted, selected = false) else it
    })
    for (draft in ordered) {
        val start = draft.anchor.offset
        val end = start + draft.anchor.quote.length
        val replacement = draft.replacement()
        val revised = next.text.replaceRange(start, end, replacement)
        val edit = ManuscriptEdit(start, end, start + replacement.length)
        val feedback = next.feedback.map { note -> note.copy(anchor = note.anchor.afterParagraphReplacement(edit, replacement)) }
        next = next.withManuscript(revised, edit).copy(feedback = feedback)
    }
    return next.copy(versions = (listOf(WritingVersion(text, "逐段采用前")) + versions).take(20))
}
