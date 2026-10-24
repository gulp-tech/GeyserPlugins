#!/usr/bin/env python3
"""Генерирует ConfigData.java (плоский снимок config.yml) для стенда SmokeIT.

Зачем: стенд работает без Bukkit-загрузчика конфигов, поэтому YAML
разворачивается в Java-карту. Файл генерируется, а не пишется руками —
иначе тесты проверяют старые пороги.

    python3 tools/localbuild/gen_config_data.py \
        VoidAntiCheat/src/main/resources/config.yml /tmp/smoke/ConfigData.java
"""

import sys

import yaml


def flatten(node, prefix="", out=None):
    out = {} if out is None else out
    for key, value in node.items():
        path = "%s.%s" % (prefix, key) if prefix else str(key)
        if isinstance(value, dict):
            flatten(value, path, out)
        elif isinstance(value, list):
            out[path] = ("LIST", [str(item) for item in value])
        elif isinstance(value, bool):
            out[path] = ("BOOL", value)
        elif isinstance(value, int):
            out[path] = ("INT", value)
        elif isinstance(value, float):
            out[path] = ("DBL", value)
        elif value is None:
            continue
        else:
            out[path] = ("STR", str(value))
    return out


def escape(text):
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    source = argv[1]
    target = argv[2] if len(argv) > 2 else "/tmp/smoke/ConfigData.java"
    with open(source, encoding="utf-8") as fh:
        config = yaml.safe_load(fh)
    values = flatten(config)

    lines = [
        "package ru.voidpe.anticheat;",
        "",
        "import java.util.LinkedHashMap;",
        "import java.util.List;",
        "import java.util.Map;",
        "",
        "/** Сгенерировано tools/localbuild/gen_config_data.py из config.yml — не править вручную. */",
        "final class ConfigData {",
        "",
        "    static final Map<String, Object> VALUES = new LinkedHashMap<>();",
        "",
        "    private ConfigData() {",
        "    }",
        "",
        "    static {",
    ]
    for key in sorted(values):
        kind, value = values[key]
        if kind == "BOOL":
            lines.append('        VALUES.put("%s", %s);' % (key, "true" if value else "false"))
        elif kind in ("INT", "DBL"):
            suffix = "d" if kind == "DBL" else ""
            lines.append('        VALUES.put("%s", %s%s);' % (key, value, suffix))
        elif kind == "STR":
            lines.append('        VALUES.put("%s", "%s");' % (key, escape(value)))
        else:
            items = ", ".join('"%s"' % escape(item) for item in value)
            lines.append('        VALUES.put("%s", List.of(%s));' % (key, items))
    lines += ["    }", "}", ""]

    with open(target, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print("ключей: %d → %s" % (len(values), target))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
