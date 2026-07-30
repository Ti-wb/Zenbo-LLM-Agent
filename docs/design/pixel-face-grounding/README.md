# Pixel Face grounding boards

The original A/B/C boards and the selected A+ hybrid were generated with the
built-in image tool as visual grounding for the `PixelFace` rewrite. They are
deliberately original and do not reproduce EVE or another copyrighted
character.

## Selected direction: A+

A+ keeps Direction A as the sole authority for face identity, continuous
capsule-eye construction, proportions, composition, and static emotion. It
borrows only the idea of a tiny three-column speech meter from Direction B.
Direction B is **not** a reference for the eyes.

| File | Purpose | Dimensions |
| --- | --- | --- |
| `source-A-plus-canonical-v1.png` | Built-in imagegen canonical source; accepted first iteration | 1536×1024 |
| `direction-A-plus-canonical.png` | Accepted 3×2 canonical board | 1472×616; six 480×300 (8:5) tiles and 16 px gutters |
| `direction-A-plus-160x100.png` | Nearest-neighbor native-resolution QA board | 488×204; six 160×100 tiles and 4 px gutters |
| `source-A-plus-motion-v1.png` | Built-in imagegen motion source; rejected for an oversized speech meter | 1774×887 |
| `source-A-plus-motion-v2.png` | Built-in imagegen targeted correction; accepted source | 1774×887 |
| `source-A-plus-motion-v3.png` | Built-in imagegen second correction; rejected because the speech heights became too uniform | 1774×887 |
| `direction-A-plus-motion.png` | Accepted 5×4 motion board based on v2 | 1712×864; twenty 336×210 (8:5) tiles and 8 px gutters |
| `implementation-capture-160x100.png` | Browser-rendered 3×2 implementation capture | 488×204; six native 160×100 tiles and 4 px gutters |
| `implementation-motion-capture-160x100.png` | Browser-rendered 5×4 implementation motion capture | 816×412; twenty native 160×100 tiles and 4 px gutters |
| `implementation-comparison-160x100.png` | Equal-size canonical/implementation full-board comparison, source left | 984×204 |
| `implementation-comparison-focused-neutral-4x.png` | Nearest-neighbor 4× neutral detail, source left | 1296×400 |
| `implementation-layout-capture-1280x720.png` | Focused desktop layout with status, caption, and keyboard focus | 1280×720 |
| `implementation-layout-capture-320x568.png` | Narrow portrait layout with status, caption, and keyboard focus | 320×568 |
| `implementation-layout-capture-800x360-sleeping.png` | Short sleeping layout with the wake target visible | 800×360 |

Canonical reading order is neutral, happy, curious / concerned, excited,
sleeping. Motion rows are:

1. blink: open, 60%, slit, 60%, open;
2. speech: `mouthLevel` 0, .25, .5, .75, 1;
3. thinking: center, +2 px, center, -2 px, center;
4. sleep: open, 65%, 30%, slit, sleeping.

Final assembly is deterministic and does not invent new face geometry. It crops
the generated panels to exact 8:5 tiles, composites exact `#061018` gutters,
normalizes near-background source pixels to `#061018`, and uses nearest-neighbor
resampling for the 160×100 QA board. The accepted motion board uses v2 and
replaces only the five generated speech marks with the exact planned core
geometry: an 8×2 line, then `[2,4,2]`, `[2,6,2]`, `[4,6,4]`, and `[4,8,4]`
three-bar stages. At 160×100 logical scale the core never exceeds 10×8 px; its
two-pixel dark halo is outside that measurement. This deterministic correction
was necessary because v2 retained the intended height progression but remained
too wide, while v3 met the width direction and lost the progression.

### Implementation capture and visual QA

The two implementation boards were rendered in the real Vite app by importing
`pixelFaceModel.js` in headed Chrome and calling its exported `renderFace` at
the native 160×100 bitmap size. Playwright captured CSS pixels at density 1;
the boards have no alpha channel and every sampled gutter pixel is exact
`#061018`. The motion rows use actual model frames: blink times
5049/5083/5116/5150/5183 ms, speech levels 0/.25/.5/.75/1, thinking times
0/1300/2600/3900/5200 ms, and sleep progress 0/.25/.5/.75/1.

