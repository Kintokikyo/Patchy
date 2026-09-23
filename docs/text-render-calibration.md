# Photoshop text render calibration

The Photoshop layout/measurement model for type layers: engine units, leading, tracking,
faux bold/italic, whole-pixel glyph folding, and the run-format columns. Split from
[text-tool.md](text-tool.md), which owns the inline-editor session machinery; the
line-plan renderer contract also lives there.

## Photoshop text model (type layers)

Probe PSDs `photoshop-text-*.psd`. The rules apply when `kLayerMetadataTextLayoutMode == "photoshop"` (set on import of non-Patchy TySh):

- **Engine units are document pixels.** 24 pt UI at 300 dpi stores `/FontSize 100` with an identity transform; the transform does NOT carry DPI. UI pt = engine size x transform y-scale x 72/dpi.
- **The TySh transform maps text space to document pixels.** Vertical scale (`hypot(yx, yy)`) multiplies sizes and leading; the x/y ratio is a pure horizontal glyph stretch (free transform folds into the matrix, so xx != yy is common). Never average the two axes.
- **Style runs omit properties equal to the ResourceDict normal style sheet**; a run without `/FontSize` uses the sheet's default (usually 12.0), never a sibling run's value.
- **Leading is per-character; a line's baseline advance = the max effective leading among the ENTERED line's characters.** Fixed leading applies only with `/AutoLeading false`; otherwise the recorded `/Leading` is stale and the effective value is the paragraph auto-leading fraction (default 1.2) x FontSize, sub-pixel exact. It may be smaller than the em.
- **Point text anchors the FIRST baseline at the transform translation (tx, ty)**; justification decides whether tx is line start, middle, or end. No leading on the first line.
- **Box text puts the first baseline at box top + OS/2 sTypoAscender x size** (largest run on line 1; capHeight and hhea/winAscent are wrong), read via QRawFont (`typographic_ascent_fraction`). Leading does not move it.
- **Tracking = FontSize x tracking/1000 px per inter-glyph gap** (not after the last glyph), as absolute letter spacing.
- **VerticalScale/HorizontalScale scale glyphs only**; auto leading stays 1.2 x FontSize, unscaled.

Run format "patchy.text.runs" v3 adds double sizes, a leading column (number or `auto`), tracking, and H/V glyph scales; v4 appends the faux-bold flag, v5 the face/style name, v6 the faux-italic flag, v7 the rotated-Roman flag of vertical type; paragraph v3 appends the auto-leading fraction, v4 the direction. Every column is read by INDEX, so the version token rises only when a run needs the new column and existing files stay byte-identical. Patchy-authored text keeps v1/v2 and Qt-natural layout (the PS model is opt-in per layer, so Patchy PSDs reopen unchanged). Export writes `/AutoLeading false` for fixed leading (PS ignores it otherwise), non-zero `/Tracking`, non-1 `/HorizontalScale`/`/VerticalScale`.

## Faux bold is not the bold face

`/FauxBold` asks Photoshop to synthesize weight on the face the run already names. It is NOT
"use the family's bold face": folding it into the run's bold flag swaps in a different typeface.
On the Dungeon Scroll `Game_Screen.psd` headings, Georgia-Italic + faux bold measures 58px wide
in Photoshop's own raster while Georgia **Bold** Italic renders 63. Photoshop's Character panel
shows the same split, so clicking into such a layer must come up Italic, not Bold + Italic.

- `PsdTextStyleRun::faux_bold` carries it, `bold`/`italic` keep meaning the real face, and runs
  v4 serializes it in column 11. The Character panel's `textCharacterFauxBold` checkbox edits it
  live per selection.
- Rendering: `apply_faux_bold_to_document` strokes the glyph outlines with a pen
  `kFauxBoldEmFraction` (0.03) of the em wide and adds the same amount to every advance, as
  Photoshop does (its faux bold pushes the glyph out on both sides and pays for it in the
  advance). Calibrated on Georgia-Italic at 12px against Photoshop's rasters: "Dungeon:" 58px and
  "Fights Left:" 69px both land exactly anywhere in 0.025-0.030.
- Applied in `build_text_render_document`, not when the runs are parsed, so it follows later
  colour and size edits and the raster pass and caret layout (which share that function) see
  identical advances. Apply it BEFORE the final `setTextWidth`: the widened advances have to be in
  place while the lines are laid out.
- Export writes `/FauxBold` from `faux_bold` alone. The run's real weight already rides in the
  font name `font_index_for_run` resolves (`Arial-BoldMT`, not Arial + FauxBold); writing both
  made Photoshop embolden an already-bold face.
