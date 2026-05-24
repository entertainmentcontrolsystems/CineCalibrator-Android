# CineCalibrator (Android)

Professional cinema lighting calibration tool for Android. Measures color-mixing LED fixtures using a phone camera or Sekonic C-800 spectrometer, controls fixtures over sACN/Art-Net DMX, and generates 3D LUTs and GDTF profiles.

## Features

- **Camera-based spectrometry** — Measures CIE 1931 xy chromaticity per diode using Android's Camera2 API
- **Sekonic C-800 support** — USB spectrometer integration for lab-grade accuracy
- **DMX control** — Drives fixtures over sACN (E1.31) or Art-Net during calibration scans
- **Desaturation-path calibration** — Compensates for camera gamut limitations using known reference fixtures
- **Color volume scanning** — Samples the fixture's full reachable gamut via random DMX blends
- **3D LUT export** — Generates .cube files for DaVinci Resolve with KNN color-volume interpolation
- **GDTF export** — Exports fixture profiles in the GDTF standard format
- **Conversion engine** — Real-time Planckian locus lookup for D16xy → fixture DMX conversion

## Requirements

- Android 8.0+ (API 26)
- Camera with Camera2 API support
- USB OTG support (for Sekonic C-800)

## Build

1. Open in Android Studio
2. Sync Gradle
3. Build → Run on device

## Architecture

```
app/src/main/java/com/cinecalibrator/
├── core/           # Color science, calibration, DMX, LUT generation
├── ui/             # Fragments and custom views
├── model/          # Room database and repositories
└── MainActivity.kt

sekonic/            # Sekonic C-800 USB library module
sacn-common/        # Shared sACN/Art-Net library (see sacn-common repo)
```

## License

Copyright © 2026 ECS Lighting. All rights reserved.