The implementation preserves A's continuous pupil-free eyes, state palette,
composition, and tiny subordinate mouth while using the exact programmatic
geometry instead of tracing the generated pixels. Consequently its core edges
are sharper, its halo is more restrained, and some generated eye silhouettes
are slightly larger than their implementation counterparts. These differences
are intentional; generated PNGs remain grounding only and are not bundled.
The largest speech core is exactly 10×8 logical pixels.

Browser QA covered native 1×, exact 4×, 1280×720, 320×568, and 800×360. The
face, focus treatment, top bar, status, caption, and 44 px wake target remain
visible without overflow. A Local Runtime stub was used for the clean app-flow
pass through listening, thinking, speaking, playback interruption, all five
emotions, error, sleep, rapid sleep/wake reversal, and wake; no browser console
warnings or errors were produced. Dense intermediate-frame checks confirmed
integer, positive-even, in-bounds rectangles and one connected component per
eye through neutral-to-happy and both sleep/wake directions.

### A+ input roles

- Image 1, `direction-A-capsule-mouth.png`: primary, authoritative identity,
  continuous-eye, proportion, composition, palette, and static-emotion
  reference.
- Image 2, `direction-B-segmented-equalizer.png`: supporting mouth-motion
  reference only. Its segmented eyes and large waveform language are excluded.
- Motion correction v2 used `source-A-plus-motion-v1.png` as its edit target,
  A as identity reference, and B only as the speech-meter reference.
- Motion correction v3 used `source-A-plus-motion-v2.png` as its edit target
  and A as the identity reference. It is preserved for traceability but not
  used by a final board.

### A+ exact prompts

#### Canonical v1 — accepted

```text
Use case: stylized-concept
Asset type: project grounding sheet for a programmatic 160×100 robot pixel face
Primary request: Create a clean 3-column × 2-row canonical emotion board containing exactly six isolated face panels in this exact reading order: top row neutral, happy, curious; bottom row concerned, excited, sleeping. Each panel represents the same original minimalist friendly robot face and must be composed only of two eyes plus one tiny mouth on a deep blue-black screen.
Input images: Image 1 is the PRIMARY and authoritative reference for character identity, eye language, proportions, composition, palette, and static emotions. Preserve its continuous solid stair-stepped capsule-eye vocabulary with absolutely no pupils or highlights. Image 2 is SUPPORTING REFERENCE ONLY for the subtle idea of a tiny mouth equalizer; do not copy Image 2’s segmented eyes, eye blocks, or large mouth waves into this canonical board.
Scene/backdrop: six equal 8:5 face panels on uniform #061018, with narrow #061018 gutters; no outer scene.
Subject: one consistent original face. Neutral: two 34×14 horizontal stepped cyan capsules and an 8×2 line mouth. Happy: two continuous 32×14 mint arch eyes and a tiny three-step smile. Curious: one 36×14 cyan capsule and one 24×12 cyan capsule, tiny 6×2 line mouth. Concerned: two continuous 32×12 coral eyes whose inner corners are 4 px higher than outer corners, plus a tiny three-step frown; worried and gentle, never angry. Excited: two friendly 18×30 vertical warm-yellow capsules and a tiny 10×6 outlined open mouth. Sleeping: two 28×4 cyan closed-eye lines and a tiny 6×2 rest mark.
Style/medium: strict low-resolution pixel art shown at a clean integer enlargement; forms built from horizontal rectangular scanlines on a 2 px logical grid; continuous filled eye silhouettes; crisp hard square edges; a restrained two-pixel block halo only; no antialiasing, gradients, bloom fog, blur, texture, or rounded vector edges.
Composition/framing: every face centered in its own landscape 8:5 panel with identical eye baseline and mouth baseline; generous negative space; eye pair dominates, mouth remains secondary and very small.
Color palette: exact screen #061018; cyan #68E8FF; mint #76F7C4; concerned coral #FF7D8B; excited warm yellow #FFE66D; halo may use only low-alpha versions of the active face color.
Constraints: exactly six panels, exactly two eye shapes and one tiny mouth per panel; no labels and no text; no pupils; no eye highlights; no nose; no eyebrows; no character shell, head outline, body, hardware bezel, interface frame, icons, stars, speech marks, or extra decorations. Eyes in every panel remain continuous solid silhouettes, never segmented into floating blocks. All panels use the same scale and center. Concerned must read as worried, not angry. Excited must read friendly, not aggressive.
Avoid: recognizable copyrighted character details, photorealism, 3D rendering, glossy surfaces, gradients, soft bloom, excessive glow, extra panels, unequal panel sizes, and any mouth larger than about 10×8 logical pixels.
Output intent: a polished design grounding source that can later be cropped into exact 160×100 reference panels; image contains no written labels.
```

