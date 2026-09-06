# Ensemble — Video-Based Unique-Person Collage

Point it at a video, it finds every unique face, picks their best shot, and turns it into a
shareable collage. 100% on-device, zero cloud, zero backend.

Built for the iykyk Android internship assignment.

---

## What it does

1. Pick a video from the device.
2. Extract frames off the main thread with live progress.
3. Detect faces per frame with ML Kit (head pose, eyes-open, smiling, sharpness).
4. Generate a 192-d face embedding per detected face with an on-device TFLite model.
5. Cluster embeddings into unique people (agglomerative clustering).
6. Count each person's distinct appearances (continuous visible segments).
7. Pick the best-quality shot per person and render a collage.
8. Save the collage to the gallery and share it via the Android share sheet.

---

## Stack

- **Language / UI**: Kotlin, Jetpack Compose
- **Min SDK**: 26 (Android 8.0)
- **DI**: Koin
- **Face detection**: ML Kit Face Detection (`PERFORMANCE_MODE_ACCURATE`,
  `CLASSIFICATION_MODE_ALL`, `LANDMARK_MODE_ALL`)
- **Face embeddings**: **MobileFaceNet**, TFLite, **192-dimensional**, L2-normalized output.
  Bundled at `app/src/main/assets/mobilefacenet.tflite`.
- **Clustering**: Agglomerative (bottom-up) clustering with average-linkage cosine distance,
  computed once over all embeddings after a full pass of the video.
- **Architecture**: MVVM/MVI — one ViewModel per screen (`ProcessingViewModel`,
  `ResultsViewModel`), sealed `Result<D, E>` + `DataError` types for error handling, no
  business logic in Composables.

---

## Build & run

1. Clone the repo.
2. Open in Android Studio (the `mobilefacenet.tflite` model is already committed under
   `app/src/main/assets/` — no manual download needed).
3. Let Gradle sync (Kotlin DSL, `libs.versions.toml` version catalog).
4. Run on a device or emulator with **API 26+**. A physical device is recommended — ML Kit
   face detection and TFLite inference are meaningfully faster on real hardware than on an
   emulator.
5. From the home screen, tap **Pick a Video**, choose one of the sample clips, and let it
   process. Progress, face-detection counts, and clustering results are shown live.

**To build a debug APK:** in Android Studio, **Build → Generate App Bundles or APKs →
Generate APKs**, or from the command line: `./gradlew assembleDebug`. Output lands at
`app/build/outputs/apk/debug/app-debug.apk`.

No backend, no network calls, no API keys — everything runs on-device.

---

## Pipeline details & tuning

### Frame sampling
Frames are extracted via `MediaMetadataRetriever` at a fixed interval of roughly **175ms**
per sampled frame (not every raw video frame), balancing coverage against processing time.
Each frame is a fully independent copy (not a reused buffer) and is processed sequentially
through detection before the next frame is pulled, so extraction cannot outrun detection.

### Face quality gate
Before a face is used for **embedding/clustering**, it's checked against a minimum quality
bar so that heavily motion-blurred or low-confidence detections don't corrupt identity
matching:

- `MIN_SHARPNESS = 3.0` (variance-of-Laplacian on the face region)
- `MIN_QUALITY_SCORE = 0.25` (combined frontality / sharpness / eyes-open / smiling score)

Faces that fail this gate are excluded from clustering and appearance counting entirely —
this is also how "blurred whip-pan passes count for nobody" (per the assignment spec) is
implemented.

### Face alignment
MobileFaceNet is sensitive to head pose in the input crop. Before embedding, the face crop
is rotated using ML Kit eye landmarks so the eye-line is horizontal (roll correction) before
resizing to 112×112 — this significantly improved embedding consistency for the same person
across different head angles compared to a naive axis-aligned bounding-box crop.

### Clustering
Agglomerative clustering was chosen over an incremental single-pass approach because the
latter is order-dependent: an early atypical-pose frame can permanently seed the wrong
cluster, and no single global threshold can correct for that. Agglomerative clustering
computes distances between all cluster pairs and merges the closest pair repeatedly until no
pair is closer than the threshold, which is order-independent.

- **Average-linkage cosine distance threshold: `0.41`**
- Distance is computed as the average pairwise cosine distance between all cross-cluster
  face-embedding pairs, not a single running centroid — this avoids centroid drift, where a
  cluster's average embedding shifts with every merge and starts falsely matching unrelated
  people.

**Threshold selection process:** the threshold was tuned empirically against a manually
verified ground truth (visually counting distinct people in the test video), not chosen in
advance. Progression:

