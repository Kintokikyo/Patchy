#include "ui/qt_geometry.hpp"

#include <QString>

#include <algorithm>

namespace patchy::ui {

QRect to_qrect(Rect rect) {
  return QRect(rect.x, rect.y, rect.width, rect.height);
}

Rect to_core_rect(QRect rect) {
  rect = rect.normalized();
  return Rect{rect.x(), rect.y(), rect.width(), rect.height()};
}

QRegion expanded_region(const QRegion& region, int pixels, QRect bounds) {
  if (region.isEmpty() || pixels <= 0) {
    return region.intersected(bounds);
  }

  pixels = std::clamp(pixels, 0, 250);
  // A square dilation is separable: sweeping the horizontal union vertically gives the same
  // result as the full (2r+1)^2 translation grid in 2(2r+1) unions.
  QRegion horizontal;
  for (int dx = -pixels; dx <= pixels; ++dx) {
    horizontal = horizontal.united(region.translated(dx, 0));
  }
  QRegion expanded;
  for (int dy = -pixels; dy <= pixels; ++dy) {
    expanded = expanded.united(horizontal.translated(0, dy));
  }
  return expanded.intersected(bounds);
}

const char* selection_stroke_location_token(SelectionStrokeLocation location) {
  switch (location) {
    case SelectionStrokeLocation::Inside:
      return "inside";
    case SelectionStrokeLocation::Outside:
      return "outside";
    case SelectionStrokeLocation::Center:
      break;
  }
  return "center";
}

SelectionStrokeLocation selection_stroke_location_from_token(const QString& token,
                                                             SelectionStrokeLocation fallback) {
  if (token == QLatin1String("inside")) {
    return SelectionStrokeLocation::Inside;
  }
  if (token == QLatin1String("outside")) {
    return SelectionStrokeLocation::Outside;
  }
  if (token == QLatin1String("center")) {
    return SelectionStrokeLocation::Center;
  }
  return fallback;
}

namespace {

// Selected pixels with at least one 4-neighbor outside the selection (the one-pixel inner rim).
[[nodiscard]] QRegion inner_rim(const QRegion& selection) {
  QRegion rim;
  rim = rim.united(selection.subtracted(selection.translated(1, 0)));
  rim = rim.united(selection.subtracted(selection.translated(-1, 0)));
  rim = rim.united(selection.subtracted(selection.translated(0, 1)));
  rim = rim.united(selection.subtracted(selection.translated(0, -1)));
  return rim;
}

[[nodiscard]] QRegion inside_band(const QRegion& selection, int width, QRect bounds) {
  if (width <= 0) {
    return {};
  }
  // Growing the rim never leaves the selection's own bounding rect, so the rect is a tight
  // clip that keeps the dilation cheap.
  return expanded_region(inner_rim(selection), width - 1, selection.boundingRect())
      .intersected(selection)
      .intersected(bounds);
}

[[nodiscard]] QRegion outside_band(const QRegion& selection, int width, QRect bounds) {
  if (width <= 0) {
    return {};
  }
  return expanded_region(selection, width, bounds).subtracted(selection);
}

}  // namespace

QRegion selection_stroke_region(const QRegion& selection, int width, SelectionStrokeLocation location,
                                QRect bounds) {
  if (selection.isEmpty() || width <= 0) {
    return {};
  }
  width = std::clamp(width, 1, 250);
  switch (location) {
    case SelectionStrokeLocation::Inside:
      return inside_band(selection, width, bounds);
    case SelectionStrokeLocation::Outside:
      return outside_band(selection, width, bounds);
    case SelectionStrokeLocation::Center:
      break;
  }
  const int inside = (width + 1) / 2;
  const int outside = width / 2;
  return inside_band(selection, inside, bounds).united(outside_band(selection, outside, bounds));
}

}  // namespace patchy::ui
