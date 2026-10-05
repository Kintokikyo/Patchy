"""psd-tools driver: a Python PSD library measured beside the editors (opt-in).

psd-tools (https://github.com/psd-tools/psd-tools) is not an editor, but it has its
own layer compositor, which makes it a useful yardstick for the render column.
`composite(force=True)` makes it composite the layers itself; without it the
library hands back the file's baked preview whenever one exists, which is exactly
the shortcut Testy's trap leg exists to catch. A file with no layer records has
only that preview, so it is read as-is there.

There is no resave leg: psd-tools writes back the blocks it read, so a "resave"
would measure nothing. Each render runs in a child interpreter so a hang or crash
in the compositor costs one cell, not the run.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

TIMEOUT_SECONDS = 180


def version() -> str | None:
    """The installed psd-tools version, or None when the package is missing."""
    try:
        from importlib import metadata

        return metadata.version("psd-tools")
    except Exception:
        return None


def missing_composite_modules() -> list[str]:
    """Modules psd-tools' compositor imports that are not installed."""
    from importlib import util

    return [name for name in ("scipy", "skimage", "aggdraw") if util.find_spec(name) is None]


def export(input_path: Path, output_path: Path) -> dict:
    try:
        completed = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), str(input_path), str(output_path)],
            capture_output=True,
            text=True,
            timeout=TIMEOUT_SECONDS,
        )
        exit_code = completed.returncode
        lines = [line for line in (completed.stderr or "").splitlines() if line.strip()]
        stderr = lines[-1][-2000:] if lines else ""
    except subprocess.TimeoutExpired:
        exit_code, stderr = -1, f"timeout after {TIMEOUT_SECONDS}s"
    except OSError as error:
        exit_code, stderr = -1, str(error)
    ok = exit_code == 0 and output_path.exists() and output_path.stat().st_size > 0
    return {
        "exitCode": exit_code,
        "stderr": stderr,
        "ok": ok,
        # Exit 1 is a Python exception from psd-tools: its verdict on the file. A
        # timeout or a crashed interpreter counts against the driver instead.
        "fileRejected": not ok and exit_code == 1,
    }


def _render(input_path: str, output_path: str) -> None:
    from psd_tools import PSDImage

    psd = PSDImage.open(input_path)
    image = psd.composite(force=True) if len(psd) else psd.composite()
    image.save(output_path)


if __name__ == "__main__":
    _render(sys.argv[1], sys.argv[2])
