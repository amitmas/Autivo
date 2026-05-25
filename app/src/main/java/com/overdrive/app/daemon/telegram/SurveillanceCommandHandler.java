package com.overdrive.app.daemon.telegram;

import org.json.JSONObject;

/**
 * Handles surveillance commands: /start, /stop, /status
 */
public class SurveillanceCommandHandler implements TelegramCommandHandler {
    
    private static final int SURVEILLANCE_IPC_PORT = 19877;
    
    @Override
    public boolean canHandle(String command) {
        return "/start".equals(command) || "/stop".equals(command) || "/status".equals(command);
    }
    
    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String cmd = args[0].toLowerCase();
        
        switch (cmd) {
            case "/start":
                handleStart(chatId, ctx);
                break;
            case "/stop":
                handleStop(chatId, ctx);
                break;
            case "/status":
                handleStatus(chatId, ctx);
                break;
        }
    }
    
    private void handleStart(long chatId, CommandContext ctx) {
        JSONObject response = sendSurveillanceCommand("START", ctx);
        if (response != null && response.optBoolean("success", false)) {
            String[][][] buttons = {{{"⛔ Stop", "cmd:/stop"}, {"📊 Status", "cmd:/status"}}};
            ctx.sendMessageWithButtons(chatId, "✅ Surveillance started", buttons);
        } else {
            ctx.sendMessage(chatId, "⚠️ Failed to start surveillance");
        }
    }
    
    private void handleStop(long chatId, CommandContext ctx) {
        JSONObject response = sendSurveillanceCommand("STOP", ctx);
        if (response != null && response.optBoolean("success", false)) {
            String[][][] buttons = {{{"✅ Start", "cmd:/start"}, {"📊 Status", "cmd:/status"}}};
            ctx.sendMessageWithButtons(chatId, "⛔ Surveillance stopped", buttons);
        } else {
            ctx.sendMessage(chatId, "⚠️ Failed to stop surveillance");
        }
    }
    
    private void handleStatus(long chatId, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Status*\n\n");
        
        // Surveillance status
        JSONObject survStatus = sendSurveillanceCommand("STATUS", ctx);
        boolean survEnabled = survStatus != null && survStatus.optBoolean("enabled", false);
        sb.append("*Surveillance:* ").append(survEnabled ? "✅ Active" : "⛔ Inactive").append("\n");
        
        // Temperature
        sb.append("*Temp:* ").append(getTemperature(ctx)).append("\n\n");
        
        // All daemons - check all known process names
        sb.append("*Daemons:*\n");
        String[][] allDaemons = {
            {"byd_cam_daemon", "Camera"},
            {"acc_sentry_daemon", "ACC Sentry"},
            {"sentry_daemon", "Sentry"},
            {"telegram_bot_daemon", "Telegram"},
            {"SurveillanceDaemon", "Surveillance"},
            {"cloudflared", "Cloudflare Tunnel"},
            {"zrok", "Zrok Tunnel"},
            {"sing-box", "Sing-Box"}
        };
        
        int runningCount = 0;
        for (String[] d : allDaemons) {
            if (isDaemonRunning(d[0], ctx)) {
                sb.append("✅ ").append(d[1]).append("\n");
                runningCount++;
            }
        }
        
        if (runningCount == 0) {
            sb.append("_No daemons running_\n");
        }
        
        // Buttons
        String[][][] buttons;
        if (survEnabled) {
            buttons = new String[][][]{{{"⛔ Stop Surveillance", "cmd:/stop"}, {"📹 Events", "cmd:/events"}}};
        } else {
            buttons = new String[][][]{{{"✅ Start Surveillance", "cmd:/start"}, {"📹 Events", "cmd:/events"}}};
        }
        
        ctx.sendMessageWithButtons(chatId, sb.toString(), buttons);
    }
    
    private JSONObject sendSurveillanceCommand(String command, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", command);
            return ctx.sendIpcCommand(SURVEILLANCE_IPC_PORT, cmd);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static final String[] CPU_THERMAL_KEYWORDS = {
        "cpu", "soc", "cluster", "little", "big", "prime",
        "cpu-thermal", "cpu_thermal", "cpuss", "cpuss-0", "cpuss-1",
        "cpu-0-0", "cpu-0-1", "cpu-1-0", "cpu-1-1", "tsens_tz_sensor"
    };

    private String getTemperature(CommandContext ctx) {
        double c = readCpuTemperatureCelsius();
        if (c <= 0) return "N/A";
        String emoji = c > 60 ? "🔥" : (c > 45 ? "🌡️" : "✅");
        return String.format("%s %.0f°C", emoji, c);
    }

    private double readCpuTemperatureCelsius() {
        for (int i = 0; i < 30; i++) {
            try {
                java.io.File typeFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/type");
                if (!typeFile.exists()) continue;
                String type;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(typeFile))) {
                    type = br.readLine();
                }
                if (type == null) continue;
                String typeLower = type.toLowerCase().trim();
                boolean match = false;
                for (String kw : CPU_THERMAL_KEYWORDS) {
                    if (typeLower.contains(kw)) { match = true; break; }
                }
                if (!match) continue;

                java.io.File tempFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (!tempFile.exists()) continue;
                String tempLine;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(tempFile))) {
                    tempLine = br.readLine();
                }
                if (tempLine == null || tempLine.trim().isEmpty()) continue;
                double raw = Double.parseDouble(tempLine.trim());
                double result = raw > 1000 ? raw / 1000.0 : raw;
                if (result >= 10 && result <= 120) return result;
            } catch (Exception ignored) {}
        }
        return 0;
    }
    
    private boolean isDaemonRunning(String processName, CommandContext ctx) {
        // Use grep -F for fixed string matching (handles hyphens in process names like sing-box)
        String output = ctx.execShell("ps -A | grep -F '" + processName + "' | grep -v grep");
        return output != null && !output.trim().isEmpty();
    }
}
