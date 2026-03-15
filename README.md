# RetroVibeCam

A live camera Android application that makes your footage look like it was captured on vintage hardware. Every frame is processed in real time — what you see through the viewfinder is exactly what gets saved.

This app was completely vibe coded with [Claude](https://claude.ai). The author had some prior experience with Android development in Java more than a decade ago, next to none experience with Kotlin, and zero knowledge of OpenGL or any other graphics technology used in this project.

See video [here](https://www.youtube.com/watch?v=_gHRJsAtM98).

---

## Controls

```
┌─────────────────────────────────┐
│  RetroVibeCam                  │
│                                 │
│                                 │
│         camera preview          │
│                                 │
│                                 │
│  ░░  ░░  ░░  ░░  ░░  ░░        │  ← palette strip
│  [⟳]    [    ◉    ]    [●]  [🔒]│  ← flip / shutter / record / lock
└─────────────────────────────────┘
```

### Shutter (large centre button)
Takes a photo. The image is saved to your gallery under **Pictures/RetroVibeCam**, with the palette filter baked in.

### Flip (left of shutter)
Switches between front and rear camera.

### Record (right of shutter)
Starts video recording. Tap again to stop. A pulsing red dot and elapsed time appear in the top-left corner while recording. Videos are saved to **Movies/RetroVibeCam**.

### Exposure Lock (far right of shutter)
Freezes the camera's automatic exposure and white balance. Useful when you want consistent colours across a shot — without it, the camera will continuously adjust as the scene changes, causing the palette mapping to shift. The button turns **solid teal** when locked. Switching cameras resets the lock.

---

## Palettes

Swipe through the palette strip at the bottom of the screen to choose a colour filter. Each palette is modelled after a specific piece of retro hardware:

| Palette | Inspired by |
|---|---|
| No Filter | Raw camera output — no processing |
| Apple II | Apple II Lo-Res (1977) |
| CGA 4 | IBM Color Graphics Adapter — 4-colour mode (1981) |
| CGA 16 | IBM Color Graphics Adapter — full 16-colour palette (1981) |
| C64 | Commodore 64 (1982) |
| Game Boy | Nintendo Game Boy (1989) |
| Intellivision | Mattel Intellivision (1979) |
| Monochrome | Black and white |
| MSX | MSX1 — TMS9918A video chip (1983) |
| Teletext | BBC Teletext / Ceefax (1974) |
| VIC-20 | Commodore VIC-20 (1980) |
| ZX Spectrum | Sinclair ZX Spectrum (1982) |

The selected palette is highlighted with a teal border. Tap any swatch to switch instantly.

---

## Tips

- **Lock exposure before you shoot.** Auto-exposure fighting the palette filter is the most common cause of flickering or inconsistent colours. Frame your shot, let the camera settle, then lock.
- **Low light works best with high-contrast palettes.** CGA and Game Boy hold up well in dim conditions. Palettes with many similar colours (Commodore 64, ZX Spectrum) are better in daylight.
- **The filter is what you see.** There is no post-processing step — if it looks right in the viewfinder, the saved file will match.

---

## Limitations

- **No audio in video recordings.** Only the video track is recorded.
- **Maximum recording length is 5 minutes.** Recording stops automatically at the limit.
- **Exposure lock may not be available on all devices.** On some hardware the lock silently has no effect.
- **Front camera quality varies by device.** Most front cameras have less dynamic range, which can make palette mapping look noisier.
- **No zoom control.** The viewfinder is fixed at the default focal length.
- **Android only.** Minimum Android 7.0 (API 24).

---

## Testing

This app has only been tested on a **Motorola Moto G34 5G** smartphone. Behaviour on other devices may vary.

Bug reports are welcome as [GitHub Issues](../../issues), though no guarantees can be given that they will be fixed.

---

## Disclaimer

> - **Non-commercial use only.** This source code and app are provided for personal, non-commercial use. Commercial use of any kind is strictly forbidden.
> - **Use at your own risk.** The source code and app are provided as-is, without warranty of any kind, express or implied.
> - **No liability.** The author accepts no responsibility or liability for any damages, data loss, or other harm arising from the use or inability to use the source code or the app.
> - **License.** This project is licensed under [Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/).
