package com.chrauto.ai.voice

/**
 * Things Chron can handle instantly on the phone, with no AI round-trip:
 * time/date, battery, "repeat that", talking speed, "what are you doing", approving a paused task,
 * forgetting the conversation, and ending a live conversation.
 */
sealed interface Local {
    data object Time : Local
    data object Date : Local
    data object Battery : Local
    data object Repeat : Local
    data class Rate(val faster: Boolean) : Local
    data object Hush : Local
    data object Status : Local
    data object Approve : Local
    data object Forget : Local
    data object Goodbye : Local
    data object Help : Local
    data object WhoAreYou : Local
}

object LocalAnswers {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val rules: List<Pair<Regex, Local>> = listOf(
        r("^(what('s| is) the time|what time is it|time (now|please)|anong oras na|what time now)$") to Local.Time,
        r("^(what('s| is) (the |today's )?date|what day is (it|today)|what('s| is) today|today's date|anong petsa)( today)?$") to Local.Date,
        r("^((what('s| is) )?(my |the )?battery( level| percentage| status)?|how much battery( do i have| is left)?( left)?)$") to Local.Battery,
        r("^(repeat( that| it)?|say (that|it) again|come again|what did you say|pardon|ulitin mo)$") to Local.Repeat,
        r("^(speak|talk|go) (a bit |a little )?(faster|quicker)$") to Local.Rate(true),
        r("^(speak|talk|go) (a bit |a little )?(slower|more slowly)$") to Local.Rate(false),
        r("^(shh+|hush|be quiet|quiet|stop talking|shut up|silence|tumahimik ka)$") to Local.Hush,
        r("^(what are you doing|what('s| is) happening|status|how('s| is) it going|are you done( yet)?|progress)$") to Local.Status,
        r("^(forget (it all|everything|that)|clear (the )?(chat|conversation|history)|new conversation|start over|reset( the)? (chat|conversation))$") to Local.Forget,
        r("^(bye|goodbye|good ?bye|bye bye|see you|see ya|that'?s all|that'?s it|i'?m done|good ?night|thanks,? that'?s all|thank you,? that'?s all|sige salamat|paalam)$") to Local.Goodbye,
        r("^(help|what can you do|what are your features|how do (i|you) use (this|you))$") to Local.Help,
        r("^(who are you|what('s| is) your name|what are you)$") to Local.WhoAreYou
    )

    private val approve = r("^(yes|yeah|yep|sure|ok(ay)?|go ahead|continue|do it|approve(d)?|confirm(ed)?|proceed|send it|oo|sige)( please| it| now)?$")

    /** @param awaitingApproval true when a task is paused waiting for the user's okay. */
    fun match(text: String, awaitingApproval: Boolean = false): Local? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?', ',').replace(Regex("^(hey |ok |okay |um |uh )+"), "").replace(Regex("\\s+"), " ")
        if (t.isBlank()) return null
        if (awaitingApproval && approve.matches(t)) return Local.Approve
        return rules.firstOrNull { it.first.matches(t) }?.second
    }

    const val HELP = "I can open apps and do things on your phone, like searching, playing music, or changing settings. " +
        "I can also answer questions. Just talk to me, and you can interrupt me anytime."
}
