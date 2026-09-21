#include "ui/pdf_export.hpp"

#include "core/layer.hpp"
#include "ui/image_document_io.hpp"
#include "ui/print_internal.hpp"

#include <QImage>
#include <QMarginsF>
#include <QPageSize>
#include <QPainter>
#include <QPdfWriter>
#include <QRect>
#include <QSizeF>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <stdexcept>

namespace patchy::ui {
namespace {

constexpr double kPointsPerInch = 72.0;
// PDF 1.7 (ISO 32000-1, Annex C) caps a page at 14400 units, i.e. 200 inches. A very
// large document at a low PPI would exceed that, so the page shrinks to the cap and the
// image simply prints smaller; the pixels are untouched either way.
constexpr double kMaxPagePoints = 14400.0;

}  // namespace

namespace pdf_detail {

QPageSize document_page_size(const Document& document) {
  const double horizontal_ppi = print_detail::document_horizontal_ppi(document);
  const double vertical_ppi = print_detail::document_vertical_ppi(document);
  double page_width_points = document.width() / horizontal_ppi * kPointsPerInch;
  double page_height_points = document.height() / vertical_ppi * kPointsPerInch;
  if (const double longest = std::max(page_width_points, page_height_points); longest > kMaxPagePoints) {
    const double fit = kMaxPagePoints / longest;
    page_width_points *= fit;
    page_height_points *= fit;
  }
  // QPageSize defaults to FuzzyMatch, which would snap a nearly-Letter page to Letter and
  // change the document's physical size. Exact sizes only.
  return QPageSize(QSizeF(std::max(page_width_points, 1.0), std::max(page_height_points, 1.0)), QPageSize::Point,
                   QString(), QPageSize::ExactMatch);
}

void configure_document_page(QPdfWriter& writer, const Document& document) {
  const double horizontal_ppi = print_detail::document_horizontal_ppi(document);
  writer.setCreator(QStringLiteral("Patchy"));
  writer.setPageSize(document_page_size(document));
  writer.setPageMargins(QMarginsF(0.0, 0.0, 0.0, 0.0));
  // The device resolution only sets the painter's logical grid; keeping it at the
  // document's own PPI makes that grid one unit per document pixel.
  writer.setResolution(std::clamp(static_cast<int>(std::lround(horizontal_ppi)), 72, 2400));
}

}  // namespace pdf_detail

void write_pdf_document_file(const Document& document, const QString& path, const PdfExportOptions& options,
                             std::vector<std::string>* notices) {
  if (options.editable_layers) {
    pdf_detail::write_editable_pdf_document_file(document, path, options, notices);
    return;
  }
  const QImage image = flat_export_qimage(document, true);
  if (image.isNull()) {
    throw std::runtime_error("The document could not be rendered for PDF export.");
  }

  QPdfWriter writer(path);
  pdf_detail::configure_document_page(writer, document);

  QPainter painter;
  if (!painter.begin(&writer)) {
    throw std::runtime_error("The PDF file could not be opened for writing.");
  }
  // LosslessImageRendering is what stops QPdfEnginePrivate::addImage from re-encoding the
  // composite as JPEG quality 94; without it every PDF export would be lossy.
  painter.setRenderHint(QPainter::LosslessImageRendering, options.lossless);
  painter.setRenderHint(QPainter::SmoothPixmapTransform, true);
  painter.drawImage(painter.viewport(), image);
  painter.end();
}

void write_multipage_pdf_file(std::span<const Document* const> pages, const QString& path,
                              const PdfExportOptions& options, std::vector<std::string>* notices) {
  if (pages.empty()) {
    throw std::runtime_error("There are no pages to export.");
  }
  for (const auto* page : pages) {
    if (page == nullptr || page->width() <= 0 || page->height() <= 0) {
      throw std::runtime_error("The document could not be rendered for PDF export.");
    }
  }

  QPdfWriter writer(path);
  // The device resolution is fixed for the whole file (it only sets the painter's
  // logical grid), so it comes from page 1; every page maps its own pixel grid onto
  // its own sheet through the window/viewport pair below.
  pdf_detail::configure_document_page(writer, *pages.front());

  QPainter painter;
  if (!painter.begin(&writer)) {
    throw std::runtime_error("The PDF file could not be opened for writing.");
  }
  for (std::size_t index = 0; index < pages.size(); ++index) {
    const Document& document = *pages[index];
    if (index > 0) {
      // A size set right before newPage() applies to the page it starts.
      writer.setPageSize(pdf_detail::document_page_size(document));
      if (!writer.newPage()) {
        painter.end();
        throw std::runtime_error("The PDF file could not start a new page.");
      }
    }
    painter.save();
    painter.setViewport(writer.pageLayout().paintRectPixels(writer.resolution()));
    painter.setWindow(QRect(0, 0, document.width(), document.height()));
    if (options.editable_layers) {
      pdf_detail::paint_editable_document(painter, document, options, notices);
    } else {
      const QImage image = flat_export_qimage(document, true);
      if (image.isNull()) {
        painter.end();
        throw std::runtime_error("The document could not be rendered for PDF export.");
      }
      painter.setRenderHint(QPainter::LosslessImageRendering, options.lossless);
      painter.setRenderHint(QPainter::SmoothPixmapTransform, true);
      painter.drawImage(QRect(0, 0, document.width(), document.height()), image);
    }
    painter.restore();
  }
  painter.end();
  if (options.editable_layers) {
    pdf_detail::apply_text_merge_post_pass(path);
  }
}

std::vector<Document> documents_for_top_level_groups(const Document& document, bool include_ungrouped_layers) {
  std::vector<Document> pages;
  const auto& layers = document.layers();
  for (std::size_t index = layers.size(); index-- > 0;) {
    const Layer& group = layers[index];
    if (group.kind() != LayerKind::Group || !group.visible()) {
      continue;
    }
    Document page = document;
    auto& page_layers = page.layers();
    for (std::size_t other = 0; other < page_layers.size(); ++other) {
      if (other == index) {
        continue;
      }
      Layer& layer = page_layers[other];
      const bool keep = include_ungrouped_layers && layer.kind() != LayerKind::Group;
      if (!keep) {
        layer.set_visible(false);
      }
    }
    pages.push_back(std::move(page));
  }
  return pages;
}

}  // namespace patchy::ui
