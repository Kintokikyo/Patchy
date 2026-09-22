#pragma once

#include "ui/divide_photos_dialog.hpp"
#include "ui/document_order_list.hpp"
#include "ui/image_sequence_dialog.hpp"

#include <QString>

#include <cstdint>
#include <optional>
#include <vector>

class QWidget;

namespace patchy::ui {

// File > Export Documents to Folder: every checked open document, in list order,
// written as one flattened numbered image each (prefix + zero-padded number +
// extension) into a folder. The document-level sibling of Export Layers as Image
// Sequence, and the half of the PDF round trip that turns tabs back into page files.
// MainWindow resolves the choice into documents and writes them
// (export_document_sessions_to_folder) because only it owns the sessions.

// Persisted as ints in exportDocumentsFolder/existingFiles (permanent identifiers).
enum class ExportDocumentsExistingFiles : int { AddNumbering = 0, Overwrite = 1 };

struct ExportDocumentsFolderChoice {
  std::vector<std::int64_t> session_ids;  // the checked sessions in list order
  QString folder;                         // absolute, already created by the dialog
  QString extension;                      // token, e.g. "png"
  ImageSequenceNaming naming;             // numbered mode only (use_layer_names stays false)
  ExportDocumentsExistingFiles existing_files{ExportDocumentsExistingFiles::AddNumbering};
};

// The format rows are the divide-photos choices (flat-image export formats minus
// SVG and the icon/cursor formats), built by the caller from the format registry.
using ExportDocumentsFormatChoice = DividePhotosFormatChoice;

// `documents` lists every open session; `active_session_id` names the current row.
// `initial_folder` seeds the folder field when nothing is stored yet. The dialog
// reads and writes the exportDocumentsFolder/* settings keys. nullopt when cancelled.
[[nodiscard]] std::optional<ExportDocumentsFolderChoice> run_export_documents_folder_dialog(
    QWidget* parent, const std::vector<DocumentOrderEntry>& documents, std::int64_t active_session_id,
    const std::vector<ExportDocumentsFormatChoice>& formats, const QString& initial_folder);

}  // namespace patchy::ui
