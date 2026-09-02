# XPlayer2 1.1.1b3 — the remote learns 3D

**This is a pre-release.** Everything here has been used on real hardware, but not yet by many
people. If something is wrong, a bug report is worth more to us than silence.

Works with **XPlayer2 PC Link 0.1.4 or newer** on the computer — the new controls only appear once
the desktop app offers them, so update both sides:
https://github.com/Xandir150/xplayer2-link-releases/releases/latest

---

## 3D, adjusted from your lap

When the PC is converting to stereo, the remote grows a **3D panel**: two sliders — strength and
where the screen plane sits — and a **Reset** that puts them back to the defaults. You adjust while
looking at the picture; the desktop app's own sliders follow along, and the setting is saved on the
PC exactly as if you had moved it there. In 2D the panel is not there at all.

## The PC's numbers, on the phone

Behind the details door: what the computer captures and encodes per second, how long the encoder
takes per frame, and what it sends over the wire — the same figures the desktop window shows, so
you can tell a slow PC from a slow network without leaving the sofa.

## A media strip

Previous, play/pause, next, volume and mute — one row above the touchpad. These go to the PC as
ordinary media keys, so they reach whatever is playing there and do nothing in a game.

## Half-width 3D fixed

With the desktop app's half-width stereo layout the picture in the glasses was square-ish. Each eye
is now fitted at the desktop's real shape, whichever layout the PC sends.

## Also

- The connect screen says where the desktop app is (xplayer2.app/#pclink), for a phone that has
  nothing to find yet.
- Spanish and Chinese now cover the whole PC Link flow.

## Requirements

XR glasses over USB-C (XREAL, RayNeo, VITURE, or a generic DisplayPort dongle), Android 10 or newer.
PC Link needs the desktop app on Windows 11 or an Apple Silicon Mac, both devices on the same network.
