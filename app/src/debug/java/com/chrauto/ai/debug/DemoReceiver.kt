package com.chrauto.ai.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chrauto.ai.agent.AgentController
import com.chrauto.ai.voice.ChronEngine

/**
 * Debug-only: `adb shell am broadcast -n com.chrauto.ai/.debug.DemoReceiver` plays a scripted assistant run.
 * Add `--es chron listening|reply|live|duplex|passive|off` to preview Chron voice states for screenshots.
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentController.init(context)
        val convo = listOf("what's a good name for a cat" to "How about Mochi? It's cute and short.")
        when (intent.getStringExtra("chron")) {
            "passive" -> ChronEngine.preview(ChronEngine.State(ChronEngine.Mode.PASSIVE, wakeOn = true))
            "listening" -> ChronEngine.preview(ChronEngine.State(ChronEngine.Mode.ACTIVE, partial = "open YouTube and play lo-fi", wakeOn = true))
            "reply" -> ChronEngine.preview(ChronEngine.State(ChronEngine.Mode.SPEAKING, heard = convo[0].first, reply = convo[0].second, wakeOn = true, conversation = convo))
            "live" -> ChronEngine.preview(ChronEngine.State(ChronEngine.Mode.ACTIVE, partial = "and make it play on shuffle", wakeOn = true, live = true, conversation = convo), level = 0.8f)
            "duplex" -> ChronEngine.preview(ChronEngine.State(ChronEngine.Mode.SPEAKING, heard = "tell me a fun fact", reply = "Octopuses have three hearts, and two of them stop when they swim.", wakeOn = true, live = true, duplex = true, conversation = convo))
            "off" -> ChronEngine.preview(ChronEngine.State())
            else -> AgentController.demo()
        }
    }
}
