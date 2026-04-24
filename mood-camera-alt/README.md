# 🎞️ film.cam

**By Akshay Sharma**  
Instagram: [@akshay.sharma](https://instagram.com/akshay.sharma)

---

## A Minimalist Film Emulation Camera for Android

film.cam is a deliberate, textured, and suspenseful camera experience that brings the soul of analog photography to your Android device. Inspired by mood.camera and built on a zerocam philosophy, it strips away computational photography to deliver pure sensor-to-film emulation.

### Core Philosophy

- **Zero Sharpening** — No artificial edge enhancement
- **Zero Noise Reduction** — Natural sensor grain preserved
- **Zero Live Preview** — Experience the suspense of film development
- **Zero Video** — Pure stills photography only
- **No Manual Controls** — Gesture-driven simplicity

---

## ✨ Features

### Film Emulation Engine
- **20 Unique Film Stocks** across 3 categories:
  - **Film**: analog, calypso, chrome, cine, gold, luminosity, nord, portra, tungsten, vista
  - **Monochrome**: mono, noir
  - **Artistic**: apollo, arizona, metro, prologue, stock, taiga, xenon, xpro
- **HALD CLUT Processing** in linear color space for authentic color science
- **Advanced Preset Editor** with 14+ parameters:
  - Emulation strength, saturation, contrast, temperature, tint
  - Fade, mute, grain level, grain size
  - Halation, bloom, chromatic aberration
  - Independent tone curve controls (highlights/midtones/shadows)
  - Dynamic range selection (High/Medium/Low)

### Capture Experience
- **DRS Fusion** (optional): 3-frame exposure bracketing (-1.5EV, 0EV, +1.5EV)
- **24MP Resolution Ceiling** with auto-binning to 12MP in low light
- **Aspect Ratios**: 1:1, 4:3, 3:2, 16:9, 2.35:1 Scope
- **Focal Length Presets**: 24mm, 28mm, 35mm, 50mm, 85mm virtual primes
- **"Developing" Animation**: 1.2s fade-in post-capture for film-like suspense

### Gesture Controls
| Edge | Gesture | Parameter |
|------|---------|-----------|
| **Top** | Swipe ↑↓ | ISO Simulation (100–3200) |
| **Right** | Swipe ↑↓ | Exposure Compensation (±3EV) |
| **Left** | Swipe ↑↓ | Dynamic Range (High/Med/Low) |
| **Bottom** | Swipe ←→ | Film Stock Scroller |

### Haptic Feedback
- Mechanical tick feedback on dial adjustments
- Triple haptic = DRS ON, Single long = OFF
- Subtle confirmation on film stock selection

### Community Features
- **Preset Sharing**: Export/import custom "recipes" as JSON
- **EXIF Searchability**: Emulation name written to UserComment field

---

## 📁 Project Structure

```
app/src/main/java/com/thetechgeekko/filmcam/
├── CameraActivity.kt              # Main activity & lifecycle
├── capture/
│   ├── CaptureController.kt       # Camera2 API, RAW/JPEG capture
│   └── DRSCaptureManager.kt      # DRS burst with motion gate
├── gpu/
│   └── GlTextureLoader.kt         # EGL context, LRU CLUT cache
├── model/
│   └── FilmSettings.kt            # Data models & parameter definitions
├── pipeline/
│   └── FilmProcessor.kt           # GPU shader pipeline (strict order)
├── settings/
│   └── SettingsManager.kt         # Persistent settings & presets
├── ui/
│   └── MinimalCameraScreen.kt     # Jetpack Compose UI
└── utils/
    ├── ClutLoader.kt              # CLUT validation & loading
    └── ExifWriter.kt              # EXIF metadata writing

app/src/main/assets/filmcam/
├── cluts/
│   ├── film/          (10 HALD CLUTs)
│   ├── monochrome/    (2 HALD CLUTs)
│   └── artistic/      (8 HALD CLUTs + skinTones.png)
├── grains/
│   ├── superfine.png  (tileable, 986×986)
│   ├── fine.png
│   ├── medium.png
│   └── coarse.png
└── metadata.json        (emulation registry)
```

---

## 🔧 Technical Specifications

### Processing Pipeline (Strict Order)
```
Camera2 Capture → DRS Fusion → Linear Space Conversion → 
HALD CLUT → Color Adjustments → Tone Curve → Dynamic Range → 
Halation → Bloom → Chromatic Aberration → sRGB Conversion → 
Film Grain (LAST) → JPEG Encode + EXIF Write
```

### Performance Targets
- **Single Frame**: <1.2s @ 24MP (Snapdragon 720G baseline)
- **DRS Fusion**: <2.0s @ 24MP
- **Peak RAM**: <200MB with texture pool eviction
- **Zero main-thread jank** during capture/processing

### Compatibility
- **Android**: 11–15 (API 30–35)
- **GPUs**: Adreno 6xx, Mali-G7x, PowerVR Rogue
- **Graceful fallback** on low-end devices (512px CLUTs, reduced effects)

---

## 🚀 Build & Install

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 35

### Build Commands
```bash
cd mood-camera-alt
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Test Capture Pipeline
```bash
adb shell am start -n com.thetechgeekko.filmcam/.CameraActivity
```

---

## 📝 Validation Checklist

### Asset Validation
- ✅ All 20 CLUTs validated (512×512 square, power-of-2)
- ✅ All 4 grain textures tile seamlessly
- ✅ skinTones.png triggers luminance-blend mode
- ✅ metadata.json schema complete

### Pipeline Validation
- ✅ Zero sharpening halos in output
- ✅ Zero NR smoothing artifacts
- ✅ CLUT applies in linear space
- ✅ Grain respects luminance mask
- ✅ Halation triggers only on highlights >0.85 luminance
- ✅ DRS preserves highlight roll-off (no clipping)

### UX Validation
- ✅ Edge swipes respond <100ms with haptic feedback
- ✅ No overlays on viewfinder by default
- ✅ Developing animation plays 1.2s post-capture
- ✅ EXIF UserComment contains emulation name

---

## 📄 License

GPL-3.0 License — Open Source, Community-Driven

---

## 🙏 Credits

**Developer**: Akshay Sharma  
**Instagram**: [@akshay.sharma](https://instagram.com/akshay.sharma)  
**Inspired by**: mood.camera, PhotonCamera (eszdman, bjzhou branches)  
**Algorithm Reference**: RapidRAW (selective extraction only)

---

## 🎯 Vision

film.cam is not just another camera app—it's a return to deliberate photography. By removing live preview, computational enhancements, and manual complexity, it restores the suspense and tactile satisfaction of shooting film. Every capture is a commitment, every frame is textured, and every image carries the soul of analog photography.

**Shoot less. Mean more.** 🎞️
