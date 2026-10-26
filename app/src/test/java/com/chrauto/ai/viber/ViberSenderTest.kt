package com.chrauto.ai.viber

import android.graphics.Rect
import com.chrauto.ai.agent.ActionResult
import com.chrauto.ai.agent.AgentAction
import com.chrauto.ai.agent.DeviceBridge
import com.chrauto.ai.agent.UiNodeSnapshot
import com.chrauto.ai.agent.UiObservation
import com.chrauto.ai.tasks.ViberMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private fun node(id: String, text: String? = null, desc: String? = null, clickable: Boolean = false, editable: Boolean = false, viewId: String? = null, hint: String? = null) =
    UiNodeSnapshot(id, text, desc, if (editable) "EditText" else "TextView", clickable, editable, false, true, Rect(0, 0, 10, 10), emptyList(), hint = hint, viewId = viewId)

private fun viber(vararg n: UiNodeSnapshot) = UiObservation(VIBER_PACKAGE, "Share", n.toList())

class ViberScreensTest {
    @Test fun groupMatchIsExactAndUnique() {
        val o = viber(node("1", "Family", clickable = true), node("2", "Family 2", clickable = true), node("3", "family", editable = true))
        assertTrue(ViberScreens.findGroup(o, " family ") is ViberScreens.GroupMatch.Unique)
        assertEquals(ViberScreens.GroupMatch.None, ViberScreens.findGroup(o, "Fam"))
        val two = viber(node("1", "Team", clickable = true), node("2", "Team", clickable = true))
        assertEquals(ViberScreens.GroupMatch.Ambiguous(2), ViberScreens.findGroup(two, "Team"))
    }

    @Test fun sendButtonMustBeSingleAndClear() {
        assertEquals("s", ViberScreens.sendButton(viber(node("s", desc = "Send", clickable = true)))?.id)
        assertEquals("s", ViberScreens.sendButton(viber(node("s", clickable = true, viewId = "com.viber.voip:id/send")))?.id)
        assertNull(ViberScreens.sendButton(viber(node("a", desc = "Send", clickable = true), node("b", text = "Share", clickable = true))))
        assertNull(ViberScreens.sendButton(viber(node("a", desc = "Send later", clickable = true))))
    }

    @Test fun sentEvidenceNeedsABubbleNotTheComposer() {
        val composer = viber(node("t", "Family"), node("c", "Hello all", editable = true))
        assertFalse(ViberScreens.sentEvidence(composer, "Family", "Hello all"))
        assertTrue(ViberScreens.composerHolds(composer, "Hello all"))
        val bubble = viber(node("t", "Family"), node("b", "Hello all"), node("c", "", editable = true))
        assertTrue(ViberScreens.sentEvidence(bubble, "Family", "Hello all"))
        assertFalse(ViberScreens.sentEvidence(bubble.copy(packageName = "com.other"), "Family", "Hello all"))
        assertFalse(ViberScreens.sentEvidence(bubble, "Work", "Hello all"))
    }
}

/** Scripted Viber: screens change in response to taps. */
private class FakeViber(var screen: UiObservation?, val onTap: FakeViber.(String) -> Unit = {}) : DeviceBridge {
    val taps = mutableListOf<String>()
    var ready = true
    override fun isReady() = ready
    override suspend fun observe() = screen
    override suspend fun settle(maxMs: Long) {}
    override suspend fun execute(action: AgentAction): ActionResult {
        if (action is AgentAction.Tap) { taps += action.nodeId; onTap(action.nodeId) }
        return ActionResult(true, "ok")
    }
}

class ViberSenderTest {
    private val msg = ViberMessage("m", "Meeting at 6")
    private val share = viber(node("row", "Family", clickable = true))
    private val chat = viber(node("title", "Family"), node("comp", "Meeting at 6", editable = true), node("send", desc = "Send", clickable = true))
    private val sent = viber(node("title", "Family"), node("bubble", "Meeting at 6"), node("comp", "", editable = true), node("send", desc = "Send", clickable = true))
    private fun sender(d: DeviceBridge, launch: String? = null) = ViberSender(d, { launch }, viberOpenTimeoutMs = 0, verifyTimeoutMs = 0, manualSendTimeoutMs = 0, searchTimeoutMs = 0, pollMs = 1)

    @Test fun autoSendTapsSendOnceAndConfirms() = runTest {
        val d = FakeViber(share) { id -> screen = if (id == "row") chat else if (id == "send") sent else screen }
        var marked = 0
        val out = sender(d).send("Family", msg, autoSend = true) { marked++ }
        assertTrue(out is SendOutcome.Sent)
        assertEquals(listOf("row", "send"), d.taps)
        assertEquals(1, marked)
    }

    @Test fun noBubbleAfterSendIsUnconfirmedNotRetried() = runTest {
        val d = FakeViber(share) { id -> if (id == "row") screen = chat }
        val out = sender(d).send("Family", msg, autoSend = true) {}
        assertTrue(out is SendOutcome.Unconfirmed)
        assertEquals(1, d.taps.count { it == "send" })
    }

    @Test fun ambiguousOrMissingGroupStopsBeforeAnyTap() = runTest {
        val two = viber(node("a", "Family", clickable = true), node("b", "Family", clickable = true), node("s", hint = "Search", editable = true))
        val d = FakeViber(two)
        var marked = false
        val out = sender(d).send("Family", msg, autoSend = true) { marked = true }
        assertTrue(out is SendOutcome.NotSent)
        assertTrue(d.taps.isEmpty()); assertFalse(marked)
        val none = FakeViber(viber(node("x", "Work", clickable = true), node("s", hint = "Search", editable = true)))
        assertTrue(sender(none).send("Family", msg, true) {} is SendOutcome.NotSent)
    }

    @Test fun manualModeNeverTapsSend() = runTest {
        val d = FakeViber(share) { id -> if (id == "row") screen = chat }
        val out = sender(d).send("Family", msg, autoSend = false) {}
        assertTrue(out is SendOutcome.Unconfirmed)
        assertEquals(listOf("row"), d.taps)
    }

    @Test fun missingSendButtonWithTextInComposerIsSafelyNotSent() = runTest {
        val noSend = viber(node("title", "Family"), node("comp", "Meeting at 6", editable = true))
        val d = FakeViber(share) { id -> if (id == "row") screen = noSend }
        assertTrue(sender(d).send("Family", msg, true) {} is SendOutcome.NotSent)
    }

    @Test fun launcherErrorsAndServiceOffAreReported() = runTest {
        assertTrue(sender(FakeViber(share), launch = "Viber is not installed").send("Family", msg, true) {} is SendOutcome.NotSent)
        val off = FakeViber(share).apply { ready = false }
        assertTrue(sender(off).send("Family", msg, true) {} is SendOutcome.NotSent)
        val notViber = FakeViber(UiObservation("com.android.launcher", "Home", emptyList()))
        assertTrue(sender(notViber).send("Family", msg, true) {} is SendOutcome.NotSent)
    }
}
