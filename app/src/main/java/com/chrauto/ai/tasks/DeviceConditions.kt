package com.chrauto.ai.tasks

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.chrauto.ai.accessibility.AccessibilityBridge

/** What a task needs before it can touch another app's screen. */
data class Requirements(val needsNetwork: Boolean)

enum class Blocker(val message: String, val resumesAutomatically: Boolean) {
    ACCESSIBILITY_OFF("Accessibility service is off. Turn on Chrauto AI in Settings → Accessibility.", true),
    SCREEN_OFF("Screen is off. The task continues when you turn the screen on and unlock.", true),
    LOCKED("Phone is locked. The task continues after you unlock.", true),
    NO_NETWORK("No internet connection for the AI. Will check again shortly.", true)
}

fun interface DeviceConditions { fun blocker(req: Requirements): Blocker? }

/**
 * Real checks. Android does not let an accessibility service act inside other apps behind the lock
 * screen, and a screen that is off shows nothing to read, so these are hard requirements rather
 * than something to work around.
 */
class AndroidDeviceConditions(context: Context) : DeviceConditions {
    private val app = context.applicationContext
    override fun blocker(req: Requirements): Blocker? {
        if (!AccessibilityBridge.isReady()) return Blocker.ACCESSIBILITY_OFF
        val power = app.getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return Blocker.SCREEN_OFF
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked) return Blocker.LOCKED
        if (req.needsNetwork && !online()) return Blocker.NO_NETWORK
        return null
    }

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
