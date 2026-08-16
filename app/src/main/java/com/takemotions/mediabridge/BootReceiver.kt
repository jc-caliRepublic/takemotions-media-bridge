package com.takemotions.mediabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Brings the media bridge back after a reboot or an app update.
 *
 * The switch is a saved preference but the service is not: without this, the app would
 * come back reading "Enable media bridge: ON" with nothing actually listening on
 * :8766, and the glasses would silently see no media until the user opened the app and
 * toggled the switch off and on again.
 *
 * Captions are deliberately NOT restarted here — Android grants capture per session,
 * from a visible activity, so they can only ever be started by hand.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant || !Prefs.mediaEnabled(context)) return

        // Starting a foreground service from these two broadcasts is exempt from the
        // background-start restrictions, and "specialUse" is not among the types
        // Android 15+ blocks at boot.
        ContextCompat.startForegroundService(
            context,
            Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_START),
        )
    }
}