#### Motion v1 — rejected for speech-meter size

```text
Use case: stylized-concept
Asset type: project grounding sheet for four programmatic animation strips of a 160×100 robot pixel face
Primary request: Create one exact 5-column × 4-row motion board, exactly twenty equal landscape face panels, no labels. Reading left to right: row 1 is blink frames open, 60%-closed, thin slit, 60%-closed, open. Row 2 is speech at mouthLevel 0, .25, .5, .75, 1. Row 3 is thinking eye drift centered, 2 logical pixels right, centered, 2 logical pixels left, centered. Row 4 is sleep transition open, 65%-closed, 30%-open, thin slit, sleeping.
Input images: Image 1 is the PRIMARY and authoritative reference for character identity, continuous capsule-eye construction, face proportions, composition, and palette. Image 2 is SUPPORTING REFERENCE ONLY for the subtle mouth equalizer idea in speech row; never reuse its segmented or stair-block eyes, and never make a large wave mouth.
Scene/backdrop: twenty equal 8:5 face panels on exact uniform #061018 with narrow #061018 gutters; no outer scene.
Subject and frame rules: the same neutral original face in all twenty panels, two continuous solid cyan stair-stepped capsule eyes plus one tiny mouth. No pupils or highlights. Blink row changes only eye height symmetrically; mouth remains an 8×2 short line. Speech row keeps eyes fully open and changes only the mouth: frame 1 an 8×2 short line; frame 2 three 2 px-wide bars with heights [2,4,2]; frame 3 [2,6,2]; frame 4 [4,6,4]; frame 5 [4,8,4]. The three bars occupy no more than 10×8 logical pixels and are vertically centered on one mouth baseline. Thinking row keeps the face unchanged except both eyes move together horizontally by only 2 logical pixels as specified; mouth stays a short line. Sleep row closes both eyes progressively and ends with two 28×4 closed-eye lines plus a tiny 6×2 rest mark.
Style/medium: strict low-resolution pixel art shown at clean integer enlargement; forms built from horizontal rectangular scanlines on a 2 px logical grid; continuous filled eye silhouettes with crisp hard square edges; restrained two-pixel block halo only; no antialiasing, gradients, bloom fog, blur, texture, or rounded vector edges.
Composition/framing: each face centered in its own 8:5 panel on common eye and mouth baselines; identical scale and spacing across every panel except the named eye animation change; generous negative space. Subtle motions must look like consecutive frames, not different characters.
Color palette: exact screen #061018 and cyan #68E8FF only; low-alpha cyan halo allowed.
Constraints: exactly five columns and four rows, exactly twenty panels; no labels, numbers, text, arrows, timelines, separators, or captions; exactly two eyes and one tiny mouth in every panel; no pupils, highlights, nose, eyebrows, shell, head outline, body, hardware bezel, UI frame, icons, stars, speech marks, or decorations. Eyes must stay continuous solid silhouettes and never segment into floating blocks. Mouth equalizer exists only in the speech row and never exceeds 10×8 logical pixels. Never vary color, scale, or camera across frames.
Avoid: recognizable copyrighted character details, photorealism, 3D rendering, gradients, soft bloom, excess glow, large audio waveforms, segmented eyes, extra panels, missing panels, unequal panels, or layout other than 5×4.
Output intent: a polished animation grounding source that will be cropped into exact 8:5 panels; image contains no written labels.
```

#### Motion v2 — accepted source correction

