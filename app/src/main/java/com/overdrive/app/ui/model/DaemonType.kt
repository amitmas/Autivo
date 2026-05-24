package com.overdrive.app.ui.model

import android.content.Context
import com.overdrive.app.R

/**
 * Types of background daemons managed by the app.
 * Note: Location Sidecar is not included here as it auto-starts silently
 * and is managed by SentryDaemon, not shown in the UI.
 *
 * `displayName` is the stable English identifier — use it for log file
 * paths, telemetry, and other non-user-visible surfaces. UI surfaces
 * MUST use [localizedName] so the row label tracks the picked locale.
 */
enum class DaemonType(val displayName: String, val processName: String) {
    CAMERA_DAEMON("Camera Daemon", "byd_cam_daemon"),
    SENTRY_DAEMON("Sentry Daemon", "sentry_daemon"),
    ACC_SENTRY_DAEMON("ACC Sentry", "acc_sentry_daemon"),
    SINGBOX_PROXY("Sing-box Proxy", "sing-box"),
    CLOUDFLARED_TUNNEL("Cloudflared Tunnel", "cloudflared"),
    ZROK_TUNNEL("Zrok Tunnel", "zrok"),
    TAILSCALE_TUNNEL("Tailscale Tunnel", "tailscaled"),
    TELEGRAM_DAEMON("Telegram Bot", "telegram_bot_daemon")
}

fun DaemonType.localizedName(context: Context): String = context.getString(when (this) {
    DaemonType.CAMERA_DAEMON      -> R.string.daemon_name_camera
    DaemonType.SENTRY_DAEMON      -> R.string.daemon_name_surveillance
    DaemonType.ACC_SENTRY_DAEMON  -> R.string.daemon_name_acc_surveillance
    DaemonType.SINGBOX_PROXY      -> R.string.daemon_name_singbox
    DaemonType.CLOUDFLARED_TUNNEL -> R.string.daemon_name_cloudflared
    DaemonType.ZROK_TUNNEL        -> R.string.daemon_name_zrok
    DaemonType.TAILSCALE_TUNNEL   -> R.string.daemon_name_tailscale
    DaemonType.TELEGRAM_DAEMON    -> R.string.daemon_name_telegram
})
