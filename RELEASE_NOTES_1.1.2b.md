# XPlayer2 1.1.2b — controls for XREAL One, and a new main screen

**This is a pre-release.** Most of it has been used on real hardware, but the XREAL One controls
have not (see below). If something is wrong, a bug report is worth more to us than silence.

---

## Glasses tab for XREAL One, One Pro and One S (experimental)

A new **Glasses** page controls XREAL One-series glasses from the phone:

- **Screen mode:** 2D at 60, 90 or 120 Hz, or 3D (3840×1080).
- **Brightness** 0–9 and **dimming** (three levels).

The page appears only when the glasses answer on their control port, and it is hidden for Air-series
glasses and for other brands. The glasses do not report their brightness or dimming, so the page
shows the last value you set in the app.

**Please read:** this protocol comes from public research (0xcaff/xr-tools). It has **not been
verified on real One, One Pro or 1S glasses** yet. It also needs a phone that brings up the glasses'
USB network adapter; some phones may need USB Ethernet or tethering mode. If the page does not
appear, or a command does nothing, tell us the phone and glasses models.

## New main screen

- On phones the pages are now in a **bottom navigation bar**. Swiping between pages still works.
  TVs and boxes keep the tab strip at the top.
- **Sources is split** into two pages: **Files** (open a video from the phone) and **Network** (URL,
  Hughey, SMB and DLNA).

## Also

- Player and remote screens have layouts for foldable phones in book and tabletop postures.
- Playback position, track settings and paused state survive the app being recreated or sent to the
  background. Opening the playback notification no longer replaces the current video.
- An older link resolution can no longer replace a newer video.
- Lazy 3D does less GPU readback and depth work when there is no new frame, and respects thermal
  limits.
- The video and PC Link remote screens are locked to portrait on phones.

## Requirements

XR glasses over USB-C (XREAL, RayNeo, VITURE, or a generic DisplayPort dongle), Android 10 or newer.
PC Link needs the desktop app on Windows 11 or an Apple Silicon Mac, both devices on the same network.
