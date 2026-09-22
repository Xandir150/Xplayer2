# XPlayer2 1.1.1

## Playback and sharing

- Restore YouTube playback from shared or pasted links. Shared text can include a video title before the URL.
- Prefer adaptive HLS for YouTube, with automatic quality up to 1080p and improved buffering. Verified direct streams remain available as fallbacks.
- Keep YouTube playback focused on the shared video, without a browsing interface.
- Preserve playback position, selected tracks, and paused state across screen recreation and background transitions.
- Keep the current video when opening playback controls from a notification. Prevent an older link request from replacing a newer video.

## Phone controls and foldable devices

- Keep video and PC Link remote controls in portrait on phones.
- Adapt video and control layouts to book and tabletop fold positions.
- Retain PC Link stereo depth controls, playback controls, performance information, and the corrected half-width stereo display from the beta releases.

## Lazy 3D

- Improve worker initialization and cleanup while keeping NPU → GPU → CPU fallback priority.
- Process the latest available frame and reduce unnecessary GPU readback, with pacing based on inference time and thermal limits.

## Downloads

- The GitHub APK includes VITURE mode switching. Its VITURE library still uses 4 KB alignment.
- The Google Play build excludes the VITURE SDK and retains 16 KB page-size support.
- Android 10 or newer is required. PC Link controls require XPlayer2 PC Link 0.1.4 or newer.

YouTube support covers anonymously available streams. SponsorBlock integration is not included.
