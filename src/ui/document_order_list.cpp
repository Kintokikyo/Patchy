#include "ui/document_order_list.hpp"

#include <QAbstractItemView>
#include <QHBoxLayout>
#include <QListWidget>
#include <QListWidgetItem>
#include <QObject>
#include <QPushButton>
#include <QVBoxLayout>
#include <QVariant>
#include <QWidget>

#include <algorithm>
#include <cstddef>
#include <numeric>

namespace patchy::ui {

QCollator natural_name_collator() {
  QCollator collator;
  collator.setNumericMode(true);
  collator.setCaseSensitivity(Qt::CaseInsensitive);
  return collator;
}

QStringList natural_sorted_titles(QStringList titles) {
  const auto collator = natural_name_collator();
  std::stable_sort(titles.begin(), titles.end(),
                   [&collator](const QString& a, const QString& b) { return collator.compare(a, b) < 0; });
  return titles;
}

void move_current_list_item(QListWidget& list, int delta) {
  const int row = list.currentRow();
  const int target = row + delta;
  if (row < 0 || target < 0 || target >= list.count()) {
    return;
  }
  auto* item = list.takeItem(row);
  list.insertItem(target, item);
  list.setCurrentItem(item);
}

namespace {

// Re-inserts every item in `order` (indices into the current rows). takeItem and
// addItem keep each QListWidgetItem intact, so its check state and user data travel
// with it; the current item is restored by identity.
void reorder_list_items(QListWidget& list, const std::vector<int>& order) {
  auto* current = list.currentItem();
  std::vector<QListWidgetItem*> items;
  items.reserve(order.size());
  for (const int index : order) {
    items.push_back(list.item(index));
  }
  while (list.count() > 0) {
    list.takeItem(list.count() - 1);
  }
  for (auto* item : items) {
    list.addItem(item);
  }
  if (current != nullptr) {
    list.setCurrentItem(current);
  }
}

}  // namespace

void sort_list_items_naturally(QListWidget& list) {
  const auto collator = natural_name_collator();
  std::vector<int> order(static_cast<std::size_t>(list.count()));
  std::iota(order.begin(), order.end(), 0);
  std::stable_sort(order.begin(), order.end(), [&list, &collator](int a, int b) {
    return collator.compare(list.item(a)->text(), list.item(b)->text()) < 0;
  });
  reorder_list_items(list, order);
}

void reverse_list_items(QListWidget& list) {
  std::vector<int> order(static_cast<std::size_t>(list.count()));
  std::iota(order.rbegin(), order.rend(), 0);
  reorder_list_items(list, order);
}

std::vector<std::int64_t> checked_session_ids(const QListWidget& list) {
  std::vector<std::int64_t> ids;
  for (int row = 0; row < list.count(); ++row) {
    const auto* item = list.item(row);
    if (item->checkState() == Qt::Checked) {
      ids.push_back(static_cast<std::int64_t>(item->data(Qt::UserRole).toLongLong()));
    }
  }
  return ids;
}

void sync_document_order_controls(const DocumentOrderControls& controls, bool enabled) {
  const int row = controls.list->currentRow();
  const int count = controls.list->count();
  controls.list->setEnabled(enabled);
  controls.move_up->setEnabled(enabled && row > 0);
  controls.move_down->setEnabled(enabled && row >= 0 && row + 1 < count);
  controls.auto_sort->setEnabled(enabled && count > 1);
  controls.reverse->setEnabled(enabled && count > 1);
}

DocumentOrderControls build_document_order_controls(QWidget* parent, const QString& object_prefix,
                                                    const std::vector<DocumentOrderEntry>& entries,
                                                    std::int64_t active_session_id) {
  DocumentOrderControls controls;
  controls.row = new QWidget(parent);
  auto* row_layout = new QHBoxLayout(controls.row);
  row_layout->setContentsMargins(0, 0, 0, 0);

  controls.list = new QListWidget(controls.row);
  controls.list->setObjectName(object_prefix + QStringLiteral("DocumentsList"));
  controls.list->setSelectionMode(QAbstractItemView::SingleSelection);
  for (const auto& entry : entries) {
    auto* item = new QListWidgetItem(entry.title, controls.list);
    item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
    item->setCheckState(Qt::Checked);
    item->setData(Qt::UserRole, QVariant::fromValue(static_cast<qlonglong>(entry.session_id)));
    if (entry.session_id == active_session_id) {
      controls.list->setCurrentItem(item);
    }
  }
  row_layout->addWidget(controls.list, 1);

  auto* buttons = new QVBoxLayout();
  controls.move_up = new QPushButton(QObject::tr("Move Up"), controls.row);
  controls.move_up->setObjectName(object_prefix + QStringLiteral("MoveUpButton"));
  controls.move_down = new QPushButton(QObject::tr("Move Down"), controls.row);
  controls.move_down->setObjectName(object_prefix + QStringLiteral("MoveDownButton"));
  controls.auto_sort = new QPushButton(QObject::tr("Auto Sort"), controls.row);
  controls.auto_sort->setObjectName(object_prefix + QStringLiteral("AutoSortButton"));
  controls.auto_sort->setToolTip(QObject::tr("Order the documents by name, numbering-aware (2 before 10)."));
  controls.reverse = new QPushButton(QObject::tr("Reverse"), controls.row);
  controls.reverse->setObjectName(object_prefix + QStringLiteral("ReverseButton"));
  controls.reverse->setToolTip(QObject::tr("Reverse the current order."));
  buttons->addWidget(controls.move_up);
  buttons->addWidget(controls.move_down);
  buttons->addSpacing(8);
  buttons->addWidget(controls.auto_sort);
  buttons->addWidget(controls.reverse);
  buttons->addStretch(1);
  row_layout->addLayout(buttons);

  auto* list = controls.list;
  const auto resync = [controls] { sync_document_order_controls(controls, controls.list->isEnabled()); };
  QObject::connect(controls.move_up, &QPushButton::clicked, controls.row, [list, resync] {
    move_current_list_item(*list, -1);
    resync();
  });
  QObject::connect(controls.move_down, &QPushButton::clicked, controls.row, [list, resync] {
    move_current_list_item(*list, +1);
    resync();
  });
  QObject::connect(controls.auto_sort, &QPushButton::clicked, controls.row, [list, resync] {
    sort_list_items_naturally(*list);
    resync();
  });
  QObject::connect(controls.reverse, &QPushButton::clicked, controls.row, [list, resync] {
    reverse_list_items(*list);
    resync();
  });
  QObject::connect(list, &QListWidget::currentRowChanged, controls.row, [resync](int) { resync(); });
  sync_document_order_controls(controls, true);
  return controls;
}

}  // namespace patchy::ui