| Approach | Threshold | Result |
|---|---|---|
| Incremental single-pass clustering | 0.31 | 25 people — badly over-fragmented |
| Incremental single-pass clustering | 0.48 | 6 people — centroid drift caused false merges between unrelated people |
| Agglomerative, unaligned crops | 0.35 | 22–27 people — order-independent but still fragmented on pose variation |
| Agglomerative + eye-line face alignment | 0.35 | 9 people — large improvement; a few same-person pairs still split (confirmed via logged pairwise centroid distances of 0.36–0.40) |
| Agglomerative + face alignment | **0.41** | **5–6 people**, matching manually verified ground truth. Confirmed near-miss duplicate pairs (Person 1↔8, 3↔7, 4↔6, 5↔9 in testing) now merge correctly, without crossing into the ≥0.43 range where over-merging of genuinely different people was observed |

### Appearance counting
Per person, consecutive sampled frames (after the quality gate) are grouped into continuous
appearance segments. A gap between detections beyond a small tolerance ends the current
segment; the next detection starts a new appearance. Two people visible in the same frame
are each counted independently, since detection and counting happen per-person, not
per-frame.

### Representative shot & collage crop
The representative shot for each person is chosen by a combined quality score (frontality +
sharpness + eyes-open + smiling). Per the assignment spec, faces are **not** cropped tightly
to the ML Kit bounding box — both the results-screen thumbnails and the final collage use a
**70% margin expansion** around the detected face box, so each tile shows head, shoulders,
hair, and surrounding context rather than a zoomed, low-context crop.

### Collage generation & sharing
`CollageGenerator` composes a single high-resolution (1200px) JPEG containing the app header,
summary statistics, and a per-person card for each identified individual (representative
shot with the 70% context crop, face count, and formatted appearance timestamp ranges). The
composed image is saved to the app's cache directory and shared via `FileProvider` through a
standard `Intent.ACTION_SEND` (`image/jpeg`) — tapping Share sends the one flattened collage
image, not a screenshot of the results list.

### Theme
The app uses a warm orange / skin-tone palette throughout, applied via `Color.kt` and
`Theme.kt`:
- Background: deep mocha/terracotta (`#1C120C` → `#2D1E16`)
- Surfaces & cards: warm wood tone (`#261A13`, `#4D3326` borders)
- Primary / buttons: vibrant orange (`#FF6D00` / `#FF9100`)
- Accents & typography: soft peach (`#FFAB40`, `#FFE0B2`)

---

## Test results

Run on the 30-second sample video (172 sampled frames, 185 total valid face detections):

| Metric | Value |
|---|---|
| People found | 5–6 (matches manually verified ground truth) |
| Total face detections | 185 |
| Frames analyzed | 172 |
| Faces excluded by quality gate | 9 |

> Results for Sample 2 and Sample 3 should be run and recorded here before final submission,
> since the assignment does not provide expected counts for those clips and asks that all
> three be tested.

---

## Known limitations

- **Threshold is a single global value**, not adaptive per-video. A video with more or less
  pose variation than the tuning clip may need a different threshold for optimal results —
  it was arrived at empirically against one video's manually verified ground truth rather
  than a labeled validation set.
- **Unit test coverage is minimal.** Given the time-box, effort was concentrated on live,
  empirical validation of clustering and appearance-counting logic against a real video
  rather than isolated unit tests. `PersonClusterer`'s distance/merge logic and the
  appearance-segmentation logic are the highest-value candidates for unit tests if the
  project continues.
- **Face alignment corrects for roll (in-plane rotation) via eye-line leveling, but not full
  similarity-transform normalization** (scale/translation to a fixed reference). This was a
  scope trade-off given the time-box; full alignment would likely further stabilize
  embeddings across pose.
- Processing a 30-second clip end-to-end (detection + embedding) takes noticeably longer
  than realtime, dominated by ML Kit inference cost per frame — expected for accurate-mode,
  on-device processing, not a performance regression.

---

## Project structure

```
app/src/main/java/com/example/ensemble/
├─ data/
│  ├─ VideoFrameExtractor.kt   — frame extraction, off-main-thread, progress reporting
│  ├─ MlKitFaceDetector.kt     — ML Kit wrapper, face attributes, quality scoring
│  ├─ FaceQualityScorer.kt     — sharpness + representative-shot scoring
│  ├─ FaceEmbedder.kt          — face alignment + MobileFaceNet TFLite inference
│  ├─ PersonClusterer.kt       — agglomerative clustering + appearance segmentation
│  └─ CollageGenerator.kt      — collage bitmap composition + save/share
├─ domain/
│  ├─ Models.kt                — FaceInstance, Person, etc.
│  ├─ Result.kt / DataError.kt — typed error handling
├─ di/
│  └─ AppModule.kt             — Koin module
└─ presentation/
   ├─ processing/              — video pick + processing screen (MVI)
   └─ results/                 — clustering results + collage screen (MVI)
```

---

## Credits

- Face detection: [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection)
- Face embeddings: MobileFaceNet (TFLite)