## Faux italic is a shear, not a font

`/FauxItalic` splits from `run.italic` exactly as faux bold splits from `run.bold`: it slants the
run's OWN face and must not resolve to the family's real Italic, which is a different typeface.
It rides `PsdTextStyleRun::faux_italic` and runs v6 column 13, the Character panel edits it
(`textCharacterFauxItalic`), and export writes `/FauxItalic` from that flag alone.

Rendering it cannot go through QFont: measured on Arial, `QFont::setStyle(QFont::StyleOblique)`
resolves to the family's REAL Italic face (`QFontInfo::styleName()` returns "Italic", identical
ink). The renderer shears the drawn line about its own baseline instead (`faux_italic_shear`,
`kFauxItalicSlant` = tan 12 degrees), leaving advances alone as Photoshop does and growing only
the raster's right bleed.

- **The shear is per LINE, not per run.** `QTextLine::draw` draws a whole line, so
  `line_is_entirely_faux_italic` gates it and a line whose runs disagree stays upright. Per-run
  would mean redrawing through `QTextLine::glyphRuns()` + `QPainter::drawGlyphRun`, reapplying
  colour, the faux-bold outline and selection per run, and moving every pinned pixel baseline in
  the suite. Known gap; faux italic is layer-level across the corpus.
- `ui_faux_italic_shears_the_rendered_glyphs` pins it on "HH" (vertical stems only): upright ink
  starts at the same column top and bottom, sheared ink ~8px further right at the top of a 64px cap.

## Glyph sizes fold only to whole pixels

Qt rasterizes glyphs at whole pixel sizes only: `QFont::setPixelSize` takes an int, and a
fractional `setPointSizeF` quantizes to the same whole pixel (measured -- 16.2px and 16px report
an identical advance). So `render_text_pixels_with_local_rect` folds a transform's vertical scale
into the glyph sizes only as far as the nearest whole pixel and leaves the remainder in
`document_transform`, which the rasterizer applies exactly because these lines are drawn THROUGH
the matrix rather than resampled after the fact. `dominant_text_run_size` picks the size that
lands exactly (the largest run, vertical glyph scale included).

Folding the whole scale rounds the text off Photoshop's size, and only for some layers: in the
Dungeon Scroll repro, 18 x 0.9 = 16.2 became 16 (~1.2% narrow -- "Jumble"/"Submit word" lost
1-2px and shifted on edit) while 14.44444 x 0.9 = 13.0 was already whole and never moved
("Quit"/"Pause"). Leaving the remainder in the matrix also agrees with the caret, which lays out
at the raw size and applies the full transform through the overlay.
`ui_dungeon_scroll_psd_text_commit_keeps_placement_if_available` pins both against Photoshop's
rasters. Photoshop's anchors sit at fractional document positions (tx 267.35, ty 305.4); both
renderers put the raster at the anchor ROUNDED to a whole pixel (next section), so what is left
is glyph-advance quantization, not grid phase.

