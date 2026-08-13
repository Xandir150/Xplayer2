# XPlayer2 1.1.1b2 — the remote grows hands

Everything from 1.1.1b, plus the part that was missing: the phone can now **drive** the computer it
is showing you.

---

## Your PC, controlled from your lap

The glasses show the desktop. Now the phone works it.

**The phone is a trackpad.** Drag to move the pointer, tap to click, two fingers to scroll, two
fingers to tap for the right button, press and hold to drag something. The screen stays black in
your lap so a lit phone does not shine up into the glasses.

**A real mouse, if you have one.** Plug a USB or Bluetooth mouse into the phone and it drives the PC
directly — buttons and wheel included. The phone stops using it for itself while the remote is open,
so the pointer you are moving is the one in the glasses.

**A keyboard too.** Keys travel by physical position, so the PC's own layout decides what they mean —
Ctrl+C is Ctrl+C, arrows are arrows, Alt+Tab switches windows, and none of it depends on what
language your phone thinks it is typing in. There is a row of sticky modifiers for the combinations
no on-screen keyboard can produce, and anything you type on the phone's own keyboard — including
characters no physical key makes — arrives as text.

**Nothing is left held down.** If the connection dies, the app is closed, or the phone is put in a
pocket, every key and button still pressed is released on the PC. A stuck Ctrl on someone else's
desktop is the worst way this could fail, so it is the one thing that happens no matter how the
session ends.

**It only works over an encrypted link.** The control channel between phone and PC is now sealed —
keystrokes and pointer movements cannot be read or altered by anything else on the network, and a
connection that tries to fall back to the unencrypted version of the protocol is refused rather than
quietly accepted. Video is unchanged and unencrypted: it is the part that would cost you frames, and
it is the part with nothing secret in it.

It is on once you pair. Pairing — six digits agreed on both screens — is where you decided to trust
that phone; you can turn input off in the desktop app if you would rather it stayed a screen.

---

## PC Link

Your whole desktop, in the glasses, over your own Wi‑Fi.

Not a screen-mirroring toy — the computer does the work and the phone stays cool. A companion app
runs on your PC or Mac, captures the screen, encodes it, and sends it to the phone; the phone
decodes it in hardware and puts it on the glasses. Your battery barely notices.

**A sharp picture, on purpose.** The desktop is captured at its native backing resolution and
scaled down by exactly two — a real box filter, not a stretch — so text arrives with Retina
crispness instead of the soft edges you get from a naive downscale. And it is not stretched to fill
the panel: a 16:10 desktop stays 16:10 in 16:9 glasses, letterboxed rather than squashed.

**Colours that are actually your colours.** The stream carries its colour signalling instead of
leaving the decoder to guess — BT.709, full range, sRGB composition, end to end. Whites are white
and skin is skin.

**Sound follows the picture.** Switch it, and your computer's audio moves to the glasses rather than
playing in both places. One switch on the phone says where the sound is, and switches it back.

---

## 3D from a computer that has no idea it is in 3D

This is the part worth reading twice.

Put the glasses into their 3D mode and the desktop arrives **already in stereo**. The depth is
estimated **on the PC**, by the PC, using the whole of its GPU — and what crosses the network is a
finished stereo pair. The phone never has to think about it.

Which means the interesting thing: **there is no longer any limit on what you can watch in 3D.**

- **Any game, in stereo.** Not a list of supported titles — any of them. If it renders on your
  screen, it converts.
- **Any video, from any source.** A streaming service in a browser window, a file no phone codec
  will open, a video call, a 40 GB remux you were never going to copy across.
- **Any application at all.** A map, a photo library, a 3D model, a spreadsheet if that is your idea
  of a good time.

The conversion is tuned where there is room to tune it — on the computer. Depth strength,
convergence, which model runs, and how hard it works are all settings in the desktop app, and they
take effect on the running stream while you watch. The glasses' own 2D/3D switch drives it: put them
in 3D and the PC starts converting; put them back and it stops.

**Get the desktop app** (free):

| | |
|---|---|
| **Windows 11** | https://github.com/Xandir150/xplayer2-link-releases/releases/latest |
| **macOS (Apple Silicon)** | https://github.com/Xandir150/xplayer2-link-releases/releases/latest |

Pairing is a six-digit code shown on both screens, compared once — the same check your headphones
use. Nothing leaves your network, and nothing goes through us.

---

## Also in this release

- **A PC-Mirror tab of its own**, next to Recent and Sources: the computers you have paired with,
  one tap to reconnect, swipe or long-press to forget one.
- **Its own remote**, separate from the film remote and built like it — two live numbers that tell
  you whether the stream is healthy, and the details behind a door for when it is not.
- **The desktop app says why the phone cannot find it.** If Windows' firewall is the reason, it now
  names the rule and the network profile instead of leaving you to guess.
- **Spatial audio for films** (off by default) on routes where the platform can render it.

---

## What is next — spaces

Today PC Link gives you **one screen**, filling your view, and it goes where you look.

The next step is **spaces**: several windows placed around you and left there, so turning your head
means something — a browser to the left, a game ahead, chat down and to the right, all standing
still in the room while you look between them.

The pieces are already built and shipping dark in this release: head tracking from the glasses' own
sensors, and a renderer that holds a screen fixed in the world. What is not solved yet is what makes
the difference between a demo and something you would use all evening:

- **Fitting the room to the glasses.** A canvas has to sit inside roughly 40°×23° to be seen whole,
  and several of them have to be reachable without a sore neck.
- **Yaw that does not wander.** A gyroscope with no absolute reference drifts, and a wall of windows
  that slowly slides to the left is worse than no wall at all. This needs solving properly, not
  papering over with a re-centre button.
- **Windows, not a screenshot.** Capturing and placing individual windows, with depth, instead of
  one flat rectangle.

So it is deliberately off in this build. One screen that behaves is worth more than four that
wander — and when spaces arrive, they will arrive working.

Game controllers are the other thing being looked at: passing a gamepad from the phone through to
the PC, so the games you can now watch in 3D are games you can also play.

---

## iOS

XPlayer2 for iPhone and iPad has all of this too, PC Link and the new controls included. It will be
**available on the App Store when it is released** — same features, same desktop app, same pairing.

---

## Requirements

XR glasses over USB-C (XREAL, RayNeo, VITURE, or a generic DisplayPort dongle), Android 10 or newer.
PC Link additionally needs the desktop app on Windows 11 or an Apple Silicon Mac, and both devices
on the same network.
