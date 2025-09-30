# CSE 535 Project 1 — Context Monitor (Starter)

**Package:** `edu.asu.cse535.contextmonitor`  
**Min SDK:** 29 | **Target:** 34 | **Language:** Kotlin

## What’s included
- Activity 0: `MainActivity` — buttons for *Record health data* and *Delete all recorded data*.
- Activity 1: `MeasureActivity` — 45s **Heart Rate** capture (CameraX + torch) and 45s **Respiratory Rate** capture (accelerometer). Stubs call `HeartRateHelper` and `RespiratoryHelper`.
- Activity 2: `SymptomsActivity` — 10 symptom sliders (0–5) and **UPLOAD SYMPTOMS** to Room.
- Room database: single table with 12 fields (HR, RR, 10 symptoms) + timestamp.
- Emulator mode: put CSV files in `res/raw/` (placeholders included). Replace helpers to read CSV/video per assignment.
- Delete-all button calls `dao.deleteAll()`.

## Setup
1. Open in Android Studio (Giraffe/Hedgehog+). When prompted, let Studio download the Gradle wrapper (8.7) and Android Gradle Plugin (8.5.x).
2. Run on a physical Android 10+ device for CameraX PPG. For emulator demo:
   - Place HR **video** on device and update the helper to pick/process it.
   - Use the included `res/raw/rr_*.csv` or replace with instructor-provided CSVs.

## Permissions
The app requests `CAMERA`, `RECORD_AUDIO`, and media read permission (Android 13+: `READ_MEDIA_VIDEO`). Torch requires back camera with flash.

## Replace the helpers
- `helpers/HeartRateHelper.kt` → implement your provided heart-rate-from-video algorithm (URI in, BPM out). Expect long processing (3–4 minutes).
- `helpers/RespiratoryHelper.kt` → implement respiratory rate from accel arrays.

## Demo video script
See assignment: show HR measure → RR measure → Symptoms upload → DB delete.

## Notes
- ViewBinding is enabled.
- Simple Material 3 theme.
- You can add a History screen and CSV export if needed.
