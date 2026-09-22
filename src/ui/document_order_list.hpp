#pragma once

#include <QCollator>
#include <QString>
#include <QStringList>

#include <cstdint>
#include <vector>

class QListWidget;
class QPushButton;
class QWidget;

namespace patchy::ui {

// The ordered, checkable list of open documents that the Export Multi-Page PDF and
// Export Documents to Folder dialogs share: one row per session, a check box per
// row, and Move Up / Move Down / Auto Sort / Reverse beside it. The dialogs own the
// semantics (what the checked order means); this owns the widgets and the sort.

// One open document as the list shows it.
struct DocumentOrderEntry {
  QString title;
  std::int64_t session_id{0};
};

struct DocumentOrderControls {
  QWidget* row{nullptr};  // list plus the button column, ready to add to a layout
  QListWidget* list{nullptr};
  QPushButton* move_up{nullptr};
  QPushButton* move_down{nullptr};
  QPushButton* auto_sort{nullptr};
  QPushButton* reverse{nullptr};
};

// Numeric-aware, case-insensitive: "Page 2" sorts before "Page 10". Shared with the
// image-sequence file ordering (sorted_sequence_paths).
[[nodiscard]] QCollator natural_name_collator();

// Stable natural sort of titles (ties keep their input order).
[[nodiscard]] QStringList natural_sorted_titles(QStringList titles);

// Builds the control. Object names are `object_prefix` + "DocumentsList",
// "MoveUpButton", "MoveDownButton", "AutoSortButton", "ReverseButton". Every row
// starts checked with its session id in Qt::UserRole; the active session's row is
// the current item. The Move buttons act on the current row; Auto Sort and Reverse
// reorder every row (check states travel with the rows). Each click re-syncs the
// buttons' enabled state; a dialog that shows a summary connects its own refresh
// to the buttons' `clicked` as well.
[[nodiscard]] DocumentOrderControls build_document_order_controls(QWidget* parent, const QString& object_prefix,
                                                                  const std::vector<DocumentOrderEntry>& entries,
                                                                  std::int64_t active_session_id);

// Session ids of the checked rows, top to bottom.
[[nodiscard]] std::vector<std::int64_t> checked_session_ids(const QListWidget& list);

// Enables the list and buttons as a unit (false grays everything); with true, the
// Move buttons follow the current row (no Move Up on the first row, and so on).
void sync_document_order_controls(const DocumentOrderControls& controls, bool enabled);

// The reorder operations, exposed for tests: rows move as whole items so check
// states and user data travel with them.
void move_current_list_item(QListWidget& list, int delta);
void sort_list_items_naturally(QListWidget& list);
void reverse_list_items(QListWidget& list);

}  // namespace patchy::ui
