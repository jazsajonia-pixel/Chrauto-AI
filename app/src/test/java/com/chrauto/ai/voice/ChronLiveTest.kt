package com.chrauto.ai.voice

import com.chrauto.ai.provider.Sse
import org.junit.Assert.*
import org.junit.Test

class ChronLiveTest {
    // ---- Barge-in echo rejection ----
    private val spoken = "Octopuses have three hearts, and two of them stop beating when they swim."

    @Test fun echoOfOwnSpeechIsIgnored() {
        assertFalse(EchoGuard.isUser("octopuses have three hearts", spoken, headset = false))
        assertFalse(EchoGuard.isUser("two of them stop beating", spoken, headset = false))
        assertFalse(EchoGuard.isUser("", spoken, headset = true))
    }

    @Test fun userTalkingOverChronIsDetected() {
        assertTrue(EchoGuard.isUser("open spotify and play jazz", spoken, headset = false))
        assertTrue(EchoGuard.isUser("wait", spoken, headset = false))
        assertTrue(EchoGuard.isUser("hey chron open maps", spoken, headset = false))
        assertTrue(EchoGuard.isUser("cool thanks", spoken, headset = true))
    }

    @Test fun interruptWordThatChronIsSayingIsNotABargeIn() {
        // "stop" is part of Chron's own sentence, so hearing it alone is probably echo.
        assertFalse(EchoGuard.isUser("stop beating", spoken, headset = false))
    }

    @Test fun loudspeakerNeedsSeveralNewWords() {
        assertFalse(EchoGuard.isUser("hearts banana", spoken, headset = false))
        assertTrue(EchoGuard.isUser("hearts banana", spoken, headset = true))
    }

    @Test fun stripEchoKeepsOnlyTheUsersWords() {
        assertEquals("open youtube", EchoGuard.stripEcho("two of them stop open youtube", spoken))
        assertEquals("open maps", EchoGuard.stripEcho("chron open maps", spoken))
        // Short overlaps are kept: they're probably the user's own words.
        assertEquals("stop it", EchoGuard.stripEcho("stop it", spoken))
        assertEquals("three hearts", EchoGuard.stripEcho("three hearts", spoken))
    }

    // ---- Sentence streaming ----
    @Test fun chunkerSpeaksFirstSentenceEarly() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.push("Tokyo is the capital"))
        assertEquals(listOf("Tokyo is the capital of Japan."), c.push(" of Japan. It has"))
        assertEquals(listOf("It has about fourteen million people."), c.push(" about fourteen million people.") + c.flush())
    }

    @Test fun chunkerMergesTinySentencesAndStripsMarkdown() {
        val c = SentenceChunker()
        val out = c.push("Sure! **Here** you go. ") + c.flush()
        assertEquals(listOf("Sure! Here you go."), out)
    }

    @Test fun chunkerCutsVeryLongSentences() {
        val c = SentenceChunker(maxChars = 40)
        val out = c.push("one two three four five six seven eight, nine ten eleven twelve thirteen")
        assertTrue(out.isNotEmpty())
        assertTrue(out.first().length <= 41)
    }

    // ---- Streamed reply protocol ----
    @Test fun answerStreamsAfterHeader() {
        val r = ReplyStream()
        assertEquals("", r.push("ANS"))
        assertNull(r.kind)
        assertEquals("Tokyo", r.push("WER: Tokyo"))
        assertEquals(ReplyStream.Kind.ANSWER, r.kind)
        assertEquals(" is big.", r.push(" is big."))
        assertEquals(Route.Answer("Tokyo is big."), r.finish())
    }

    @Test fun taskIsParsedWithAck() {
        val r = ReplyStream()
        r.push("TASK: Open Spotify and play jazz "); r.push("|| On it")
        assertEquals("", r.answer)
        assertEquals(Route.Task("Open Spotify and play jazz", "On it"), r.finish())
    }

    @Test fun stopAndPlainTextAndJsonFallbacks() {
        assertEquals(Route.Stop("Okay."), ReplyStream().apply { push("STOP: Okay.") }.finish())
        val plain = ReplyStream(); val spokenText = plain.push("Hello there, friend!")
        assertEquals("Hello there, friend!", spokenText)
        assertEquals(Route.Answer("Hello there, friend!"), plain.finish())
        assertEquals(Route.Answer("Tokyo!"), ReplyStream().apply { push("""{"type":"answer","reply":"Tokyo!"}""") }.finish())
    }

    @Test fun thinkBlocksAreSkipped() {
        val r = ReplyStream()
        assertEquals("", r.push("<think>user wants a fact"))
        assertEquals("", r.push("</think>\n"))
        assertEquals("Hi!", r.push("ANSWER: Hi!"))
        assertEquals(Route.Answer("Hi!"), r.finish())
    }

    @Test fun markdownBoldHeaderWorks() {
        val r = ReplyStream()
        r.push("**ANSWER:** Sure thing.")
        assertEquals(ReplyStream.Kind.ANSWER, r.kind)
        assertTrue(r.finish().let { it is Route.Answer && it.reply.endsWith("Sure thing.") })
    }

    // ---- Instant local answers ----
    @Test fun localAnswers() {
        assertEquals(Local.Time, LocalAnswers.match("What time is it?"))
        assertEquals(Local.Date, LocalAnswers.match("what day is it today"))
        assertEquals(Local.Battery, LocalAnswers.match("what's my battery level"))
        assertEquals(Local.Repeat, LocalAnswers.match("say that again"))
        assertEquals(Local.Rate(true), LocalAnswers.match("talk a bit faster"))
        assertEquals(Local.Rate(false), LocalAnswers.match("speak slower"))
        assertEquals(Local.Goodbye, LocalAnswers.match("Okay bye"))
        assertEquals(Local.Goodbye, LocalAnswers.match("that's all"))
        assertEquals(Local.Forget, LocalAnswers.match("start over"))
        assertEquals(Local.Status, LocalAnswers.match("what are you doing?"))
        assertEquals(Local.Hush, LocalAnswers.match("stop talking"))
        assertNull(LocalAnswers.match("what is the capital of Japan"))
        assertNull(LocalAnswers.match("open youtube"))
    }

    @Test fun approvalOnlyWhenTaskIsPaused() {
        assertNull(LocalAnswers.match("yes go ahead"))
        assertEquals(Local.Approve, LocalAnswers.match("go ahead", awaitingApproval = true))
        assertEquals(Local.Approve, LocalAnswers.match("Yes please", awaitingApproval = true))
        assertEquals(Local.Approve, LocalAnswers.match("sige", awaitingApproval = true))
    }

    // ---- Streaming (SSE) parsers ----
    @Test fun sseDataLines() {
        assertEquals("{\"a\":1}", Sse.data("data: {\"a\":1}"))
        assertNull(Sse.data("data: [DONE]"))
        assertNull(Sse.data("event: ping"))
        assertNull(Sse.data(""))
    }

    @Test fun providerStreamDeltas() {
        assertEquals("Hel", Sse.openAiDelta("""{"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertNull(Sse.openAiDelta("""{"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertEquals("lo", Sse.geminiDelta("""{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true},{"text":"lo"}]}}]}"""))
        assertEquals("Hi", Sse.anthropicDelta("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""))
        assertNull(Sse.anthropicDelta("""{"type":"message_start","message":{}}"""))
        assertEquals("yo", Sse.responsesDelta("""{"type":"response.output_text.delta","delta":"yo"}"""))
    }
}
