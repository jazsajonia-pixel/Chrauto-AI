package com.chrauto.ai.viber

import com.chrauto.ai.agent.AgentAction
import com.chrauto.ai.agent.DeviceBridge
import com.chrauto.ai.agent.UiObservation
import com.chrauto.ai.tasks.ViberMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Opens Viber's share screen with a message's text and images. Returns an error message, or null on success. */
fun interface ShareLauncher { fun launch(message: ViberMessage): String? }

sealed interface SendOutcome {
    /** Evidence on screen shows the message in the group chat. */
    data class Sent(val evidence: String) : SendOutcome
    /** Send may have happened but could not be confirmed. Never retried automatically. */
    data class Unconfirmed(val reason: String) : SendOutcome
    /** Definitely not sent (we stopped before tapping Send). [needsUser] = a person must look at it. */
    data class NotSent(val reason: String, val needsUser: Boolean) : SendOutcome
}

/**
 * Sends one prepared message to an existing Viber group through Viber's own share screen, using
 * accessibility to pick the group. No AI is involved: every step is a fixed check, and the flow
 * stops (rather than guesses) when the group, the Send button, or the result is unclear.
 *
 * Send is tapped at most once per message. [onAboutToSend] is called (and must persist SENDING)
 * right before the chat is picked (which may itself send), so a crash in between can never lead to a silent resend.
 */
class ViberSender(
    private val device: DeviceBridge,
    private val launcher: ShareLauncher,
    private val log: (String) -> Unit = {},
    private val viberOpenTimeoutMs: Long = 9_000,
    private val verifyTimeoutMs: Long = 10_000,
    private val manualSendTimeoutMs: Long = 120_000,
    private val searchTimeoutMs: Long = 4_000,
    private val pollMs: Long = 500
) {
    suspend fun send(groupName: String, message: ViberMessage, autoSend: Boolean, onAboutToSend: suspend () -> Unit): SendOutcome {
        if (!device.isReady()) return SendOutcome.NotSent("Accessibility service is off", needsUser = true)
        launcher.launch(message)?.let { return SendOutcome.NotSent(it, needsUser = true) }
        log("Opened Viber's share screen")

        var o = waitFor(viberOpenTimeoutMs) { ViberScreens.isViber(it) }
            ?: return SendOutcome.NotSent("Viber did not open (is the phone unlocked?)", needsUser = true)

        var match = ViberScreens.findGroup(o, groupName)
        if (match !is ViberScreens.GroupMatch.Unique) {
            var field = ViberScreens.searchField(o)
            if (field == null) {
                ViberScreens.searchButton(o)?.let { btn ->
                    device.execute(AgentAction.Tap(btn.id)); device.settle(1_200)
                    o = device.observe() ?: o
                    field = ViberScreens.searchField(o)
                }
            }
            val search = field ?: return SendOutcome.NotSent("Couldn't find Viber's search box to look up \"$groupName\"", needsUser = true)
            val typed = device.execute(AgentAction.TypeText(search.id, groupName))
            if (!typed.ok) return SendOutcome.NotSent("Couldn't type the group name: ${typed.message}", needsUser = true)
            log("Searched for \"$groupName\"")
            device.settle(1_800)
            o = waitFor(searchTimeoutMs) { ViberScreens.findGroup(it, groupName) is ViberScreens.GroupMatch.Unique } ?: (device.observe() ?: o)
            match = ViberScreens.findGroup(o, groupName)
        }
        val row = when (match) {
            is ViberScreens.GroupMatch.Unique -> match.node
            is ViberScreens.GroupMatch.Ambiguous -> return SendOutcome.NotSent("${match.count} chats are named \"$groupName\"; pick the right one yourself", needsUser = true)
            ViberScreens.GroupMatch.None -> return SendOutcome.NotSent("No chat is named exactly \"$groupName\"", needsUser = true)
        }
        coroutineContext.ensureActive()
        // In some Viber versions picking a chat in the share sheet sends straight away, so from this
        // tap on the message counts as possibly sent.
        onAboutToSend()
        val picked = device.execute(AgentAction.Tap(row.id))
        if (!picked.ok) return SendOutcome.NotSent("Couldn't select \"$groupName\": ${picked.message}", needsUser = true)
        log("Selected \"$groupName\"")
        device.settle(1_500)
        o = device.observe() ?: return SendOutcome.Unconfirmed("Screen unreadable after selecting the group")
        if (ViberScreens.sentEvidence(o, groupName, message.text)) return SendOutcome.Sent("Message seen in \"$groupName\" right after selecting it")
        // Not provably unsent unless the text is still sitting in the composer.
        fun notSentOr(reason: String): SendOutcome =
            if (ViberScreens.composerHolds(o, message.text)) SendOutcome.NotSent(reason, needsUser = true) else SendOutcome.Unconfirmed(reason)
        if (!ViberScreens.isViber(o) || !ViberScreens.showsGroup(o, groupName))
            return notSentOr("Couldn't confirm \"$groupName\" is the selected chat")

        if (!autoSend) {
            log("Ready in Viber — waiting for you to tap Send")
            val done = waitFor(manualSendTimeoutMs) { ViberScreens.sentEvidence(it, groupName, message.text) }
            return if (done != null) SendOutcome.Sent("Message seen in \"$groupName\"")
            else SendOutcome.Unconfirmed("Prepared in Viber for you to send; Chrauto didn't see it in the chat")
        }

        val send = ViberScreens.sendButton(o) ?: run {
            device.settle(1_000); device.observe()?.let { o = it }; ViberScreens.sendButton(o)
        } ?: return notSentOr("Couldn't find a single, clear Send button")

        coroutineContext.ensureActive()
        val tapped = device.execute(AgentAction.Tap(send.id))
        if (!tapped.ok) return notSentOr("Send tap was not delivered: ${tapped.message}")
        log("Tapped Send once")
        device.settle(2_000)
        val seen = waitFor(verifyTimeoutMs) { ViberScreens.sentEvidence(it, groupName, message.text) }
        return when {
            seen != null -> SendOutcome.Sent("Message seen in \"$groupName\"")
            message.text.isBlank() -> SendOutcome.Unconfirmed("Image-only message: Send was tapped, but images can't be confirmed automatically")
            else -> SendOutcome.Unconfirmed("Send was tapped once, but the message wasn't seen in the chat afterwards")
        }
    }

    private suspend fun waitFor(timeoutMs: Long, check: (UiObservation) -> Boolean): UiObservation? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            coroutineContext.ensureActive()
            device.observe()?.let { if (check(it)) return it }
            if (System.currentTimeMillis() >= end) return null
            delay(pollMs)
        }
    }
}
