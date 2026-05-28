Lazy 3D depth model
====================

This app's "Lazy 3D" toggle synthesises stereo depth on top of any 2D source by running a
mobile-friendly monocular-depth network on every (or every N-th) frame. The model itself
is NOT bundled in the APK — it is ~24 MB and licensed separately.

To enable Lazy 3D depth:

1. Download a TFLite build of Depth-Anything-V2-Small. Known good source:
     https://huggingface.co/google/depth_v2_small
   or any community export at 256x256 INT8 with NHWC float-input / float-output.

2. Rename the file to  `depth_v2_small.tflite`.

3. Drop it into  `app/src/main/assets/`  alongside this README and rebuild the APK.

If the file is missing, Lazy 3D falls back to head-tracking parallax only (the IMU path
from XREAL goggles), and the depth-synthesis half of the feature is silently disabled.
A line like "Lazy 3D: model asset ... not present" is emitted to logcat at startup.

License notes
-------------
Depth-Anything-V2 weights are released under Apache 2.0 and can be redistributed.
The reference implementation, evaluation code and trainers live under the original
project at https://github.com/DepthAnything/Depth-Anything-V2.

The view-synthesis math used in our GL shader is inspired by `nunif/iw3`
(https://github.com/nagadomi/nunif) — backward-warp + edge dilation. Algorithm only,
not source code; no AGPL contamination.
