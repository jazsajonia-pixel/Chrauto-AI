package com.chrauto.ai.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chrauto.ai.agent.AgentController

/** Debug-only: `adb shell am broadcast -n com.chrauto.ai/.debug.DemoReceiver` plays a scripted assistant run. */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentController.init(context)
        AgentController.demo()
    }
}