```text
Use case: precise-object-edit
Asset type: targeted correction of the project grounding 5-column × 4-row robot pixel-face motion board
Primary request: Edit Image 1 only. Change only the five tiny mouth marks in ROW 2, the speech row. Shrink each row-2 mouth to at most 10×8 logical pixels when its panel is interpreted as 160×100. Keep every mouth centered at the existing mouth baseline. Preserve the five ordered stages: short 8×2 line; three bars [2,4,2]; [2,6,2]; [4,6,4]; [4,8,4], where each bar is 2 logical pixels wide with 2 logical pixel gaps. Make them visibly much smaller and more subordinate to the eyes than in Image 1.
Input images: Image 1 is the EDIT TARGET and all of its layout, panel count, eyes, background, frame order, scale, palette, and rows 1, 3, and 4 are immutable. Image 2 is the authoritative face-identity reference. Image 3 supports only the concept of a tiny three-column equalizer; do not copy its segmented eyes or large mouth waves.
Invariants: preserve exactly five columns and four rows, exactly twenty equal panels, all eye geometry and placement, row 1 blink sequence, row 3 thinking drift, row 4 sleep transition, deep blue-black backdrop, cyan palette, hard pixel edges, and current restrained halo. Do not change any eyes. Do not change any mouth outside row 2. No pupils, highlights, text, labels, numbers, arrows, shell, head, body, bezel, decorations, new colors, new panels, or removed panels. Eyes remain continuous solid capsules, never segmented. No antialiasing, gradients, or additional glow.
Avoid: any speech mouth wider than 10 logical pixels or taller than 8 logical pixels; more than three bars; large waveform; segmented eyes; frame-to-frame face drift. This is a single targeted size correction only.
```

#### Motion v3 — rejected correction

```text
Use case: precise-object-edit
Asset type: second targeted size correction of the project grounding 5×4 robot pixel-face motion board
Primary request: Edit Image 1 only. The previous correction improved the speech row, but row-2 mouth frames 2–5 are still too wide. Change only the five row-2 mouth marks. Keep frame 1 as its current tiny line. Shrink frames 2–5 to roughly 40% of their current total width and 70% of their current height. In the 1774×887 source image, each complete three-bar mouth including gaps must fit inside an approximately 20×18 source-pixel bounding box, centered at the existing mouth position. Keep exactly three very narrow bars and the ordered height progression; the biggest frame must still be no wider than the tiny line in frame 1 plus only a few pixels.
Input images: Image 1 is the EDIT TARGET; Image 2 is the authoritative face-identity reference.
Invariants: change only row 2 mouths; preserve all twenty panels, exact 5×4 layout, gutters, background, every eye, all face positions, row 1 blink, row 3 drift, row 4 sleep, palette, pixel style, and halo exactly. Do not move the mouths from their existing baseline. No pupils, segmented eyes, text, labels, numbers, arrows, shell, body, bezel, new colors, extra elements, extra panels, or additional glow.
Avoid: row-2 mouth wider than approximately 20 source pixels; more than three bars; large waveform; editing any eye or any other row. This is one surgical size correction only.
```

## Cell order

| | Column 1 | Column 2 | Column 3 |
| --- | --- | --- | --- |
| Top row | neutral | happy | curious |
| Bottom row | concerned | excited | sleeping |

## Files

| Direction | Final board | Uncropped source | Final tile size |
| --- | --- | --- | --- |
| A — capsule eyes and speech mouth | `direction-A-capsule-mouth.png` | `source-A.png` | 512×320 |
| B — segmented eyes and equalizer mouth | `direction-B-segmented-equalizer.png` | `source-B.png` | 480×300 |
| C — eye-dominant wedges and signal glyph | `direction-C-eye-dominant-glyph.png` | `source-C.png` | 576×360 |

Every final tile has an exact 8:5 aspect ratio. The boards use 16-pixel
`#061018` gutters. Final assembly only crops and composites source pixels; it
does not rescale the generated face geometry. `source-C.png` is the accepted
second iteration. Direction C used the exact base-generation prompt and then
the exact targeted-correction prompt recorded below.

## Exact prompts

### Direction A

