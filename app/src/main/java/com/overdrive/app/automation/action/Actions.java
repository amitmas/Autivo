package com.overdrive.app.automation.action;

import com.overdrive.app.automation.type.AppType;
import com.overdrive.app.automation.type.AudioType;
import com.overdrive.app.automation.type.ColourType;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.light.LightConstants;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class Actions {
    // Create LinkedHashMap to maintain the insertion order of actions to display in the frontend
    private final Map<String, Action> actions = new LinkedHashMap<>();

    /**
     * Initialize actions list with actions that can be selected
     */
    public Actions() {
        addAction(new NotificationAction(new Label("notification", "automation.send_notification"), "automation.send_notification_description"));
        // ── Composite vehicle commands (parity with key mapping) ──────────
        // lock / unlock / flash / find-car route through the dedicated
        // /api/vehicle/<action> endpoints (cloud-first on this generation). These
        // are the same commands the keymap "vehicle" kind exposes; the endpoints
        // are already inside the automation /api/vehicle/ allowlist.
        addAction(new ApiAction(
                new Label("lock", "automation.lock"), "automation.lock_description",
                "POST", "/api/vehicle/lock", ""));
        addAction(new ApiAction(
                new Label("unlock", "automation.unlock"), "automation.unlock_description",
                "POST", "/api/vehicle/unlock", ""));
        addAction(new ApiAction(
                new Label("flash", "automation.flash"), "automation.flash_description",
                "POST", "/api/vehicle/flash", ""));
        addAction(new ApiAction(
                new Label("findCar", "automation.find_car"), "automation.find_car_description",
                "POST", "/api/vehicle/find-car", ""));
        addAction(new VehicleControlAction(
                new Label("adas_slw", "automation.set_slw"), "automation.set_slw_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // ESP / Electronic Stability Control — label id MUST equal the catalog key
        // "esp_control" so VehicleControlAction.trigger resolves it. SAFETY control.
        addAction(new VehicleControlAction(
                new Label("esp_control", "automation.set_esp"), "automation.set_esp_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // iTAC (Intelligent Torque Adaption Control) — label id MUST equal the catalog
        // key "itac". Performance/traction feature, not a safety interlock.
        addAction(new VehicleControlAction(
                new Label("itac", "automation.set_itac"), "automation.set_itac_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("cpd", "automation.set_cpd"),
                "automation.set_cpd_description",
                "POST",
                "/api/vehicle/setting",
                "{\"target\":\"childPresenceDetection\",\"value\":${action}}",
                new EnumType(
                        new Label("action", "automation.action"),
                        new Label("2", "automation.off"),
                        new Label("1", "automation.on"),
                        new Label("3", "automation.cpd_delay"))));
        addAction(new VehicleControlAction(
                new Label("drl", "automation.set_drl"), "automation.set_drl_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // The all windows option can't be done to a specific percentage so not including it
        addAction(new ApiAction(
                new Label("windows", "automation.open_windows"),
                "automation.open_windows_description",
                "POST",
                "/api/vehicle/window",
                "{\"area\":${area},\"targetPercent\":${percent}}",
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("1", "automation.area_lf"),
                        new Label("2", "automation.area_rf"),
                        new Label("3", "automation.area_lr"),
                        new Label("4", "automation.area_rr"),
                        new Label("5", "automation.area_sunroof"),
                        new Label("6", "automation.area_sunshade")),
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Window position PRESET — friendlier than a raw percentage. Maps a named
        // position (Closed/Vent/Half/Full) to a target percent via the same
        // /api/vehicle/window closed-loop positioning path (moveWindowToPercent).
        // Per-area, since only individual windows support percent positioning (the
        // all-windows path is open/close/stop only). The preset value IS the percent
        // (0/15/50/100) so it substitutes straight into targetPercent.
        addAction(new ApiAction(
                new Label("windowsPreset", "automation.window_preset"),
                "automation.window_preset_description",
                "POST",
                "/api/vehicle/window",
                "{\"area\":${area},\"targetPercent\":${preset}}",
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("1", "automation.area_lf"),
                        new Label("2", "automation.area_rf"),
                        new Label("3", "automation.area_lr"),
                        new Label("4", "automation.area_rr"),
                        new Label("5", "automation.area_sunroof"),
                        new Label("6", "automation.area_sunshade")),
                new EnumType(
                        new Label("preset", "automation.window_position"),
                        new Label("0", "automation.window_closed"),
                        new Label("15", "automation.window_vent"),
                        new Label("50", "automation.window_half"),
                        new Label("100", "automation.window_full"))));
        // All-windows open/close/stop (parity with keymap windows_all). Routes to
        // /api/vehicle/window with area=0; the handler picks the cloud close-all path
        // for close and the SDK path for open/stop. command: 1=open, 2=close, 3=stop.
        addAction(new ApiAction(
                new Label("windowsAll", "automation.windows_all"),
                "automation.windows_all_description",
                "POST",
                "/api/vehicle/window",
                "{\"area\":0,\"command\":${command}}",
                new EnumType(
                        new Label("command", "automation.action"),
                        new Label("1", "automation.open"),
                        new Label("2", "automation.close"),
                        new Label("3", "automation.stop"))));
        // Tailgate / trunk open/close/stop (parity with keymap tailgate).
        addAction(new ApiAction(
                new Label("tailgate", "automation.tailgate"),
                "automation.tailgate_description",
                "POST",
                "/api/vehicle/trunk",
                "{\"action\":\"${action}\"}",
                new EnumType(
                        new Label("action", "automation.action"),
                        new Label("open", "automation.open"),
                        new Label("close", "automation.close"),
                        new Label("stop", "automation.stop"))));
        addAction(new VehicleControlAction(
                new Label("sunshade", "automation.set_sunshade"),
                "automation.set_sunshade_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        addAction(new VehicleControlAction(
                new Label("sunroof", "automation.set_sunroof"),
                "automation.set_sunroof_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        addAction(new ApiAction(
                new Label("setAc", "automation.set_ac"),
                "automation.set_ac_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"power_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("setAcTemp", "automation.set_ac_temp"),
                "automation.set_ac_temp_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"set_temp\",\"temp\":${temperature}}",
                new IntType(new Label("temperature", "automation.temperature"), 17, 33)));
        addAction(new ApiAction(
                new Label("setAcFan", "automation.set_ac_fan"),
                "automation.set_ac_fan_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"set_fan\",\"fan\":${speed}}",
                new IntType(new Label("speed", "automation.speed"), 1, 7)));
        addAction(new VehicleControlAction(
                new Label("seat", "automation.seat_climate"),
                "automation.set_seat_climate_description",
                new EnumType(new Label("type", "automation.type"), new Label("heat", "automation.heat"), new Label("vent", "automation.cool")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("driver", "automation.driver"),
                        new Label("passenger", "automation.passenger")),
                // Seat climate is 3-level hardware (off/low/high); the SDK write path collapses any
                // "medium" onto high, so exposing only these three keeps the action honest and
                // symmetric with the seatClimate condition enum.
                new EnumType(
                        new Label("payload", "automation.state"),
                        new Label("off", "automation.off"),
                        new Label("low", "automation.low"),
                        new Label("high", "automation.high"))));
        addAction(new ApiAction(
                new Label("seatPosition", "automation.set_seat_position"),
                "automation.set_seat_position_description",
                "POST",
                "/api/vehicle/seat",
                "{\"action\":\"position\",\"position\":${position}}",
                new EnumType(
                        new Label("position", "automation.action"),
                        new Label("1", "automation.position_1"),
                        new Label("2", "automation.position_2"))));
        // Save current driver-seat position into a memory slot (parity with keymap
        // seat_save; mirror of the recall above).
        addAction(new ApiAction(
                new Label("seatSave", "automation.save_seat_position"),
                "automation.save_seat_position_description",
                "POST",
                "/api/vehicle/seat",
                "{\"action\":\"save\",\"position\":${position}}",
                new EnumType(
                        new Label("position", "automation.action"),
                        new Label("1", "automation.position_1"),
                        new Label("2", "automation.position_2"))));
        // Child lock on/off (parity with keymap child_lock). label id "child_lock"
        // matches the VehicleControlCatalog key.
        addAction(new VehicleControlAction(
                new Label("child_lock", "automation.child_lock"), "automation.child_lock_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // Phone wireless charger on/off (parity with keymap wireless_charging).
        addAction(new VehicleControlAction(
                new Label("wireless_charging", "automation.wireless_charging"), "automation.wireless_charging_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("setAmbient", "automation.set_ambient"),
                "automation.set_ambient_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"ambientColour\",\"value\":${colour}}",
                new ColourType(new Label("colour", "automation.colour"), LightConstants.AMBIENT_COLOURS)));
        // Media volume (STREAM_MUSIC) as an ABSOLUTE step 0-40 — the same scale as the
        // car's own volume button. Routes through the allowlisted /api/vehicle/media
        // (AudioManager on the daemon context; the index is clamped to the stream max).
        addAction(new ApiAction(
                new Label("mediaVolume", "automation.set_media_volume"),
                "automation.set_media_volume_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"volume\",\"value\":${level}}",
                new IntType(new Label("level", "automation.volume_level"), 0, 40)));
        // Infotainment screen brightness as a 0-100 percentage (setting HAL).
        addAction(new ApiAction(
                new Label("screenBrightness", "automation.set_screen_brightness"),
                "automation.set_screen_brightness_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"brightness\",\"value\":${percent}}",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Driver-cluster (instrument display) brightness as a 0-100 percentage
        // (dedicated setDriverDisplayBrightness setter on the setting HAL).
        addAction(new ApiAction(
                new Label("clusterBrightness", "automation.set_cluster_brightness"),
                "automation.set_cluster_brightness_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"cluster_brightness\",\"value\":${percent}}",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Head-up-display brightness as a 0-100 percentage (dedicated setHUDBrightness
        // setter). There is no HUD on/off switch on this platform — 0 dims it out.
        addAction(new ApiAction(
                new Label("hudBrightness", "automation.set_hud_brightness"),
                "automation.set_hud_brightness_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"hud_brightness\",\"value\":${percent}}",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // HUD on/off — a friendlier switch than the brightness slider. This platform
        // has no dedicated HUD power switch (confirmed against the OEM firmware), so
        // "off" sets brightness 0 (dims it out) and "on" restores full brightness. The
        // enum value IS the brightness the daemon writes (0=off, 100=on).
        addAction(new ApiAction(
                new Label("hudPower", "automation.set_hud_power"),
                "automation.set_hud_power_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"hud_power\",\"value\":${state}}",
                new EnumType(
                        new Label("state", "automation.action"),
                        new Label("0", "automation.off"),
                        new Label("100", "automation.on"))));
        // Infotainment (centre) screen on/off. Uses the proven backlight path
        // (PowerManager.turnBacklightOn/Off → BYDAutoSettingDevice → shell
        // WAKEUP/SLEEP keyevent) — NOT goToSleep, which the car's ACC-on
        // keep-awake logic fights. value=0 → off, value=1 → on.
        addAction(new ApiAction(
                new Label("screenPower", "automation.set_screen_power"),
                "automation.set_screen_power_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"screen_power\",\"value\":${state}}",
                new EnumType(
                        new Label("state", "automation.action"),
                        new Label("0", "automation.off"),
                        new Label("1", "automation.on"))));
        // Lane assist (Lane Departure Warning / Prevention) — BYDAutoADASDevice
        // setLKSMode. Multi-mode (not on/off): Off / LDW / LDP / LDW+LDP. Catalog key
        // "lane_assist" must match the VehicleControlCatalog entity.
        addAction(new VehicleControlAction(
                new Label("lane_assist", "automation.set_lane_assist"), "automation.set_lane_assist_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("0", "automation.off"),
                        new Label("1", "automation.lane_ldw"),
                        new Label("2", "automation.lane_ldp"),
                        new Label("3", "automation.lane_ldw_ldp"))));
        // Media volume on a chosen audio channel (media/navigation/voice/phone/…).
        // Routes through the same allowlisted /api/vehicle/media handler, which maps
        // the channel to an AudioManager stream.
        addAction(new ApiAction(
                new Label("channelVolume", "automation.set_channel_volume"),
                "automation.set_channel_volume_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"volume\",\"channel\":\"${channel}\",\"value\":${level}}",
                new EnumType(
                        new Label("channel", "automation.audio_channel"),
                        new Label("media", "automation.channel_media"),
                        new Label("navigation", "automation.channel_navigation"),
                        new Label("voice", "automation.channel_voice"),
                        new Label("phone", "automation.channel_phone"),
                        new Label("system", "automation.channel_system"),
                        new Label("alarm", "automation.channel_alarm")),
                // Absolute step 0-40 (car's own volume scale), clamped to the chosen
                // channel's real stream max daemon-side.
                new IntType(new Label("level", "automation.volume_level"), 0, 40)));
        // Brake feel (comfort vs sport/strong) — BYDAutoADASDevice brake foot-sense.
        // Catalog key "brake_feel" must match the VehicleControlCatalog entity.
        addAction(new VehicleControlAction(
                new Label("brake_feel", "automation.set_brake_feel"), "automation.set_brake_feel_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("comfort", "automation.brake_comfort"),
                        new Label("sport", "automation.brake_sport"),
                        new Label("toggle", "automation.toggle"))));
        // Play an uploaded sound (MP3/WAV/MP4) through the daemon MediaPlayer on a
        // chosen channel — AUDIO ONLY (an MP4's picture is not shown; use Play Video
        // for that). The sound is chosen from the audio library via a live dropdown
        // (AudioType → GET /api/audio/library) — the same upload-once / pick-later
        // model the Screen Deterrent uses for its image. Routes through the allowlisted
        // /api/vehicle/play-audio (the daemon resolves the name to the library path and
        // validates it). display=speakers is baked in so the picture never appears.
        addAction(new ApiAction(
                new Label("playAudio", "automation.play_audio"),
                "automation.play_audio_description",
                "POST",
                "/api/vehicle/play-audio",
                "{\"name\":\"${name}\",\"channel\":\"${channel}\",\"display\":\"speakers\",\"loop\":${loop}}",
                new AudioType(new Label("name", "automation.audio_file")),
                new EnumType(
                        new Label("channel", "automation.audio_channel"),
                        new Label("media", "automation.channel_media"),
                        new Label("navigation", "automation.channel_navigation"),
                        new Label("voice", "automation.channel_voice"),
                        new Label("alarm", "automation.channel_alarm")),
                // Loop the sound until Stop Audio (or a new Play). "false"/"true"
                // substitute as JSON booleans into the body.
                new EnumType(
                        new Label("loop", "automation.audio_loop"),
                        new Label("false", "automation.audio_loop_off"),
                        new Label("true", "automation.audio_loop_on"))));
        // Play an uploaded VIDEO (MP4) with its picture shown on the head-unit screen
        // (audio on the chosen channel). Distinct action so video is discoverable,
        // mirroring the keymap "Play video" entry. display=screen is baked in.
        addAction(new ApiAction(
                new Label("playVideo", "automation.play_video"),
                "automation.play_video_description",
                "POST",
                "/api/vehicle/play-audio",
                "{\"name\":\"${name}\",\"channel\":\"${channel}\",\"display\":\"screen\",\"loop\":${loop}}",
                new AudioType(new Label("name", "automation.video_file")),
                new EnumType(
                        new Label("channel", "automation.audio_channel"),
                        new Label("media", "automation.channel_media"),
                        new Label("navigation", "automation.channel_navigation"),
                        new Label("voice", "automation.channel_voice"),
                        new Label("alarm", "automation.channel_alarm")),
                new EnumType(
                        new Label("loop", "automation.audio_loop"),
                        new Label("false", "automation.audio_loop_off"),
                        new Label("true", "automation.audio_loop_on"))));
        // Stop any audio or video started by Play Audio / Play Video.
        addAction(new ApiAction(
                new Label("stopAudio", "automation.stop_audio"),
                "automation.stop_audio_description",
                "POST",
                "/api/vehicle/stop-audio",
                ""));
        // Drive / energy modes — same catalog entities the keymap and MQTT use.
        // The Label id must match the VehicleControlCatalog key so
        // VehicleControlAction.trigger resolves it.
        addAction(new VehicleControlAction(
                new Label("drive_mode", "automation.set_drive_mode"), "automation.set_drive_mode_description",
                // Drive mode now routes through the setting-device "drive config" axis
                // (NORMAL=1, ECO=2, SPORT=3, SNOW=4) which — unlike the old
                // energy-device operation-mode path (eco/sport only) — supports NORMAL.
                // SNOW is left out of the picker (it interacts with a road-surface axis
                // and is rarely a user automation), but the payload mapping accepts it.
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("normal", "automation.mode_normal"),
                        new Label("eco", "automation.mode_eco"),
                        new Label("sport", "automation.mode_sport"))));
        addAction(new VehicleControlAction(
                new Label("powertrain_mode", "automation.set_powertrain_mode"), "automation.set_powertrain_mode_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("ev", "automation.mode_ev"),
                        new Label("hev", "automation.mode_hev"))));
        // regen / steering / brake feel expose a "toggle" option that CYCLES to the
        // next mode (the daemon tracks the last-commanded value — no HAL readback
        // exists), so an automation can flip the mode without knowing the current one.
        addAction(new VehicleControlAction(
                new Label("regen_level", "automation.set_regen"), "automation.set_regen_description",
                new EnumType(new Label("payload", "automation.level"),
                        new Label("standard", "automation.regen_standard"),
                        new Label("high", "automation.regen_high"),
                        new Label("toggle", "automation.toggle"))));
        addAction(new VehicleControlAction(
                new Label("steering_mode", "automation.set_steering"), "automation.set_steering_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("comfort", "automation.steering_comfort"),
                        new Label("sport", "automation.steering_sport"),
                        new Label("toggle", "automation.toggle"))));
        addAction(new ApiAction(
                new Label("surveillance", "automation.set_surveillance"),
                "automation.set_surveillance_description",
                "POST",
                "/api/surveillance/${action}",
                "",
                new EnumType(
                        new Label("action", "automation.action"),
                        new Label("disable", "automation.off"),
                        new Label("enable", "automation.on"))));
        addAction(new ApiAction(
                new Label("recording", "automation.set_recording"),
                "automation.set_recording_description",
                "POST",
                "/api/recording/mode",
                "{\"mode\":\"${mode}\"}",
                new EnumType(
                        new Label("mode", "automation.mode"),
                        new Label("NONE", "automation.none"),
                        new Label("CONTINUOUS", "automation.continuous"),
                        new Label("DRIVE_MODE", "automation.drive_mode"),
                        new Label("PROXIMITY_GUARD", "automation.proximity_guard"))));
        // Save an instant replay from the encoded camera ring. Manual-only
        // recording path: a dedicated in-process action (NOT the /api allowlist)
        // so replay stays off the automation HTTP surface, matching key mapping.
        // Fires only while the camera pipeline is already running (an ACC-on
        // recording mode active) — it never keeps the pipeline alive on its own.
        addAction(new ManualClipAction(
                new Label("manualClip", "automation.save_replay"),
                "automation.save_replay_description"));
        // Open app — launch a user-selected installed app. The package is chosen
        // from a live picker (AppType → dropdown fed by GET /api/apps/list). Routes
        // through the allowlisted POST /api/apps/launch (shared with key mapping).
        addAction(new ApiAction(
                new Label("openApp", "automation.open_app"),
                "automation.open_app_description",
                "POST",
                "/api/apps/launch",
                "{\"package\":\"${package}\"}",
                new AppType(new Label("package", "automation.app"))));
        // Open app in split-screen — docks the chosen app to one half of the
        // screen (windowingMode 3) so it shares the display. Same picker + launch
        // endpoint as openApp, with the split flag set.
        addAction(new ApiAction(
                new Label("openAppSplit", "automation.open_app_split"),
                "automation.open_app_split_description",
                "POST",
                "/api/apps/launch",
                "{\"package\":\"${package}\",\"split\":true}",
                new AppType(new Label("package", "automation.app"))));
        // Show camera view — a native camera feed (front/rear/left/right/all-4) on
        // the SAME SurfaceControl lane the blind-spot feature uses, with position +
        // target. Routes through the allowlisted /api/camview/show; the query params
        // (${cam}/${target}/${position}) are substituted by ApiAction. Blind-spot
        // has priority: while BS is armed and showing (turn signal), it owns the lane.
        addAction(new ApiAction(
                new Label("showCameraView", "automation.show_camera_view"),
                "automation.show_camera_view_description",
                "POST",
                "/api/camview/show?cam=${cam}&target=${target}&preset=${size}/${position}",
                "",
                new EnumType(
                        new Label("cam", "automation.camera"),
                        new Label("all", "automation.camera_all"),
                        new Label("front", "automation.camera_front"),
                        new Label("rear", "automation.camera_rear"),
                        new Label("left", "automation.camera_left"),
                        new Label("right", "automation.camera_right")),
                new EnumType(
                        new Label("target", "automation.display"),
                        new Label("head_unit", "automation.display_headunit"),
                        new Label("cluster", "automation.display_cluster")),
                // Size and position are now SEPARATE pickers (the daemon accepts
                // preset=sizePct/corner). Size is the on-screen width %; the two combine
                // into ?preset=${size}/${position}. Fullscreen ignores the corner.
                new EnumType(
                        new Label("size", "automation.camera_size"),
                        // Width as a % of the panel. Small is a compact floating card
                        // (~25% ≈ 480px on a 1920 panel); the daemon floor is 15%.
                        new Label("25", "automation.size_small"),
                        new Label("45", "automation.size_medium"),
                        new Label("70", "automation.size_large"),
                        new Label("90", "automation.size_full")),
                new EnumType(
                        new Label("position", "automation.position"),
                        new Label("center", "automation.pos_center"),
                        new Label("tr", "automation.pos_tr"),
                        new Label("tl", "automation.pos_tl"),
                        new Label("br", "automation.pos_br"),
                        new Label("bl", "automation.pos_bl"))));
        // Hide the camera view.
        addAction(new ApiAction(
                new Label("hideCameraView", "automation.hide_camera_view"),
                "automation.hide_camera_view_description",
                "POST",
                "/api/camview/hide",
                ""));
        // ── Control-flow steps ───────────────────────────────────────────
        // Pause N milliseconds before the next action. Blocks only this automation's
        // worker (sequential action chain), never telemetry/other automations.
        addAction(new PauseAction(
                new Label("pause", "automation.pause"), "automation.pause_description"));
        // Wait until a scalar signal satisfies a comparison (bounded by a timeout).
        // Same worker-thread-only blocking as pause; polls the live automation state.
        addAction(new WaitUntilAction(
                new Label("waitUntil", "automation.wait_until"), "automation.wait_until_description"));
        // Wait until an ON/OFF signal reaches a state — the on/off counterpart of the
        // scalar Wait Until above. Covers indicators (left/right/either), lights, AC,
        // etc., so a chain can pause on "wait until indicators = off".
        addAction(new WaitUntilStateAction(
                new Label("waitUntilState", "automation.wait_until_state"), "automation.wait_until_state_description"));
        // Set a named variable / flag — a marker the user sets and reads to coordinate
        // automations (a mutex that stops re-triggering while running, a mode flag other
        // automations key off). Pairs with the "variable" trigger/condition.
        addAction(new SetVariableAction(
                new Label("setVariable", "automation.set_variable"), "automation.set_variable_description"));

        // Shell command — the free-text StringType variable is defined inside
        // ShellAction. Autonomous exec, so it self-gates on the dedicated
        // automation.allowShell flag (toggle on the Automations page) at fire
        // time (off → logged no-op). Registered last so curated actions lead.
        addAction(new ShellAction(
                new Label("shell", "automation.run_shell"), "automation.run_shell_description"));

    }

    /**
     * Add an action to the map
     * Stored as a map to prevent duplicates
     *
     * @param action The Action to store
     */
    private void addAction(Action action) {
        actions.put(action.getLabel().getId(), action);
    }

    /**
     * Get a stored action
     *
     * @param key The id of the action
     * @return The requested action
     */
    public Action getAction(String key) {
        return actions.get(key);
    }

    /**
     * A JSON representation of the primary actions schema ("Then").
     * All automations require at least 1 action so required is 1.
     *
     * @return A JSON representation of the Actions schema
     */
    public JSONObject toJson() {
        return sectionJson("actions", "Then", "automation.actions_description", 1);
    }

    /**
     * A JSON representation of the OPTIONAL else-actions schema ("Else"). Same
     * option catalog as the primary actions, but required 0 — an automation with
     * no else branch is valid, which is why every pre-existing automation keeps
     * working. Rendered by the same schema-driven form code as the primary section
     * (it is an options list with a distinct id), so no bespoke UI is needed.
     *
     * @return A JSON representation of the else-actions schema
     */
    public JSONObject elseToJson() {
        return sectionJson("elseActions", "Else", "automation.else_actions_description", 0);
    }

    /**
     * Build one actions-schema section with the given id/label/description/required,
     * sharing the single action catalog. Extracted so the primary and else sections
     * cannot drift in their option set.
     */
    private JSONObject sectionJson(String id, String fallbackLabel, String descriptionKey, int required) {
        JSONObject section = new Label(id, fallbackLabel).toJson();

        try {
            section.put("description", Messages.get(descriptionKey));
            JSONArray actionsList = new JSONArray();
            for (Action action : this.actions.values()) {
                actionsList.put(action.toJson());
            }
            section.put("options", actionsList);
            section.put("required", required);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return section;
    }
}
