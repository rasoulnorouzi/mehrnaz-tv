# Target device: Xiaomi Mi TV P1 43" (ELA4584EU)

Researched 2026-10-11. Some facts come from retailer pages or the 55" sibling. **(unverified)** marks
anything not yet confirmed on the device. Confirm with `adb shell getprop` in M0 and update this file.

| Item | Value |
|---|---|
| Model | Mi TV P1 43", ELA4584EU (L43M6-6AEU), 2021 |
| SoC | MediaTek MT9611: 4× Cortex-A55 @ 1.5 GHz, Mali-G52 MP2 |
| RAM / storage | **2 GB** / 16 GB |
| OS | **Android TV 11 (API 30)**, confirmed by the user on 2026-10-11 (the TV received the update) + Xiaomi PatchWall launcher. App minSdk stays 29 |
| Panel | 4K 3840×2160 @ 60 Hz. UI is rendered at 1080p and upscaled; video on SurfaceView can use full res |
| HDR | Dolby Vision, HLG, HDR10 (HDR10+ unclear). Irrelevant for our SDR H.264 sources |
| Audio | Dolby Audio, DTS-HD, eARC on one HDMI port |
| Video HW decode | H.264 ✔, HEVC ✔, VP9 ✔ (likely); AV1 uncertain. **Our streams are H.264 MP4, so HW decode is guaranteed** |
| Network | Dual-band Wi-Fi (2.4/5 GHz; probably Wi-Fi 5), Ethernet, BT 5.0 |
| Known issues | Gets sluggish over time (low RAM), apps killed in the background, occasional freezes on app switch |

## ADB setup (M0.4)
1. Settings → Device Preferences → About → click **Build** (some Mi TVs: model name) 7× → developer options.
2. Developer options → enable **USB debugging / ADB debugging** (Xiaomi calls it "ADB debug").
3. Find the TV IP: Settings → Network.
4. On the PC: `adb connect <IP>:5555` → accept the "Allow debugging" prompt on the TV (tick "always allow").
5. If refused: Mi TVs may need one USB-A↔USB-A cable session for `adb tcpip 5555`, which may reset on reboot.
   Fallback: install a "Wireless ADB" toggle helper or re-run after reboots (record the outcome here).

## The app on the device
- Package (`applicationId`): `app.mehrnaz.tv`; launcher label «مهرناز»; launch component `app.mehrnaz.tv/app.bolbol.tv.MainActivity` (code namespace unchanged). `tools/tv.sh` uses these as its `PKG` / `ACTIVITY` defaults.
- Deep link check: `adb shell am start -a android.intent.action.VIEW -d "mehrnaz://play/353371/0/0" app.mehrnaz.tv`.

## Implications for the app
- 2 GB RAM: keep total PSS ≤ 250 MB; low-memory killer is aggressive, so persist progress often.
- API 29: no `Modifier.blur`, no `frameOverrunMs`, can't generate Baseline Profiles on-device.
- PatchWall: our app appears under "Apps". The Watch Next row may appear only on the stock Android TV home **(unverified)**.
- Remote: D-pad, OK, Back, Home, Menu (≡), mic (Google Assistant), volume, Netflix/Prime buttons.
  Handle `KEYCODE_MENU` for the overlay and `KEYCODE_MEDIA_*` for play/pause/ff/rw.

## Verified on device (fill in during M0)
- [ ] `getprop ro.build.version.sdk` = ?
- [ ] `getprop ro.product.model` = ?
- [ ] Display mode / UI density (`wm size`, `wm density`) = ?
- [ ] Network ADB works without USB? = ?
- [ ] Official app package name + version = ?
