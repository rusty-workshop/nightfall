package com.rusty.nightfall.drawer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
 *   swipe up (or Home on the face) -> app list      -> Nightfall drawer
 *   swipe right                    -> notifications -> Nightfall inbox
 *   swipe down                     -> quick panel   -> Nightfall panel
 *
 * How the swap is hidden:
 *  1. React to Samsung's *first* event for each screen, matched from the event itself where possible (the quick
 *     panel announces itself by text; the app list by the id of the view that changed), so there's no extra lookup.
 *  2. A curtain (an accessibility overlay, above everything incl. the quick panel, added once up front) shows the
 *     Nightfall screen in the next frame, from a snapshot of its last frame with the clock redrawn live, sliding in
 *     the way the finger went.
 *  3. Underneath, the always-alive Nightfall activity comes to the front with no animation; once it has drawn its
 *     first frame it tells us and the curtain disappears - the two look identical, so the hand-off is invisible.
 * Leaving presses Home, which resets Samsung's screen underneath to the face. Only Samsung's two system-UI packages
 * are watched, and nothing is checked while the screen is off.
 */
class SwipeRedirectService : AccessibilityService() {
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var curtain: Curtain? = null
    private var lastFire = 0L
    private var lastRootCheck = 0L
    private var bypassUntil = 0L
    private var bypassSurface: Surface? = null
    /** What Samsung is showing underneath our screen (so leaving knows to reset it), or null. */
    private var underlay: Surface? = null
    private val curtainTimeout = Runnable { curtain?.hide(); if (qsClosing) { qsClosing = false; afterQs?.invoke(); afterQs = null } }
    /** Samsung's quick panel is still on screen behind the curtain (it ignores Back while it's sliding in). */
    private var qsClosing = false
    private var surfaceReady = false
    private var qsTries = 0
    private var afterQs: (() -> Unit)? = null
    private val closeQs = object : Runnable {
        override fun run() {
            if (!qsClosing) return
            if (!qsOpen() || qsTries++ > 30) {
                qsClosing = false
                afterQs?.invoke(); afterQs = null           // only now bring ours up: no stray Back can reach it
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            main.postDelayed(this, 50)
        }
    }

    /** Whether Samsung's quick panel is on screen, looking at every window (it isn't always the focused one). */
    private fun qsOpen(): Boolean = runCatching {
        windows.any { w -> w.root?.let { it.packageName == QS_PKG && it.findAccessibilityNodeInfosByViewId(QS_ID).any { n -> n.isVisibleToUser } } == true }
    }.getOrDefault(false)

    override fun onServiceConnected() {
        instance = this
        AppsCache.warm(this)
        curtain = Curtain(this).also { it.attach() }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { runCatching { Steps.register(this@SwipeRedirectService) } }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        curtain?.detach(); curtain = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!power.isInteractive) { curtain?.hide(); return }      // always-on redraws can't be any of these screens
        val now = SystemClock.uptimeMillis()
        if (now - lastFire < 900) return
        val bypassed = if (now < bypassUntil) bypassSurface else null
        val pkg = event.packageName?.toString() ?: return
        // while you're using Samsung's own screen (via the "Samsung" buttons), keep letting it through
        if (bypassed != null) bypassUntil = now + 3000

        if (pkg == QS_PKG) {
            if (bypassed == Surface.PANEL || !isOn(this, Surface.PANEL)) return
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            // the panel says so itself ("Quick panel., Page 1 of 4") the moment it starts sliding in
            val says = event.text.any { it?.contains("Quick panel", ignoreCase = true) == true }
            if (says || rootInActiveWindow?.let { it.packageName == QS_PKG && visible(it, QS_ID) } == true) fire(Surface.PANEL, now)
            return
        }
        if (pkg != SYSUI) return

        // fast path: the very first event of the app list comes from its tray
        val src = runCatching { event.source?.viewIdResourceName }.getOrNull()
        if (src == TRAY || src == APPS_GRID) {
            if (bypassed != Surface.DRAWER && isOn(this, Surface.DRAWER)) fire(Surface.DRAWER, now)
            return
        }
        // otherwise look (rate-limited: scrolling Samsung's lists sends a stream of events)
        if (now - lastRootCheck < 40) return
        lastRootCheck = now
        val root = rootInActiveWindow ?: return
        if (root.packageName != SYSUI) return
        when {
            bypassed != Surface.DRAWER && isOn(this, Surface.DRAWER) && visible(root, APPS_GRID) -> fire(Surface.DRAWER, now)
            bypassed != Surface.INBOX && isOn(this, Surface.INBOX) && isNotificationList(root) -> fire(Surface.INBOX, now)
        }
    }

