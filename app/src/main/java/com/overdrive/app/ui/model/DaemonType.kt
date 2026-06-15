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
enum class DaemonType(
    val displayName: String,
    val processName: String,
    /**
     * Absolute path of this daemon's "user stopped it — keep it down"
     * sentinel file. This is the ONE durable, cross-UID signal that a stop
     * was user-initiated (as opposed to a crash): it lives in
     * /data/local/tmp, is written `chmod 666` so both the app UID and the
     * UID-2000 daemon family can read it, and is honored by BOTH the
     * watchdog shell scripts (which exit instead of respawning) AND the
     * app-side 30s health-check (which skips relaunch). A crash leaves NO
     * sentinel, so the watchdog / health-check still revives a daemon that
     * died on its own — which is the whole point of the auto-restart.
     *
     * Filenames are historical and do NOT all match [processName] (camera
     * uses `camera_daemon.disabled`, not `byd_cam_daemon.disabled`); this
     * map is the single source of truth — never re-derive a sentinel name
     * from the process name.
     */
    val sentinelPath: String
) {
    CAMERA_DAEMON("Camera Daemon", "byd_cam_daemon", "/data/local/tmp/camera_daemon.disabled"),
    SENTRY_DAEMON("Sentry Daemon", "sentry_daemon", "/data/local/tmp/sentry_daemon.disabled"),
    ACC_SENTRY_DAEMON("ACC Sentry", "acc_sentry_daemon", "/data/local/tmp/acc_sentry_daemon.disabled"),
    SINGBOX_PROXY("Sing-box Proxy", "sing-box", "/data/local/tmp/singbox.disabled"),
    CLOUDFLARED_TUNNEL("Cloudflared Tunnel", "cloudflared", "/data/local/tmp/cloudflared.disabled"),
    ZROK_TUNNEL("Zrok Tunnel", "zrok", "/data/local/tmp/zrok.disabled"),
    TAILSCALE_TUNNEL("Tailscale Tunnel", "tailscaled", "/data/local/tmp/tailscale.disabled"),
    TELEGRAM_DAEMON("Telegram Bot", "telegram_bot_daemon", "/data/local/tmp/telegram_bot_daemon.disabled")
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
