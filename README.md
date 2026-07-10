# Frame Interpolation - Optical Flow Edition

**Pure optical flow-based frame interpolation for Android.** No heavy ML models, no special GPU extensions needed. Works on screen capture without root.

## Current progress

- Real screen capture is now implemented using `MediaProjection`.
- `VirtualDisplay` and `ImageReader` are used to capture the screen into bitmaps.
- Captured frames are fed into an optical flow interpolator.
- Generated frames are displayed in an overlay on top of the screen.
- A live on-screen graph shows real frames, generated frames, and total frames.
- Capture scaling is adaptive to the device screen size so it fits lower-resolution displays.

## How it works

1. The app requests screen capture permission via `MediaProjection`.
2. A `VirtualDisplay` is created with an `ImageReader` surface.
3. Each captured screen image is converted to a bitmap.
4. Consecutive frames are converted to grayscale and analyzed with OpenCV Farneback optical flow.
5. The flow vectors are used to warp the previous frame toward the current frame.
6. The interpolated frame is displayed as an overlay, and frame counts are updated.

## Features

- ✅ No root required
- ✅ Screen capture-based interpolation
- ✅ CPU-only optical flow using OpenCV
- ✅ Overlay display of generated frames
- ✅ Frame stats graph showing real/generated/total counts
- ✅ Adaptive capture size for smaller screens

## Limitations

- **CPU-bound** — optical flow is intensive and will be slower on weaker phones.
- **Capture resolution tradeoff** — lower capture sizes improve speed but reduce detail.
- **Artifacts possible** — Farneback flow and forward warping can produce ghosting on fast motion.
- **Overlay permission required** — the app needs permission to draw over other apps.

## Build

```bash
./gradlew :app:assembleDebug
```

APK is generated in `app/build/outputs/apk/debug/app-debug.apk`.

## How to use

1. Install APK
2. Open app and tap **Start Frame Interpolation**
3. Grant overlay permission if requested
4. Grant screen capture permission when prompted
5. The interpolated output appears in an overlay with frame counters
6. Stop by closing the app or the service notification

## Notes

- This is a prototype implementation designed to run on a range of Android devices.
- For best performance on entry-level phones, keep the capture resolution low.
- The app is meant to work with screen capture only, not direct camera input.
