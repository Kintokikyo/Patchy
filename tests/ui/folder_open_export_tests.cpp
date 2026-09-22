// File > Open Folder (every image in a folder as its own tab, also a dropped or
// command-line directory), File > Export Documents to Folder (the checked open
// documents as numbered image files), and the shared document-order list's Auto
// Sort / Reverse buttons (docs/import.md, docs/pdf.md).

#include "core/document.hpp"
#include "ui/app_settings.hpp"
#include "ui/document_order_list.hpp"
#include "ui/export_documents_folder_dialog.hpp"
#include "ui/image_document_io.hpp"
#include "ui/image_sequence_dialog.hpp"
#include "ui/main_window.hpp"

#include "test_harness.hpp"
#include "ui_test_access.hpp"
#include "ui_test_groups.hpp"
#include "ui_test_support.hpp"

#include <QAction>
#include <QApplication>
#include <QColor>
#include <QComboBox>
#include <QDialog>
#include <QDir>
#include <QDragEnterEvent>
#include <QDropEvent>
#include <QFile>
#include <QFileInfo>
#include <QImage>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QMessageBox>
#include <QMimeData>
#include <QProgressDialog>
#include <QPushButton>
#include <QRadioButton>
#include <QSpinBox>
#include <QStatusBar>
#include <QStringList>
#include <QTemporaryDir>
#include <QTimer>
#include <QUrl>

#include <cstdint>
#include <memory>
#include <vector>

using patchy::test::ui::find_top_level_dialog;
using patchy::test::ui::require_action;
using patchy::test::ui::save_widget_artifact;
using patchy::test::ui::SettingsValueRestorer;
using patchy::test::ui::show_window;

namespace {

using patchy::ui::MainWindowTestAccess;

void write_solid_image(const QString& path, int width, int height, QColor color) {
  QImage image(width, height, QImage::Format_RGBA8888);
  image.fill(color);
  CHECK(image.save(path));
}

void write_text_file(const QString& path) {
  QFile file(path);
  CHECK(file.open(QIODevice::WriteOnly));
  file.write("not an image");
}

// page-1.jpg (12x8), page-2.png (8x8), page-10.png (16x8): natural order is
// 1, 2, 10, which a plain string sort gets wrong (10 before 2). Plus a text file
// and a subfolder holding an image, neither of which may open.
void make_page_folder(const QTemporaryDir& temp) {
  write_solid_image(temp.filePath(QStringLiteral("page-10.png")), 16, 8, QColor(255, 0, 0));
  write_solid_image(temp.filePath(QStringLiteral("page-2.png")), 8, 8, QColor(0, 255, 0));
  write_solid_image(temp.filePath(QStringLiteral("page-1.jpg")), 12, 8, QColor(0, 0, 255));
  write_text_file(temp.filePath(QStringLiteral("notes.txt")));
  CHECK(QDir(temp.path()).mkdir(QStringLiteral("nested")));
  write_solid_image(temp.filePath(QStringLiteral("nested/sub.png")), 4, 4, QColor(0, 0, 0));
}

patchy::Document solid_document(int width, int height, QColor color) {
  QImage image(width, height, QImage::Format_RGBA8888);
  image.fill(color);
  return patchy::ui::document_from_qimage(image, "Page");
}

// Restores and seeds every exportDocumentsFolder/* key so the dialog starts from
// its defaults regardless of suite order.
struct ExportDocumentsSettingsGuard {
  SettingsValueRestorer folder{QStringLiteral("exportDocumentsFolder/folder")};
  SettingsValueRestorer prefix{QStringLiteral("exportDocumentsFolder/prefix")};
  SettingsValueRestorer start{QStringLiteral("exportDocumentsFolder/start")};
  SettingsValueRestorer padding{QStringLiteral("exportDocumentsFolder/padding")};
  SettingsValueRestorer format{QStringLiteral("exportDocumentsFolder/format")};
  SettingsValueRestorer existing_files{QStringLiteral("exportDocumentsFolder/existingFiles")};

