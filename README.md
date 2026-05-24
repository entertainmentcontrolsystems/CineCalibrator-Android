# CineCalibrator (Android)

Professional cinema lighting calibration tool for Android.

## Quick Start

### Requirements
- **Android Studio** (latest stable)
- Android device running **Android 8.0+** (API 26) with a rear camera

### Build & Run
1. Clone: `git clone https://github.com/entertainmentcontrolsystems/CineCalibrator-Android.git`
2. Open in Android Studio
3. Wait for Gradle sync to complete
4. Connect your Android device via USB (with USB debugging enabled)
5. Press Run ▶️

All dependencies are declared in `build.gradle.kts` — Gradle handles everything automatically.

### What It Does
- Measures CIE 1931 xy chromaticity using the phone camera or a Sekonic C-800 spectrometer
- Controls lighting fixtures over sACN (E1.31) or Art-Net DMX
- Desaturation-path camera calibration against known reference fixtures (ETC Fos/4, Arri SkyPanel, etc.)
- Color volume scanning for full gamut characterization
- 3D LUT export (.cube) for DaVinci Resolve
- GDTF fixture profile export
- Real-time Planckian locus D16xy → fixture DMX conversion

## Architecture

```
app/src/main/java/com/cinecalibrator/
├── core/           # Color science, calibration, DMX, LUT generation
├── ui/             # Fragments and custom views
├── model/          # Room database and repositories
└── MainActivity.kt

sekonic/            # Sekonic C-800 USB library module
```

## License

Copyright © 2026 ECS Lighting. All rights reserved.
