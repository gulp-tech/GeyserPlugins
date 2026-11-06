#!/usr/bin/env python3
"""Verify that every bundled HyperTNT type ignores every shulker colour."""
from pathlib import Path
import yaml

class UniqueLoader(yaml.SafeLoader):
    pass

def unique_mapping(loader, node, deep=False):
    pairs = loader.construct_pairs(node, deep=deep)
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate YAML key: {key!r}")
        result[key] = value
    return result

UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)
base = Path("HyperTNT/tnts")
types = {"tnt_basic", "tnt_fire", "tnt_obs", "tnt_heavy", "tnt_explosive", "tnt_poseidon"}
variants = {"SHULKER_BOX"} | {f"{x}_SHULKER_BOX" for x in (
    "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
    "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK")}
for name in sorted(types):
    path = base / (name + ".yml")
    doc = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueLoader)
    assert len(doc) == 1, f"{path}: expected one type"
    config = next(iter(doc.values()))
    per_material = config["explosion"]["blocks"]["per-material"]
    for variant in variants:
        assert per_material.get(variant, {}).get("mode") == "IGNORE", f"{path}: unsafe {variant}"
    print(f"{path}: {len(variants)} shulker variants IGNORE")
print("HyperTNT configs: all checks passed")
