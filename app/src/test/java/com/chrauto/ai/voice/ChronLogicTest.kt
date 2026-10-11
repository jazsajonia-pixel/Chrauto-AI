package com.chrauto.ai.voice

import com.chrauto.ai.agent.Shortcuts
import org.junit.Assert.*
import org.junit.Test

class ChronLogicTest {
    @Test fun wakeWordAtStartReturnsCommand() {
        assertEquals("open YouTube", WakeWord.find("Chron open YouTube")?.command)
        assertEquals("open YouTube", WakeWord.find("Chron, please open YouTube")?.command)
        assertEquals("what's the time", WakeWord.find("hey Kron what's the time")?.command)
        assertEquals("search cats", WakeWord.find("Cron, search cats")?.command)
    }

    @Test fun wakeOnlyIsDetected() {
        assertTrue(WakeWord.find("Chron")!!.wakeOnly)
        assertTrue(WakeWord.find("hey chron!")!!.wakeOnly)
    }

    @Test fun chromeOnlyCountsAfterGreeting() {
        assertNull(WakeWord.find("open chrome"))
        assertNull(WakeWord.find("chrome is my browser"))
        assertEquals("open settings", WakeWord.find("hey chrome open settings")?.command)
    }

    @Test fun ignoresWakeWordLaterInSentence() {
        assertNull(WakeWord.find("I told my friend about chron yesterday"))
        assertNull(WakeWord.find("the weather is nice"))
    }

    @Test fun customWakeNameWithFuzzyMatch() {
        assertEquals("open maps", WakeWord.find("Jarvis open maps", "Jarvis")?.command)
        assertEquals("open maps", WakeWord.find("Jarviss open maps", "Jarvis")?.command)
        assertNull(WakeWord.find("Chron open maps", "Jarvis"))
    }

    @Test fun quickRouteRecognisesStopAndTasks() {
        assertTrue(CommandRouter.quickRoute("stop") is Route.Stop)
        assertTrue(CommandRouter.quickRoute("Cancel that.") is Route.Stop)
        assertEquals(Route.Task("open YouTube and play lo-fi"), CommandRouter.quickRoute("open YouTube and play lo-fi"))
        assertNull(CommandRouter.quickRoute("what is the capital of Japan?"))
    }

    @Test fun routerParsesModelJson() {
        assertEquals(Route.Answer("Tokyo!"), CommandRouter.parse("""{"type":"answer","reply":"Tokyo!"}"""))
        assertEquals(Route.Task("Open Spotify", "On it"), CommandRouter.parse("""```json
{"type":"task","task":"Open Spotify","reply":"On it"}
```"""))
        assertNull(CommandRouter.parse("not json"))
    }

    private val apps = mapOf("YouTube" to "com.google.android.youtube", "Chrome" to "com.android.chrome", "Settings" to "com.android.settings", "Google Maps" to "com.google.android.apps.maps")

    @Test fun openShortcutFindsAppAndRest() {
        val s = Shortcuts.openApp("Open Chrome and search for Kotlin Android development tutorials.", apps)!!
        assertEquals("com.android.chrome", s.packageName)
        assertEquals("search for Kotlin Android development tutorials", s.rest)
        assertEquals("", Shortcuts.openApp("open youtube", apps)!!.rest)
        assertEquals("com.google.android.apps.maps", Shortcuts.openApp("launch google maps app", apps)!!.packageName)
        assertEquals("com.google.android.youtube", Shortcuts.openApp("open you tube", apps)!!.packageName)
    }

    @Test fun openShortcutIgnoresUnknownAppsAndOtherVerbs() {
        assertNull(Shortcuts.openApp("open the pod bay doors", apps))
        assertNull(Shortcuts.openApp("search for chrome tips", apps))
    }
}
