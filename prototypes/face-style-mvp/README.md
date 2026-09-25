# Zenbo face style MVP

Standalone local visual study for the existing 160 × 100 PixelFace area. It does not change or connect to the production Web or Android app.

## Open locally

From the repository root:

```sh
npm run dev
```

Open `/prototypes/face-style-mvp/` on the Vite URL shown in the terminal.

## Scope

- Original eye-only character: two large, standalone light pixel eyes on Zenbo's near-black `#061018` canvas, drawn at 160 × 100 with 2 px steps and nearest-neighbor CSS scaling. The default composition brings the eyes close to the top of the 8:5 screen without a reserved in-screen header. A light-stage comparison switch inverts the contrast.
- The five existing emotion keys (`NEUTRAL`, `HAPPY`, `CURIOUS`, `CONCERNED`, `EXCITED`) stay selectable. `SLEEPING` is an independent display state.
- `EXCITED` reuses the original PixelFace star-eye geometry; `CURIOUS` keeps the asymmetric eye study.
- Large preview and native-size comparison, eye-span control (100–128 px), optional light stage, and reduced-motion-aware blink. The 8:5 screen layout switch compares the proposed top-edge composition with an illustrative reserved-top composition; this is not a measured reproduction of the current App.
- No static mouths or detached brows. A small mouth appears only when speech amplitude is above zero. Amplitude 0–4 is a **manual or simulated visual test only**. It is not connected to actual audio, Hermes, or Native.
- No remote assets or added dependencies.

This prototype is for direction review before any production integration or device acceptance.
