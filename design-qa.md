# Pixel Face A+ design QA

## Comparison target and evidence

- Source visual truth: `docs/design/pixel-face-grounding/direction-A-plus-160x100.png`.
- Rendered implementation: `docs/design/pixel-face-grounding/implementation-capture-160x100.png`.
- Full-view comparison: `docs/design/pixel-face-grounding/implementation-comparison-160x100.png` (source left, implementation right).
- Focused comparison: `docs/design/pixel-face-grounding/implementation-comparison-focused-neutral-4x.png` (source left, nearest-neighbor 4×).
- Motion evidence: `docs/design/pixel-face-grounding/implementation-motion-capture-160x100.png`.
- Browser layout evidence:
  - `docs/design/pixel-face-grounding/implementation-layout-capture-1280x720.png`
  - `docs/design/pixel-face-grounding/implementation-layout-capture-320x568.png`
  - `docs/design/pixel-face-grounding/implementation-layout-capture-800x360-sleeping.png`

The source and static implementation are both 488×204 RGB images containing
six 160×100 tiles separated by exact 4 px `#061018` gutters. The implementation
was rendered by the live Vite app in headed Chrome and captured at CSS-pixel
scale with device-density normalization of 1. The focused evidence enlarges
the same 160×100 neutral tiles to 640×400 with nearest-neighbor scaling.
Comparison state is the dark A+ canonical order: neutral, happy, curious /
concerned, excited, sleeping.

## Findings

No actionable P0, P1, or P2 fidelity issues remain.

- [P3] Generated grounding has a slightly larger eye core and softer glow.
  Location: focused neutral comparison and several canonical tiles.
  Evidence: the generated source has approximate ImageGen silhouettes; the
  implementation uses the specified 34×14 neutral eyes and a two-layer 2 px
  rectangular halo.
  Impact: the implementation reads a little sharper and more restrained at 4×,
  while retaining the selected A identity and proportions.
  Disposition: accepted. The generated PNG is grounding rather than a runtime
  sprite specification, and the code follows the approved integer geometry.

## Five required fidelity surfaces

- Fonts and typography: the face source intentionally contains no typography.
  The surrounding app retains its existing Inter / Noto Sans TC stack,
  hierarchy, letter spacing, two-line caption clamp, and Chinese aria labels;
  the final desktop and narrow captures show no clipping.
- Spacing and layout rhythm: every face remains centered in an 8:5 tile with a
  common eye/mouth baseline. At 1280×720 the canvas is 768×480 at y=105.1,
  above-bar clearance is 37.1 px, and the caption ends at y=670.9. At 320×568
  the canvas is 294.4×184 at y=186.5 and the caption ends at y=437.5. At
  800×360 sleeping, the canvas is 342.4×214 at y=76 and the 44 px wake target
  ends at y=348. No tested viewport scrolls or clips.
- Colors and visual tokens: sampled implementation gutters are exactly
  `#061018`; cyan, mint, coral, and warm-yellow state colors follow the A+
  model palette. The halo is deliberately limited to two low-alpha pixel
  layers rather than a blurred CSS shadow.
- Image quality and asset fidelity: eyes remain continuous, pupil-free,
  hard-edged scanline silhouettes. Native 1× (160×100) and exact 4×
  (640×400) checks preserve crisp pixels with a 160×100 backing canvas.
  Implementation captures are RGB without alpha. No generated image is
  shipped in the runtime bundle.
- Copy and content: grounding boards contain no labels or extraneous marks.
  App aria output correctly covered listening, thinking, speaking, idle,
  error, sleeping, and awake; live `mouthLevel` was not announced.

## Responsive, motion, and interaction checks

- Browser viewports: 1280×720, 320×568, exact 1× at 400×500, exact 4× at
  800×650, and sleeping at 800×360.
- State flow: listening → thinking → speaking → playback interruption; all
  five emotions; error; sleep; rapid mid-flight sleep/wake reversal; settled
  wake.
- Speech levels 0/.25/.5/.75/1 resolve to equalizer bands 0/1/2/3/4 with
  bounds 8×2, 10×4, 10×6, 10×6, and 10×8.
- Reduced-motion model output disables blink and thinking drift while keeping
  the functional speech band.
- Dense 101-step scans for neutral→happy, sleep, wake, close→wake reversal,
  and wake→sleep reversal found only integer, positive-even, in-bounds
  rectangles and exactly one connected component per eye. Reversal first and
  final frames match their current and target geometries.
- Codex in-app browser verification used a Local Runtime stub and exercised
  LISTENING (`好奇；狀態：聆聽`) → sleep (`休眠；狀態：休眠`) → wake/listening;
  browser console errors and warnings were 0. The headed standalone Vite run
  additionally exercised every state above; its sole application error was
  the expected unavailable `127.0.0.1:8787` Local Runtime, and its sole warning
  was induced by QA's repeated `getImageData` readback.

## Comparison history

- Earlier [P2] at 1280×720: the face canvas began at y=-1.1 and the caption
  ended at y=747.1, overlapping the top-bar region and clipping below the
  viewport. `src/assets/main.css` now reserves safe-area/top-bar, copy, and
  wake-button height using `vh`/`dvh` constraints. Post-fix evidence is the
  1280×720 layout capture and the measurements above.
- Earlier [P2] transition fidelity: rapid close→wake could emit y=40.5, and a
  later raster union could emit 1 px-high scanlines. The face model now uses
  canonical sleep slits and positive even scanline dimensions. Post-fix dense
  browser scans and the final motion board pass integer/even/bounds,
  connectivity, continuity, and endpoint checks.

## Open questions and follow-up polish

- Zenbo K hardware rendering, real TTS loudness, barge-in, thermal behavior,
  and long-running GeckoView frame pacing remain device-only acceptance work.
  This does not block browser visual fidelity.
- Optional P3 follow-up: if a softer ImageGen-like glow is preferred after
  hardware review, tune only the two halo alpha values; keep the current core
  geometry and 2 px maximum spread.

## Implementation checklist

- [x] Capture equal-size full-view and focused source/implementation evidence.
- [x] Resolve the responsive overflow and transition-grid P2 findings.
- [x] Verify canonical, motion, responsive, focus, interaction, and console
  states in a real browser.
- [x] Preserve generated boards as grounding only; keep runtime rendering
  programmatic.
- [ ] Complete the separately tracked Zenbo K hardware acceptance pass.

final result: passed