```text
Use case: stylized-concept
Asset type: project-bound visual grounding board for a robot UI face rendered on an 8:5, 160×100 logical pixel display
Primary request: Create ONE clean ultra-wide landscape concept board containing a precise 3-column × 2-row matrix of the SAME original friendly retro-futurist pixel robot face in six ordered emotional states. Reading order is fixed: top-left neutral, top-center happy, top-right curious; bottom-left concerned, bottom-center excited, bottom-right sleeping. Do not include labels, captions, numbers, typography, legends, borders, or any other text.
Scene/backdrop: Every cell is an isolated near-black navy display field, exact visual cue #061018, with consistent generous negative space. Use subtle equal gutters only to reveal the clean 3×2 matrix. No environment.
Subject: Direction A — a compact central face made from TWO luminous elongated horizontal capsule eyes plus ONE tiny minimal pixel mouth below. The mouth must visibly support speech: closed one-pixel dash, slightly open small rectangular gap, and more open compact block shape, while remaining clearly secondary to the eyes. Preserve one coherent face identity, scale, eye spacing, vertical placement, and construction across all six cells. Emotion changes should come from hard-edged eye curvature/tilt/height and restrained mouth opening: neutral balanced; happy gently upturned/smiling eyes; curious asymmetrical height or one shortened eye; concerned subtly inward/downward eye angles; excited wider/taller bright eyes and open mouth; sleeping two low thin closed-eye slits with a tiny dormant mouth mark.
Style/medium: deliberately low-resolution pixel display concept art; hard-edged integer-like block geometry; crisp stepped corners; no vector-smooth curves; no soft illustration; scalable and instantly readable at thumbnail size. Sleek, warm, minimal, emotionally expressive, original robot UI language.
Composition/framing: Each cell should visually read as an 8:5 face display at 160×100 logical pixels. Face centered and compact, occupying roughly the middle 40–48% of each tile width and less than 35% of height, leaving large quiet margins. Match alignment exactly across cells. Overall board should read as a disciplined sprite-sheet-like 3×2 system, not six unrelated drawings.
Lighting/mood: emissive pixels only, restrained one-to-two-pixel cyan bloom immediately around shapes; otherwise flat deep black-navy. No ambient lighting.
Color palette: background #061018. State accent cues: neutral #76f4ff; happy #68ffd1; curious #6ce8ff; concerned #ff7f91; excited #ffdf6c; sleeping cool cyan. Keep each state monochrome aside from a slightly brighter core.
Materials/textures: pure digital emissive pixels; no texture, no glass, no reflections.
Constraints: exactly six cells and exactly one face per cell; exactly two eyes plus at most one tiny mouth per face; same original face design in all cells; no antialiasing; no soft gradients beyond restrained cyan glow; no head silhouette, no body, no hands, no ears, no nose, no room, no control panel, no frame or monitor hardware; no text or symbols that look like writing.
Avoid: Do not depict EVE, WALL-E, or any exact copyrighted character likeness. Do not reproduce any recognizable proprietary eye geometry. No logos, branding, watermarks, decorative stars, speech bubbles, emoji faces, anime eyes, pupils, photorealism, 3D rendering, glossy white casing, or character body. Create an unmistakably original visual system that only evokes the broad qualities of sleek, friendly, emotionally expressive retro-futurist robot design.
```

### Direction B

