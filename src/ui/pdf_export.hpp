#pragma once

#include "core/document.hpp"

#include <QPageSize>
#include <QString>

#include <functional>
#include <span>
#include <string>
#include <vector>

class QPainter;
class QPdfWriter;

namespace patchy::ui {

// Single-page PDF export sized to the document itself, not to a sheet of paper: the
// page is pixels / document PPI inches per axis, matching Photoshop's Save As PDF.
// The paper-relative flow (page layout, margins, crop marks, scale-to-fit) stays in
// print_dialog.hpp's write_print_pdf.
//
// Qt's PDF engine re-encodes every non-grayscale image as JPEG quality 94 unless the
// painter asks for QPainter::LosslessImageRendering, and it exposes no quality knob,
// so the image choice really is one bool. Lossless is the default: an image editor's
// PDF export must not silently degrade pixels.
struct PdfExportOptions {
  bool lossless{true};
  // Keep layers as editable objects instead of one flattened image: shape layers become
  // PDF paths, text layers real text with embedded fonts, pixel and smart-object layers
  // images. What Qt's PDF engine cannot composite per object (blend modes, adjustment
  // layers, group opacity, raster masks on vectors, layer styles) flattens into an
  // image chunk with a notice, so the page can look different from the canvas.
  bool editable_layers{false};
  // Editable mode only: a text layer whose font is not installed is embedded as its pixels
  // instead of being drawn as real text in a substitute face (the default keeps it text,
  // with a notice naming the missing font). Persists as saveOptions/pdfMissingFontsAsImages.
  bool missing_fonts_as_images{false};
};

// Writes a one-page PDF of the document. Flat mode holds the flattened composite
// (document alpha becomes a PDF /SMask); editable mode walks the layer stack (see
// pdf_export_editable.cpp). `notices` receives one line per structural loss in editable
// mode. Throws std::runtime_error when the file cannot be written.
void write_pdf_document_file(const Document& document, const QString& path, const PdfExportOptions& options = {},
                             std::vector<std::string>* notices = nullptr);

// A multi-page PDF, one page per document in order, each page sized from its own
// pixels and PPI exactly as the single-page writer sizes its page (File > Export
// Multi-Page PDF and app.exportPdf). Flat or editable per `options`, the same way per
// page; editable-mode losses land in `notices`. `progress` is called before each page
// (1-based page, page count); returning false cancels: the partial file is removed and
// the function returns false. Returns true when the file was written. Throws
// std::runtime_error on an empty list, a null or empty document, or a file that
// cannot be written.
using PdfPageProgress = std::function<bool(int page, int page_count)>;
bool write_multipage_pdf_file(std::span<const Document* const> pages, const QString& path,
                              const PdfExportOptions& options = {}, std::vector<std::string>* notices = nullptr,
                              const PdfPageProgress& progress = {});

// "Print a folder as a page": one copy of the document per visible top-level layer
// group, with every other top-level layer hidden except, when
// `include_ungrouped_layers`, the non-group root layers (a shared background). Top of
// the layer stack first, so groups named Page 1, Page 2, ... in the panel come out in
// reading order. Empty when the document has no visible top-level group.
[[nodiscard]] std::vector<Document> documents_for_top_level_groups(const Document& document,
                                                                   bool include_ungrouped_layers);

namespace pdf_detail {
// The page a document exports to: pixels / PPI inches per axis, exact match, shrunk
// to the 14400 pt cap when larger.
[[nodiscard]] QPageSize document_page_size(const Document& document);
// Page sized from the document (pixels / PPI inches per axis, exact-match size, zero
// margins, the 14400 pt cap) and the device resolution pinned to the document PPI so the
// painter's logical grid is one unit per document pixel. Shared by both export modes.
void configure_document_page(QPdfWriter& writer, const Document& document);
// The editable-layers writer.
void write_editable_pdf_document_file(const Document& document, const QString& path, const PdfExportOptions& options,
                                      std::vector<std::string>* notices);
// The editable walk onto a painter that is already begun on a PDF device: sets the
// window to the document's pixel grid and draws every layer. One page's worth; the
// multi-page writer calls it per page.
void paint_editable_document(QPainter& painter, const Document& document, const PdfExportOptions& options,
                             std::vector<std::string>* notices);
// The glyph-run merge (formats/pdf_text_merge.hpp) over a file Qt just wrote, so
// importers see words rather than one object per letter. A file the pass cannot
// handle is left as written. Run after every editable export.
void apply_text_merge_post_pass(const QString& path);
}  // namespace pdf_detail

}  // namespace patchy::ui
