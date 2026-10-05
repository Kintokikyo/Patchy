"""Minimal PSD/PSB section walker for Testy's trap-file generator.

Only structural navigation lives here: enough parsing to find where the four
header sections end and the merged-composite image-data section begins, so
staging.py can rewrite that trailing section with a sentinel fill while leaving
every layer byte untouched. This is deliberately not a PSD reader; Patchy's C++
codec remains the only real parser in the project.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass


class PsdParseError(Exception):
    pass


@dataclass
class PsdLayout:
    is_psb: bool
    channels: int
    height: int
    width: int
    depth: int  # bits per channel: 1, 8, 16, 32
    color_mode: int
    image_data_offset: int  # file offset of the compression u16 that starts the image-data section
    # Layer records in the file: 0 for a flattened document (the merged composite is
    # its only pixel data), None when the inner layer-info structure was unreadable.
    layer_count: int | None


# Additional-layer-info keys whose length field is 8 bytes in PSB files.
_PSB_LONG_KEYS = {b"LMsk", b"Lr16", b"Lr32", b"Layr", b"Mt16", b"Mt32", b"Mtrn",
                  b"Alph", b"FMsk", b"lnk2", b"FEid", b"FXid", b"PxSD"}
# Blocks that hold the real layer records when the standard layer info is empty.
_LAYER_RECORD_KEYS = (b"Lr16", b"Lr32", b"Layr")


def _read_layer_count(data: bytes, start: int, section_length: int, is_psb: bool) -> int | None:
    """Layer-record count inside the layer-and-mask section starting at `start`.

    0 means a genuinely flattened file. 16/32-bit documents leave the standard
    layer info empty and keep their layer records in an additional-info block
    (Lr16/Lr32), so an empty standard block alone proves nothing; the additional
    blocks are walked before concluding 0. None = structure not understood.
    """
    if section_length == 0:
        return 0
    end = min(start + section_length, len(data))
    length_size = 8 if is_psb else 4
    if section_length < length_size or start + length_size > len(data):
        return None
    info_length = struct.unpack_from(">Q" if is_psb else ">I", data, start)[0]
    if info_length >= 2:
        if start + length_size + 2 > len(data):
            return None
        # Negative means the first alpha channel holds merged transparency.
        return abs(struct.unpack_from(">h", data, start + length_size)[0])
    if info_length != 0:
        return None

    # Empty standard block: walk global mask info, then the additional blocks.
    offset = start + length_size
    if offset + 4 > end:
        return 0  # nothing after the empty block: flattened
    offset += 4 + struct.unpack_from(">I", data, offset)[0]
    while offset + 12 <= end:
        if data[offset:offset + 4] not in (b"8BIM", b"8B64"):
            return None  # lost the structure; claim nothing
        key = data[offset + 4:offset + 8]
        if is_psb and key in _PSB_LONG_KEYS:
            if offset + 16 > end:
                return None
            block_length = struct.unpack_from(">Q", data, offset + 8)[0]
            data_start = offset + 16
        else:
            block_length = struct.unpack_from(">I", data, offset + 8)[0]
            data_start = offset + 12
        if key in _LAYER_RECORD_KEYS:
            if block_length >= 2 and data_start + 2 <= len(data):
                return abs(struct.unpack_from(">h", data, data_start)[0])
            return None
        pad = 4 if is_psb else 2
        offset = data_start + block_length + (-block_length % pad)
    # Walked cleanly to the end without meeting a layer-record block: flattened.
    return 0 if end - offset < 12 else None


def read_layout(data: bytes) -> PsdLayout:
    """Walk the fixed header and the three length-prefixed sections."""
    if len(data) < 26 + 4 + 4 + 4:
        raise PsdParseError("file too small to be a PSD")
    signature, version = struct.unpack_from(">4sH", data, 0)
    if signature != b"8BPS":
        raise PsdParseError("missing 8BPS signature")
    if version not in (1, 2):
        raise PsdParseError(f"unknown PSD version {version}")
    is_psb = version == 2
    channels, height, width, depth, color_mode = struct.unpack_from(">HIIHH", data, 12)
    offset = 26

    color_mode_length = struct.unpack_from(">I", data, offset)[0]
    offset += 4 + color_mode_length

    if offset + 4 > len(data):
        raise PsdParseError("truncated before image resources")
    resources_length = struct.unpack_from(">I", data, offset)[0]
    offset += 4 + resources_length

    if is_psb:
        if offset + 8 > len(data):
            raise PsdParseError("truncated before layer info")
        layer_info_length = struct.unpack_from(">Q", data, offset)[0]
        section_start = offset + 8
    else:
        if offset + 4 > len(data):
            raise PsdParseError("truncated before layer info")
        layer_info_length = struct.unpack_from(">I", data, offset)[0]
        section_start = offset + 4
    layer_count = _read_layer_count(data, section_start, layer_info_length, is_psb)
    offset = section_start + layer_info_length

    if offset + 2 > len(data):
        raise PsdParseError("truncated before image data")
    return PsdLayout(
        is_psb=is_psb,
        channels=channels,
        height=height,
        width=width,
        depth=depth,
        color_mode=color_mode,
        image_data_offset=offset,
        layer_count=layer_count,
    )


# Magenta: maximally unlike real design content and trivially detectable in renders.
SENTINEL_RGB = (255, 0, 255)


def sentinel_composite_bytes(layout: PsdLayout) -> bytes:
    """A raw (compression 0) merged composite filled with the sentinel color.

    Channel order in the composite section is R, G, B[, extra alpha/spot planes...]
    for RGB documents; extra channels are written fully opaque. Non-RGB modes get an
    alternating light/dark per-channel fill, which is still unmistakably wrong on
    screen without pretending to know each mode's semantics.
    """
    plane_values: list[int] = []
    for channel_index in range(layout.channels):
        if layout.color_mode == 3 and channel_index < 3:  # RGB
            plane_values.append(SENTINEL_RGB[channel_index])
        else:
            plane_values.append(255)

    pixels_per_plane = layout.width * layout.height
    parts = [struct.pack(">H", 0)]  # compression 0 = raw
    for value in plane_values:
        if layout.depth == 8:
            parts.append(bytes([value]) * pixels_per_plane)
        elif layout.depth == 16:
            parts.append(struct.pack(">H", value * 257) * pixels_per_plane)
        elif layout.depth == 32:
            parts.append(struct.pack(">f", value / 255.0) * pixels_per_plane)
        elif layout.depth == 1:
            row_bytes = (layout.width + 7) // 8
            parts.append((b"\xff" if value >= 128 else b"\x00") * (row_bytes * layout.height))
        else:
            raise PsdParseError(f"unsupported depth {layout.depth}")
    return b"".join(parts)


def write_sentinel_composite(source_path: str, output_path: str) -> PsdLayout:
    """Copy source_path to output_path with the merged composite replaced by sentinel.

    Everything before the image-data section is byte-identical to the original, so the
    editors under test see the exact original layer data; only the baked flat preview
    changes. An editor whose "render" shows magenta was displaying that preview.
    """
    with open(source_path, "rb") as f:
        data = f.read()
    layout = read_layout(data)
    with open(output_path, "wb") as f:
        f.write(data[: layout.image_data_offset])
        f.write(sentinel_composite_bytes(layout))
    return layout


# --- cached layer pixels -------------------------------------------------------------
#
# A type layer, a shape or fill layer and a smart object each carry two descriptions of
# themselves: the data that defines them (text and fonts, a path and a fill, an embedded
# document) and the pixels Photoshop cached for them. An editor that shows the cache has
# not rendered the layer. strip_cached_pixels() removes only the cache, so what an editor
# then shows for such a layer is what its own engine drew, or nothing.

# Additional-layer-info keys that mark a layer whose pixels are a cache, by kind.
CACHED_LAYER_KEYS = {
    "text": (b"TySh",),
    # Embedded smart objects only: a linked one ('SoLE') has nothing in the file to
    # render from, so its pixels are the only picture any reader can show.
    "smart": (b"SoLd", b"PlLd"),
    "vector": (b"SoCo", b"GdFl", b"PtFl", b"vscg"),
}
# The user mask (-2) and real user mask (-3) describe the layer and are kept; every
# other channel (transparency and the color planes) is the cached picture.
_MASK_CHANNEL_IDS = (-2, -3)
# What a defining block is renamed to in the "plain" copy (see strip_cached_pixels).
_PLAIN_LAYER_KEY = b"tsTY"


def _cached_kind(extra: bytes) -> str | None:
    for kind, keys in CACHED_LAYER_KEYS.items():
        for key in keys:
            if b"8BIM" + key in extra or b"8B64" + key in extra:
                return kind
    return None


def strip_cached_pixels(data: bytes, plain: bool = False,
                        keep_kinds: tuple[str, ...] = ()) -> tuple[bytes, list[dict]]:
    """Return (new file bytes, layers changed) with every cached layer's pixels removed.

    Each changed layer keeps its record, blocks, masks and position in the stack; its
    stored rectangle becomes empty and its color and transparency channels shrink to a
    bare compression word, the state Photoshop itself writes for fill layers in 16-bit
    files. With `plain`, the blocks that define the layer are also renamed to a key no
    reader knows, which leaves an ordinary empty pixel layer in the same place: the
    reference for "this layer contributed nothing" (the copies differ in nothing else,
    so any difference between an editor's two renders is what it drew for the layer).
    Layers of a kind in `keep_kinds` ("text", "smart", "vector") are left untouched.

    The returned list holds {"index", "kind", "bounds": [left, top, right, bottom]} per
    layer, bottom-up, bounds as stored before the change. Nothing outside the layer
    info block moves except the two length fields in front of it. Raises PsdParseError
    for layouts this does not handle (16/32-bit documents keep their layer records in
    an additional-info block, and a file with no cached layers has nothing to strip)."""
    layout = read_layout(data)
    length_format, length_size = (">Q", 8) if layout.is_psb else (">I", 4)
    offset = 26
    offset += 4 + struct.unpack_from(">I", data, offset)[0]  # color mode data
    offset += 4 + struct.unpack_from(">I", data, offset)[0]  # image resources
    section_length_at = offset
    section_length = struct.unpack_from(length_format, data, offset)[0]
    info_length_at = offset + length_size
    if section_length < length_size + 2:
        raise PsdParseError("no layer info to strip")
    info_length = struct.unpack_from(length_format, data, info_length_at)[0]
    if info_length < 2:
        raise PsdParseError("layer records are not in the standard layer info block")
    info_start = info_length_at + length_size
    info_end = info_start + info_length
    if info_end > len(data):
        raise PsdParseError("layer info runs past the end of the file")

    count_raw = struct.unpack_from(">h", data, info_start)[0]
    cursor = info_start + 2
    records = []
    for index in range(abs(count_raw)):
        record_start = cursor
        top, left, bottom, right = struct.unpack_from(">iiii", data, cursor)
        channel_count = struct.unpack_from(">H", data, cursor + 16)[0]
        cursor += 18
        channels = []
        for _ in range(channel_count):
            channel_id = struct.unpack_from(">h", data, cursor)[0]
            channel_length = struct.unpack_from(length_format, data, cursor + 2)[0]
            channels.append((channel_id, channel_length))
            cursor += 2 + length_size
        if data[cursor:cursor + 4] != b"8BIM":
            raise PsdParseError(f"layer record {index} has no blend-mode signature")
        cursor += 12  # signature, blend key, opacity, clipping, flags, filler
        extra_length = struct.unpack_from(">I", data, cursor)[0]
        cursor += 4
        extra = data[cursor:cursor + extra_length]
        cursor += extra_length
        if cursor > info_end:
            raise PsdParseError(f"layer record {index} runs past the layer info")
        records.append({"bytes": bytearray(data[record_start:cursor]), "channels": channels,
                        "kind": _cached_kind(extra), "bounds": [left, top, right, bottom],
                        "channel_count": channel_count})

    changed: list[dict] = []
    pixel_parts: list[bytes] = []
    for index, record in enumerate(records):
        bounds = record["bounds"]
        strip = (record["kind"] is not None and record["kind"] not in keep_kinds
                 and bounds[2] > bounds[0] and bounds[3] > bounds[1])
        if strip:
            changed.append({"index": index, "kind": record["kind"], "bounds": bounds})
        flags_at = 18 + record["channel_count"] * (2 + length_size) + 10
        if strip:
            struct.pack_into(">iiii", record["bytes"], 0, 0, 0, 0, 0)
        if strip and plain:
            for keys in CACHED_LAYER_KEYS.values():
                for key in keys:
                    for signature in (b"8BIM", b"8B64"):
                        at = record["bytes"].find(signature + key, flags_at)
                        if at >= 0:
                            record["bytes"][at + 4:at + 8] = _PLAIN_LAYER_KEY
        for position, (channel_id, channel_length) in enumerate(record["channels"]):
            chunk = data[cursor:cursor + channel_length]
            if len(chunk) != channel_length:
                raise PsdParseError("channel image data runs past the end of the file")
            cursor += channel_length
            if strip and channel_id not in _MASK_CHANNEL_IDS:
                chunk = b"\x00\x00"  # raw compression, zero pixels
                struct.pack_into(length_format, record["bytes"], 18 + position * (2 + length_size) + 2, 2)
            pixel_parts.append(chunk)
    if cursor > info_end:
        raise PsdParseError("channel image data runs past the layer info")
    if not changed:
        raise PsdParseError("no cached text, shape, fill or smart-object layers")

    body = struct.pack(">h", count_raw) + b"".join(bytes(r["bytes"]) for r in records) + b"".join(pixel_parts)
    # Keep the block's own padding rule: Photoshop rounds it to a multiple of 4 or 2.
    alignment = 4 if info_length % 4 == 0 else 2
    body += b"\x00" * (-len(body) % alignment)
    delta = len(body) - info_length
    rebuilt = (data[:section_length_at]
               + struct.pack(length_format, section_length + delta)
               + struct.pack(length_format, len(body))
               + body
               + data[info_end:])
    return rebuilt, changed


def write_stripped_caches(source_path: str, output_path: str, plain: bool = False,
                          keep_kinds: tuple[str, ...] = ()) -> list[dict]:
    """Write `output_path`: `source_path` with cached layer pixels removed (or, with
    `plain`, those layers turned into empty pixel layers), and the flattened composite replaced by the sentinel
    so that showing it is as visible as ever. Returns strip_cached_pixels()'s list."""
    with open(source_path, "rb") as f:
        data = f.read()
    stripped, changed = strip_cached_pixels(data, plain=plain, keep_kinds=keep_kinds)
    layout = read_layout(stripped)
    with open(output_path, "wb") as f:
        f.write(stripped[: layout.image_data_offset])
        f.write(sentinel_composite_bytes(layout))
    return changed
