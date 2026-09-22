#pragma once

#include "core/layer.hpp"

#include <cstdint>
#include <functional>

namespace patchy {

// Classic exemplar-based inpainting (Criminisi, Perez, Toyama 2003; Microsoft
// US 6987520 expired 2023-03-30; Efros-Leung 1999 prior art): the hole is
// filled from its boundary inward in a fixed priority order (confidence times
// isophote strength, integer math, lexicographic tie-breaks), and every target
// patch takes the best source patch found by an EXHAUSTIVE scan of a bounded
// window of the same image at the working resolution (integer L1 distance
// over the already-known pixels plus a fixed per-pixel penalty on the source
// offset, first-in-scan-order tie-break). Each target's search
// is independent of every other patch's chosen offset: there is no offset
// field, no propagation from neighbouring patches, no random perturbation, no
// pruned candidate lists, no belief propagation, no coarse-to-fine map, and no
// patch rotation, scale, mirror, or color adaptation. Those are the claimed
// elements of Adobe's active US 8285055 / US 8340463 / US 8355592 (into 2031)
// and US 9396530 (to 2034), which this implementation deliberately does not
// practice. Read docs/legal-constraints.md ("Content-aware fill and exemplar
// inpainting") and docs/patent-research-inpainting.md before changing the
// search or the fill order. Deterministic across toolchains: integers only,
// fixed scan orders, SIMD and scalar distance paths that produce the same
// sums, and a parallel candidate scan whose reduction picks the smallest
// distance and then the smallest scan index.
struct ExemplarInpaintOptions {
  std::int32_t patch_size{9};      // odd; the target and source patch side
  std::int32_t search_radius{96};  // half-size of the candidate window around a target
  // Added to a candidate's distance per pixel of Manhattan offset between the
  // source and target centres: a fixed geometric preference for nearby
  // sources that keeps a texture's phase continuous across a large hole.
  std::int32_t offset_penalty{2};
  bool single_threaded{false};     // skip the candidate-scan fan-out (PATCHY_RENDER_SINGLE_THREADED)
};

struct ExemplarInpaintResult {
  bool filled{false};        // false when some target patch had no usable source
  std::int64_t patches{0};   // patches copied
};

// `image` is interleaved RGBA8, row-major, `width * 4` bytes per row, and is
// filled in place. `hole` is row-major 8-bit coverage over `bounds` (already
// clipped to the image): non-zero cells are filled. `progress(done, total)`
// runs once per copied patch with the pixel counts. Pixels outside the hole
// are never modified; when the result is not `filled`, the image is left
// unchanged.
[[nodiscard]] ExemplarInpaintResult exemplar_inpaint(
    std::uint8_t* image, std::int32_t width, std::int32_t height, const std::uint8_t* hole, Rect bounds,
    const ExemplarInpaintOptions& options, const std::function<void(std::int64_t, std::int64_t)>& progress = {});

// Tone match after the fill: the filled hole's low-pass band (a box blur of
// radius `blur_radius`) is replaced by the harmonic interpolation, across the
// hole, of the surroundings' low-pass band (a normalized box blur over the
// ORIGINAL known pixels only, so the removed object never leaks in), leaving
// the fill's high-pass detail untouched. This is classic frequency
// separation plus the healing membrane of the expired US 6587592 (Dirichlet
// interpolation of boundary values, core/heal_membrane.hpp): no gradients are
// composited and no guidance field exists (US 9058699 stays untouched). It
// removes the brightness steps where fill fronts from differently lit edges
// meet. `filled` and `original` are RGBA8 images of the same size; only hole
// cells of `filled` change.
void exemplar_match_tone(std::uint8_t* filled, const std::uint8_t* original, std::int32_t width,
                         std::int32_t height, const std::uint8_t* hole, Rect bounds, std::int32_t blur_radius);

}  // namespace patchy
