# RetroVibeCam – Development Workflow

## What the app does

Live camera viewfinder with real-time retro palette quantization, photo capture, and video recording. Each camera frame is processed on the GPU: every pixel is replaced with the nearest colour in the selected palette, using the CompuPhase redmean colour-distance formula.

---

## Project structure

```
README.md                        – User-facing manual (introduction, controls, palettes, limitations)
workflow.md                      – This file: developer notes and technical reference
apk/                             – Built APKs copied here automatically after each assemble

app/src/main/java/com/retrovibecam/
    MainActivity.kt          – UI, camera lifecycle, photo/video save
    CameraFilterRenderer.kt  – OpenGL ES 2.0 renderer, EGL recording surface
    Palette.kt               – Palette data class and loadPalettes(context)
    PaletteStripAdapter.kt   – RecyclerView adapter for the palette swatch strip
    PaletteSwatchView.kt     – Custom View that draws palette colours as a horizontal strip

app/src/main/res/
    layout/activity_main.xml      – ConstraintLayout: toolbar, GLSurfaceView, palette strip, buttons
    layout/palette_swatch_item.xml – Single item in the palette strip (swatch + label)
    values/palettes.xml           – Palette definitions as #RRGGBB hex string-arrays
    values/themes.xml             – NoActionBar theme (custom Toolbar used instead)
    drawable/ic_*.xml             – Vector icons: camera, flip, record, stop, lock open/closed
    drawable/bg_*.xml             – Button and indicator background shapes/ripples
```

---

## Build requirements

- Android Studio Hedgehog or later
- AGP 8.2.2, Kotlin 1.9.22
- `compileSdk`/`targetSdk` 34, `minSdk` 24
- CameraX 1.3.1 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- RecyclerView 1.3.2

---

## APK output

After every `assemble` (debug or release), Gradle automatically copies the APK into `apk/` at the project root. The filename is `retrovibecam-debug.apk` or `retrovibecam-release-unsigned.apk`.

---

## How the render pipeline works

```
Camera frame
    │
    ▼
SurfaceTexture (OES external texture, GL_NEAREST sampling)
    │  OnFrameAvailableListener → requestRender()
    ▼
GLSurfaceView.onDrawFrame()
    │
    ├─ updateTexImage() + getTransformMatrix()
    │
    ├─ GLES20 draw call — fragment shader:
    │      for each pixel: find nearest palette colour via redmean distance
    │      uPassthrough = 1.0 → bypass filter (No Filter mode)
    │
    ├─ draw to display surface
    │
    ├─ if recording:
    │      eglMakeCurrent(recorderEglSurface)
    │      draw again at encoder resolution
    │      eglSwapBuffers → MediaRecorder encodes the frame
    │      eglMakeCurrent(displaySurface)
    │
    └─ if captureRequested:
           glReadPixels → flip vertically → save PNG to gallery
```

**Colour distance formula** (CompuPhase redmean, 0–255 range):
```
rmean = (e1.r + e2.r) / 2
dist  = sqrt( ((512 + rmean) * dr²) / 256
            + 4 * dg²
            + ((767 - rmean) * db²) / 256 )
```

**Renderer performance decisions:**
- Uniform and attribute locations (`aPosition`, `aTexCoord`, `uTexMatrix`, `sTexture`, `uPassthrough`, `uPalette`) are queried once after program link in `onSurfaceCreated` and stored in fields. `glGetUniformLocation` / `glGetAttribLocation` are never called at draw time.
- The palette uniform (`vec3 uPalette[16]`, 48 floats) is uploaded to the GPU only when the palette changes, guarded by a `paletteDirty` flag set in `setPalette()` and cleared after upload.
- The `glReadPixels` capture buffer is allocated once in `onSurfaceChanged` (sized to the surface dimensions) and reused for every photo capture, avoiding a large `ByteBuffer.allocateDirect` on demand.
- The colour distance function uses squared distance (`colourDistanceSq`) — nearest-colour selection only requires relative ordering, so `sqrt` is unnecessary. Eliminates one `sqrt` per palette entry per pixel.

**Ordered dithering:**
- A 4×4 Bayer matrix is uploaded once as a `GL_LUMINANCE` / `GL_REPEAT` texture bound to texture unit 1. Each pixel samples its threshold using `gl_FragCoord mod 4`, yielding a value in [−0.5, 0.5).
- The threshold (×64, giving ±32 in the 0–255 colour space) is added to the raw camera colour before the nearest-palette lookup, causing adjacent pixels to alternate between palette entries and simulate intermediate colours.
- Dithering is bypassed in passthrough (No Filter) mode.

