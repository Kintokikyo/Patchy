#pragma once

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

// A streaming PDF writer for pages that are one image each. Qt-free.
//
// Qt's PDF engine can only write an image as Flate RGB or as JPEG at a fixed quality,
// never as grayscale JPEG, and it cannot carry an already-encoded stream at all. This
// writer takes image streams that are ALREADY encoded (the caller picks the codec, or
// hands over the bytes an imported PDF page carried) and lays each one over its whole
// page, so a scanned multi-page document can be written at the size it came in at.
//
// Pages are written to disk as they arrive; only the object offsets are kept, so a
// file of any page count costs one page of memory. Output is deterministic: no dates,
// no ids, numbers formatted without the C locale.

namespace patchy::pdf {

// One image XObject's stream and the dictionary entries that describe it.
struct ImageStream {
  // The encoded bytes exactly as they go between `stream` and `endstream`.
  std::vector<std::uint8_t> bytes;
  // Filter name without the slash: "DCTDecode", "FlateDecode", "JPXDecode". Empty
  // means the bytes are raw samples.
  std::string filter;
  // Verbatim PDF text for the optional entries, empty to omit each:
  // "<< /Predictor 15 /Columns 100 >>", "/DeviceGray" or "[/ICCBased ...]", "[1 0]".
  std::string decode_parms;
  std::string color_space;  // may be empty only for JPXDecode, which carries its own
  std::string decode;
  int bits_per_component{8};
  int width{0};
  int height{0};
};

struct ImagePage {
  // Page size in points (1/72 inch). The image is drawn over the whole page.
  double width_points{0.0};
  double height_points{0.0};
  ImageStream image;
  // An 8-bit DeviceGray alpha channel the size of the image, for pages with
  // transparency. Its color_space and bits_per_component are forced.
  std::optional<ImageStream> soft_mask;
};

class ImageWriter {
public:
  // Opens `path` for writing and emits the header. Throws std::runtime_error when the
  // file cannot be created.
  explicit ImageWriter(const std::filesystem::path& path);
  // An unfinished file (finish() never ran, or abort() did) is removed.
  ~ImageWriter();
  ImageWriter(const ImageWriter&) = delete;
  ImageWriter& operator=(const ImageWriter&) = delete;

  // Throws std::runtime_error on a bad page, a write failure, or a file that has
  // outgrown the classic cross-reference table's ten-digit offsets.
  void add_page(const ImagePage& page);
  // Writes the page tree, catalog, cross-reference table, and trailer. At least one
  // page must have been added.
  void finish();
  // Closes and removes the file. Safe to call more than once.
  void abort() noexcept;

  [[nodiscard]] int page_count() const noexcept { return static_cast<int>(page_objects_.size()); }

private:
  std::uint32_t begin_object();
  void begin_reserved_object(std::uint32_t number);
  void write_image_object(const ImageStream& image, std::uint32_t soft_mask_object, bool is_soft_mask);
  void write(std::string_view text);
  void write(const std::vector<std::uint8_t>& bytes);

  std::filesystem::path path_;
  std::ofstream file_;
  std::uint64_t position_{0};
  // Byte offset of object N at index N; index 0 is the free-list head.
  std::vector<std::uint64_t> offsets_;
  std::vector<std::uint32_t> page_objects_;
  bool finished_{false};
  bool open_{false};
};

// A PDF number: up to four decimals, no exponent, no trailing zeros, and a '.' decimal
// point whatever the process locale says.
[[nodiscard]] std::string format_pdf_number(double value);

}  // namespace patchy::pdf
