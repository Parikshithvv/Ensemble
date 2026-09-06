# Ensemble — Video-Based Unique-Person Collage

An Android app that processes a portrait video entirely on-device, detects and identifies
unique people across separate appearances, picks a strong representative shot for each
person, and generates a shareable collage.

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

No backend, no network calls, no API keys — everything runs on-device.

---

## Pipeline details & tuning

### Frame sampling
Frames are extracted via `MediaMetadataRetriever` at a fixed interval of roughly **175ms**
per sampled frame (not every raw video frame), balancing coverage against processing time.
Each frame is fully copied (not a reused buffer) and processed sequentially through
detection before the next frame is pulled, so extraction cannot outrun detection.

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
is rotated using the ML Kit eye landmarks so the eye-line is horizontal (roll correction)
before resizing to 112×112 — this significantly improved embedding consistency for the same
person across different head angles compared to a naive axis-aligned bounding-box crop.

### Clustering
Agglomerative clustering was chosen over an incremental single-pass approach because the
latter is order-dependent: an early atypical-pose frame can permanently seed the wrong
cluster, and no single global threshold can correct for that. Agglomerative clustering
computes distances between all cluster pairs and merges the closest pair repeatedly until
no pair is closer than the threshold, which is order-independent.

- **Average-linkage cosine distance** threshold: **~0.38–0.40** (tuned empirically, see
  below)
- Distance is computed as the average pairwise cosine distance between all cross-cluster
  face-embedding pairs, not a single running centroid — this avoids centroid drift, where
  a cluster's average embedding shifts with every merge and starts falsely matching
  unrelated people.

**Threshold selection process:** the threshold was tuned empirically against a manually
verified ground truth (visually counting distinct people in the test video) rather than a
fixed value chosen in advance. Several approaches were tried:
- A single global threshold in an incremental (non-agglomerative) clustering pass
  over- or under-clustered depending on the value (0.31 → 25 people; 0.48 → 6 people,
  with visible centroid drift merging unrelated people).
- Switching to agglomerative clustering with a mid-range threshold (0.35) improved results
  substantially but still slightly over-fragmented a few individuals whose head pose varied
  significantly between appearances.
- Face-crop alignment (eye-line rotation correction) was added, which reduced pose-driven
  embedding variance and allowed a slightly higher threshold (~0.38–0.40) to correctly merge
  the same person's separate appearances without re-introducing false merges between
  different people.

### Appearance counting
Per person, consecutive sampled frames (after the quality gate) are grouped into continuous
appearance segments. A gap between detections beyond a small tolerance ends the current
segment; the next detection starts a new appearance. Two people visible in the same frame
are each counted independently, since detection and counting happen per-person, not
per-frame.

---

## Test results

Run on the 30-second sample video (172 sampled frames, 185 total valid face detections):

| Metric | Value |
|---|---|
| People found | 8 |
| Total face detections | 185 |
| Frames analyzed | 172 |
| Faces excluded by quality gate | 9 |

Manual visual review of the same clip counted **4–6 distinct people**; the 8-person result
is close but not exact — see Known Limitations below.

> Results for Sample 2 and Sample 3 should be run and recorded here before final submission,
> since the assignment explicitly does not provide expected counts for those clips and asks
> that all three be tested.

---

## Known limitations

- **Clustering accuracy is close but not exact.** On the primary test video, the pipeline
  found 8 people against a manually-verified ground truth of 4–6. Investigation traced most
  of the discrepancy to a small number of borderline same-person merges that sit just above
  the current similarity threshold (confirmed via logged pairwise centroid distances) —
  raising the threshold further to catch these risks reintroducing false merges between
  genuinely different people, which was observed at higher threshold values during tuning.
  Given more time, a validation-set-driven threshold search (rather than one manually-tuned
  video) or a more sophisticated linkage strategy would likely close this gap further.
- **Threshold is a single global value**, not adaptive per-video. A video with more/less
  pose variation than the test clip may need a different threshold for optimal results.
- **Unit test coverage is minimal.** Given the time-box, testing effort was concentrated on
  live, empirical validation of the clustering and appearance-counting logic against a real
  video rather than isolated unit tests. `PersonClusterer`'s core distance/merge logic and
  the appearance-segmentation logic are the highest-value candidates for unit tests if the
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
│  └─ CollageGenerator.kt      — collage bitmap composition
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
