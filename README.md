# GestureCanvas 

Draw in the air with your index finger. An Android app that turns the front camera into a gesture-controlled canvas using on-device hand tracking.



## Features

- **Draw** with your index finger, no touch needed
- **Preview** your drawing in a popup by showing an open palm
- **Clear** the canvas by making a fist
- Runs fully **on-device**, no internet or account required
- Smooth strokes with adaptive filtering and Bezier curve rendering

## Gestures

| Hand sign | Action |
|---|---|
| Index finger up, other fingers curled | Draw |
| Open palm (hold ~0.7 s) | Open drawing popup |
| Fist (hold ~0.9 s) | Clear drawing |
| Any other pose | Pen up (stroke ends) |

On-screen **Clear** and **Show drawing** buttons are also available as a fallback.

## Tech stack

| Area | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (Canvas, Dialog) |
| Camera | CameraX (`Preview` + `ImageAnalysis`) |
| Hand tracking | MediaPipe Tasks Vision, Hand Landmarker |
| Build | Gradle (AGP 9.2.1, Gradle 9.4.1), `minSdk 24` |

## How it works

```
CameraX frame
    -> MediaPipe Hand Landmarker (21 landmarks, GPU delegate with CPU fallback)
    -> HandResult (normalized, mirrored for front camera)
    -> DrawingEngine (gesture state machine + smoothing)
    -> Compose Canvas (incremental Path rendering)
```

1. **Frames**: CameraX streams frames to the analyzer. Only the latest frame is kept (`KEEP_ONLY_LATEST`), so slow inference never builds a queue.
2. **Landmarks**: MediaPipe returns 21 hand landmarks per frame. Rotation is handled by MediaPipe via `ImageProcessingOptions`, avoiding an extra bitmap copy per frame.
3. **Gesture detection**: A finger counts as extended when its tip is farther from the wrist than its PIP joint (compared in pixel space, so image aspect ratio does not skew the result).
4. **Pen position**: Landmark 8 (index fingertip) is mapped to screen coordinates, accounting for the `FILL_CENTER` crop of the preview.
5. **Rendering**: Points are appended to a Compose `Path` using quadratic Bezier segments. Strokes are never rebuilt per frame.

## Engineering notes

- **Time-based thresholds, not frame-based.** Debouncing in frames made behavior depend on FPS. On a slow device, "4 frames" became ~400 ms and strokes broke randomly. All gesture timings are now in milliseconds.
- **Hysteresis on the draw gesture.** Starting a stroke needs a strict pose; continuing needs a lenient one. This absorbs landmark jitter without ending strokes.
- **Adaptive smoothing.** Slow finger movement is smoothed more, fast movement less, which reduces jitter without adding visible lag.
- **Latency.** Lower analysis resolution (480x640), stale-frame dropping, MediaPipe-side rotation, and incremental path building.
- **Mirror handling.** Front camera preview is mirrored, so x coordinates are flipped once in the tracker rather than transforming bitmaps.

## Getting started

### Requirements

- Android Studio (recent stable) with Android SDK Platform 37
- JDK 17+
- A device with a front camera (tested on Samsung SM-A035F)

### Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/saifikram969/<repo-name>.git
   ```
2. Download the Hand Landmarker model:
   [hand_landmarker.task](https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task)
3. Place it exactly here (create the `assets` folder if missing):
   ```
   app/src/main/assets/hand_landmarker.task
   ```
4. Open the project in Android Studio, sync Gradle, and run on a device.
5. Grant the camera permission when prompted.

### Troubleshooting

| Problem | Fix |
|---|---|
| `FileNotFoundException: hand_landmarker.task` | The model is not in `app/src/main/assets/` (it must be inside `main`), or the file name is wrong |
| Crash on start with GPU errors | Set `TRY_GPU = false` in `HandTracker.kt` |
| Gestures not detected | Use good lighting and keep the whole hand inside the frame |

## Tuning

Constants at the top of `HandXrayScreen.kt`:

| Constant | Purpose |
|---|---|
| `START_MS` | How long the draw pose must hold before a stroke starts |
| `STOP_MS` | How long the pose can be lost before a stroke ends |
| `PALM_MS` | Hold time for the open-palm popup |
| `FIST_MS` | Hold time for the fist clear gesture |
| `MIN_POINT_PX` | Minimum distance between recorded points |

## Limitations

- Tracks one hand (`setNumHands(1)`) to keep latency low
- Accuracy drops in poor lighting or when fingers are partly hidden
- Drawings are not persisted between sessions yet

## Roadmap

- [ ] Color change gesture
- [ ] Eraser
- [ ] Save drawing to gallery
- [ ] Front/back camera toggle

## Project structure

```
app/src/main/
├── assets/hand_landmarker.task      (downloaded separately)
└── java/com/example/skinpeel/
    ├── MainActivity.kt
    ├── HandTracker.kt               (MediaPipe wrapper)
    └── HandXrayScreen.kt            (gestures, drawing engine, Compose UI)
```

## Acknowledgements

- [MediaPipe Hand Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker) by Google
- [CameraX](https://developer.android.com/training/camerax) by Android Jetpack