- **Text renders UNHINTED**: PS never runs TrueType hinting; every antialiased `/AntiAlias` mode maps to `QFont::PreferNoHinting` (`configure_text_font_smoothing`); mode 0/None keeps `NoAntialias` + full hinting, which fattens stems on small-print-era fonts and shifts advances into collisions.
- **Imported type layers keep Photoshop's raster until edited** (`should_regenerate_imported_text_preview`, psd_text_write.cpp): a missing font never changes appearance on open. Rasters are kept even under big effects; regenerate only when the stored preview is visibly NOT any run's declared fill color (baked-in effect pixels would corrupt the live outer-effect contour), or when the type block is Patchy-authored. Editing a kept raster warns before substituting fonts; `--append-text` substitutes silently. **Continuing past that warning really substitutes**: `substituted_text_family` (what `QFontInfo` resolves the missing family to, then the UI font, then the original when nothing installed can draw the text) moves the session's base family and `substitute_missing_document_font_families` every run, blank paragraphs' block char formats included. Otherwise the commit stores the missing name back over a raster drawn in the substitute and the layer stays badged. The editable PDF export is the one reader that re-lays-out a kept raster without an edit (real text placed on the raster's ink, missing fonts substituted unless asked for pixels; see [pdf.md](pdf.md)).
- **Black/Heavy faces (weight >= 800, DirectWrite or font database)** resolve to their FULL face name so the family+style matcher finds the real face (family+bold renders Bold, ~15% narrower); the bold flag stays set for fallback. Never feed such a name raw to the font combo: `QFont("Arial Black")` resolves to Tahoma; use `text_font_combo_font_for_family`.
- **Rotated point-text anchoring**: committed placement pins the TEXT-SPACE anchor (justification fraction along the reading axis, first-line side on the stack axis), never a fixed document corner; the CS-era document-bounds fallback pins the corresponding fractional point of the source ink box.
- **Scaled BOX text**: runs and box dims (`patchy.text.box_width/height`, from `/BoxBounds`) are engine units, but a PSD-frame edit session works in DOCUMENT space; the render call's `layout_scale` folds the transform's vertical scale into glyph sizes WITHOUT scaling box dims, and commit stores frame dims divided back to raw units so runs, box and transform stay one coordinate system.
- Committing a transformed point-text layer re-renders CRISP through the aligned transform even when the font is substituted (resampling delivers the same glyphs blurry). The first re-edit after conversion settles placement by a few pixels; later cycles are identical.
- Known gaps: LeadingType 1 (Japanese top-to-top), per-run BaselineShift, VerticalScale x auto leading under a folded transform; box-text RE-edits resample when the residual still has a linear part (rotation, aspect): Free Transform and Image Size fold a uniform scale into the size and frame dims, so those re-edits commit crisp, while the commit-time crisp path stays point-text only.

## Vertical type (tategaki)

Photoshop 2026 captures: `local-test-fixtures/psd/ps2026_vtext/` (`capture_vtext.jsx`, PSD + PNG +
`manifest.jsonl` with DOM bounds; `dump_tysh.py` prints a TySh). Four are committed as
`test-fixtures/psd/photoshop-text-vertical-{point,box,rotated-roman}.psd` and
`photoshop-text-rtl-hebrew.psd` (corpus digests pinned). Pinned by
`ui_vertical_text_matches_photoshop_capture` (re-rendered ink lands within 1 px of PS's 67x92
raster on the point capture; skips without MS Gothic, since entering an imported layer whose face
is missing raises the modal substitution prompt) and `psd_vertical_*captures*` in tests/core.

- **Every glyph is upright, Latin included, by default**: "Hello" stacks H, e, l, l, o. Photoshop's
  "Standard Vertical Roman Alignment" lies Roman glyphs on their side instead: per run,
  `/BaselineDirection` 1 = upright (what PS writes by default), 2 = rotated; a run WITHOUT the
  key re-lays out rotated, so every vertical run Patchy writes carries it. Patchy: runs v7
  column 14 (`2` = rotated, written only then), `kTextRotatedRomanFormatProperty`, the
  Character panel's "Rotate Latin (vertical text)"; a rotated cell advances by the glyph's
  horizontal width and is drawn turned 90 degrees clockwise with its ascent+descent box centred
  on the axis; CJK clusters stay upright either way. Cell pitch = FontSize x
  VerticalScale (MS Gothic and Arial both advance 32 px per glyph at 32 px); whitespace advances
  by its horizontal width (a 32 px Arial space is 8.89 px of column, so "Hello World" spans
  10 x 32 + 8.89). Tracking adds FontSize x tracking/1000 after every cell except a column's
  last (three cells at +200 = 96 + 2 x 6.4).
- **Glyph placement in the cell**: centred on the column axis horizontally; the font's
  ascent + descent box centred vertically, baseline = cell top + (em - (asc + desc)) / 2 + asc.
  MS Gothic's box is exactly 1 em (ink 3 px inside the cell top), Arial's is 1.117 em (caps
  4-5 px below the cell top, ~0.84 em baseline).
- **Columns advance left by the entered column's max effective leading** (auto 1.2 x 32 =
  38.4: second column left edge at -54.4; fixed 48: at -64), the horizontal per-line rule
  transposed.
- **Anchors** (`bounds` in the TySh, transform = the click): point text x in [-em/2, em/2]
  around the first column's axis; y in [0, h] for left (top), [-h/2, h/2] for center,
  [-h, 0] for right (bottom). Box text: transform at the frame's top-left, `/BoxBounds`
  [0 0 w h], the first column against the frame's RIGHT edge, wrapping by whole cells at the
  frame height (7 cells of 32 in a 250 px frame), overflow columns hidden.
- **Patchy raster = cell union + bleed** `vertical_text_bleed_for_size(size)` = ceil(0.25 x
  base size) on every side (psd/psd_text_runs.hpp, shared by the renderer and the Qt-free PSD
  writer), so `text_geometry_for_layer` recovers the anchor from the raster rect alone:
  tx = right - bleed - em/2, ty = top + bleed + fraction x (height - 2 x bleed).