    private fun visible(root: AccessibilityNodeInfo, id: String) =
        root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }

    /** Samsung's notification list exists (it slides in with the finger, so don't wait until it counts as visible). */
    private fun isNotificationList(root: AccessibilityNodeInfo): Boolean {
        if (root.findAccessibilityNodeInfosByViewId(NOTIF_LIST).isEmpty()) return false
        return root.findAccessibilityNodeInfosByViewId(TILE).isEmpty() && root.findAccessibilityNodeInfosByViewId(APPS_GRID).isEmpty()
    }

    private fun fire(s: Surface, now: Long) {
        lastFire = now
        Log.i(TAG, "Samsung ${s.name.lowercase()} -> Nightfall")
        when (s) {   // the curtain goes up in the very next frame, sliding the way the finger went
            Surface.DRAWER -> curtain?.show(s, 0f, 0.35f)
            Surface.INBOX -> curtain?.show(s, -1f, 0f)
            Surface.PANEL -> curtain?.show(s, 0f, -1f)
        }
        main.removeCallbacks(curtainTimeout)
        main.postDelayed(curtainTimeout, 2000)                     // never leave it up if something goes wrong
        surfaceReady = false
        val open = { startActivity(NightfallActivity.intent(this, s).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
        if (s == Surface.PANEL) {
            // Samsung's quick panel is an overlay above every app and ignores Back until it has finished sliding
            // in, so keep pressing Back (behind the curtain) until it's really gone; ours comes up meanwhile.
            underlay = null
            qsClosing = true
            qsTries = 0
            afterQs = open
            main.post(closeQs)
        } else {
            underlay = s
            open()
        }
    }

    /** The Nightfall screen has drawn its first frame behind the curtain: take the curtain down. */
    private fun shown() {
        surfaceReady = true
        maybeLift()
    }

    /** Lift the curtain only once ours is drawn AND nothing of Samsung's is left on top of it. */
    private fun maybeLift() {
        if (!surfaceReady || qsClosing) return
        main.removeCallbacks(curtainTimeout)
        main.postDelayed({ curtain?.hide() }, 16)
    }

    /** Leaving a Nightfall screen: if Samsung's list is still underneath, Home resets it to the face. */
    private fun leave(): Boolean {
        curtain?.hide()
        if (underlay == null) return false
        underlay = null
        bypassUntil = SystemClock.uptimeMillis() + 800
        bypassSurface = null
        lastFire = SystemClock.uptimeMillis()                      // don't catch Samsung's list on its way out
        performGlobalAction(GLOBAL_ACTION_HOME)
        return true
    }

    /**
     * "Samsung" buttons. One UI Watch ignores the accessibility "open quick settings / notifications" actions, so:
     * step aside, and if Samsung's screen isn't already sitting underneath us, swipe for it for real.
     */
    private fun openSamsung(s: Surface, host: android.app.Activity): Boolean {
        if (s == Surface.DRAWER) return false
        val alreadyUnder = underlay == s                    // we came in over it: it's right there underneath
        bypassSurface = s
        bypassUntil = SystemClock.uptimeMillis() + 3000
        underlay = null
        curtain?.hide()
        host.moveTaskToBack(true)
        if (!alreadyUnder) main.postDelayed({ swipeFor(s) }, 350)
        return true
    }

    private fun swipeFor(s: Surface) {
        bypassUntil = SystemClock.uptimeMillis() + 3000
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val path = android.graphics.Path().apply {
            if (s == Surface.PANEL) { moveTo(w / 2, 1f); lineTo(w / 2, h * 0.75f) }     // down from the top edge
            else { moveTo(2f, h / 2); lineTo(w * 0.85f, h / 2) }                        // right from the left edge
        }
        dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 260)).build(), null, null)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "NightfallSwipe"
        private const val SYSUI = "com.samsung.android.wearable.sysui"
        private const val QS_PKG = "com.google.android.apps.wearable.systemui"
        private const val TRAY = "$SYSUI:id/launcher_tray"
        private const val APPS_GRID = "$SYSUI:id/apps_recycler_view"
        private const val NOTIF_LIST = "$SYSUI:id/recycler_view"
        private const val TILE = "$SYSUI:id/item_tile"
        private const val QS_ID = "$QS_PKG:id/quick_panel"

        @Volatile private var instance: SwipeRedirectService? = null

        fun leaveToFace(): Boolean = instance?.leave() ?: false
        fun openSamsung(s: Surface, host: android.app.Activity): Boolean = instance?.openSamsung(s, host) ?: false
        fun surfaceShown() { instance?.let { svc -> svc.main.post { svc.shown() } } }

        fun isOn(ctx: Context, s: Surface) = prefs(ctx).getBoolean("redirect_${s.name.lowercase()}", true)
        fun setOn(ctx: Context, s: Surface, on: Boolean) = prefs(ctx).edit().putBoolean("redirect_${s.name.lowercase()}", on).apply()

        /** Whether the user has turned the service on in Settings > Accessibility. */
        fun isServiceEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
