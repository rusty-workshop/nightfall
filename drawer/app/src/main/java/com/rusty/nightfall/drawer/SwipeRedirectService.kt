package com.rusty.nightfall.drawer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Opens Nightfall Drawer whenever Samsung's app list ("Apps screen") appears, e.g. after swiping up on the face.
 *
 * Samsung's app list isn't its own activity - it lives inside the system UI - so this listens (only) to the system
 * UI package, rate-limited by notificationTimeout, and checks whether the apps grid is on screen. When it is: go
 * home (closing the list) and open the drawer. Off switch: the "SWIPE UP" row at the bottom of the drawer.
 */
class SwipeRedirectService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private var lastFire = 0L
    private var events = 0L
    private var checks = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        events++
        if (!isRedirectOn(this) || !power.isInteractive) return   // always-on redraws can't be the app list
        val now = SystemClock.uptimeMillis()
        if (now - lastFire < 1500) return
        checks++
        val root = rootInActiveWindow ?: return
        val grid = root.findAccessibilityNodeInfosByViewId(APPS_GRID)
        if (grid.none { it.isVisibleToUser }) return
        lastFire = now
        Log.i(TAG, "apps screen seen -> drawer (events=$events checks=$checks)")
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({
            startActivity(Intent(this, DrawerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, 60)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "NightfallSwipe"
        private const val APPS_GRID = "com.samsung.android.wearable.sysui:id/apps_recycler_view"

        fun isRedirectOn(ctx: Context) =
            ctx.getSharedPreferences("drawer", Context.MODE_PRIVATE).getBoolean("redirect", true)

        fun setRedirectOn(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences("drawer", Context.MODE_PRIVATE).edit().putBoolean("redirect", on).apply()

        /** Whether the user has turned the service on in Settings > Accessibility. */
        fun isServiceEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
