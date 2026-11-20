package com.rusty.nightfall.drawer

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Replaces Samsung's screens with Nightfall's, as seamlessly as an app can without root:
 *   swipe up (or Home on the face) -> app list  -> Nightfall drawer   (slides up, over Samsung's list)
 *   swipe right                    -> notifications -> Nightfall inbox (slides in from the left)
 *   swipe down                     -> quick panel   -> Nightfall panel (panel dismissed, ours drops in)
 *
 * Tricks: the Nightfall host activity stays alive in this same warm process (no splash, no reload); it is launched
 * straight over Samsung's screen with a slide that continues the swipe (no detour to the face first); leaving it
 * presses Home, which resets Samsung's screen underneath to the face. Only Samsung's two system-UI packages are
 * watched, at most every 50 ms, and nothing is checked while the screen is off.
 */
class SwipeRedirectService : AccessibilityService() {
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastFire = 0L
    private var bypassUntil = 0L
    private var bypassSurface: Surface? = null
    /** What Samsung is showing underneath our screen (so leaving knows to reset it), or null. */
    private var underlay: Surface? = null

    override fun onServiceConnected() {
        instance = this
        AppsCache.warm(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { runCatching { Steps.register(this@SwipeRedirectService) } }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!power.isInteractive) return                       // always-on redraws can't be any of these screens
        val now = SystemClock.uptimeMillis()
        if (now - lastFire < 900) return
        val bypassed = if (now < bypassUntil) bypassSurface else null
        val pkg = event.packageName?.toString() ?: return

        if (pkg == QS_PKG) {
            if (bypassed == Surface.PANEL || !isOn(this, Surface.PANEL)) return
            val root = rootInActiveWindow ?: return
            if (root.packageName == QS_PKG && visible(root, QS_ID)) fire(Surface.PANEL, now)
            return
        }
        if (pkg != SYSUI) return
        val root = rootInActiveWindow ?: return
        if (root.packageName != SYSUI) return
        when {
            bypassed != Surface.DRAWER && isOn(this, Surface.DRAWER) && visible(root, APPS_GRID) -> fire(Surface.DRAWER, now)
            bypassed != Surface.INBOX && isOn(this, Surface.INBOX) && isNotificationList(root) -> fire(Surface.INBOX, now)
        }
    }

    private fun visible(root: AccessibilityNodeInfo, id: String) =
        root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }

    private fun isNotificationList(root: AccessibilityNodeInfo): Boolean {
        if (!visible(root, NOTIF_LIST) || visible(root, TILE) || visible(root, APPS_GRID)) return false
        return visible(root, NOTIF_CARD) || root.findAccessibilityNodeInfosByText("notification").isNotEmpty()
    }

    private fun fire(s: Surface, now: Long) {
        lastFire = now
        Log.i(TAG, "Samsung ${s.name.lowercase()} -> Nightfall")
        val anim = when (s) {
            Surface.DRAWER -> R.anim.in_from_bottom
            Surface.INBOX -> R.anim.in_from_left
            Surface.PANEL -> R.anim.in_from_top
        }
        val open = { startActivity(NightfallActivity.intent(this, s), ActivityOptions.makeCustomAnimation(this, anim, R.anim.stay).toBundle()) }
        if (s == Surface.PANEL) {
            // Samsung's quick panel is an overlay above every app, so it has to close first (Back does it; the
            // "dismiss shade" action doesn't on One UI Watch). Ours drops in right behind it.
            underlay = null
            performGlobalAction(GLOBAL_ACTION_BACK)
            main.postDelayed(open, 40)
        } else {
            underlay = s
            open()
        }
    }

    /** Leaving a Nightfall screen: if Samsung's list is still underneath, Home resets it to the face. */
    private fun leave(): Boolean {
        if (underlay == null) return false
        underlay = null
        bypassUntil = SystemClock.uptimeMillis() + 800
        bypassSurface = null
        lastFire = SystemClock.uptimeMillis()                  // don't catch Samsung's list on its way out
        performGlobalAction(GLOBAL_ACTION_HOME)
        return true
    }

    /** "Samsung's version" buttons: let the real screen through for a few seconds. */
    private fun openSamsung(s: Surface): Boolean {
        val action = when (s) {
            Surface.PANEL -> GLOBAL_ACTION_QUICK_SETTINGS
            Surface.INBOX -> GLOBAL_ACTION_NOTIFICATIONS
            Surface.DRAWER -> return false
        }
        bypassSurface = s
        bypassUntil = SystemClock.uptimeMillis() + 8000
        underlay = null
        return performGlobalAction(action)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "NightfallSwipe"
        private const val SYSUI = "com.samsung.android.wearable.sysui"
        private const val QS_PKG = "com.google.android.apps.wearable.systemui"
        private const val APPS_GRID = "$SYSUI:id/apps_recycler_view"
        private const val NOTIF_LIST = "$SYSUI:id/recycler_view"
        private const val NOTIF_CARD = "$SYSUI:id/chunk_view"
        private const val TILE = "$SYSUI:id/item_tile"
        private const val QS_ID = "$QS_PKG:id/quick_panel"

        @Volatile private var instance: SwipeRedirectService? = null

        fun leaveToFace(): Boolean = instance?.leave() ?: false
        fun openSamsung(s: Surface): Boolean = instance?.openSamsung(s) ?: false

        fun isOn(ctx: Context, s: Surface) = prefs(ctx).getBoolean("redirect_${s.name.lowercase()}", true)
        fun setOn(ctx: Context, s: Surface, on: Boolean) = prefs(ctx).edit().putBoolean("redirect_${s.name.lowercase()}", on).apply()

        /** Whether the user has turned the service on in Settings > Accessibility. */
        fun isServiceEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
