#!/usr/bin/env python3
"""Build VoidResourcePack.mcpack deterministically from tracked source files."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "VoidResourcePack"
OUTPUT = ROOT / "VoidResourcePack.mcpack"
FIXED_DATE = (2026, 1, 1, 0, 0, 0)


def main() -> None:
    files = sorted(path for path in SOURCE.rglob("*") if path.is_file() and path.name != "README.md")
    if not files:
        raise SystemExit("VoidResourcePack source is empty")
    with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(SOURCE).as_posix(), FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes())
    print(f"Built {OUTPUT.name}: {len(files)} files, {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
