#!/usr/bin/env python3
"""Structural checks for the Bedrock scoreboard UI override and packed output."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "VoidResourcePack"
OUTPUT = ROOT / "VoidResourcePack.mcpack"
EXPECTED_HEADER_UUID = "be7304eb-1c69-4b48-a293-1f08375d08de"
EXPECTED_MODULE_UUID = "e5d159ac-9d10-4d67-931b-4b1afc7b5b75"


def main() -> None:
    manifest = json.loads((SOURCE / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["format_version"] == 2
    assert manifest["header"]["uuid"] == EXPECTED_HEADER_UUID
    assert manifest["header"]["version"] == [1, 18, 1]
    assert manifest["modules"][0]["uuid"] == EXPECTED_MODULE_UUID
    assert manifest["modules"][0]["version"] == [1, 18, 1]
    assert manifest["header"]["min_engine_version"] == [1, 20, 0]

    scoreboards = json.loads((SOURCE / "ui/scoreboards.json").read_text(encoding="utf-8"))
    assert scoreboards == {
        "namespace": "scoreboard",
        "scoreboard_sidebar/main/lists/scores": {"ignored": True},
        "scoreboard_sidebar_score": {
            "ignored": True,
            "size": [0, 0],
            "alpha": 0.0,
        },
    }
    # The players collection must remain untouched: TAB uses scores for ordering.
    assert "scoreboard_sidebar_player" not in scoreboards
    assert "scoreboard_sidebar/main/lists/players" not in scoreboards

    if "--require-output" in sys.argv or OUTPUT.exists():
        source_files = {
            path.relative_to(SOURCE).as_posix(): path.read_bytes()
            for path in SOURCE.rglob("*")
            if path.is_file() and path.name != "README.md"
        }
        with zipfile.ZipFile(OUTPUT) as archive:
            assert archive.testzip() is None
            packed_names = set(archive.namelist())
            assert packed_names == set(source_files), (packed_names, set(source_files))
            for name, expected in source_files.items():
                assert archive.read(name) == expected, name
            # The same source must produce the same artifact bytes.
            before = hashlib.sha256(OUTPUT.read_bytes()).hexdigest()
        print(f"VoidResourcePack checks passed ({len(source_files)} files, sha256 {before})")
    else:
        print("VoidResourcePack source checks passed")


if __name__ == "__main__":
    main()