- **`/Tracking` is written as an integer.** Photoshop's engine re-lays out a layer with a
  negative float tracking (`-305.000000`) as "the result would be too big", every edit failing;
  `-305` and positive floats work (COM bisect on a user file, September 2026).

## Pixel grid and fractional anchors

PS 27.9 COM captures (September 2026): "Hg", Arial 48 px, Sharp, placed at x or y 100.0 / 100.3 /
100.5 / 100.7, plus 10-degree rotated and 150% scaled variants; two are committed as
`test-fixtures/psd/photoshop-text-anchor-{whole,half}.psd` (x 100.0 and 100.5).

- **The TySh keeps the fractional anchor** (tx 100.3, 100.5, 100.7 round-trip exactly;
  `textClickPoint` carries the same value in percent of the document). Patchy keeps it too:
  `patchy.text.transform` serializes at 17 significant digits (`serialize_layer_affine_transform`),
  `committed_text_transform` leaves tx/ty alone when their rounding already equals the committed
  document point, and integer moves add to them.
- **Photoshop rasterizes from the anchor rounded to a whole pixel, halves up** (`snap_to_pixel_grid`,
  core/pixel_grid.hpp: floor(v + 0.5)): x 100.3 renders byte-identically to 100.0, and 100.5 and
  100.7 identically to each other; y likewise (100.5 is the 100.0 raster shifted one row). The
  rotated and scaled layers behave the same: every raster is a whole-pixel shift of its base.
  `build_text_render_plan` therefore snaps the document transform's dx/dy before drawing, and the
  editor's document point (`set_text_editor_transform_override`, `rendered_text_bounds_for_editor`,
  session entry) rounds the same way instead of flooring. Photoshop's own raster in the half
  fixture starts one column later than the whole one (record rect 104 vs 103). Pinned by
  `ui_text_transform_rerender_rounds_anchor_like_photoshop` (148.3 renders as 148.0, 148.5 as
  149.0, the fraction survives in the stored transform), `ui_box_text_edit_keeps_fractional_anchor`
  and `psd_text_anchor_captures_keep_fractional_transform`.
- **Known gap: per-glyph x rounding.** Photoshop also rounds EACH glyph's absolute x position: at
  x 100.5 the "H" moved one column while the "g" (100.5 + 34.67 = 135.17 -> 135, the same column
  as 134.67) stayed. Qt places glyphs at fractional advances, so a line can differ from Photoshop
  by a column inside the run even when the anchor agrees.
- Box text keeps a fractional `/BoxBounds` in PS (100.6 x 80.3); `patchy.text.box_width/height`
  round it (`extract_type_tool_text_box`). The frame origin rounds like a point anchor.
- **TySh encoding**: descriptor `Ornt` enum `Vrtc`; engine data `/WritingDirection 2` in
  both the Shapes and Lines dictionaries and `/Procession 1` (horizontal: 0, 0). Nothing else
  in the engine data differs between a vertical and a horizontal save.
- **OpenType `vert`** rides on the render document's default font (`QFont::setFeature`; Qt 6.8
  char formats cannot carry features), so fonts with the table get their vertical brackets and
  long-vowel marks.
- **Acceptance (COM, September 2026)**: Photoshop 2026 opened the Patchy-authored
  `test-artifacts/vertical_text_check.psd` (written by
  `ui_vertical_text_recommit_keeps_origin_and_round_trips_psd`, dialogs suppressed), read the
  layer back as `orientation:vertical` with bounds [-54.4, -32, 16, 32] (its own convention for
  centred two-column text), and a forced type re-render landed the two columns within 4 px of
  Patchy's ink (`readback_patchy.jsx`). A warning-enabled open was not checked (it needs the
  desktop); `/ParagraphDirection` acceptance is unverified.

## Paragraph direction (right-to-left)

`patchy.text.paragraph_runs` v4 appends column 9 (`auto`/`ltr`/`rtl`), written only when a
paragraph carries an explicit direction. Photoshop keeps its `directionType` OUTSIDE the TySh
(September 2026 captures: `rtl_hebrew_dir_rtl.psd` and `_ltr.psd` differ only in bounds; the DOM
reads it back from the document-level Txt2 resource), so a Patchy-authored PSD can only express
it through the Middle Eastern composer's `/ParagraphDirection` paragraph key (1 = RTL, 0 = LTR,
written only for explicit directions; read back into the v4 column). Whether Photoshop's Latin
composer honours that key on a foreign file is unverified. Photoshop's default engine already
reorders Hebrew and shapes Arabic (captures `rtl_mixed.png`, `rtl_arabic_left.png`), as Qt does.
