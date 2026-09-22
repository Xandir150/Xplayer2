# XPlayer2 for Android — Unreleased

## What's new

- Added fold-aware player and remote layouts for book and tabletop postures. Video and controls use separate screen areas around the fold.
- Restored YouTube link playback through the system Share menu. Shared text can include a title before the URL. Video titles are preserved, and no YouTube browsing interface is added.
- Updated YouTube stream extraction, verified selected video and audio URLs before playback, and applied the required HTTP headers to all playback paths.
- Preserved playback position, track settings, and paused state across activity recreation and background transitions. Opening the playback notification no longer replaces the current source.
- Prevented an older link-resolution request from replacing a newer video. Switching sources clears stale audio URLs, headers, and titles.
- Kept Lazy 3D initialization, inference, and cleanup on its worker thread. Processing remains latest-frame-only, with NPU → GPU → CPU fallback priority.
- Reduced unnecessary GPU readback and depth work when no new video frame is available. Readback pacing now accounts for inference time and thermal limits.
- Updated the compile SDK to 37 and the JVM JSON test dependency.

## Validation

- Full and Play debug builds completed successfully.
- 619 JVM tests passed.
- Simulator checks covered lifecycle restoration, notification reopening, fold layouts, and Share-menu registration.
- A shared YouTube video played beyond 30 seconds with 1080p video, AAC audio, and its title.

YouTube support remains limited to anonymously available direct or HLS streams. SponsorBlock integration is not included. Accelerator performance still requires testing on physical hardware.

## Android playback follow-up

- Prefer adaptive HLS for shared YouTube links, cap video at 1080p, and allow quality to adapt on Wi-Fi. Increase startup and rebuffer protection for YouTube; retain verified direct streams as fallbacks.
- Lock both video and PC Link remote-control screens to portrait on phones.
- Add a non-debuggable preview build signed with the local debug certificate for in-place device updates. This avoids Android's debug-only compatibility dialog. The full flavor still includes VITURE's 4 KB-aligned SDK; this change does not make that SDK 16 KB-compatible.

- Validation: the Full preview build completed successfully and was installed on the Samsung SM-S918B without clearing app data. The user tested the update and confirmed that Android playback was satisfactory.
