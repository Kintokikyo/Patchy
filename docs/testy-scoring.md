# Testy scoring: what each cell measures

Companion to [testy.md](testy.md) (setup, running, machine specifics).

## The measurements

For every (PSD, editor) pair, the editor opens a staged COPY (corpus files are never
touched; a SHA check at the end of every run proves it), and Testy records:

- **Opens** - did the file load at all.
- **Render accuracy** - the editor's flattened PNG vs Photoshop's, composited over
  white at document size. Two comparisons always run, labeled **byte match** and
  **perceptual** in the report. Byte match counts pixels off by more than 6/255 per
  channel (plus RMSE); honest about raw data, but a subtle color-management shift
  can mark a visually identical render ~100% different. Perceptual counts pixels
  that actually look wrong: SSIM's contrast-structure term combined with CIEDE2000
  deltaE, both computed on lightly blurred copies so anti-aliasing jitter stays
  quiet, with the deltaE threshold scaled up under strong local contrast. A global
  8/255 shift scores ~0% perceptually while byte match reports ~100%; a genuinely
  missing, misplaced, or recolored object fires both. Each metric also gets a
  per-object breakdown using ground-truth layer bounds; an object "renders ok"
  while under 25% of its region's pixels are off (text legitimately differs on
  glyph edges; a bbox also contains what renders behind it, so one error can hit
  several objects). Worst offenders are named in the detail panel, ranked by the
  run's comparison mode. Byte match runs at document resolution; perceptual costs
  about a second and 150 MB of numpy temporaries per megapixel, so it runs on
  copies area-averaged down to `PERCEPTUAL_MAX_PIXELS` (4 MP) and is skipped when
  the renders match pixel for pixel. Above 4 MP the downsample can shift the
  perceptual `badFraction` in relative terms; it drives a 10% triage threshold, not
  a pinned number, and the byte-match figure is unchanged.
  `python testy\analyze.py --selftest` pins all of it against synthetic renders; no
  Photoshop or corpus needed.
- **Honest rendering (trap)** - the editor also opens a byte-patched variant whose
  embedded flat composite is replaced with magenta (`psd_sections.py` rewrites only
  the trailing image-data section; all layer data stays byte-identical). Magenta in
  the render means the editor displayed Photoshop's baked composite instead of
  compositing layers itself. Flattened files (zero layer records) get no trap: the
  composite is the only image data, so reading it is correct and even Photoshop
  would trip the sentinel (noted in the detail panel; old cached cells are fixed
  on reuse). Photoshop tripping its own trap means even the ground
  truth could not re-render the layers (missing fonts etc.) and fell back to the
  baked composite; another editor matching that is not a cheat (a neutral note says
  so) and does not flag in scan mode. Only sentinel coverage more than 5 points
  beyond Photoshop's own counts as a cheat.
- **Native preservation** (labeled "data kept in .psd save" in the report and CLI
  summary; the results.json/history.jsonl keys stay `native`/`nativeScore`) - the
  editor's re-saved PSD is reopened in Photoshop and its layer manifest compared
  against the original's: text still `TEXT`, each adjustment still its exact kind,
  smart objects still smart, groups/masks/vector masks/live effects/clipping/blend
  modes intact. This is the "23/40 objects survived" number; a resave Photoshop
  refuses to open scores as rejected.
- **Round-trip render** - Photoshop's render of the editor's resave vs the
  original's render.
