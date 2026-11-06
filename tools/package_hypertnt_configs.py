#!/usr/bin/env python3
"""Package only the six reviewed TNT configs and installation notes."""
from pathlib import Path
import sys
from zipfile import ZipFile, ZIP_DEFLATED

names = ("tnt_basic", "tnt_fire", "tnt_obs", "tnt_heavy", "tnt_explosive", "tnt_poseidon")
output = Path(sys.argv[1])
with ZipFile(output, "w", ZIP_DEFLATED) as z:
    z.write("HyperTNT/README-VoidPE.txt", "README-VoidPE.txt")
    for name in names:
        path = Path("HyperTNT/tnts") / (name + ".yml")
        z.write(path, path.as_posix())
print(output)
