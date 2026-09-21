# Vector PSD fixtures

The self-authored Photoshop fixtures the vector codec and renderer tests read. Split out of [vector-tools.md](vector-tools.md) (September 2026) to keep that file under the size limit.

## Inventory (test-fixtures/psd, self-authored via COM, July 2026)

Each .psd has a sibling .bmp, Photoshop's own flatten (24-bit, white
background layer), for render-parity tests; the embedded composites are
headless-stale (ps-compat.md).

- photoshop-shape-solid.psd/bmp: curved shape, SoCo red; pins knot in/out
  order via render.
- photoshop-shape-gradient.psd/bmp: GdFl linear 37 deg, 3 color + 3
  transparency stops with midpoints.
- photoshop-shape-pattern.psd/bmp: PtFl, 8x8 checker in the Patt block.
- photoshop-shape-strokes.psd/bmp: six stroked layers (alignments, caps,
  joins, dashed open curve, stroke-only / fillEnabled false).
- photoshop-shape-boolean.psd/bmp: four subpaths add/subtract/intersect/xor
  (sequential-combine ground truth).
- photoshop-shape-first-ops.psd/bmp: single-subpath layers with op
  subtract/intersect/xor (initial-accumulator semantics).
- photoshop-shape-live-rect.psd/bmp: live rounded rect (radii 4/8/12/16),
  live ellipse, live line w4 (vogk per kind; vowv presence).
- photoshop-vector-mask-on-pixel.psd/bmp: pixel layer + vector mask; no mask
  channel or section.
- photoshop-both-masks.psd/bmp: raster + vector masks on one layer; second
  layer at density 60% + feather 1.5 px (parameters + baked -2, flags 0x18).
- photoshop-vector-mask-feather.psd/bmp: vector feather 4 (path on the
  canvas corner) and 8.
- photoshop-user-mask-params.psd/bmp: raster-mask feather 3 + density 50%,
  feather 6.5, density 25%.
- photoshop-saved-paths.psd/bmp: "Alpha Path" (rect, clipping path), "Beta
  Path" (donut), work path; resources 2000/2001/1025/2999.
- photoshop-shape.psb/photoshop-shape-psb.bmp: PSB variant of the solid
  shape.