**Noise/consistency measures:**
- `GL_NEAREST` texture sampling ensures each pixel reads exactly one camera texel — no blending between neighbours.
- Manual **Lock Exposure** button (Camera2 interop) locks AE, AWB, scene mode, chromatic aberration correction, and noise reduction mode when tapped, preventing frame-to-frame colour shifts. Resets automatically when the camera is flipped. Button turns solid teal when locked; dark/transparent when unlocked.

---

## Key EGL decisions

### Why `RecordableEGLConfigChooser` exists

`GLSurfaceView` picks its own EGL config by default. Creating a secondary window surface for `MediaRecorder` with a *different* config causes `eglMakeCurrent` to fail with `EGL_BAD_MATCH (0x3009)` — the context and surface configs must match.

The fix: install `RecordableEGLConfigChooser` on the `GLSurfaceView` before `setRenderer`. It picks a config with `EGL_RECORDABLE_ANDROID = 1`, so the same config can be reused for the encoder surface. `CameraFilterRenderer.chooseRecorderConfig()` simply returns `eglConfig` (the context's own config captured in `onSurfaceCreated`).

```kotlin
// MainActivity.onCreate — order matters
glSurfaceView.setEGLContextClientVersion(2)
glSurfaceView.setEGLConfigChooser(RecordableEGLConfigChooser())  // must be before setRenderer
glSurfaceView.setRenderer(renderer)
```

### Recording start sequence

MediaRecorder setup (including `contentResolver.insert` and `recorder.prepare()`) runs on a background thread to avoid blocking the main thread. A `CountDownLatch` + `AtomicBoolean` synchronise the GL thread's EGL surface creation with `recorder.start()`:

```
Background thread                GL thread (queueEvent)
─────────────────                ──────────────────────
recorder.prepare()
queueEvent { startRecordingOnGLThread(..., latch, result) }
latch.await()          ←──────── eglCreateWindowSurface
                                 result.set(true)
                                 latch.countDown()
if result: recorder.start()
```

---

## Palette system

Palettes are defined entirely in `res/values/palettes.xml` as `#RRGGBB` hex color strings — no code changes needed to add or edit palettes.

RGB values for each palette are sourced from [Lospec](https://lospec.com/palette-list), a public database of pixel-art palettes.

At startup, `loadPalettes(context)` in `Palette.kt` reads the XML and converts each hex value to a `FloatArray(r, g, b)` in the 0–255 range expected by the shader. An empty color array marks the palette as passthrough (no filter).

### Adding a new palette

Edit `res/values/palettes.xml`:

1. Add the new ID to `palette_ids`:
```xml
<string-array name="palette_ids">
    ...
    <item>my_palette</item>
</string-array>
```

2. Add a label and color list (up to 16 entries):
```xml
<string name="palette_label_my_palette">My Palette</string>
<string-array name="palette_colors_my_palette">
    <item>#RRGGBB</item>
    <item>#RRGGBB</item>
</string-array>
```

Palettes with fewer than 16 colours are automatically padded with the last colour in `toFlat()`. The shader uniform is always `vec3 uPalette[16]`.

---

## Permissions

| Permission | When required |
|---|---|
| `CAMERA` | Always — requested at startup |
| `WRITE_EXTERNAL_STORAGE` | Only on API < 29 — requested on first photo capture |

Video and photo files are saved via `MediaStore` (API 29+, `IS_PENDING` pattern) or `Environment.getExternalStoragePublicDirectory` (API < 28). Saved to `Movies/RetroVibeCam` and `Pictures/RetroVibeCam`.

---

## Video recording limits

- Max duration: 5 minutes (`setMaxDuration(300_000)`)
- Codec: H.264, MPEG-4 container
- Bitrate: 4 Mbps
- Resolution: GLSurfaceView dimensions rounded down to nearest multiple of 16 (encoder requirement)

---

## Version history

| versionCode | versionName | Notes |
|---|---|---|
| 1 | 0.1 | Initial release |
| 2 | 0.2 | GL_NEAREST sampling, exposure lock button, teal theme, button layout refactor, remove 3×3 box blur |
| 3 | 0.3 | Palette swatch strip replaces toolbar spinner; camera-style button layout (large shutter, flanking flip/record icons); exposure lock moved to lower-right overlay with teal active state; pulsing red dot + mm:ss recording indicator with dark pill background; README.md user manual added |
| 4 | 0.4 | Renderer optimisations: cached GL locations, palette dirty flag, pre-allocated capture buffer |
| 5 | 0.5 | 4×4 Bayer ordered dithering added to fragment shader; colour distance sqrt removed |
| 6 | 0.6 | 6 new palettes (Monochrome, CGA 16, VIC-20, Apple II, Teletext, MSX); palettes sorted alphabetically; max recording length increased to 5 minutes |
| 7 | 0.61 | Palette name included in saved photo and video filenames (e.g. `RetroVibeCam_Game_Boy_<timestamp>.png`) |

---

## Repository

GitHub: `https://github.com/t33bu/retrovibecam`