- **Forced text re-render** - scriptable editors append `~TESTY~` to every text
  layer so cached rasters cannot satisfy the render: Photoshop via COM
  (`textItem.contents`), Patchy via `patchy.exe --append-text` (real inline-editor
  sessions per layer). Mutated renders are compared within text-layer regions.
  Before mutation, Photoshop checks every unlocked text layer's style ranges
  against its available fonts. If a required font is missing or cannot be
  inspected, the whole image's forced-text comparison is explicitly skipped,
  with the font names/reason shown in the detail panel. Neither editor mutates
  text for that comparison; ordinary rendering and PSD preservation checks still
  run. No font is silently replaced, and a skipped comparison has no score.
  Mutation errors also suppress the comparison. The font inventory participates
  in ground-truth and Patchy caches, so installing fonts invalidates old text
  results on the next run. Dialog suppression is limited to the mutation step
  when a probe enables opening warnings; it does not conceal PSD opening errors.
  Krita 5.3 and Affinity re-render text on open by design, and GIMP's PSD import
  keeps text layers as baked rasters, so none of them has a mutation leg. The detail panel shows the "render, text appended" pair only
  for editors with the leg (Patchy; Photoshop's lives with the ground truth);
  others state why it is absent (`TEXT_MUTATION_SKIPPED` in testy.py). Photopea
  has no mutation pass; its text is exercised by the cache-free leg below.

The Photoshop column doubles as a control: ~100% render accuracy and full native
preservation validate the pipeline itself.


## The reference render

- Photoshop's reference PNG is always 8-bit sRGB. `normalizeForPng` in
  `drivers/photoshop.py` converts Bitmap to Grayscale, any non-RGB mode to RGB, the
  document profile to sRGB (relative colorimetric, black point compensation; skipped
  for 32-bit), and 16-bit to 8-bit. Without it a grayscale or CMYK file's reference
  was in the document's own space and every editor scored against the wrong numbers.
- The comparison honors an embedded ICC profile in either render
  (`analyze.load_srgb_rgba`), so an editor that exports in the document space with
  the profile attached is not marked down for it.
- Type layers are laid out afresh for the reference: `refreshText` writes each type
  layer's own `textKey` descriptor back (`setd` on `textLayer`), which makes
  Photoshop render the text again and changes nothing else. An old file's cached
  text pixels can differ from what today's Photoshop draws. Skipped when a font is
  missing (the cache is then the only faithful picture).
- `reference_space_key` adds `-srgb1` and `-freshtext1` to the ground-truth and cell
  cache keys for the files these rules change, so older cache entries are not reused.

## Files an editor refuses

A file Photoshop opens and an editor does not counts as a 0% match for that editor
in every average (`refusedWithReference` in the report, `_aggregate` in testy.py).
Harness failures (a wedged Photoshop, a skipped editor) stay out of the averages.

## Scoring without Photoshop's cached pixels

A PSD stores a second copy of every type layer, shape or fill layer and smart
object: the pixels Photoshop last drew for it. An editor that shows those pixels
has not rendered the layer, and a reader of the scores cannot tell. So for files
with such layers the scored render comes from a copy with the caches removed.

- `psd_sections.strip_cached_pixels` empties the stored rectangle and the color and
  transparency channels of each such layer (keys in `CACHED_LAYER_KEYS`); records,
  blocks, masks and layer order stay byte-identical, and the flat composite becomes
  the sentinel. This is the state Photoshop itself writes for fill layers in 16-bit
  files. Testy writes it natively, with no third-party PSD library. Files whose layer
  records sit in an `Lr16`/`Lr32` block (16/32-bit) are not stripped and are scored as
  opened.
- The "plain" copy also renames the defining blocks to an unknown key (`tsTY`),
  leaving ordinary empty pixel layers. The two copies differ in nothing else, so a
  difference between an editor's two renders inside a layer's box is what the editor
  drew for that layer; no difference means it drew nothing. (An earlier version hid
  the layers instead; GIMP exports a different canvas when nothing is visible.)
- `_no_cache_leg` renders the stripped copy (`nocache.png`), keeps the normal render
  as `render_as_opened.png`, and writes the scored `render.png`: the stripped render,
  with each layer the editor drew nothing for outlined and labeled ("Cannot render
  text objects", "Cannot render shape or fill layers", "Cannot render smart
  objects"). The cell's `noCache` block lists `notRendered` and `notMeasured` layers.

The leg must never mark an editor down for the harness's own mistake:

- **A blank type layer or smart object is not proof of a missing engine.** Photoshop
  itself shows nothing for either once the cache is gone, until the layer is edited.
  A blank one counts against an editor only where `BLANK_IS_FAILURE` says the editor
  is known to draw that kind from the layer's data (or to have no engine for it).
  Otherwise the box keeps the as-opened pixels and the layer is reported as "not
  measured (cache shown)". Today that is PhotoDemon's text: it keeps PSD text
  editable but has no scripting to make it lay the text out. Blank shape and fill
  layers always count: Photoshop draws those from the layer's data.
- **Patchy's type layers keep their cache and are re-rendered by script**
  (`TEXT_CACHE_KEPT`; the `*_textkept` staged copies strip everything else). Patchy
  takes a type layer's placement from the cached layer, so on a fully stripped copy
  its text comes out small and misplaced, which says nothing about its text engine.
  `patchy.render_text_afresh` runs `drivers/patchy_text_afresh.js`, which calls
  `layer.rerenderText()` on every type layer (lays the layer out again and changes
  nothing else) and exports. This is how Photoshop's own reference is produced. A
  layer the script did not reach is not measured.
- **Photopea** shows cached text until a text layer is edited, so its stripped render
  goes through `photopea.render_text_afresh`: the host page assigns each text
  layer's `kind` to itself, one layer per script with its own timeout. Only
  `LayerKind.TEXT` layers are touched; reading `textItem` on any other layer makes
  the script engine hang without answering. A text layer the edit did not reach is
  not measured.
- **The extra renders must be of the same document.** A stripped or plain render that
  comes back at another size, or differs from the as-opened render outside the
  cached layers' boxes (grown by a quarter plus 8 px) on more than 5% of those
  pixels, voids the leg after one retry: `noCache.state` is "not measured" with the
  reason, and the cell stays scored as opened. So does an editor that cannot open the
  stripped copy. This caught Affinity exporting the previous file's document when
  every staged copy was named `nocache.psd` (the driver now stages each copy under a
  per-file name).
- **An editor that falls back to the flat composite** when no layer has pixels
  (PhotoDemon) shows the sentinel. That is read as "drew nothing for the cached
  layers" (`showedComposite`), and the scored image is the as-opened render with
  those boxes emptied and labeled, not a magenta canvas.
- A layer invisible in the as-opened render too (covered, zero fill) is no finding.

Measured on open, caches removed (October 2026): Affinity redraws text, shapes and
fills; Patchy redraws shapes and fills (not smart objects); Krita redraws text and gradient fills but nothing for
vector-masked solid fills; Photopea redraws shapes, fills and smart objects, and
text after the scripted edit; psd-tools redraws shapes and fills only; GIMP and
PhotoDemon draw nothing. Cell cache keys carry `-nocache4`.

Known harness gap (open, October 2026): `krita --export` is not deterministic on fill
and vector layers. The same PSD comes out drawn on one run and blank on the next,
apparently because the export does not wait for Krita's asynchronous layer
rendering, so Krita's scores on such files are unreliable. `kritarunner` (a script
that waits with `waitForDone`) silently runs nothing on this install.

## Text rendering in the Standing card

`TEXT_RENDER_BASIS` says what an editor's text score rests on: "forced" (Patchy,
the appended-text leg; its scored render also carries re-rendered text), "open" (Krita, Affinity, and Photopea through the scripted
edit), "replay" (GIMP, psd-tools: no text engine, cached pixels only) or
"unmeasured" (PhotoDemon). The Standing card ranks the first two and names the rest.
