#include "ui/ui_font.hpp"

namespace patchy::ui {

std::vector<UiFontCandidate> windows_ui_font_candidates() {
  return {
      {QStringLiteral("Arial"),
       {QStringLiteral("C:/Windows/Fonts/arial.ttf"), QStringLiteral("C:/Windows/Fonts/arialbd.ttf"),
        QStringLiteral("C:/Windows/Fonts/ariali.ttf"), QStringLiteral("C:/Windows/Fonts/arialbi.ttf")}},
      {QStringLiteral("Segoe UI"),
       {QStringLiteral("C:/Windows/Fonts/segoeui.ttf"), QStringLiteral("C:/Windows/Fonts/segoeuib.ttf"),
        QStringLiteral("C:/Windows/Fonts/segoeuii.ttf"), QStringLiteral("C:/Windows/Fonts/segoeuiz.ttf")}},
      {QStringLiteral("Calibri"),
       {QStringLiteral("C:/Windows/Fonts/calibri.ttf"), QStringLiteral("C:/Windows/Fonts/calibrib.ttf"),
        QStringLiteral("C:/Windows/Fonts/calibrii.ttf"), QStringLiteral("C:/Windows/Fonts/calibriz.ttf")}},
  };
}

QString installed_ui_font_family(const QStringList& installed_families) {
  for (const auto& candidate : windows_ui_font_candidates()) {
    if (installed_families.contains(candidate.family, Qt::CaseInsensitive)) {
      return candidate.family;
    }
  }
  return {};
}

QStringList wasm_cjk_fallback_families(const QString& language_code) {
  static const QString jp = QStringLiteral("Noto Sans JP");
  static const QString sc = QStringLiteral("Noto Sans SC");
  static const QString tc = QStringLiteral("Noto Sans TC");
  if (language_code.startsWith(QStringLiteral("zh_TW")) || language_code.startsWith(QStringLiteral("zh_Hant"))) {
    return {tc, sc, jp};
  }
  if (language_code.startsWith(QStringLiteral("zh"))) {
    return {sc, tc, jp};
  }
  // Japanese and every Latin-script language: Japanese shapes first, then the Chinese
  // families so a pasted or imported Chinese string still resolves instead of tofu.
  return {jp, sc, tc};
}

QStringList ui_font_files_to_register(const QStringList& installed_families) {
  if (!installed_ui_font_family(installed_families).isEmpty()) {
    return {};  // the family is installed: registering its file would break PDF embedding
  }
  return windows_ui_font_candidates().front().files;
}

}  // namespace patchy::ui
