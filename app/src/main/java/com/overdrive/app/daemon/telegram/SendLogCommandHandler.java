package com.overdrive.app.daemon.telegram;

import com.overdrive.app.logging.DaemonLogPaths;

import org.json.JSONObject;

/**
 * Handles /sendlog — uploads a single daemon's log to the Cloudflare Worker
 * (via IPC UPLOAD_LOG → SurveillanceIpcServer → LogUploader) and replies with
 * the short retrieval code the user reads back to the maintainer.
 *
 * Braveheart-only in practice: on a plain release/alpha build LogUploader has
 * no Worker URL configured, so the server returns "not available in this build"
 * and we surface that cleanly.
 *
 *   /sendlog            → list the daemon keys + tap-to-send buttons
 *   /sendlog <daemon>   → upload that daemon's log, reply with the code
 */
public class SendLogCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19877;
    // Upload is network-bound (read log → POST to CF). 35s sits above
    // LogUploader's bounded worst case (proxy 12s + direct-retry 12s = 24s,
    // via its callTimeout) so the IPC read never races a still-running upload.
    private static final int UPLOAD_TIMEOUT_MS = 35_000;

    @Override
    public boolean canHandle(String command) {
        return "/sendlog".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        if (args.length < 2 || args[1].trim().isEmpty()) {
            // No daemon specified — show the list as tap buttons.
            StringBuilder sb = new StringBuilder();
            sb.append("📄 *Send a daemon log*\n\nPick a daemon (or `/sendlog <name>`):");
            java.util.List<String[][]> rows = new java.util.ArrayList<>();
            for (String key : DaemonLogPaths.keys()) {
                rows.add(new String[][] {{key, "cmd:/sendlog " + key}});
            }
            ctx.sendMessageWithButtons(chatId, sb.toString(), rows.toArray(new String[0][][]));
            return;
        }

        String daemon = args[1].trim().toLowerCase();
        if (DaemonLogPaths.pathFor(daemon) == null) {
            ctx.sendMessage(chatId, "❌ Unknown daemon `" + daemon + "`.\nTry: " + DaemonLogPaths.keyList());
            return;
        }

        ctx.sendMessage(chatId, "⏳ Uploading *" + daemon + "* log…");

        JSONObject req = new JSONObject();
        try {
            req.put("command", "UPLOAD_LOG");
            req.put("daemon", daemon);
        } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, UPLOAD_TIMEOUT_MS);
        if (resp == null) {
            ctx.sendMessage(chatId, "⚠️ Could not reach the log service. " +
                    "The camera daemon may not be running (`/daemon camera start`).");
            return;
        }
        if (!resp.optBoolean("success", false)) {
            ctx.sendMessage(chatId, "⚠️ Upload failed: " + resp.optString("error", "unknown"));
            return;
        }

        String code = resp.optString("code", "");
        ctx.sendMessage(chatId,
                "✅ *" + daemon + "* log uploaded.\n\n" +
                "Share this code with us, plus a note on what went wrong:\n`" + code + "`\n\n" +
                "Report on:\n" +
                "• Discord: https://discord.gg/PZutk9fg4h\n" +
                "• GitHub: https://github.com/yash-srivastava/Overdrive-release/issues\n" +
                "• WhatsApp: https://chat.whatsapp.com/HChmriCWgr9KwAtE6OEkiM\n\n" +
                "_Secrets were redacted before upload. The log auto-expires._");
    }
}