```text
Use case: stylized-concept
Asset type: project-bound visual grounding board for a robot UI face rendered on an 8:5, 160×100 logical pixel display
Primary request: Create ONE clean ultra-wide landscape concept board containing a precise 3-column × 2-row matrix of the SAME original friendly retro-futurist pixel robot face in six ordered emotional states. Reading order is fixed: top-left neutral, top-center happy, top-right curious; bottom-left concerned, bottom-center excited, bottom-right sleeping. Do not include labels, captions, numbers, typography, legends, borders, or any other text.
Scene/backdrop: Every cell is an isolated near-black navy display field, exact visual cue #061018, with consistent generous negative space. Use subtle equal gutters only to reveal the clean 3×2 matrix. No environment.
Subject: Direction B — a compact central face built from TWO distinctive segmented stepped-pixel eyes. Each eye is a short horizontal cluster made of 3–5 discrete rectangular light blocks with crisp one-pixel gaps or notches, giving a modular LED language rather than smooth capsules. Below them is ONE short centered equalizer/light-bar mouth made of 3–5 narrow vertical pixel columns; it must clearly support a continuous mouthLevel 0–1 by changing bar heights while remaining subordinate to the eyes. Preserve one coherent face identity, scale, spacing, vertical placement, segment vocabulary, and construction across all six cells. Emotion changes: neutral even stepped bars and mouth nearly flat; happy outer eye segments lift while the lower equalizer forms a shallow smile/rising rhythm; curious one eye cluster compresses and the other lifts, with a tiny uneven mouth pulse; concerned inner segments dip toward center with a low restrained bar; excited eyes expand into tall stepped clusters and equalizer bars rise strongly; sleeping eyes collapse into two thin segmented slits and mouth becomes one minimal low pulse.
Style/medium: deliberately low-resolution pixel display concept art; hard-edged integer-like block geometry; crisp steps and clearly separated modules; no vector-smooth curves; no soft illustration; scalable and instantly readable at thumbnail size. Sleek, warm, minimal, emotionally expressive, original robot UI language.
Composition/framing: Each cell should visually read as an 8:5 face display at 160×100 logical pixels. Face centered and compact, occupying roughly the middle 44–52% of each tile width and less than 38% of height, leaving large quiet margins. Match alignment exactly across cells. Overall board should read as a disciplined sprite-sheet-like 3×2 system, not six unrelated drawings.
Lighting/mood: emissive pixels only, restrained one-to-two-pixel bloom immediately around shapes; otherwise flat deep black-navy. No ambient lighting.
Color palette: background #061018. State accent cues: neutral #76f4ff; happy #68ffd1; curious #6ce8ff; concerned #ff7f91; excited #ffdf6c; sleeping cool cyan. Keep each state monochrome aside from a slightly brighter core.
Materials/textures: pure digital emissive pixels; no texture, no glass, no reflections.
Constraints: exactly six cells and exactly one face per cell; exactly two segmented eye clusters plus at most one tiny lower equalizer mouth per face; same original face design in all cells; no antialiasing; no soft gradients beyond restrained cyan glow; no head silhouette, no body, no hands, no ears, no nose, no room, no control panel, no frame or monitor hardware; no text or symbols that look like writing.
Avoid: Do not depict EVE, WALL-E, or any exact copyrighted character likeness. Do not reproduce any recognizable proprietary eye geometry. No smooth capsule eyes, no logos, branding, watermarks, decorative stars, speech bubbles, emoji faces, anime eyes, pupils, photorealism, 3D rendering, glossy white casing, or character body. Create an unmistakably original visual system that only evokes the broad qualities of sleek, friendly, emotionally expressive retro-futurist robot design.
```

### Direction C — base generation

```text
Use case: stylized-concept
Asset type: project-bound visual grounding board for a robot UI face rendered on an 8:5, 160×100 logical pixel display
Primary request: Create ONE clean ultra-wide landscape concept board containing a precise 3-column × 2-row matrix of the SAME original friendly retro-futurist pixel robot face in six ordered emotional states. Reading order is fixed: top-left neutral, top-center happy, top-right curious; bottom-left concerned, bottom-center excited, bottom-right sleeping. Do not include labels, captions, numbers, typography, legends, borders, or any other text.
Scene/backdrop: Every cell is an isolated near-black navy display field, exact visual cue #061018, with consistent generous negative space. Use subtle equal gutters only to reveal the clean 3×2 matrix. No environment.
Subject: Direction C — a radical eye-dominant compact face with TWO broad expressive luminous eyes constructed as solid hard-edged pixel wedges/ribbons, each with a characteristic clipped inner notch and stepped tapered outer tip. The eyes are not capsules and not groups of separate blocks; each is one contiguous sculpted pixel silhouette. Nearly all emotion must come from strong but coherent eye deformation: neutral long shallow notched ribbons; happy upward crescent-like stepped ribbons; curious one tall compact diamond-wedge and one long raised ribbon; concerned inward-drooping angular ribbons; excited enlarged tall radiant kite-wedges; sleeping extremely thin downward-curved stepped slits. Below the eyes, include only ONE subtle tiny centered signal glyph of 1–3 pixels, such as a micro chevron/pulse, whose height can gently respond to speech amplitude without reading as a conventional mouth. Preserve the same clipped-notch/taper identity, overall scale, eye spacing, vertical placement, and pixel vocabulary across all six cells.
Style/medium: deliberately low-resolution pixel display concept art; hard-edged integer-like block geometry; crisp stepped silhouette deformation; no vector-smooth curves; no soft illustration; scalable and instantly readable at thumbnail size. Sleek, warm, minimal, emotionally expressive, original robot UI language.
Composition/framing: Each cell should visually read as an 8:5 face display at 160×100 logical pixels. Face centered and compact, occupying roughly the middle 46–54% of each tile width and less than 38% of height, leaving large quiet margins. Match alignment exactly across cells. Overall board should read as a disciplined sprite-sheet-like 3×2 system, not six unrelated drawings.
Lighting/mood: emissive pixels only, restrained one-to-two-pixel bloom immediately around shapes; otherwise flat deep black-navy. No ambient lighting.
Color palette: background #061018. State accent cues: neutral #76f4ff; happy #68ffd1; curious #6ce8ff; concerned #ff7f91; excited #ffdf6c; sleeping cool cyan. Keep each state monochrome aside from a slightly brighter core.
Materials/textures: pure digital emissive pixels; no texture, no glass, no reflections.
Constraints: exactly six cells and exactly one face per cell; exactly two contiguous eye silhouettes plus at most one tiny lower signal glyph per face; same original face design in all cells; eye-dominant and more radical than conventional robot eyes; no antialiasing; no soft gradients beyond restrained cyan glow; no head silhouette, no body, no hands, no ears, no nose, no room, no control panel, no frame or monitor hardware; no text or symbols that look like writing.
Avoid: Do not depict EVE, WALL-E, or any exact copyrighted character likeness. Do not reproduce any recognizable proprietary eye geometry. No capsule eyes, no separately segmented equalizer eyes, no conventional smiling mouth, no logos, branding, watermarks, decorative stars, speech bubbles, emoji faces, anime eyes, pupils, photorealism, 3D rendering, glossy white casing, or character body. Create an unmistakably original visual system that only evokes the broad qualities of sleek, friendly, emotionally expressive retro-futurist robot design.
```

