package com.chrauto.ai.viber

import com.chrauto.ai.agent.UiNodeSnapshot
import com.chrauto.ai.agent.UiObservation

const val VIBER_PACKAGE = "com.viber.voip"

/**
 * Pure readers for Viber's share / chat screens. Viber's UI is not a public API and changes between
 * versions, so every reader is conservative: when something is missing or ambiguous it returns a
 * "not sure" answer and the sender stops instead of guessing.
 */
object ViberScreens {
    fun normalize(s: String?): String = s.orEmpty().replace(Regex("\\s+"), " ").trim()

    fun isViber(o: UiObservation?) = o?.packageName == VIBER_PACKAGE

    sealed interface GroupMatch {
        data class Unique(val node: UiNodeSnapshot) : GroupMatch
        data object None : GroupMatch
        data class Ambiguous(val count: Int) : GroupMatch
    }

    /**
     * Finds the row for [groupName]. Only an exact (whitespace-normalized, case-insensitive) label
     * match counts; "Family" never matches "Family 2". Text inside editable fields (the search box)
     * is ignored. Several exact matches = ambiguous.
     */
    fun findGroup(o: UiObservation, groupName: String): GroupMatch {
        val want = normalize(groupName)
        if (want.isEmpty()) return GroupMatch.None
        val hits = o.nodes.filter { n ->
            !n.editable && (normalize(n.text).equals(want, ignoreCase = true) || normalize(n.contentDescription).equals(want, ignoreCase = true))
        }
        // One visible row often yields both a label and its clickable parent; count distinct clickable rows.
        val rows = hits.mapNotNull { o.clickableTarget(it.id) ?: it.takeIf { n -> n.clickable } }.distinctBy { it.id }
        return when {
            rows.isEmpty() -> GroupMatch.None
            rows.size > 1 -> GroupMatch.Ambiguous(rows.size)
            else -> GroupMatch.Unique(rows.single())
        }
    }

    /** The search text field, if one is on screen. */
    fun searchField(o: UiObservation): UiNodeSnapshot? = o.nodes.firstOrNull { it.editable && it.enabled && looksLikeSearch(it) }
        ?: o.nodes.singleOrNull { it.editable && it.enabled }

    /** A search icon/button that opens the search field. */
    fun searchButton(o: UiObservation): UiNodeSnapshot? = o.nodes.firstOrNull { n ->
        !n.editable && n.enabled && (n.clickable) && looksLikeSearch(n)
    }

    private fun looksLikeSearch(n: UiNodeSnapshot): Boolean {
        val label = listOf(n.text, n.contentDescription, n.hint, n.viewId).joinToString(" ") { it.orEmpty() }.lowercase()
        return "search" in label
    }

    private val sendWords = Regex("^(send|share|send message|done)$", RegexOption.IGNORE_CASE)

    /**
     * The single Send/Share control. Matches by view id (…send…) or by an exact label. Returns null
     * if there are zero or several candidates, so we never pick between two buttons.
     */
    fun sendButton(o: UiObservation): UiNodeSnapshot? {
        val candidates = o.nodes.filter { n ->
            n.enabled && !n.editable && (n.clickable || o.clickableTarget(n.id) != null) && (
                n.viewId?.substringAfter(":id/")?.lowercase()?.let { it == "send" || it.startsWith("send_") || it.endsWith("_send") || it == "share" } == true ||
                    sendWords.matches(normalize(n.contentDescription)) || sendWords.matches(normalize(n.text))
                )
        }.mapNotNull { o.clickableTarget(it.id) ?: it }.distinctBy { it.id }
        return candidates.singleOrNull()
    }

    /** The group name is visible on screen (title of the open chat or the selected recipient chip). */
    fun showsGroup(o: UiObservation, groupName: String): Boolean {
        val want = normalize(groupName)
        return want.isNotEmpty() && o.nodes.any { normalize(it.text).equals(want, true) || normalize(it.contentDescription).equals(want, true) }
    }

    /**
     * Evidence the message was sent: we are in Viber, the group is shown, and the message text appears
     * in a non-editable node (a chat bubble), not just in the composer. Image-only messages can't be
     * confirmed this way, so they are reported as unconfirmed rather than sent.
     */
    fun sentEvidence(o: UiObservation, groupName: String, text: String): Boolean {
        if (!isViber(o) || !showsGroup(o, groupName)) return false
        val probe = normalize(text).take(60)
        if (probe.isEmpty()) return false
        val inComposer = o.nodes.any { it.editable && normalize(it.text).contains(probe, true) }
        val inBubble = o.nodes.any { !it.editable && normalize(it.text).contains(probe, true) }
        return inBubble && !inComposer
    }

    /** The message text is still in an editable composer field, i.e. it has definitely not been sent yet. */
    fun composerHolds(o: UiObservation, text: String): Boolean {
        val probe = normalize(text).take(60)
        return probe.isNotEmpty() && isViber(o) && o.nodes.any { it.editable && normalize(it.text).contains(probe, true) } &&
            o.nodes.none { !it.editable && normalize(it.text).contains(probe, true) }
    }
}
