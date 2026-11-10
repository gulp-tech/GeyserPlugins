#!/usr/bin/env python3
"""Structural checks for the Pistoletov gif entity pack and packed output."""
import json
import struct
import sys
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "PistoletovPack"
OUTPUT = ROOT / "PistoletovPack.mcpack"
EXPECTED_HEADER_UUID = "b75e302b-bf8d-4bd5-8e0d-3c35c2f074ef"
EXPECTED_MODULE_UUID = "d8e39da4-49a3-4d5a-b713-4140f17b6972"
VOID_PACK_HEADER_UUID = "be7304eb-1c69-4b48-a293-1f08375d08de"
FRAME_COUNT = 76


def png_size(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    assert data[12:16] == b"IHDR", path
    width, height = struct.unpack(">II", data[16:24])
    return width, height


def main() -> None:
    manifest = json.loads((SOURCE / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["format_version"] == 2
    assert manifest["header"]["uuid"] == EXPECTED_HEADER_UUID
    assert manifest["header"]["uuid"] != VOID_PACK_HEADER_UUID  # must not collide with VoidResourcePack
    assert manifest["header"]["version"] == [1, 0, 0]
    assert manifest["header"]["min_engine_version"] == [1, 20, 0]
    assert manifest["modules"][0]["uuid"] == EXPECTED_MODULE_UUID
    assert manifest["modules"][0]["type"] == "resources"
    assert manifest["modules"][0]["version"] == [1, 0, 0]
    assert (SOURCE / "pack_icon.png").is_file()

    entity = json.loads((SOURCE / "entity/zombie_horse.entity.json").read_text(encoding="utf-8"))
    description = entity["minecraft:client_entity"]["description"]
    assert description["identifier"] == "minecraft:zombie_horse"
    # Must beat vanilla zombie_horse_v3 (1.17.10) but stay <= pack min_engine_version.
    assert description["min_engine_version"] == "1.20.0"
    assert description["materials"] == {"default": "entity"}
    assert description["geometry"] == {"default": "geometry.pistoletov"}
    assert description["render_controllers"] == ["controller.render.pistoletov"]
    textures = description["textures"]
    assert set(textures) == {"default"} | {f"f{i:03d}" for i in range(FRAME_COUNT)}
    for i in range(FRAME_COUNT):
        assert textures[f"f{i:03d}"] == f"textures/entity/pistoletov/f{i:03d}"

    geo = json.loads((SOURCE / "models/entity/pistoletov.geo.json").read_text(encoding="utf-8"))
    assert geo["format_version"] == "1.12.0"
    geometry = geo["minecraft:geometry"][0]
    assert geometry["description"]["identifier"] == "geometry.pistoletov"
    assert geometry["description"]["texture_width"] == 320
    assert geometry["description"]["texture_height"] == 242
    cube = geometry["bones"][0]["cubes"][0]
    assert cube["size"] == [32, 24.2, 1]
    # Both visible faces must show the full gif texture (south UV mirrored).
    assert cube["uv"]["north"] == {"uv": [0, 0], "uv_size": [320, 242]}
    assert cube["uv"]["south"] == {"uv": [320, 0], "uv_size": [-320, 242]}

    render_controller = json.loads(
        (SOURCE / "render_controllers/pistoletov.render_controllers.json").read_text(encoding="utf-8")
    )
    controller = render_controller["render_controllers"]["controller.render.pistoletov"]
    frames = controller["arrays"]["textures"]["Array.frames"]
    assert frames == [f"Texture.f{i:03d}" for i in range(FRAME_COUNT)]
    assert controller["geometry"] == "Geometry.default"
    assert controller["materials"] == [{"*": "Material.default"}]
    assert controller["textures"] == [
        f"Array.frames[math.mod(math.floor(query.life_time * 25.0), {FRAME_COUNT}.0)]"
    ]

    for i in range(FRAME_COUNT):
        frame = SOURCE / f"textures/entity/pistoletov/f{i:03d}.png"
        assert frame.is_file(), frame
        assert png_size(frame) == (320, 242), frame

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
    print("PistoletovPack structural checks passed")


if __name__ == "__main__":
    main()
