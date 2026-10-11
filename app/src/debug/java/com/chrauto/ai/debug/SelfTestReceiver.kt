package com.chrauto.ai.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chrauto.ai.accessibility.AccessibilityBridge
import com.chrauto.ai.agent.AgentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only end-to-end check of the device layer (no AI):
 * `adb shell am broadcast -n com.chrauto.ai/.debug.SelfTestReceiver`, then read logcat tag ChrautoSelfTest.
 */
class SelfTestReceiver : BroadcastReceiver() {
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        GlobalScope.launch(Dispatchers.Main) {
            fun report(name: String, pass: Boolean, extra: String = "") { Log.i("ChrautoSelfTest", "SELFTEST $name=${if (pass) "PASS" else "FAIL"} $extra") }
            try {
                context.startActivity(Intent(context, TouchLabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                delay(2_000)
                val lab = TouchLabActivity.instance ?: return@launch report("setup", false, "activity missing")
                report("service", AccessibilityBridge.isReady())

                // 1) Tap a label inside a clickable row after the layout shifted (stale node path).
                var obs = AccessibilityBridge.observe()
                val label = obs?.nodes?.firstOrNull { it.text == "Open row label" }
                lab.insertBanner(); delay(600)
                val tap = label?.let { AccessibilityBridge.execute(AgentAction.Tap(it.id)) }
                delay(500)
                report("tap_stale_label", lab.status.text == "Row tapped", "result=${tap?.message} status=${lab.status.text}")

                // 2) Tap a button in the bottom corner, under where the floating bubble sits.
                obs = AccessibilityBridge.observe()
                val corner = obs?.nodes?.firstOrNull { it.text == "Corner button" }
                val tap2 = corner?.let { AccessibilityBridge.execute(AgentAction.Tap(it.id)) }
                delay(500)
                report("tap_under_bubble", lab.status.text == "Corner tapped", "result=${tap2?.message} status=${lab.status.text}")

                // 3) Type into the search field and submit it with Enter.
                obs = AccessibilityBridge.observe()
                val field = obs?.nodes?.firstOrNull { it.editable }
                val typed = field?.let { AccessibilityBridge.execute(AgentAction.TypeText(it.id, "kotlin tutorials")) }
                delay(300)
                val enter = field?.let { AccessibilityBridge.execute(AgentAction.PressEnter(it.id)) }
                delay(500)
                report("type_and_enter", lab.status.text == "Submitted: kotlin tutorials", "typed=${typed?.ok} enter=${enter?.message} status=${lab.status.text}")
                Log.i("ChrautoSelfTest", "SELFTEST done")
            } catch (t: Throwable) {
                report("crash", false, t.toString())
            } finally {
                pending.finish()
            }
        }
    }
}
