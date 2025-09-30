# Project Report (4 pages, excluding references)

## Abstract (0.5 page)
Briefly present goals, methods (HR via camera+flash; RR via accelerometer), local storage, symptoms, privacy (delete-all), and outcomes.

## Introduction (0.5 page)
Context-aware mobile sensing, challenges (noise, lighting, motion, privacy), objectives.

## Technical Approach (1 page)
- Architecture diagram.
- CameraX setup (torch, 45s recording).
- Accelerometer collection (45s), sampling rate, helper processing.
- Data schema: 12 fields per record.
- Emulator fallback (CSV/video).

## Design Choices (1 page)
Sampling windows, filtering/peak detection (from helpers), UX for stillness and finger placement, permission strategy, offline and privacy decisions.

## Implications & Limitations (0.5 page)
Latency of HR processing, lighting/skin tone variability, motion artifacts, battery/thermal limits, generalizability.

## Future Work (0.5 page)
HRV, signal quality checks, adaptive feedback loops, on-device modeling, optional cloud export, reminders.

## Links
- GitHub repo (private, TAs added)
- Unlisted YouTube demo

## References
- bHealthy paper
- Health-Dev paper
- Android CameraX / Room docs