  ExportDocumentsSettingsGuard() {
    auto settings = patchy::ui::app_settings();
    for (const auto* key : {"exportDocumentsFolder/folder", "exportDocumentsFolder/prefix",
                            "exportDocumentsFolder/start", "exportDocumentsFolder/padding",
                            "exportDocumentsFolder/format", "exportDocumentsFolder/existingFiles"}) {
      settings.remove(QLatin1String(key));
    }
  }
};

void click_message_box_when_shown(const QString& object_name, QMessageBox::StandardButton button,
                                  std::shared_ptr<bool> done, int attempts_left = 300) {
  QTimer::singleShot(10, [object_name, button, done, attempts_left] {
    auto* dialog = find_top_level_dialog(object_name);
    if (dialog == nullptr || !dialog->isVisible()) {
      if (attempts_left > 0 && !*done) {
        click_message_box_when_shown(object_name, button, done, attempts_left - 1);
      }
      return;
    }
    auto* box = qobject_cast<QMessageBox*>(dialog);
    CHECK(box != nullptr);
    if (box != nullptr) {
      *done = true;
      box->button(button)->click();
    }
  });
}

void ui_open_folder_opens_images_in_natural_order() {
  SettingsValueRestorer recent_folders_restorer(QStringLiteral("recentFolders"));
  SettingsValueRestorer last_open_restorer(QStringLiteral("lastOpenDirectory"));
  SettingsValueRestorer recent_files_restorer(QStringLiteral("recentFiles"));
  QTemporaryDir temp;
  CHECK(temp.isValid());
  make_page_folder(temp);

  patchy::ui::MainWindow window;
  show_window(window);
  const auto before = MainWindowTestAccess::session_count(window);
  const auto* first_document = &MainWindowTestAccess::document(window);

  // The progress dialog is up while the loop pumps events, so a zero-delay timer
  // fires inside it.
  bool saw_progress = false;
  QTimer::singleShot(0, [&saw_progress] {
    auto* dialog = qobject_cast<QProgressDialog*>(find_top_level_dialog(QStringLiteral("openFolderProgressDialog")));
    if (dialog == nullptr) {
      return;
    }
    CHECK(dialog->windowTitle().startsWith(QStringLiteral("Opening ")));
    CHECK(dialog->labelText().contains(QStringLiteral(" of 3")));
    saw_progress = true;
  });
  const int opened = MainWindowTestAccess::open_folder_path(window, temp.path());
  QApplication::processEvents();
  CHECK(opened == 3);
  CHECK(saw_progress);
  CHECK(MainWindowTestAccess::session_count(window) == before + 3);
  // Natural order: page-1 (12 wide), page-2 (8), page-10 (16); the subfolder's
  // image and the text file stayed out.
  CHECK(MainWindowTestAccess::session_document(window, before).width() == 12);
  CHECK(MainWindowTestAccess::session_document(window, before + 1).width() == 8);
  CHECK(MainWindowTestAccess::session_document(window, before + 2).width() == 16);
  CHECK(MainWindowTestAccess::session_title(window, before) == QStringLiteral("page-1.jpg"));
  CHECK(MainWindowTestAccess::session_title(window, before + 1) == QStringLiteral("page-2.png"));
  CHECK(MainWindowTestAccess::session_title(window, before + 2) == QStringLiteral("page-10.png"));
  // The first image is the active document (the rest opened in the background),
  // and every session keeps its file path so Save works.
  CHECK(&MainWindowTestAccess::document(window) == &MainWindowTestAccess::session_document(window, before));
  CHECK(&MainWindowTestAccess::document(window) != first_document);
  CHECK(MainWindowTestAccess::active_session_path(window).endsWith(QStringLiteral("page-1.jpg")));
  CHECK(window.statusBar()->currentMessage().contains(QStringLiteral("3")));
  // The folder, not each image, lands in the recent history.
  const auto recent_folders = patchy::ui::recent_history_settings().value(QStringLiteral("recentFolders")).toStringList();
  CHECK(!recent_folders.isEmpty());
  CHECK(!recent_folders.isEmpty() &&
        QFileInfo(recent_folders.front()).absoluteFilePath() == QFileInfo(temp.path()).absoluteFilePath());
}

void ui_open_folder_empty_reports_status() {
  QTemporaryDir temp;
  CHECK(temp.isValid());
  write_text_file(temp.filePath(QStringLiteral("readme.txt")));

  patchy::ui::MainWindow window;
  show_window(window);
  const auto before = MainWindowTestAccess::session_count(window);
  CHECK(MainWindowTestAccess::open_folder_path(window, temp.path()) == 0);
  CHECK(MainWindowTestAccess::session_count(window) == before);
  CHECK(window.statusBar()->currentMessage().contains(QStringLiteral("No supported images")));
  // A path that is not a directory at all is the same no-op.
  CHECK(MainWindowTestAccess::open_folder_path(window, temp.filePath(QStringLiteral("missing"))) == 0);
  CHECK(MainWindowTestAccess::session_count(window) == before);
}

void ui_open_folder_drop_and_cli_expand_directories() {
  SettingsValueRestorer recent_folders_restorer(QStringLiteral("recentFolders"));
  SettingsValueRestorer last_open_restorer(QStringLiteral("lastOpenDirectory"));
  SettingsValueRestorer recent_files_restorer(QStringLiteral("recentFiles"));
  QTemporaryDir temp;
  CHECK(temp.isValid());
  make_page_folder(temp);

  patchy::ui::MainWindow window;
  show_window(window);
  const auto before = MainWindowTestAccess::session_count(window);

  // Command line: a directory argument opens its images.
  window.open_command_line_files({temp.path()});
  QApplication::processEvents();
  CHECK(MainWindowTestAccess::session_count(window) == before + 3);

  // Drag and drop: a folder URL is accepted by the drag check and opens on the
  // deferred drop, same as a multi-file drop.
  {
    QMimeData mime;
    mime.setUrls({QUrl::fromLocalFile(temp.path())});
    QDragEnterEvent enter(QPoint(50, 50), Qt::CopyAction | Qt::MoveAction, &mime, Qt::LeftButton, Qt::NoModifier);
    CHECK(MainWindowTestAccess::accept_open_file_drag(window, &enter));
    QDropEvent drop(QPointF(50, 50), Qt::CopyAction | Qt::MoveAction, &mime, Qt::LeftButton, Qt::NoModifier);
    CHECK(MainWindowTestAccess::open_dropped_files(window, &drop));
    CHECK(drop.isAccepted());
  }
  CHECK(MainWindowTestAccess::session_count(window) == before + 3);  // nothing until the drop returns
  QApplication::processEvents();
  CHECK(MainWindowTestAccess::session_count(window) == before + 6);
  CHECK(MainWindowTestAccess::session_title(window, before + 3) == QStringLiteral("page-1.jpg"));
  CHECK(MainWindowTestAccess::session_title(window, before + 5) == QStringLiteral("page-10.png"));
  // A folder with nothing to open is still refused at the drag stage only when it
  // is not a directory; an empty directory reports through the status bar on drop.
  {
    QTemporaryDir empty;
    CHECK(empty.isValid());
    QMimeData mime;
    mime.setUrls({QUrl::fromLocalFile(empty.path())});
    QDropEvent drop(QPointF(50, 50), Qt::CopyAction, &mime, Qt::LeftButton, Qt::NoModifier);
    CHECK(MainWindowTestAccess::open_dropped_files(window, &drop));
    QApplication::processEvents();
    CHECK(MainWindowTestAccess::session_count(window) == before + 6);
    CHECK(window.statusBar()->currentMessage().contains(QStringLiteral("No supported images")));
  }
}

void ui_folder_actions_and_commands_registered() {
  patchy::ui::MainWindow window;
  show_window(window);
  auto* open_folder = window.findChild<QAction*>(QStringLiteral("fileOpenFolderAction"));
  CHECK(open_folder != nullptr);
  const auto* open_command = window.hotkey_registry().find_command(QStringLiteral("file.open_folder"));
  CHECK(open_command != nullptr);
  CHECK(open_command != nullptr && open_command->action == open_folder);
  auto* export_folder = window.findChild<QAction*>(QStringLiteral("fileExportDocumentsToFolderAction"));
  CHECK(export_folder != nullptr);
  const auto* export_command =
      window.hotkey_registry().find_command(QStringLiteral("file.export_documents_to_folder"));
  CHECK(export_command != nullptr);
  CHECK(export_command != nullptr && export_command->action == export_folder);
}

void ui_export_documents_to_folder_writes_numbered_files() {
  SettingsValueRestorer last_save_restorer(QStringLiteral("lastSaveDirectory"));
  QTemporaryDir temp;
  CHECK(temp.isValid());
  patchy::ui::MainWindow window;
  show_window(window);
  const auto base = MainWindowTestAccess::session_count(window);
  window.add_document_session(solid_document(10, 6, QColor(255, 0, 0)), QStringLiteral("Red"));
  window.add_document_session(solid_document(12, 6, QColor(0, 255, 0)), QStringLiteral("Green"));
  window.add_document_session(solid_document(14, 6, QColor(0, 0, 255)), QStringLiteral("Blue"));
  const auto* active_before = &MainWindowTestAccess::document(window);
  const std::vector<std::int64_t> ids = {MainWindowTestAccess::session_id(window, base),
                                         MainWindowTestAccess::session_id(window, base + 1),
                                         MainWindowTestAccess::session_id(window, base + 2)};
  patchy::ui::ImageSequenceNaming naming;
  naming.prefix = QStringLiteral("doc_");
  naming.start = 1;
  naming.padding = 3;

  const auto written = MainWindowTestAccess::export_document_sessions_to_folder(
      window, ids, temp.path(), QStringLiteral("png"), naming,
      patchy::ui::ExportDocumentsExistingFiles::AddNumbering);
  CHECK(written.has_value());
  CHECK(written.has_value() && written->size() == 3);
  const QStringList expected_names = {QStringLiteral("doc_001.png"), QStringLiteral("doc_002.png"),
                                      QStringLiteral("doc_003.png")};
  const std::vector<QColor> expected_colors = {QColor(255, 0, 0), QColor(0, 255, 0), QColor(0, 0, 255)};
  for (int index = 0; index < 3; ++index) {
    CHECK(QFileInfo(written->at(index)).fileName() == expected_names[index]);
    const QImage image(written->at(index));
    CHECK(image.width() == 10 + index * 2);
    CHECK(image.height() == 6);
    CHECK(image.pixelColor(image.width() / 2, 3) == expected_colors[static_cast<std::size_t>(index)]);
  }
  // Nothing about the sessions changed: same active document, none marked saved.
  CHECK(&MainWindowTestAccess::document(window) == active_before);
  CHECK(MainWindowTestAccess::active_session_path(window).isEmpty());

  // Add mode continues after the files already there.
  const auto added = MainWindowTestAccess::export_document_sessions_to_folder(
      window, ids, temp.path(), QStringLiteral("png"), naming,
      patchy::ui::ExportDocumentsExistingFiles::AddNumbering);
  CHECK(added.has_value());
  CHECK(added.has_value() && QFileInfo(added->front()).fileName() == QStringLiteral("doc_004.png"));
  CHECK(added.has_value() && QFileInfo(added->back()).fileName() == QStringLiteral("doc_006.png"));

  // Overwrite mode asks once, naming the first colliding file; Yes rewrites from 001.
  {
    QFile placeholder(temp.filePath(QStringLiteral("doc_001.png")));
    CHECK(placeholder.open(QIODevice::WriteOnly));
    placeholder.write("placeholder");
  }
  auto declined = std::make_shared<bool>(false);
  QString prompt_text;
  QTimer::singleShot(0, [declined, &prompt_text] {
    auto* box = qobject_cast<QMessageBox*>(find_top_level_dialog(QStringLiteral("exportDocumentsOverwriteMessageBox")));
    CHECK(box != nullptr);
    if (box != nullptr) {
      prompt_text = box->text();
      *declined = true;
      box->button(QMessageBox::No)->click();
    }
  });
  const auto refused = MainWindowTestAccess::export_document_sessions_to_folder(
      window, ids, temp.path(), QStringLiteral("png"), naming, patchy::ui::ExportDocumentsExistingFiles::Overwrite);
  CHECK(*declined);
  CHECK(prompt_text.contains(QStringLiteral("doc_001.png")));
  CHECK(!refused.has_value());
  {
    QFile placeholder(temp.filePath(QStringLiteral("doc_001.png")));
    CHECK(placeholder.open(QIODevice::ReadOnly));
    CHECK(placeholder.readAll() == QByteArray("placeholder"));
  }
  auto accepted = std::make_shared<bool>(false);
  click_message_box_when_shown(QStringLiteral("exportDocumentsOverwriteMessageBox"), QMessageBox::Yes, accepted);
  const auto overwritten = MainWindowTestAccess::export_document_sessions_to_folder(
      window, ids, temp.path(), QStringLiteral("png"), naming, patchy::ui::ExportDocumentsExistingFiles::Overwrite);
  CHECK(*accepted);
  CHECK(overwritten.has_value());
  CHECK(overwritten.has_value() && QFileInfo(overwritten->front()).fileName() == QStringLiteral("doc_001.png"));
  const QImage rewritten(temp.filePath(QStringLiteral("doc_001.png")));
  CHECK(rewritten.width() == 10);
  CHECK(QDir(temp.path()).entryList(QDir::Files).size() == 6);
}

void ui_export_documents_dialog_round_trip() {
  ExportDocumentsSettingsGuard settings_guard;
  SettingsValueRestorer last_save_restorer(QStringLiteral("lastSaveDirectory"));
  QTemporaryDir temp;
  CHECK(temp.isValid());
  patchy::ui::MainWindow window;
  show_window(window);
  const auto base = MainWindowTestAccess::session_count(window);
  window.add_document_session(solid_document(10, 6, QColor(255, 0, 0)), QStringLiteral("Red"));
  window.add_document_session(solid_document(12, 6, QColor(0, 255, 0)), QStringLiteral("Green"));
  window.add_document_session(solid_document(14, 6, QColor(0, 0, 255)), QStringLiteral("Blue"));
  CHECK(MainWindowTestAccess::session_count(window) == base + 3);

  bool saw_dialog = false;
  QTimer::singleShot(0, [&saw_dialog, &temp, base] {
    auto* dialog = find_top_level_dialog(QStringLiteral("exportDocumentsFolderDialog"));
    CHECK(dialog != nullptr);
    if (dialog == nullptr) {
      return;
    }
    auto* list = dialog->findChild<QListWidget*>(QStringLiteral("exportDocumentsDocumentsList"));
    auto* folder_edit = dialog->findChild<QLineEdit*>(QStringLiteral("exportDocumentsFolderEdit"));
    auto* prefix_edit = dialog->findChild<QLineEdit*>(QStringLiteral("exportDocumentsPrefixEdit"));
    auto* start_spin = dialog->findChild<QSpinBox*>(QStringLiteral("exportDocumentsStartSpin"));
    auto* padding_spin = dialog->findChild<QSpinBox*>(QStringLiteral("exportDocumentsPaddingSpin"));
    auto* format_combo = dialog->findChild<QComboBox*>(QStringLiteral("exportDocumentsFormatCombo"));
    auto* preview = dialog->findChild<QLabel*>(QStringLiteral("exportDocumentsPreviewLabel"));
    auto* summary = dialog->findChild<QLabel*>(QStringLiteral("exportDocumentsSummaryLabel"));
    auto* reverse = dialog->findChild<QPushButton*>(QStringLiteral("exportDocumentsReverseButton"));
    auto* export_button = dialog->findChild<QPushButton*>(QStringLiteral("exportDocumentsExportButton"));
    CHECK(list != nullptr && folder_edit != nullptr && prefix_edit != nullptr && start_spin != nullptr &&
          padding_spin != nullptr && format_combo != nullptr && preview != nullptr && summary != nullptr &&
          reverse != nullptr && export_button != nullptr);
    if (list == nullptr || folder_edit == nullptr || prefix_edit == nullptr || start_spin == nullptr ||
        padding_spin == nullptr || format_combo == nullptr || preview == nullptr || summary == nullptr ||
        reverse == nullptr || export_button == nullptr) {
      dialog->reject();
      return;
    }
    CHECK(static_cast<std::size_t>(list->count()) == base + 3);
    // The current row is the active document (the last one added).
    CHECK(list->currentRow() == list->count() - 1);
    // Defaults: page_001.png onward, Export gated on a folder.
    CHECK(prefix_edit->text() == QStringLiteral("page_"));
    CHECK(start_spin->value() == 1);
    CHECK(padding_spin->value() == 3);
    CHECK(format_combo->currentData().toString() == QStringLiteral("png"));
    CHECK(preview->text().contains(QStringLiteral("page_001.png")));
    folder_edit->setText(QDir::toNativeSeparators(temp.path()));
    CHECK(export_button->isEnabled());
    // Drop the startup document, reverse the rest, and rename the set.
    list->item(0)->setCheckState(Qt::Unchecked);
    reverse->click();
    CHECK(list->item(0)->text() == QStringLiteral("Blue"));
    CHECK(list->item(list->count() - 1)->checkState() == Qt::Unchecked);
    prefix_edit->setText(QStringLiteral("pg"));
    start_spin->setValue(7);
    padding_spin->setValue(2);
    CHECK(preview->text().contains(QStringLiteral("pg07.png")));
    CHECK(preview->text().contains(QStringLiteral("pg09.png")));
    CHECK(summary->text().contains(QStringLiteral("3")));
    save_widget_artifact("ui_export_documents_folder_dialog", *dialog);
    saw_dialog = true;
    export_button->click();
  });
  require_action(window, "fileExportDocumentsToFolderAction")->trigger();
  CHECK(saw_dialog);
  // Reversed order: Blue, Green, Red as pg07, pg08, pg09.
  const auto names = QDir(temp.path()).entryList(QDir::Files, QDir::Name);
  CHECK(names == QStringList({QStringLiteral("pg07.png"), QStringLiteral("pg08.png"), QStringLiteral("pg09.png")}));
  CHECK(QImage(temp.filePath(QStringLiteral("pg07.png"))).width() == 14);
  CHECK(QImage(temp.filePath(QStringLiteral("pg09.png"))).width() == 10);
  CHECK(window.statusBar()->currentMessage().contains(QStringLiteral("Exported 3 images")));
  // The choices persisted for next time.
  auto settings = patchy::ui::app_settings();
  CHECK(settings.value(QStringLiteral("exportDocumentsFolder/prefix")).toString() == QStringLiteral("pg"));
  CHECK(settings.value(QStringLiteral("exportDocumentsFolder/start")).toInt() == 7);
  CHECK(settings.value(QStringLiteral("exportDocumentsFolder/padding")).toInt() == 2);
  CHECK(settings.value(QStringLiteral("exportDocumentsFolder/format")).toString() == QStringLiteral("png"));
  CHECK(QFileInfo(settings.value(QStringLiteral("exportDocumentsFolder/folder")).toString()).absoluteFilePath() ==
        QFileInfo(temp.path()).absoluteFilePath());
}

// The shared list's Auto Sort (numbering-aware, stable, case-insensitive) and
// Reverse, driven through the Export Multi-Page PDF dialog that hosts it.
void ui_document_order_auto_sort_and_reverse() {
  // Widget-free: the collation rule itself.
  const auto sorted = patchy::ui::natural_sorted_titles(
      {QStringLiteral("scan.pdf - Page 10"), QStringLiteral("b2.png"), QStringLiteral("scan.pdf - Page 2"),
       QStringLiteral("B10.png"), QStringLiteral("scan.pdf - Page 1"), QStringLiteral("b2.PNG")});
  CHECK(sorted == QStringList({QStringLiteral("b2.png"), QStringLiteral("b2.PNG"), QStringLiteral("B10.png"),
                               QStringLiteral("scan.pdf - Page 1"), QStringLiteral("scan.pdf - Page 2"),
                               QStringLiteral("scan.pdf - Page 10")}));

  patchy::ui::app_settings().setValue(QStringLiteral("exportOptions/multiPagePdfSource"), QStringLiteral("documents"));
  patchy::ui::MainWindow window;
  show_window(window);
  const auto base = MainWindowTestAccess::session_count(window);
  window.add_document_session(solid_document(8, 8, QColor(255, 0, 0)), QStringLiteral("scan.pdf - Page 10"));
  window.add_document_session(solid_document(8, 8, QColor(0, 255, 0)), QStringLiteral("scan.pdf - Page 2"));
  window.add_document_session(solid_document(8, 8, QColor(0, 0, 255)), QStringLiteral("scan.pdf - Page 1"));
  CHECK(MainWindowTestAccess::session_count(window) == base + 3);

  bool seen = false;
  QTimer::singleShot(0, [&seen, base] {
    auto* dialog = find_top_level_dialog(QStringLiteral("multiPagePdfExportDialog"));
    CHECK(dialog != nullptr);
    if (dialog == nullptr) {
      return;
    }
    auto* list = dialog->findChild<QListWidget*>(QStringLiteral("multiPagePdfDocumentsList"));
    auto* auto_sort = dialog->findChild<QPushButton*>(QStringLiteral("multiPagePdfAutoSortButton"));
    auto* reverse = dialog->findChild<QPushButton*>(QStringLiteral("multiPagePdfReverseButton"));
    auto* move_up = dialog->findChild<QPushButton*>(QStringLiteral("multiPagePdfMoveUpButton"));
    auto* summary = dialog->findChild<QLabel*>(QStringLiteral("multiPagePdfSummaryLabel"));
    CHECK(list != nullptr && auto_sort != nullptr && reverse != nullptr && move_up != nullptr && summary != nullptr);
    if (list == nullptr || auto_sort == nullptr || reverse == nullptr || move_up == nullptr || summary == nullptr) {
      dialog->reject();
      return;
    }
    const auto row_of = [list](const QString& title) {
      for (int row = 0; row < list->count(); ++row) {
        if (list->item(row)->text() == title) {
          return row;
        }
      }
      return -1;
    };
    const QString page1 = QStringLiteral("scan.pdf - Page 1");
    const QString page2 = QStringLiteral("scan.pdf - Page 2");
    const QString page10 = QStringLiteral("scan.pdf - Page 10");
    // Creation order: the startup document, then Page 10, Page 2, Page 1.
    CHECK(row_of(page10) == static_cast<int>(base));
    CHECK(row_of(page1) == static_cast<int>(base) + 2);
    // The unchecked state and the current row ride along with their rows.
    list->item(row_of(page2))->setCheckState(Qt::Unchecked);
    list->setCurrentRow(row_of(page10));
    CHECK(auto_sort->isEnabled());
    auto_sort->click();
    CHECK(static_cast<std::size_t>(list->count()) == base + 3);
    // Numbering-aware: 1, 2, 10 (a plain string sort would put 10 before 2). The
    // startup document sorts by its own title wherever that lands.
    CHECK(row_of(page1) < row_of(page2));
    CHECK(row_of(page2) < row_of(page10));
    CHECK(list->item(row_of(page2))->checkState() == Qt::Unchecked);
    CHECK(list->item(row_of(page10))->checkState() == Qt::Checked);
    CHECK(list->currentItem() != nullptr && list->currentItem()->text() == page10);
    CHECK(summary->text().contains(QString::number(list->count() - 1)));
    reverse->click();
    CHECK(row_of(page10) < row_of(page2));
    CHECK(row_of(page2) < row_of(page1));
    CHECK(list->item(row_of(page2))->checkState() == Qt::Unchecked);
    CHECK(list->currentItem() != nullptr && list->currentItem()->text() == page10);
    // Move Up is off on the first row and comes back below it.
    list->setCurrentRow(0);
    CHECK(!move_up->isEnabled());
    list->setCurrentRow(1);
    CHECK(move_up->isEnabled());
    seen = true;
    dialog->reject();
  });
  require_action(window, "fileExportMultiPagePdfAction")->trigger();
  CHECK(seen);
}

}  // namespace

std::vector<patchy::test::TestCase> folder_open_export_tests() {
  return {
      {"ui_open_folder_opens_images_in_natural_order", ui_open_folder_opens_images_in_natural_order},
      {"ui_open_folder_empty_reports_status", ui_open_folder_empty_reports_status},
      {"ui_open_folder_drop_and_cli_expand_directories", ui_open_folder_drop_and_cli_expand_directories},
      {"ui_folder_actions_and_commands_registered", ui_folder_actions_and_commands_registered},
      {"ui_export_documents_to_folder_writes_numbered_files", ui_export_documents_to_folder_writes_numbered_files},
      {"ui_export_documents_dialog_round_trip", ui_export_documents_dialog_round_trip},
      {"ui_document_order_auto_sort_and_reverse", ui_document_order_auto_sort_and_reverse},
  };
}
