# XPlayer2 1.1.2b2 — network shares with a login, and 3D-safe dialogs

**This is a pre-release.** None of it has been tested on glasses hardware yet.

## Network shares (SMB)

- Browse a share and play its files, with seeking.
- The add form has username, password and domain fields. If a server refuses the login, the app
  asks for one and tries again.
- The password is stored in the app's private settings. It is never put in the link or in Recent.

## Dialogs and keyboard on a 3D screen

- On a 32:9 screen, dialogs are drawn once for each eye instead of stretching across both.
- Dialogs with text fields have an on-screen keyboard (EN, RU, symbols). A remote or the
  head-gesture D-pad can press its keys.

## Companion devices (Pocket TV and similar)

- The glasses button now explains that these glasses have no USB control channel.
- On a 32:9 screen the button shows "3D".

## RayNeo

- RayNeo GT is supported (2D/3D from the app). It is not tested on a real GT yet.
- RayNeo Air 4 Pro uses the same entry as the Air 3s Pro.

## Not fixed yet

- Subtitles in 3D may still break. We need a description of the problem to fix it.