### Direction C — accepted correction

```text
Use case: stylized-concept
Asset type: targeted correction of the just-generated project-bound 3×2 robot PixelFace grounding board
Primary request: Edit the immediately previous Direction C board only. Preserve the exact 3-column × 2-row layout, cell order, dark backdrop, palette, pixel scale, face identity, radical contiguous wedge/ribbon visual language, clipped inner notch, tapered outer tips, tiny centered chevron signal glyph, generous negative space, and all acceptable cells. Correct only the emotional legibility and eye geometry in the top-right curious, bottom-left concerned, and bottom-center excited cells.
Required corrections:
1. Top-right curious: it must still show exactly TWO clearly separate, horizontally based eye silhouettes at the normal left-eye and right-eye positions. Make them asymmetrical in height, length, tilt, or notch emphasis to imply attentive gaze, but neither eye may become vertical or diamond-shaped. Keep both recognizably derived from the neutral horizontal wedge/ribbon eyes.
2. Bottom-left concerned: make the pair unmistakably worried and vulnerable, never angry. Each eye must have its INNER corner nearest the center raised higher and its OUTER corner lowered, producing a gentle worried roof/tilt. Soften the overall silhouette and keep the eyes relatively narrow. Do not point the inner corners downward.
3. Bottom-center excited: make the pair unmistakably delighted, open, and welcoming, never aggressive. Use wider and/or taller upward-opening eye shapes, with lifted outer energy and a buoyant rounded-stepped silhouette. Do not use long downward-pointing blades, predatory inward slants, or angry V geometry.
Invariants: exactly six cells; top-left neutral, top-center happy, top-right curious; bottom-left concerned, bottom-center excited, bottom-right sleeping. Same original robot face in every cell. Exactly two contiguous hard-edged pixel eye silhouettes and at most one tiny lower chevron/pulse glyph. Background #061018. State colors remain neutral #76f4ff, happy #68ffd1, curious #6ce8ff, concerned #ff7f91, excited #ffdf6c, sleeping cool cyan. No labels, captions, typography, legends, numbers, logos, branding, watermark, head, body, room, hardware, pupils, anime eyes, smooth capsules, separated equalizer-eye blocks, photorealism, or 3D casing. No antialiasing or soft gradients beyond restrained one-to-two-pixel cyan glow. Do not depict EVE, WALL-E, or any exact copyrighted character likeness; preserve the unmistakably original retro-futurist robot UI system.
```
