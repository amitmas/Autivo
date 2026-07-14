/*
 * Key Mapping settings controller.
 *
 * ES5-only (Chrome 58 / Android 7.1 head-unit floor) — no let/const/arrow/template
 * literals. Talks to the daemon's KeymapApiHandler:
 *   GET  /api/keymap/config  -> { enabled, allowAdvanced, bindings[], a11yEnabled }
 *   POST /api/keymap/config  -> persist { enabled, allowAdvanced, bindings[] }
 *
 * A binding is:
 *   { keycode:int, pressType:"single|double|long", enabled:true,
 *     label:"<human summary>", action:<fire-payload> }
 * where <fire-payload> is exactly what /api/keymap/fire consumes:
 *   { kind:"catalog", key, sub?, payload }   — curated vehicle action
 *   { kind:"vehicle", action }               — lock/unlock/flash/find_car
 *   { kind:"shell",   cmd }                  — advanced escape hatch
 *
 * The curated action list below MIRRORS the daemon's VehicleControlCatalog /
 * composite command set. It is the client-side menu only; the daemon re-resolves
 * and re-validates every fire, so a stale entry here just fails safe server-side.
 */
window.KM = (function () {
    'use strict';

    // Curated actions the user can bind. `payloads` (when present) becomes the
    // Value dropdown; the chosen option's `v` is sent as the fire payload.
    //   kind:"catalog" -> {kind:'catalog', key, sub, payload:v}
    //   kind:"vehicle" -> {kind:'vehicle', action:key}   (no payload)
    //   kind:"manualClip" -> {kind:'manualClip', beforeSeconds, afterSeconds}
    //   kind:"api"     -> {kind:'api', id, method, path, body}
    //       For features with no VehicleControlCatalog SDK entity (surveillance,
    //       recording). `api.path`/`api.body` support ${v} substitution from the
    //       chosen payload option. The daemon routes these through the same
    //       allowlisted automation bypass, so only the curated control surface
    //       is reachable. `id` is carried for the UI label only (daemon ignores it).
    var CURATED = [
        { id: 'lock',            i18n: 'keymap.act_lock',            kind: 'vehicle', key: 'lock' },
        { id: 'unlock',          i18n: 'keymap.act_unlock',          kind: 'vehicle', key: 'unlock' },
        { id: 'flash',           i18n: 'keymap.act_flash',           kind: 'vehicle', key: 'flash' },
        { id: 'find_car',        i18n: 'keymap.act_find_car',        kind: 'vehicle', key: 'find_car' },
        { id: 'windows_all',     i18n: 'keymap.act_windows',         kind: 'catalog', key: 'windows_all',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        // Per-door window position presets. A hardware button binds one fixed door,
        // so area is baked into the path and ${v} carries the preset percent
        // (0=closed/15=vent/50=half/100=full) through the closed-loop positioning
        // endpoint. api kind → allowlisted /api/vehicle/window.
        { id: 'window_lf',       i18n: 'keymap.act_window_lf',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":1,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'window_rf',       i18n: 'keymap.act_window_rf',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":2,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'window_lr',       i18n: 'keymap.act_window_lr',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":3,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'window_rr',       i18n: 'keymap.act_window_rr',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":4,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'tailgate',        i18n: 'keymap.act_tailgate',        kind: 'catalog', key: 'tailgate',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'sunroof',         i18n: 'keymap.act_sunroof',         kind: 'catalog', key: 'sunroof',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'sunshade',        i18n: 'keymap.act_sunshade',        kind: 'catalog', key: 'sunshade',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'climate',         i18n: 'keymap.act_climate',         kind: 'catalog', key: 'climate', sub: 'mode',
          payloads: [ { v: 'auto', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        // AC fan speed — a button binds ONE fixed level (1-7). api kind → allowlisted
        // /api/vehicle/climate set_fan. ${v} carries the level.
        { id: 'ac_fan',          i18n: 'keymap.act_ac_fan',           kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"set_fan","fan":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.fan_1' }, { v: '2', i18n: 'keymap.fan_2' }, { v: '3', i18n: 'keymap.fan_3' }, { v: '4', i18n: 'keymap.fan_4' }, { v: '5', i18n: 'keymap.fan_5' }, { v: '6', i18n: 'keymap.fan_6' }, { v: '7', i18n: 'keymap.fan_7' } ] },
        { id: 'drl',             i18n: 'keymap.act_drl',             kind: 'catalog', key: 'drl',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'esp_control',     i18n: 'keymap.act_esp',             kind: 'catalog', key: 'esp_control',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'itac',            i18n: 'keymap.act_itac',            kind: 'catalog', key: 'itac',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        // Camera views on the native SurfaceControl lane (shares blind-spot pipeline;
        // BS has priority). A button picks WHICH camera; it shows centered on the
        // head-unit at a chosen SIZE (small/medium/large/full). ${v} = size % → the
        // preset's sizePct (corner fixed center for a button). api kind → allowlisted
        // /api/camview. Hide is a separate action.
        { id: 'camview_all',     i18n: 'keymap.act_camview_all',     kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=all&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_front',   i18n: 'keymap.act_camview_front',   kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=front&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_rear',    i18n: 'keymap.act_camview_rear',    kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=rear&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_left',    i18n: 'keymap.act_camview_left',    kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=left&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_right',   i18n: 'keymap.act_camview_right',   kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=right&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_hide',    i18n: 'keymap.act_camview_hide',    kind: 'api',
          method: 'POST', path: '/api/camview/hide', body: '' },
        { id: 'seat_heat_driver',    i18n: 'keymap.act_seat_heat_driver',    kind: 'catalog', key: 'seat_heat_driver',
          payloads: [ { v: 'off', i18n: 'keymap.off' }, { v: 'low', i18n: 'keymap.low' }, { v: 'high', i18n: 'keymap.high' } ] },
        { id: 'seat_heat_passenger', i18n: 'keymap.act_seat_heat_passenger', kind: 'catalog', key: 'seat_heat_passenger',
          payloads: [ { v: 'off', i18n: 'keymap.off' }, { v: 'low', i18n: 'keymap.low' }, { v: 'high', i18n: 'keymap.high' } ] },
        { id: 'child_lock',      i18n: 'keymap.act_child_lock',      kind: 'catalog', key: 'child_lock',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'wireless_charging', i18n: 'keymap.act_wireless_charging', kind: 'catalog', key: 'wireless_charging',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'drive_mode',      i18n: 'keymap.act_drive_mode',       kind: 'catalog', key: 'drive_mode',
          payloads: [ { v: 'normal', i18n: 'keymap.mode_normal' }, { v: 'eco', i18n: 'keymap.mode_eco' }, { v: 'sport', i18n: 'keymap.mode_sport' } ] },
        { id: 'powertrain_mode', i18n: 'keymap.act_powertrain_mode',  kind: 'catalog', key: 'powertrain_mode',
          payloads: [ { v: 'ev', i18n: 'keymap.mode_ev' }, { v: 'hev', i18n: 'keymap.mode_hev' } ] },
        // regen / steering / brake feel: no telemetry readback exists, so "toggle"
        // CYCLES to the next option (daemon tracks the last-commanded value) — ideal
        // for a single button that flips between the two modes on each press.
        { id: 'regen_level',     i18n: 'keymap.act_regen',            kind: 'catalog', key: 'regen_level',
          payloads: [ { v: 'standard', i18n: 'keymap.regen_standard' }, { v: 'high', i18n: 'keymap.regen_high' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'steering_mode',   i18n: 'keymap.act_steering',         kind: 'catalog', key: 'steering_mode',
          payloads: [ { v: 'comfort', i18n: 'keymap.steering_comfort' }, { v: 'sport', i18n: 'keymap.steering_sport' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'brake_feel',      i18n: 'keymap.act_brake_feel',       kind: 'catalog', key: 'brake_feel',
          payloads: [ { v: 'comfort', i18n: 'keymap.brake_comfort' }, { v: 'sport', i18n: 'keymap.brake_sport' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // Speed-limit-warning + child-presence: real snapshot state → support toggle
        // (flip current) alongside explicit on/off. catalog kind resolves toggle daemon-side.
        { id: 'adas_slw',        i18n: 'keymap.act_slw',              kind: 'catalog', key: 'adas_slw',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'adas_cpd',        i18n: 'keymap.act_cpd',              kind: 'catalog', key: 'adas_cpd',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // Lane assist — multi-mode (Off/LDW/LDP/LDW+LDP); "toggle" cycles via live
        // readback. catalog kind resolves against the "lane_assist" entity daemon-side.
        { id: 'lane_assist',     i18n: 'keymap.act_lane_assist',      kind: 'catalog', key: 'lane_assist',
          payloads: [ { v: '0', i18n: 'keymap.lane_off' }, { v: '1', i18n: 'keymap.lane_ldw' }, { v: '2', i18n: 'keymap.lane_ldp' }, { v: '3', i18n: 'keymap.lane_ldw_ldp' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // ── Display brightness (media handler; allowlisted). A button steps to a
        //    chosen level — pick the level when binding. ${v} = 0-100 percentage. ──
        { id: 'screen_brightness', i18n: 'keymap.act_screen_brightness', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"brightness","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.level_0' }, { v: '25', i18n: 'keymap.level_25' }, { v: '50', i18n: 'keymap.level_50' }, { v: '75', i18n: 'keymap.level_75' }, { v: '100', i18n: 'keymap.level_100' } ] },
        { id: 'cluster_brightness', i18n: 'keymap.act_cluster_brightness', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"cluster_brightness","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.level_0' }, { v: '25', i18n: 'keymap.level_25' }, { v: '50', i18n: 'keymap.level_50' }, { v: '75', i18n: 'keymap.level_75' }, { v: '100', i18n: 'keymap.level_100' } ] },
        { id: 'hud_brightness', i18n: 'keymap.act_hud_brightness', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"hud_brightness","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.level_0' }, { v: '25', i18n: 'keymap.level_25' }, { v: '50', i18n: 'keymap.level_50' }, { v: '75', i18n: 'keymap.level_75' }, { v: '100', i18n: 'keymap.level_100' } ] },
        // HUD on/off — no dedicated switch on this platform, so off=brightness 0 /
        // on=brightness 100 (value IS the brightness the daemon writes).
        { id: 'hud_power', i18n: 'keymap.act_hud_power', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"hud_power","value":${v}}',
          payloads: [ { v: '100', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        // Infotainment screen on/off — proven backlight path (PowerManager
        // turnBacklightOn/Off → BYDAutoSettingDevice → shell WAKEUP/SLEEP), NOT
        // goToSleep. Bind a button to Off to blank the panel; On restores it.
        { id: 'screen_power', i18n: 'keymap.act_screen_power', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"screen_power","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.off' }, { v: '1', i18n: 'keymap.on' } ] },
        // Media volume on a fixed channel — a button binds ONE channel + one level.
        // Separate curated entries per channel keep the two-dropdown form to a single
        // value picker (the channel is baked into the body).
        // Volume is an ABSOLUTE step (0-40, the car's own volume scale), not a percent.
        // The daemon clamps to the channel's real stream max.
        { id: 'volume_media', i18n: 'keymap.act_volume_media', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"volume","channel":"media","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.vol_0' }, { v: '10', i18n: 'keymap.vol_10' }, { v: '20', i18n: 'keymap.vol_20' }, { v: '30', i18n: 'keymap.vol_30' }, { v: '40', i18n: 'keymap.vol_40' } ] },
        { id: 'volume_navigation', i18n: 'keymap.act_volume_navigation', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"volume","channel":"navigation","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.vol_0' }, { v: '10', i18n: 'keymap.vol_10' }, { v: '20', i18n: 'keymap.vol_20' }, { v: '30', i18n: 'keymap.vol_30' }, { v: '40', i18n: 'keymap.vol_40' } ] },
        // Play audio: the payload dropdown is populated LIVE from the audio library
        // (dynamicPayloads:'audio' → GET /api/audio/library), so a button binds one of
        // the sounds the user uploaded on the Automations page. ${v} = the sound's
        // filename; the daemon resolves it to the library path. A button fires once
        // (loop off); for looping/screen use an automation. Stop is payloadless.
        { id: 'play_audio', i18n: 'keymap.act_play_audio', kind: 'api',
          method: 'POST', path: '/api/vehicle/play-audio',
          body: '{"name":"${v}","channel":"media","display":"speakers","loop":false}', dynamicPayloads: 'audio' },
        // Play a video with its picture on the head-unit screen (same library picker).
        { id: 'play_video', i18n: 'keymap.act_play_video', kind: 'api',
          method: 'POST', path: '/api/vehicle/play-audio',
          body: '{"name":"${v}","channel":"media","display":"screen","loop":false}', dynamicPayloads: 'audio' },
        { id: 'stop_audio', i18n: 'keymap.act_stop_audio', kind: 'api',
          method: 'POST', path: '/api/vehicle/stop-audio', body: '' },
        // ── API actions: features with no SDK/catalog entity (routed via the
        //    allowlisted automation bypass). path/body use ${v} for the payload. ──
        { id: 'surveillance',    i18n: 'keymap.act_surveillance',     kind: 'api',
          method: 'POST', path: '/api/surveillance/${v}', body: '',
          payloads: [ { v: 'enable', i18n: 'keymap.on' }, { v: 'disable', i18n: 'keymap.off' } ] },
        { id: 'recording',       i18n: 'keymap.act_recording',        kind: 'api',
          method: 'POST', path: '/api/recording/mode', body: '{"mode":"${v}"}',
          payloads: [ { v: 'NONE', i18n: 'keymap.rec_none' }, { v: 'CONTINUOUS', i18n: 'keymap.rec_continuous' },
                      { v: 'DRIVE_MODE', i18n: 'keymap.rec_drive' }, { v: 'PROXIMITY_GUARD', i18n: 'keymap.rec_proximity' } ] },
        // Seat memory: recall moves the driver seat to a stored slot; save stores
        // the current position into it. Two curated actions so a button can be
        // bound to either. Routed via /api/vehicle/seat (already automation-allowlisted).
        { id: 'seat_recall',     i18n: 'keymap.act_seat_recall',      kind: 'api',
          method: 'POST', path: '/api/vehicle/seat', body: '{"action":"position","position":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.seat_slot_1' }, { v: '2', i18n: 'keymap.seat_slot_2' } ] },
        { id: 'seat_save',       i18n: 'keymap.act_seat_save',        kind: 'api',
          method: 'POST', path: '/api/vehicle/seat', body: '{"action":"save","position":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.seat_slot_1' }, { v: '2', i18n: 'keymap.seat_slot_2' } ] },
        // Manual-only recording action. This has a dedicated daemon action kind
        // instead of the automation-shared API path; the two window values are
        // edited by the clip controls below and persisted directly in the binding.
        { id: 'manual_clip',     i18n: 'keymap.act_manual_clip',       kind: 'manualClip' }
    ];

    // Known BYD steering-wheel / dash buttons (keycode → label), from the OEM
    // keycode map verified on DiLink firmware.
    //
    // CRITICAL firmware model: on this hardware a LONG PRESS is NOT repeatCount>0
    // of a base keycode — the firmware detects the hold itself and emits a
    // SEPARATE keycode (302 = "Next long", the long-press of 87 = "Next"; likewise
    // 303←88, 306←305, 312←304). So:
    //   • base keys (87/88/289/…) support single + double (timing between presses),
    //   • long-variant keys (302/303/306/312) must fire ON ARRIVAL — the keycode
    //     showing up IS the completed long-press. Binding them as pressType "long"
    //     (which waits for repeatCount>0) would NEVER fire, since they arrive as a
    //     plain repeatCount==0 DOWN. So `fixed: 'single'` = fire immediately; the
    //     label already tells the user it's the long-press button.
    // `pressTypes` limits the press-type selector to what the base keycode really
    // supports (no "long" on a base key, because long is a different keycode here).
    var KNOWN_BUTTONS = [
        { code: 87,  i18n: 'keymap.btn_next',      pressTypes: ['single', 'double'] },
        { code: 88,  i18n: 'keymap.btn_prev',      pressTypes: ['single', 'double'] },
        { code: 289, i18n: 'keymap.btn_mode',      pressTypes: ['single', 'double'] },
        { code: 291, i18n: 'keymap.btn_vol_up',    pressTypes: ['single'] },
        { code: 292, i18n: 'keymap.btn_vol_down',  pressTypes: ['single'] },
        { code: 293, i18n: 'keymap.btn_mute',      pressTypes: ['single', 'double'] },
        { code: 294, i18n: 'keymap.btn_surround',  pressTypes: ['single', 'double'] },
        { code: 304, i18n: 'keymap.btn_voice',     pressTypes: ['single', 'double'] },
        { code: 305, i18n: 'keymap.btn_rotate',    pressTypes: ['single', 'double'] },
        { code: 313, i18n: 'keymap.btn_phone',     pressTypes: ['single', 'double'] },
        { code: 317, i18n: 'keymap.btn_power',     pressTypes: ['single', 'double'] },
        { code: 302, i18n: 'keymap.btn_next_long',   fixed: 'single' },
        { code: 303, i18n: 'keymap.btn_prev_long',   fixed: 'single' },
        { code: 306, i18n: 'keymap.btn_rotate_long', fixed: 'single' },
        { code: 312, i18n: 'keymap.btn_voice_long',  fixed: 'single' }
    ];

    var state = {
        enabled: false,
        allowAdvanced: false,
        bindings: [],
        a11yEnabled: false,
        restartRequired: false
    };
    var capturing = false;
    var captured = null; // last captured keycode while arming

    var CLIP_MAX_BEFORE_SECONDS = 60;
    var CLIP_MAX_AFTER_SECONDS = 60;
    var CLIP_MAX_TOTAL_SECONDS = 60;
    var CLIP_DEFAULT_PRESET = '30:0';
    // Monotonic token for async payload-dropdown builds (dynamicPayloads). Bumped on
    // every onCuratedChange so a late fetch response for a since-changed action is
    // discarded instead of clobbering the current action's payload options.
    var payloadReqSeq = 0;

    function $(id) { return document.getElementById(id); }
    function tr(key, vars) { return (window.BYD && BYD.i18n) ? (BYD.i18n.t(key, vars) || key) : key; }
    function toast(msg, kind) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, kind || 'info'); }
    function curatedById(id) {
        for (var i = 0; i < CURATED.length; i++) if (CURATED[i].id === id) return CURATED[i];
        return null;
    }

    // ───────────────────────── Load / paint ─────────────────────────

    // Guards for the a11y self-heal re-fetch below. a11yRecheckPending blocks
    // OVERLAPPING timers; a11yRechecksLeft BOUNDS the total number of sequential
    // rechecks so the page never polls forever. The daemon's self-heal lands
    // within ~5-11s (one or two 5.5s windows) on a provisioned unit, so a small
    // budget covers the legitimate "heal in flight" case. If the service still
    // isn't enabled after that (e.g. the Secure-settings write can't stick on a
    // permission-limited build, or the user hasn't enabled it), we STOP re-fetching
    // and leave the nudge card up — the user acts via "Open accessibility settings".
    // Without this cap, recheckA11y()->scheduleA11yRecheck() re-armed every 5.5s for
    // the life of the page, and each GET spawned a server-side settings-exec heal.
    var a11yRecheckPending = false;
    var a11yRechecksLeft = 0;
    var A11Y_RECHECK_BUDGET = 3;

    function load() {
        fetch('/api/keymap/config', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (s) {
                state.enabled = !!s.enabled;
                state.allowAdvanced = !!s.allowAdvanced;
                state.bindings = (s.bindings && s.bindings.length) ? s.bindings : [];
                state.a11yEnabled = !!s.a11yEnabled;
                state.restartRequired = !!s.restartRequired;
                paint();
                // Fresh foreground load — refill the recheck budget so the
                // heal-window poll runs, but stays bounded (see scheduleA11yRecheck).
                a11yRechecksLeft = A11Y_RECHECK_BUDGET;
                scheduleA11yRecheck();
            })
            .catch(function () { toast(tr('keymap.load_failed'), 'error'); });
    }

    // Client-side half of the daemon's load-time self-heal
    // (KeymapApiHandler.handleGetConfig): when mapping is ON but the a11y service
    // is off, the GET makes the daemon kick an async enable that lands ~4-5s later,
    // but it reports a11yEnabled from a PRE-heal read — so a11yEnabled=true only
    // shows up on a SUBSEQUENT GET. Re-check once after the heal window so the
    // nudge auto-hides instead of lingering until a manual reload.
    //
    // CRITICAL: this MUST refresh ONLY the a11y status, never re-assign
    // state.bindings / state.enabled / state.allowAdvanced. This is a BACKGROUND
    // timer that can fire in the middle of the user adding bindings; if it called
    // the full load() (as it used to), it would clobber state.bindings with the
    // server snapshot — dropping any binding added since the timer was armed, or
    // one that a still-in-flight persist() hasn't committed yet. That was the
    // "adding a new binding deletes the older one" bug. So recheckA11y() reads the
    // config but adopts only a11yEnabled, leaving the user's working binding list
    // and toggles untouched.
    function scheduleA11yRecheck() {
        if (state.enabled && !state.a11yEnabled && !a11yRecheckPending && a11yRechecksLeft > 0) {
            a11yRecheckPending = true;
            a11yRechecksLeft--;
            setTimeout(function () { a11yRecheckPending = false; recheckA11y(); }, 5500);
        }
    }

    function recheckA11y() {
        fetch('/api/keymap/config', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (s) {
                // Adopt ONLY the a11y status — do NOT touch bindings/enabled/allowAdvanced.
                state.a11yEnabled = !!s.a11yEnabled;
                // Refresh just the nudge card, not the whole list (paint() re-renders
                // from the untouched state.bindings, so it's safe, but keep it minimal).
                var card = $('kmA11yCard');
                if (card) card.style.display = (state.enabled && !state.a11yEnabled) ? '' : 'none';
                // If still not enabled, arm one more recheck (self-terminates once it flips).
                scheduleA11yRecheck();
            })
            .catch(function () { /* transient — leave the nudge as-is */ });
    }

    function paint() {
        $('kmEnable').checked = state.enabled;
        $('kmAllowAdvanced').checked = state.allowAdvanced;

        // The "Advanced: shell" action only exists when the Advanced toggle is on.
        // Hiding the OPTION (not just gating on save) makes the relationship
        // obvious: flip the toggle → the shell choice appears/disappears. If it
        // was selected and Advanced is turned off, snap the picker back to the
        // safe curated kind so no stale shell form is left showing.
        var shellOpt = $('kmShellOption');
        if (shellOpt) {
            shellOpt.style.display = state.allowAdvanced ? '' : 'none';
            shellOpt.disabled = !state.allowAdvanced;
        }
        if (!state.allowAdvanced && $('kmActionKind').value === 'shell') {
            $('kmActionKind').value = 'curated';
        }
        onKindChange();

        var badge = $('kmStatusBadge');
        if (state.enabled) {
            badge.textContent = tr('keymap.badge_on');
            badge.className = 'status-badge active';
        } else {
            badge.textContent = tr('keymap.badge_off');
            badge.className = 'status-badge inactive';
        }

        // Show the a11y nudge only when mapping is enabled but the OS service is off.
        $('kmA11yCard').style.display = (state.enabled && !state.a11yEnabled) ? '' : 'none';
        paintRestartWarning();

        renderList();
    }

    function paintRestartWarning() {
        var card = $('kmReplayRestartCard');
        if (card) card.style.display = state.restartRequired ? '' : 'none';
    }

    function renderList() {
        var list = $('kmList');
        var empty = $('kmEmpty');
        list.innerHTML = '';
        if (!state.bindings.length) {
            empty.style.display = '';
            return;
        }
        empty.style.display = 'none';
        for (var i = 0; i < state.bindings.length; i++) {
            list.appendChild(bindingRow(state.bindings[i], i));
        }
    }

    function bindingRow(b, index) {
        var row = document.createElement('div');
        row.className = 'km-binding';

        var key = document.createElement('div');
        key.className = 'km-key';
        key.textContent = b.keycode;
        row.appendChild(key);

        var meta = document.createElement('div');
        meta.className = 'km-meta';
        var action = document.createElement('div');
        action.className = 'km-action';
        action.textContent = b.label || describeAction(b.action);
        var sub = document.createElement('div');
        sub.className = 'km-sub';
        sub.textContent = pressLabel(b.pressType);
        meta.appendChild(action);
        meta.appendChild(sub);
        row.appendChild(meta);

        var pill = document.createElement('span');
        pill.className = 'km-press-pill';
        pill.textContent = pressLabel(b.pressType);
        row.appendChild(pill);

        // Edit — pre-fill the Add form from this binding and switch to the Add tab.
        var edit = document.createElement('button');
        edit.className = 'btn btn-icon';
        edit.setAttribute('aria-label', tr('keymap.edit'));
        edit.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:16px;height:16px;"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
        edit.onclick = function () { editBinding(index); };
        row.appendChild(edit);

        var del = document.createElement('button');
        del.className = 'btn btn-icon btn-danger-ghost';
        del.setAttribute('aria-label', tr('keymap.delete'));
        del.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
        del.onclick = function () { removeBinding(index); };
        row.appendChild(del);
        return row;
    }

    function pressLabel(t) {
        if (t === 'double') return tr('keymap.press_double');
        if (t === 'long') return tr('keymap.press_long');
        return tr('keymap.press_single');
    }

    function describeClipWindow(beforeSeconds, afterSeconds) {
        if (beforeSeconds > 0 && afterSeconds > 0) {
            return tr('keymap.clip_summary_both', {
                before: beforeSeconds,
                after: afterSeconds
            });
        }
        if (beforeSeconds > 0) {
            return tr('keymap.clip_summary_before', { before: beforeSeconds });
        }
        return tr('keymap.clip_summary_after', { after: afterSeconds });
    }

    // Human summary of a fire-payload, used when a binding has no stored label.
    function describeAction(a) {
        if (!a) return '';
        if (a.kind === 'vehicle') return tr('keymap.act_' + a.action);
        if (a.kind === 'manualClip') {
            var clipBefore = parseInt(a.beforeSeconds, 10);
            var clipAfter = parseInt(a.afterSeconds, 10);
            if (isNaN(clipBefore)) clipBefore = 0;
            if (isNaN(clipAfter)) clipAfter = 0;
            return tr('keymap.act_manual_clip') + ' — '
                + describeClipWindow(clipBefore, clipAfter);
        }
        if (a.kind === 'catalog') {
            var c = curatedById(a.key);
            var name = c ? tr(c.i18n) : a.key;
            return a.payload ? (name + ' — ' + a.payload) : name;
        }
        if (a.kind === 'api') {
            var ca = curatedById(a.id);
            var nm = ca ? tr(ca.i18n) : (a.path || a.id || 'api');
            // Surface the chosen value so e.g. "Surveillance — enable" reads clearly.
            // Only show a value we can attribute to a real payload — NOT a blind
            // last-path-segment, which mislabels fixed-path actions (camview's
            // ?preset=60/center → "center", stop-audio → "stop-audio").
            var v = '';
            if (a.body && a.body.indexOf('"name"') >= 0) {
                // Play audio/video: the sound filename is the payload.
                var mn = a.body.match(/"name"\s*:\s*"([^"]*)"/);
                if (mn) v = mn[1];
            } else if (a.body && a.body.indexOf('"mode"') >= 0) {
                var m = a.body.match(/"mode"\s*:\s*"([^"]*)"/);
                if (m) v = m[1];
            } else if (a.body && a.body.indexOf('"position"') >= 0) {
                // Seat recall/save: the slot lives in the body as "position":N, and
                // the path is the constant /api/vehicle/seat — surface the slot so
                // "Recall seat position — Slot 1" distinguishes the two slots.
                var mp = a.body.match(/"position"\s*:\s*(\d+)/);
                if (mp) v = tr('keymap.seat_slot_' + mp[1]) || mp[1];
            } else if (ca && ca.path && ca.path.indexOf('${v}') >= 0 && a.path) {
                // The curated template puts the payload IN the path. It may sit in the
                // path proper (e.g. /api/surveillance/${v}) OR inside the query string
                // (e.g. /api/camview/show?...&preset=${v}/center for the camera-view
                // size). Diff over whichever segment holds ${v}: if the placeholder is
                // BEFORE any '?', compare path-only (ignoring the query); otherwise
                // compare the full string so a query-embedded ${v} is recoverable.
                var q = ca.path.indexOf('?');
                var vInQuery = q >= 0 && ca.path.indexOf('${v}') > q;
                var cleanTpl = vInQuery ? ca.path : ca.path.split('?')[0];
                var cleanPath = vInQuery ? a.path : a.path.split('?')[0];
                var tv = recoverV(cleanTpl, cleanPath);
                // Map a recovered camview size % back to its friendly label.
                if (tv != null && ca.payloads) {
                    for (var pi = 0; pi < ca.payloads.length; pi++) {
                        if (ca.payloads[pi].v === tv) { v = tr(ca.payloads[pi].i18n) || tv; break; }
                    }
                    if (!v) v = tv;
                } else if (tv != null) {
                    v = tv;
                }
            }
            return v ? (nm + ' — ' + v) : nm;
        }
        if (a.kind === 'openApp') {
            var appName = a.label || a['package'] || '';
            var suffix = a.split ? ' (' + tr('keymap.split_screen') + ')' : '';
            return tr('keymap.kind_open_app') + ': ' + appName + suffix;
        }
        if (a.kind === 'shell') return tr('keymap.kind_shell') + ': ' + (a.cmd || '');
        if (a.kind === 'sequence') {
            var steps = a.steps || [];
            var parts = [];
            for (var i = 0; i < steps.length; i++) parts.push(describeAction(steps[i]));
            return parts.join(' → ');
        }
        return a.kind || '';
    }

    // ───────────────────────── Config toggles ─────────────────────────

    function saveConfig() {
        state.enabled = $('kmEnable').checked;
        state.allowAdvanced = $('kmAllowAdvanced').checked;
        persist(function (ok) {
            if (ok) { paint(); } else { toast(tr('keymap.save_failed'), 'error'); }
        });
    }

    function persist(cb) {
        fetch('/api/keymap/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                enabled: state.enabled,
                allowAdvanced: state.allowAdvanced,
                bindings: state.bindings
            })
        }).then(function (r) { return r.json(); })
          .then(function (d) {
              // The save response echoes the live a11y state (the daemon may have
              // just auto-enabled the service on the enable edge), so adopt it and
              // let paint() hide the nag without waiting for a full reload.
              if (d && typeof d.a11yEnabled !== 'undefined') state.a11yEnabled = !!d.a11yEnabled;
              if (d && d.success && typeof d.restartRequired !== 'undefined') {
                  state.restartRequired = !!d.restartRequired;
                  paintRestartWarning();
              }
              if (cb) cb(!!(d && d.success));
          })
          .catch(function () { if (cb) cb(false); });
    }

    // ───────────────────────── Capture ─────────────────────────

    function toggleCapture() {
        capturing = !capturing;
        var box = $('kmCaptureBox');
        var label = $('kmCaptureBtnLabel');
        if (capturing) {
            box.classList.add('armed');
            $('kmCapHint').textContent = tr('keymap.capture_press');
            label.textContent = tr('keymap.capture_stop');
        } else {
            box.classList.remove('armed');
            label.textContent = tr('keymap.capture');
        }
        // Tell the native side to route hardware keys to us while armed. Hardware
        // buttons hit the native AccessibilityService (onKeyEvent), NOT the WebView
        // DOM, so without this the capture box can only ever see keys that happen
        // to reach the DOM. When armed, the dispatcher forwards + consumes the key
        // and calls window.KM.onNativeKey(code) below.
        try {
            if (window.AndroidBridge && AndroidBridge.setKeyCapture) {
                AndroidBridge.setKeyCapture(capturing);
            }
        } catch (e) { /* not in the app WebView — DOM keydown fallback still works */ }
    }

    // Record a captured keycode (shared by the native bridge and the DOM
    // fallback). Reflects into the display + manual field and disarms.
    function recordCapturedKey(code) {
        if (!code) return;
        captured = code;
        $('kmCapKeycode').textContent = code;
        $('kmCapHint').textContent = tr('keymap.captured');
        $('kmManualKeycode').value = code;
        // If the captured code is a known long-variant (302/303/306/312), narrow
        // the press-type selector to fixed:'single' now — those codes fire on
        // arrival, so a bound "long" would be a dead binding.
        refreshPressTypeForManual();
        if (capturing) toggleCapture(); // disarm after one capture (also clears native capture)
    }

    // Called by the native AccessibilityService (via evaluateJavascript) when a
    // hardware key arrives during capture — the reliable path on the head unit.
    function onNativeKey(code) {
        if (!capturing) return;
        recordCapturedKey(code);
    }

    // In-page keydown fallback for phone/desktop PWA (and any key that DOES reach
    // the DOM). On the head unit the native bridge above is the real path.
    function onKeydown(e) {
        if (!capturing) return;
        var code = e.keyCode || e.which;
        if (!code) return;
        e.preventDefault();
        recordCapturedKey(code);
    }

    // ───────────────────────── Add / remove ─────────────────────────

    function onKindChange() {
        formDirty = true;
        var kind = $('kmActionKind').value;
        $('kmCuratedWrap').style.display = kind === 'curated' ? '' : 'none';
        $('kmShellWrap').style.display = kind === 'shell' ? '' : 'none';
        var appWrap = $('kmOpenAppWrap');
        if (appWrap) appWrap.style.display = kind === 'openApp' ? '' : 'none';
        if (kind === 'openApp') {
            var splitEl = $('kmOpenAppSplit');
            if (splitEl) splitEl.checked = false; // fresh default when switching into openApp
            buildAppOptions();
        }
    }

    // Rebuild the payload dropdown for the selected curated action. `preselect`
    // (optional) is a payload value to select once options are built — used by
    // editBinding() to restore a saved choice, including for dynamic (async) lists.
    function onCuratedChange(preselect) {
        formDirty = true;
        var c = curatedById($('kmCuratedAction').value);
        var row = $('kmPayloadRow');
        var sel = $('kmPayload');
        var clipWrap = $('kmClipWindowWrap');
        var isManualClip = !!(c && c.kind === 'manualClip');
        if (clipWrap) clipWrap.style.display = isManualClip ? '' : 'none';
        sel.innerHTML = '';
        if (isManualClip) {
            // Manual replay has two numeric dimensions instead of the regular
            // one-value payload dropdown. Invalidate any in-flight dynamic fetch
            // and drive the dedicated clip-window controls instead.
            payloadReqSeq++;
            row.style.display = 'none';
            applyClipPreset(false);
            return;
        }
        if (c && c.dynamicPayloads === 'audio') {
            // Live-populate from the audio library (uploaded on the Automations page).
            row.style.display = '';
            var loading = document.createElement('option');
            loading.value = '';
            loading.textContent = tr('keymap.loading_audio');
            loading.disabled = true; loading.selected = true;
            sel.appendChild(loading);
            // Stale-response guard: a fetch is async, but the user may switch the
            // curated action before it resolves. Stamp this request; on resolve, bail
            // if a newer onCuratedChange has run (token bumped) or the selected action
            // is no longer THIS dynamic one — otherwise a late audio response would
            // clobber a different action's (synchronously-built) payload dropdown.
            var myToken = ++payloadReqSeq;
            var myActionId = c.id;
            var stillMine = function () {
                if (myToken !== payloadReqSeq) return false;
                var cur = curatedById($('kmCuratedAction').value);
                return !!(cur && cur.id === myActionId && cur.dynamicPayloads === 'audio');
            };
            fetch('/api/audio/library', { cache: 'no-store' })
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    if (!stillMine()) return;
                    var sounds = (j && j.success && j.sounds) ? j.sounds : [];
                    sel.innerHTML = '';
                    if (!sounds.length) {
                        var none = document.createElement('option');
                        none.value = '';
                        none.textContent = tr('keymap.no_audio');
                        none.disabled = true; none.selected = true;
                        sel.appendChild(none);
                        return;
                    }
                    for (var k = 0; k < sounds.length; k++) {
                        var so = document.createElement('option');
                        so.value = sounds[k].name;
                        so.textContent = sounds[k].name;
                        sel.appendChild(so);
                    }
                    // Restore a saved selection if it's still present; else keep the first.
                    if (preselect) {
                        var found = false;
                        for (var m = 0; m < sel.options.length; m++) {
                            if (sel.options[m].value === preselect) { found = true; break; }
                        }
                        if (!found) {
                            var miss = document.createElement('option');
                            miss.value = preselect;
                            miss.textContent = preselect + ' ' + tr('keymap.audio_missing');
                            sel.appendChild(miss);
                        }
                        sel.value = preselect;
                    }
                })
                ['catch'](function () {
                    if (!stillMine()) return;
                    sel.innerHTML = '';
                    var err = document.createElement('option');
                    err.value = '';
                    err.textContent = tr('keymap.no_audio');
                    err.disabled = true; err.selected = true;
                    sel.appendChild(err);
                });
            return;
        }
        // A synchronous rebuild also invalidates any in-flight dynamic fetch.
        payloadReqSeq++;
        if (c && c.payloads && c.payloads.length) {
            row.style.display = '';
            for (var i = 0; i < c.payloads.length; i++) {
                var o = document.createElement('option');
                o.value = c.payloads[i].v;
                o.textContent = tr(c.payloads[i].i18n);
                sel.appendChild(o);
            }
            if (preselect) sel.value = preselect;
        } else {
            row.style.display = 'none';
        }
    }

    function updateClipWindowLabels() {
        var before = parseInt($('kmClipBefore').value, 10);
        var after = parseInt($('kmClipAfter').value, 10);
        if (isNaN(before)) before = 0;
        if (isNaN(after)) after = 0;
        $('kmClipBeforeValue').textContent = tr('keymap.clip_seconds', { n: before });
        $('kmClipAfterValue').textContent = tr('keymap.clip_seconds', { n: after });
    }

    // Apply one of the curated windows. "Custom" keeps the current values and
    // reveals both sliders so the user can pick any whole-second split.
    function applyClipPreset(markDirty) {
        var preset = $('kmClipPreset');
        var customWrap = $('kmClipCustomWrap');
        if (!preset || !customWrap) return;
        var value = preset.value || CLIP_DEFAULT_PRESET;
        var custom = value === 'custom';
        customWrap.style.display = custom ? '' : 'none';
        if (!custom) {
            var parts = value.split(':');
            if (parts.length === 2) {
                $('kmClipBefore').value = parseInt(parts[0], 10);
                $('kmClipAfter').value = parseInt(parts[1], 10);
            }
        }
        updateClipWindowLabels();
        if (markDirty) formDirty = true;
    }

    function onClipPresetChange() {
        applyClipPreset(true);
    }

    function onClipWindowChange() {
        formDirty = true;
        // Slider edits always represent an intentional custom window, even if
        // the resulting pair happens to equal one of the convenience presets.
        var preset = $('kmClipPreset');
        if (preset) preset.value = 'custom';
        updateClipWindowLabels();
    }

    function resetClipWindow() {
        var preset = $('kmClipPreset');
        if (preset) preset.value = CLIP_DEFAULT_PRESET;
        var before = $('kmClipBefore');
        var after = $('kmClipAfter');
        if (before) before.value = 30;
        if (after) after.value = 0;
        applyClipPreset(false);
    }

    // Restore a saved manualClip window into the form when editing a binding.
    // Selects the matching preset when the pair is one of the presets, else
    // switches to Custom and reveals both sliders — the inverse of readClipWindow.
    function applyClipWindowToForm(beforeSeconds, afterSeconds) {
        var before = parseInt(beforeSeconds, 10);
        var after = parseInt(afterSeconds, 10);
        if (isNaN(before) || before < 0) before = 0;
        if (isNaN(after) || after < 0) after = 0;
        var beforeEl = $('kmClipBefore');
        var afterEl = $('kmClipAfter');
        if (beforeEl) beforeEl.value = before;
        if (afterEl) afterEl.value = after;
        var preset = $('kmClipPreset');
        if (preset) {
            var pair = before + ':' + after;
            var matched = false;
            for (var i = 0; i < preset.options.length; i++) {
                if (preset.options[i].value === pair) { preset.value = pair; matched = true; break; }
            }
            if (!matched) preset.value = 'custom';
        }
        applyClipPreset(false);
    }

    // Parse and validate the persisted manualClip contract. HTML range inputs
    // already constrain normal interaction, but this remains strict so a DOM edit
    // cannot save fractions, negatives, or a window longer than the daemon accepts.
    function readClipWindow() {
        var beforeRaw = $('kmClipBefore').value;
        var afterRaw = $('kmClipAfter').value;
        var wholeSeconds = /^\d+$/;
        var before = Number(beforeRaw);
        var after = Number(afterRaw);
        var valid = wholeSeconds.test(beforeRaw) && wholeSeconds.test(afterRaw)
            && isFinite(before) && isFinite(after)
            && Math.floor(before) === before && Math.floor(after) === after
            && before >= 0 && before <= CLIP_MAX_BEFORE_SECONDS
            && after >= 0 && after <= CLIP_MAX_AFTER_SECONDS
            && before + after >= 1 && before + after <= CLIP_MAX_TOTAL_SECONDS;
        if (!valid) {
            toast(tr('keymap.clip_window_invalid'), 'error');
            return null;
        }
        return { beforeSeconds: before, afterSeconds: after };
    }

    function buildCuratedOptions() {
        var sel = $('kmCuratedAction');
        sel.innerHTML = '';
        for (var i = 0; i < CURATED.length; i++) {
            var o = document.createElement('option');
            o.value = CURATED[i].id;
            o.textContent = tr(CURATED[i].i18n);
            sel.appendChild(o);
        }
        onCuratedChange();
    }

    // Cached installed-app list [{package,label}] for the openApp picker.
    var appList = null;

    // Populate the app picker from /api/apps/list (fetched once, then cached).
    // Shows a "loading" placeholder until the list arrives.
    function buildAppOptions() {
        var sel = $('kmOpenApp');
        if (!sel) return;
        var fill = function (apps) {
            var prev = sel.value;
            sel.innerHTML = '';
            var ph = document.createElement('option');
            ph.value = '';
            ph.textContent = tr('keymap.pick_app');
            ph.disabled = true; ph.selected = true;
            sel.appendChild(ph);
            var prevFound = false;
            for (var i = 0; i < apps.length; i++) {
                var o = document.createElement('option');
                o.value = apps[i]['package'];
                o.textContent = apps[i].label || apps[i]['package'];
                sel.appendChild(o);
                if (prev && apps[i]['package'] === prev) prevFound = true;
            }
            // If the previously-selected package (e.g. one injected by editBinding for
            // an app that's since been uninstalled, or not yet in the fetched list) is
            // absent, re-add it so the selection round-trips instead of silently
            // reverting to the placeholder (which would make the edit unsaveable).
            if (prev && !prevFound) {
                var keep = document.createElement('option');
                keep.value = prev;
                keep.textContent = prev;
                sel.appendChild(keep);
            }
            if (prev) sel.value = prev;
        };
        if (appList) { fill(appList); return; }
        // placeholder while loading
        sel.innerHTML = '';
        var loading = document.createElement('option');
        loading.value = '';
        loading.textContent = tr('keymap.loading_apps');
        loading.disabled = true; loading.selected = true;
        sel.appendChild(loading);
        fetch('/api/apps/list', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (j) {
                appList = (j && j.success && j.apps) ? j.apps : [];
                fill(appList);
            })
            ['catch'](function () { appList = []; fill(appList); });
    }

    // Populate the "Button" dropdown from KNOWN_BUTTONS. First option is a
    // placeholder ("Choose a button…"); a final option is the custom/capture
    // escape hatch that reveals the capture box + manual field.
    function buildKnownButtonOptions() {
        var sel = $('kmKnownButton');
        if (!sel) return;
        sel.innerHTML = '';
        var ph = document.createElement('option');
        ph.value = '';
        ph.textContent = tr('keymap.pick_button');
        sel.appendChild(ph);
        for (var i = 0; i < KNOWN_BUTTONS.length; i++) {
            var b = KNOWN_BUTTONS[i];
            var o = document.createElement('option');
            o.value = String(b.code);
            // Show the code so power users can still see it: "Next  (87)"
            o.textContent = tr(b.i18n) + '  (' + b.code + ')';
            sel.appendChild(o);
        }
        var custom = document.createElement('option');
        custom.value = 'custom';
        custom.textContent = tr('keymap.btn_custom');
        sel.appendChild(custom);
    }

    function knownByCode(code) {
        for (var i = 0; i < KNOWN_BUTTONS.length; i++) {
            if (KNOWN_BUTTONS[i].code === code) return KNOWN_BUTTONS[i];
        }
        return null;
    }

    // When a known button is chosen: set the keycode, reveal/hide the capture
    // area, and tailor the press-type selector to what THIS button supports.
    function onKnownButtonChange() {
        var sel = $('kmKnownButton');
        var val = sel ? sel.value : '';
        var customWrap = $('kmCustomKeyWrap');
        // Leaving the custom/capture branch while still armed would hide the
        // capture wrap without disarming — the dispatcher would keep globally
        // consuming (and silently swallowing) the next hardware key, suppressing
        // its OEM action. toggleCapture() no-ops the display side effects here but
        // flips `capturing` false AND calls AndroidBridge.setKeyCapture(false),
        // clearing WebViewFragment.captureArmed. Re-entering custom from a
        // non-armed state is unaffected (arming still happens via the Capture btn).
        if (val !== 'custom' && capturing) toggleCapture();
        if (val === 'custom') {
            // Reveal the capture + manual-entry area (the secondary path). An
            // arbitrary captured/typed key gets the full press-type choice (we
            // can't know the firmware's gesture model for an unknown keycode) —
            // but if the entered/captured code turns out to be a KNOWN
            // long-variant (302/303/306/312), refreshPressTypeForManual() locks
            // it to fixed:'single' so the user can't create a never-firing
            // "long" binding (those codes arrive as a plain repeatCount==0 DOWN).
            if (customWrap) customWrap.style.display = '';
            captured = null;
            $('kmManualKeycode').value = '';
            refreshPressTypeForManual();
            return;
        }
        if (customWrap) customWrap.style.display = 'none';
        if (!val) { captured = null; $('kmManualKeycode').value = ''; buildPressTypeOptions(null); return; }
        var code = parseInt(val, 10);
        captured = code;
        $('kmManualKeycode').value = code;
        buildPressTypeOptions(knownByCode(code));
    }

    // Rebuild the press-type <select> for the chosen button:
    //   • fixed button (long-variant keycode) → single locked option; the keycode
    //     arriving already IS the gesture, so it fires on arrival (pressType single),
    //     and we disable the selector so the user can't pick a never-firing "long".
    //   • base button with pressTypes → only those options (no "long", since long
    //     is a different keycode on this firmware).
    //   • null (custom/capture) → all three, free choice.
    function buildPressTypeOptions(btn) {
        var pt = $('kmPressType');
        if (!pt) return;
        var opts;
        var disabled = false;
        if (btn && btn.fixed) { opts = [btn.fixed]; disabled = true; }
        else if (btn && btn.pressTypes) { opts = btn.pressTypes; }
        else { opts = ['single', 'double', 'long']; }
        var labelKey = { single: 'keymap.press_single', double: 'keymap.press_double', long: 'keymap.press_long' };
        pt.innerHTML = '';
        for (var i = 0; i < opts.length; i++) {
            var o = document.createElement('option');
            o.value = opts[i];
            o.textContent = tr(labelKey[opts[i]] || opts[i]);
            pt.appendChild(o);
        }
        pt.value = opts[0];
        pt.disabled = disabled;
    }

    // Press-type selector for the manual/custom path. Reads the current manual
    // keycode and, if it is a KNOWN button (base or long-variant), restricts the
    // selector to exactly what that keycode supports — locking the four
    // long-variant keycodes (302/303/306/312) to fixed:'single' so the custom
    // path can't mint a dead "long" (or an unperformable double-of-a-hold)
    // binding. A genuinely-arbitrary keycode still gets all three types (the
    // escape hatch for platforms that DO auto-repeat). Called on entering the
    // custom branch, after capturing a key, and on every manual-keycode edit.
    function refreshPressTypeForManual() {
        var code = parseInt($('kmManualKeycode').value, 10);
        buildPressTypeOptions(code ? knownByCode(code) : null);
    }

    // In-progress sequence steps (each a fire-action object). When non-empty,
    // "Add binding" wraps them as a {kind:'sequence'} action; a lone step is
    // stored as that single action (no needless sequence wrapper).
    var seqSteps = [];

    // Index of the binding currently being edited, or -1 when adding a fresh one.
    // Set by editBinding(); consumed by addBinding() to replace that exact slot
    // (so a keycode/pressType change during edit doesn't leave the original behind);
    // cleared by resetForm(). Kept as an index into state.bindings — the list is not
    // reordered between opening the editor and saving (persist only happens on save).
    var editIndex = -1;

    // Has the Add-form's action selection been touched since the last "Add step"
    // (or form reset)? Because buildActionFromForm() never returns null for the
    // default curated pick, "Add binding" cannot tell a genuinely-edited-but-not-
    // added selection from the leftover of the previous "Add step". This flag
    // lets addBinding() fold a pending selection ONLY when the user actually
    // edited it, so the documented "Add step … Add binding" chain doesn't
    // silently duplicate its last action (e.g. lock → flash → flash).
    var formDirty = false;

    // Build one fire-action object from the current Add-form selection, or null
    // (with a toast) if the form is incomplete. Shared by "Add binding" and
    // "Add step" so a step and a standalone binding are built identically.
    function buildActionFromForm() {
        var kind = $('kmActionKind').value;
        if (kind === 'curated') {
            var c = curatedById($('kmCuratedAction').value);
            if (!c) { toast(tr('keymap.need_action'), 'error'); return null; }
            if (c.kind === 'vehicle') return { kind: 'vehicle', action: c.key };
            if (c.kind === 'manualClip') {
                var clipWindow = readClipWindow();
                if (!clipWindow) return null;
                return {
                    kind: 'manualClip',
                    beforeSeconds: clipWindow.beforeSeconds,
                    afterSeconds: clipWindow.afterSeconds
                };
            }
            var payload = ($('kmPayload').value != null) ? $('kmPayload').value : '';
            if (c.kind === 'api') {
                // Substitute the chosen payload into the path/body templates; the
                // daemon receives a concrete method/path/body (no ${v} at fire time).
                var sub = function (s) { return (s || '').split('${v}').join(payload); };
                return { kind: 'api', id: c.id, method: c.method || 'POST',
                         path: sub(c.path), body: sub(c.body) };
            }
            var a = { kind: 'catalog', key: c.key, payload: payload };
            if (c.sub) a.sub = c.sub;
            return a;
        }
        if (kind === 'openApp') {
            var sel = $('kmOpenApp');
            var pkg = sel ? sel.value : '';
            if (!pkg) { toast(tr('keymap.need_app'), 'error'); return null; }
            var label = '';
            if (sel && sel.selectedIndex >= 0) label = sel.options[sel.selectedIndex].textContent;
            var splitEl = $('kmOpenAppSplit');
            var split = !!(splitEl && splitEl.checked);
            var act = { kind: 'openApp', 'package': pkg, label: label };
            // Only carry the flag when set, so existing (non-split) bindings serialize unchanged.
            if (split) act.split = true;
            return act;
        }
        if (kind === 'shell') {
            if (!state.allowAdvanced) { toast(tr('keymap.advanced_disabled'), 'error'); return null; }
            var cmd = $('kmShellCmd').value.trim();
            if (!cmd) { toast(tr('keymap.need_cmd'), 'error'); return null; }
            return { kind: 'shell', cmd: cmd };
        }
        return null;
    }

    // Append the current form selection to the in-progress sequence and re-render
    // the step chips. Lets the user compose "close windows → lock → sport mode".
    // After a successful push we blank the action inputs so the just-added value
    // isn't silently re-appended by addBinding()'s pending-fold (WYSIWYG: what's
    // in the chips is what fires).
    function addStep() {
        var a = buildActionFromForm();
        if (!a) return;
        if (a.kind === 'manualClip') {
            toast(tr('keymap.clip_sequence_unsupported'), 'warning');
            return;
        }
        seqSteps.push(a);
        clearActionForm();
        renderSteps();
    }

    // Reset only the action inputs (not keycode/pressType, which describe the key
    // being bound — not the step) so the next step starts blank. Because this is
    // a programmatic reset, clear formDirty AFTER the handlers (which set it) run.
    function clearActionForm() {
        $('kmActionKind').value = 'curated';
        onKindChange();
        $('kmCuratedAction').selectedIndex = 0;
        onCuratedChange();
        $('kmShellCmd').value = '';
        resetClipWindow();
        formDirty = false;
    }

    function removeStep(i) {
        seqSteps.splice(i, 1);
        renderSteps();
    }

    // Swap step i with its neighbour (dir -1 = up, +1 = down) so a one-key routine runs
    // in the order the user wants. No-op at the ends.
    function moveStep(i, dir) {
        var j = i + dir;
        if (j < 0 || j >= seqSteps.length) return;
        var tmp = seqSteps[j]; seqSteps[j] = seqSteps[i]; seqSteps[i] = tmp;
        renderSteps();
    }

    function renderSteps() {
        var wrap = $('kmSeqSteps');
        if (!wrap) return;
        wrap.innerHTML = '';
        wrap.style.display = seqSteps.length ? '' : 'none';
        for (var i = 0; i < seqSteps.length; i++) {
            (function (idx) {
                var chip = document.createElement('div');
                chip.className = 'km-step';
                var span = document.createElement('span');
                span.textContent = (idx + 1) + '. ' + describeAction(seqSteps[idx]);
                chip.appendChild(span);
                // Reorder arrows (only meaningful with 2+ steps). Disabled at the ends
                // so the chip layout stays stable.
                if (seqSteps.length > 1) {
                    var up = document.createElement('button');
                    up.className = 'km-step-move';
                    up.setAttribute('aria-label', tr('keymap.move_up'));
                    up.innerHTML = '&#8593;'; // ↑
                    if (idx === 0) up.disabled = true;
                    else up.onclick = function () { moveStep(idx, -1); };
                    chip.appendChild(up);

                    var down = document.createElement('button');
                    down.className = 'km-step-move';
                    down.setAttribute('aria-label', tr('keymap.move_down'));
                    down.innerHTML = '&#8595;'; // ↓
                    if (idx === seqSteps.length - 1) down.disabled = true;
                    else down.onclick = function () { moveStep(idx, 1); };
                    chip.appendChild(down);
                }
                var x = document.createElement('button');
                x.className = 'km-step-x';
                x.setAttribute('aria-label', tr('keymap.delete'));
                x.innerHTML = '&times;';
                x.onclick = function () { removeStep(idx); };
                chip.appendChild(x);
                wrap.appendChild(chip);
            })(i);
        }
    }

    function addBinding() {
        var keycode = parseInt($('kmManualKeycode').value, 10);
        if (!keycode && captured) keycode = captured;
        if (!keycode || keycode <= 0) { toast(tr('keymap.need_keycode'), 'error'); return; }

        var pressType = $('kmPressType').value;
        var action = null;
        var label = '';

        if (seqSteps.length > 0) {
            // One key → N actions. Only fold an actually-edited, un-added form
            // selection in as the final step — addStep() blanks the form and
            // clears formDirty, so a user who clicked "Add step" for every action
            // doesn't get its last one silently duplicated (lock → flash → flash).
            // A user who edits the form but forgets "Add step" still gets it.
            var steps = seqSteps.slice();
            if (formDirty) {
                var pending = buildActionFromForm();
                if (pending && pending.kind === 'manualClip') {
                    toast(tr('keymap.clip_sequence_unsupported'), 'warning');
                    return;
                }
                if (pending) steps.push(pending);
            }
            if (steps.length === 1) {
                action = steps[0];
                label = describeAction(action);
            } else {
                action = { kind: 'sequence', steps: steps };
                label = tr('keymap.sequence_of', { n: steps.length }) || (steps.length + ' steps');
            }
        } else {
            action = buildActionFromForm();
            if (!action) return; // toast already shown
            label = describeAction(action);
        }

        // Snapshot the list first so a persist failure can roll back to exactly what
        // the server has — otherwise a phantom binding lingers in local state and a
        // later saveConfig()->persist() (from toggling Enable/Advanced) would
        // silently commit it. Mirrors removeBinding's load()-on-failure resync.
        var prev = state.bindings.slice();
        // Preserve the original enabled flag when editing so a Save doesn't silently
        // re-enable a binding the user had toggled off.
        var wasEnabled = (editIndex >= 0 && state.bindings[editIndex])
            ? (state.bindings[editIndex].enabled !== false) : true;
        var newBinding = { keycode: keycode, pressType: pressType, enabled: wasEnabled, label: label, action: action };
        var replaced = false;

        if (editIndex >= 0 && editIndex < state.bindings.length) {
            // Editing an existing binding: replace that exact slot (so a keycode /
            // press-type change updates in place rather than adding a duplicate).
            // If the edit collided the keycode+pressType onto ANOTHER existing
            // binding, drop that other one first so we don't leave a dup the
            // dispatcher would double-fire.
            for (var j = state.bindings.length - 1; j >= 0; j--) {
                if (j !== editIndex && state.bindings[j].keycode === keycode
                        && state.bindings[j].pressType === pressType) {
                    state.bindings.splice(j, 1);
                    if (j < editIndex) editIndex--; // keep the target index valid
                }
            }
            state.bindings[editIndex] = newBinding;
            replaced = true;
        } else {
            // Adding: reject an exact keycode+pressType duplicate — the dispatcher's
            // "newest wins" would otherwise fire both. Replace instead.
            for (var i = 0; i < state.bindings.length; i++) {
                if (state.bindings[i].keycode === keycode && state.bindings[i].pressType === pressType) {
                    state.bindings[i] = newBinding;
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                state.bindings.push(newBinding);
            }
        }

        persist(function (ok) {
            if (!ok) { state.bindings = prev; toast(tr('keymap.save_failed'), 'error'); renderList(); return; }
            toast(replaced ? tr('keymap.updated') : tr('keymap.added'), 'success');
            resetForm();
            paint();
            if (window.OT_setActiveTab) window.OT_setActiveTab('bindings');
        });
    }

    // ───────────────────────── Edit ─────────────────────────

    // Load an existing binding into the Add form and switch to the Add tab. The
    // form then behaves exactly like adding, except addBinding() replaces the
    // original slot (editIndex) on save — so changing the keycode or press type
    // updates in place instead of leaving a duplicate behind.
    function editBinding(index) {
        var b = state.bindings[index];
        if (!b) return;
        // Start from a clean form, then populate. resetForm() clears editIndex, so
        // set it AFTER.
        resetForm();
        editIndex = index;

        // Keycode: prefer the Known-button dropdown when the code is known, else the
        // custom/manual path. Setting the dropdown drives onKnownButtonChange, which
        // fills the keycode + press-type options for us.
        var known = knownByCode(b.keycode);
        var knownSel = $('kmKnownButton');
        if (known && knownSel) {
            knownSel.value = String(b.keycode);
            onKnownButtonChange();
        } else if (knownSel) {
            knownSel.value = 'custom';
            onKnownButtonChange();       // reveals the custom wrap
            $('kmManualKeycode').value = b.keycode;
            refreshPressTypeForManual(); // narrow press types for the entered code
        }
        // Press type — set after the options are built above so the value sticks.
        var pt = $('kmPressType');
        if (pt && !pt.disabled) pt.value = b.pressType || 'single';

        // Action → form. A sequence loads its steps as chips (with the form left
        // blank so addBinding folds nothing extra); a single action loads into the
        // action inputs directly.
        if (b.action && b.action.kind === 'sequence') {
            seqSteps = (b.action.steps || []).slice();
            renderSteps();
        } else if (b.action) {
            loadActionIntoForm(b.action);
        }
        // The programmatic population above tripped the change handlers; the form
        // faithfully mirrors the saved binding, so treat it as pristine — an
        // unmodified Save re-persists the same action (not a duplicated step).
        formDirty = false;

        setFormMode(true);
        toast(tr('keymap.editing'), 'info');
        // Suppress the one tab-change we're about to cause, so the active-changed
        // listener doesn't immediately reset the edit session we just set up.
        suppressNextTabReset = true;
        if (window.OT_setActiveTab) window.OT_setActiveTab('add');
    }

    // True for exactly one 'ot-tabs:active-changed' — the switch editBinding() itself
    // triggers. Any OTHER switch to the Add tab (manual tab click, empty-state CTA)
    // means the user wants a FRESH binding, so we reset edit mode then. This closes
    // the "edit A → tab to Add → save overwrites A" data-loss trap.
    var suppressNextTabReset = false;

    function onTabChanged(id) {
        if (id !== 'add') return;
        if (suppressNextTabReset) { suppressNextTabReset = false; return; }
        // Entered the Add tab NOT via the edit pencil → start clean.
        if (editIndex >= 0) resetForm();
    }

    // Reflect add-vs-edit in the form's title + primary-button label so the user
    // knows a Save will overwrite the existing binding rather than add a new one.
    function setFormMode(editing) {
        var title = $('kmAddTitle');
        var btn = $('kmAddBtnLabel');
        if (title) title.textContent = tr(editing ? 'keymap.edit_title' : 'keymap.add_title');
        if (btn) btn.textContent = tr(editing ? 'keymap.save_changes' : 'keymap.add_binding');
    }

    // Populate the action inputs (kind + curated action + payload / shell / app)
    // from a single fire-action object — the inverse of buildActionFromForm(). Falls
    // back gracefully when a stored action doesn't match a curated entry (e.g. a
    // hand-crafted api action): the kind is still set so the user sees the closest
    // editable representation.
    function loadActionIntoForm(a) {
        var kindSel = $('kmActionKind');
        if (a.kind === 'shell') {
            if (kindSel) kindSel.value = 'shell';
            onKindChange();
            $('kmShellCmd').value = a.cmd || '';
            return;
        }
        if (a.kind === 'openApp') {
            if (kindSel) kindSel.value = 'openApp';
            onKindChange(); // triggers buildAppOptions()
            var appSel = $('kmOpenApp');
            if (appSel) {
                // The list may still be loading; set now and again once it arrives.
                var want = a['package'] || '';
                appSel.value = want;
                if (!appSel.value && want) {
                    // App list not populated yet — inject the value so it round-trips.
                    var o = document.createElement('option');
                    o.value = want; o.textContent = a.label || want;
                    appSel.appendChild(o);
                    appSel.value = want;
                }
            }
            var splitEl = $('kmOpenAppSplit');
            if (splitEl) splitEl.checked = !!a.split;
            return;
        }
        // Manual instant replay: a curated action with its own two-value clip
        // window instead of a payload dropdown. Select it, reveal the clip UI via
        // onCuratedChange, then restore the saved before/after window.
        if (a.kind === 'manualClip') {
            if (kindSel) kindSel.value = 'curated';
            onKindChange();
            var clipSel = $('kmCuratedAction');
            if (clipSel) clipSel.value = 'manual_clip';
            onCuratedChange();
            applyClipWindowToForm(a.beforeSeconds, a.afterSeconds);
            return;
        }
        // catalog / vehicle / api all live under the "curated" kind, matched back to
        // a CURATED entry by key (catalog/vehicle) or id (api).
        if (kindSel) kindSel.value = 'curated';
        onKindChange();
        var curatedId = null;
        if (a.kind === 'vehicle') curatedId = matchCuratedByVehicle(a.action);
        else if (a.kind === 'catalog') curatedId = matchCuratedByCatalog(a.key, a.sub);
        else if (a.kind === 'api') curatedId = a.id || matchCuratedByApiPath(a.path, a.body);
        var curSel = $('kmCuratedAction');
        if (curSel && curatedId) {
            curSel.value = curatedId;
            // Reconstruct the saved payload and pass it as the preselect so
            // onCuratedChange restores it — this also works for DYNAMIC (async) payload
            // lists like the audio library, where a synchronous post-set would race the
            // fetch. For static lists it selects immediately.
            var payloadVal = reconstructPayload(a);
            onCuratedChange(payloadVal != null ? payloadVal : undefined);
        }
    }

    // Match a curated entry whose kind is 'vehicle' and key equals the action verb.
    function matchCuratedByVehicle(actionVerb) {
        for (var i = 0; i < CURATED.length; i++) {
            if (CURATED[i].kind === 'vehicle' && CURATED[i].key === actionVerb) return CURATED[i].id;
        }
        return null;
    }
    // Match a curated 'catalog' entry by key (+ sub when the entry carries one).
    function matchCuratedByCatalog(key, sub) {
        for (var i = 0; i < CURATED.length; i++) {
            var c = CURATED[i];
            if (c.kind === 'catalog' && c.key === key && (c.sub || null) === (sub || null)) return c.id;
        }
        return null;
    }
    // Match a curated 'api' entry by comparing the path prefix (before any ${v}) and
    // the body's stable prefix. Used only when a stored api action lost its id.
    function matchCuratedByApiPath(path, body) {
        for (var i = 0; i < CURATED.length; i++) {
            var c = CURATED[i];
            if (c.kind !== 'api') continue;
            var cPathPrefix = (c.path || '').split('${v}')[0];
            if (path && cPathPrefix && path.indexOf(cPathPrefix) === 0) return c.id;
        }
        return null;
    }

    // Reconstruct the payload option value that produced this action, so the payload
    // dropdown can re-select it. For catalog it's the stored payload; for api we
    // recover ${v} by diffing the stored path/body against the curated template.
    function reconstructPayload(a) {
        if (a.kind === 'catalog') return a.payload != null ? a.payload : null;
        if (a.kind === 'api') {
            var c = curatedById(a.id) || null;
            if (!c) return null;
            // Recover ${v} from whichever template carried it.
            var fromPath = recoverV(c.path, a.path);
            if (fromPath != null) return fromPath;
            return recoverV(c.body, a.body);
        }
        return null;
    }

    // Given a curated template containing ${v} and the concrete stored string, return
    // the substring that ${v} expanded to, or null if the template has no ${v} or
    // doesn't match.
    function recoverV(template, concrete) {
        if (!template || concrete == null) return null;
        var idx = template.indexOf('${v}');
        if (idx < 0) return null;
        var before = template.substring(0, idx);
        var after = template.substring(idx + 4);
        // Concrete must start with the pre-${v} text; strip it.
        if (concrete.indexOf(before) !== 0) return null;
        var rest = concrete.substring(before.length);
        // …and end with the post-${v} text; strip that too (proper endsWith, ES5-safe).
        if (after) {
            if (rest.length < after.length) return null;
            if (rest.substring(rest.length - after.length) !== after) return null;
            rest = rest.substring(0, rest.length - after.length);
        }
        return rest;
    }

    function removeBinding(index) {
        // Do the actual delete once confirmed.
        function doDelete() {
            state.bindings.splice(index, 1);
            persist(function (ok) {
                if (ok) { renderList(); toast(tr('keymap.deleted'), 'success'); }
                else { toast(tr('keymap.save_failed'), 'error'); load(); }
            });
        }
        // Use the app's themed modal (matches every other page). Native
        // window.confirm looks foreign in the WebView; keep it only as a
        // fallback for very-early-init / older shells without BYD.utils.
        if (window.BYD && BYD.utils && BYD.utils.confirmDialog) {
            BYD.utils.confirmDialog({
                title: tr('keymap.delete'),
                body: tr('keymap.confirm_delete'),
                confirmLabel: tr('keymap.delete'),
                cancelLabel: tr('common.cancel'),
                danger: true
            }).then(function (ok) { if (ok) doDelete(); });
        } else if (window.confirm(tr('keymap.confirm_delete'))) {
            doDelete();
        }
    }

    function resetForm() {
        captured = null;
        editIndex = -1;
        $('kmManualKeycode').value = '';
        $('kmCapKeycode').textContent = '—';
        $('kmCapHint').textContent = tr('keymap.capture_idle');
        $('kmShellCmd').value = '';
        resetClipWindow();
        seqSteps = [];
        formDirty = false;
        renderSteps();
        setFormMode(false);
    }

    // Empty-state CTA — jump to the Add tab for a FRESH binding. Reset first so a
    // leftover edit session (editIndex) can't turn this "add" into an overwrite.
    function goAdd() {
        resetForm();
        if (window.OT_setActiveTab) window.OT_setActiveTab('add');
    }

    function openA11ySettings() {
        // Standard OS accessibility settings. In the head-unit WebView this may
        // be intercepted by the native shell to deep-link BYD's a11y screen.
        try { window.location.href = 'intent://settings/accessibility'; } catch (e) {}
        toast(tr('keymap.a11y_hint'), 'info');
    }

    // ───────────────────────── Init ─────────────────────────

    function init() {
        buildCuratedOptions();
        buildKnownButtonOptions();
        onKnownButtonChange(); // set initial custom-wrap visibility (placeholder → hidden)
        onKindChange();
        document.addEventListener('keydown', onKeydown, true);
        // Reset a stale edit session when the user switches to the Add tab by any
        // means other than the edit pencil (see onTabChanged) — closes the edit trap.
        document.addEventListener('ot-tabs:active-changed', function (e) {
            try { onTabChanged(e && e.detail ? e.detail.id : null); } catch (_) {}
        }, false);
        // Mark the Add-form touched when the user edits the free-text / payload
        // inputs directly (the two <select>s already flag via their onchange
        // handlers). Lets addBinding() distinguish a real un-added selection from
        // the blanked leftover of "Add step". Wired once; harmless when disabled.
        wireDirty('kmPayload');
        wireDirty('kmShellCmd');
        wireDirty('kmManualKeycode');
        // Typing a keycode directly (custom path) should immediately narrow the
        // press-type selector: entering a known long-variant (302/303/306/312)
        // locks it to 'single', preventing a never-firing "long" binding, while
        // an unknown code keeps all three types. Complements the capture-path and
        // custom-branch calls to refreshPressTypeForManual().
        var manualEl = $('kmManualKeycode');
        if (manualEl) {
            manualEl.addEventListener('input', refreshPressTypeForManual, false);
            manualEl.addEventListener('change', refreshPressTypeForManual, false);
        }
        // buildCuratedOptions()/onKindChange() above set formDirty as a side
        // effect of programmatic setup; the form is pristine at rest, so clear it.
        formDirty = false;
        load();
    }

    // Flag the form dirty on any user edit of the given input.
    function wireDirty(id) {
        var el = $(id);
        if (!el) return;
        var mark = function () { formDirty = true; };
        el.addEventListener('input', mark, false);
        el.addEventListener('change', mark, false);
    }

    return {
        init: init,
        load: load,
        saveConfig: saveConfig,
        toggleCapture: toggleCapture,
        onNativeKey: onNativeKey,
        onKnownButtonChange: onKnownButtonChange,
        onKindChange: onKindChange,
        onCuratedChange: onCuratedChange,
        onClipPresetChange: onClipPresetChange,
        onClipWindowChange: onClipWindowChange,
        addStep: addStep,
        addBinding: addBinding,
        goAdd: goAdd,
        openA11ySettings: openA11ySettings
    };
}());
