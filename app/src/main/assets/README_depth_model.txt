Lazy 3D depth model
====================

The "Lazy 3D" toggle synthesises stereo depth on top of any 2D source by running a
monocular-depth network per frame. The model is NOT bundled in the APK — it is
auto-downloaded at runtime (first time Lazy 3D is enabled, or pre-fetched in the
background on Wi-Fi at first launch) from the project's GitHub release.

Model: MiDaS v2.1 small, 256x256, FP32 (~65 MB) — downloaded at runtime, DELIBERATELY not
  bundled: a bundled copy takes priority over the cache AND disables the update check
  (isBundled() short-circuits isUpdateAvailable()), which would freeze the model forever.
  Keeping it remote lets us publish a tuned/updated .tflite and have every install pick it
  up automatically (size-change check, once per process, see PlayerActivity's update path).
  input  : float32 [1,256,256,3] RGB, ImageNet-normalised
  output : float32 [1,256,256,1] inverse depth (higher = nearer)

Model: V-Model (our own DA-V2 distillation, 448x448) — DORMANT since 1.0.10b5: lost the
  beta A/B to the tuned MiDaS, its asset was removed from the APK (−14 MB) and its enum
  entry is selectable=false. To re-test a retrained version: flip selectable in
  DepthModelManager.DepthModel.V_MODEL and either bundle the new .tflite here or publish
  it at the entry's URL. Same I/O contract as MiDaS (ImageNet-normalised RGB in, inverse
  depth out); gpuSafe was never verified (possible DA-V2 ViT remnant, TF #93476).

Download URL and filename for the downloaded models are defined in DepthModelManager
(release tag `models-v1`).

Manual / offline install
------------------------
Drop a model's `.tflite` file (matching its `DepthModel.filename`) into this `assets/`
folder and rebuild — the bundled copy takes priority over the download.

Upgrading the model later
-------------------------
Publish a new .tflite under a new release tag, bump REMOTE_VERSION in DepthModelManager.
Clients notice the size change on their next update check and re-download automatically.
If you switch to a different architecture (e.g. Depth-Anything-V2), keep the same float
NHWC I/O contract or update DepthEstimator's pre/post-processing to match.

Credits
-------
View-synthesis math (depth -> per-eye disparity, edge dilation) is adapted from the ideas
in nagadomi/nunif (iw3): https://github.com/nagadomi/nunif . Algorithm only.
MiDaS: https://github.com/isl-org/MiDaS (MIT).
