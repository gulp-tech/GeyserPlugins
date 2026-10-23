#!/usr/bin/env python3
"""Сверка собранного jar с реальным Bukkit/Paper API.

Зачем: плагины этого репозитория иногда собираются локально против заглушек
(в песочнице нет Maven и paper-api). Заглушка может объявить класс там, где
в настоящем API интерфейс (или наоборот), либо неверный тип возврата.
Компилятор этого не видит, а сервер падает при включении плагина:

    IncompatibleClassChangeError: Found interface org.bukkit.Registry,
    but class was expected

Скрипт читает пул констант jar, находит все ссылки на org.bukkit /
io.papermc / com.destroystokyo и сверяет их с исходниками PaperMC/Paper:

  * вид владельца (class / interface / enum) — ловит invokevirtual vs
    invokeinterface, то есть ровно тот IncompatibleClassChangeError;
  * наличие члена и его дескриптор с учётом наследования — ловит неверный
    тип возврата или параметров (NoSuchMethodError);
  * статичность поля — ловит IncompatibleClassChangeError на полях.

Использование:
    python3 tools/paper_api_audit.py VoidAntiCheat-2.0.0.jar [ещё jars...]
    python3 tools/paper_api_audit.py --ref <ветка-или-коммит> <jar>

Код возврата: 0 — расхождений нет, 1 — есть, 2 — часть типов не проверена.
Нужен доступ к api.github.com (результат кэшируется в $PAPER_CACHE).
"""

import io
import os
import re
import struct
import sys
import urllib.request
import zipfile

API = "https://api.github.com/repos/PaperMC/Paper/contents/paper-api/src/main/java/{path}.java?ref={ref}"
DEFAULT_REF = "main"
CACHE_DIR = os.environ.get("PAPER_CACHE", "/tmp/paper-api-cache")

NAMESPACES = ("org/bukkit", "io/papermc", "com/destroystokyo")
PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}
JAVA_LANG = {
    "String": "java/lang/String", "Object": "java/lang/Object", "Class": "java/lang/Class",
    "Runnable": "java/lang/Runnable", "Iterable": "java/lang/Iterable",
    "Comparable": "java/lang/Comparable", "CharSequence": "java/lang/CharSequence",
    "Throwable": "java/lang/Throwable", "Exception": "java/lang/Exception",
    "RuntimeException": "java/lang/RuntimeException", "Integer": "java/lang/Integer",
    "Long": "java/lang/Long", "Double": "java/lang/Double", "Float": "java/lang/Float",
    "Boolean": "java/lang/Boolean", "Byte": "java/lang/Byte", "Short": "java/lang/Short",
    "Character": "java/lang/Character", "Number": "java/lang/Number",
    "Void": "java/lang/Void", "Enum": "java/lang/Enum", "Record": "java/lang/Record",
    "Math": "java/lang/Math", "System": "java/lang/System", "Thread": "java/lang/Thread",
    "Cloneable": "java/lang/Cloneable", "AutoCloseable": "java/lang/AutoCloseable",
    "Appendable": "java/lang/Appendable", "Readable": "java/lang/Readable",
}
JAVA_UTIL = {
    "List": "java/util/List", "Set": "java/util/Set", "Map": "java/util/Map",
    "Collection": "java/util/Collection", "UUID": "java/util/UUID",
    "Optional": "java/util/Optional", "Consumer": "java/util/function/Consumer",
    "Function": "java/util/function/Function", "Predicate": "java/util/function/Predicate",
    "Supplier": "java/util/function/Supplier", "Iterator": "java/util/Iterator",
}


# ------------------------------------------------------------------ пул констант

class Ref:
    __slots__ = ("owner", "name", "desc", "is_interface_ref", "kind")

    def __init__(self, owner, name, desc, is_interface_ref, kind):
        self.owner = owner
        self.name = name
        self.desc = desc
        self.is_interface_ref = is_interface_ref  # 10 = Methodref(класс), 11 = InterfaceMethodref
        self.kind = kind                          # method | field

    def key(self):
        return (self.owner, self.name, self.desc, self.kind)

    def pretty(self):
        return "%s#%s%s" % (self.owner.replace("/", "."), self.name, self.desc)


def read_constant_pool(data):
    buf = io.BytesIO(data)
    magic = struct.unpack(">I", buf.read(4))[0]
    if magic != 0xCAFEBABE:
        raise ValueError("не class-файл")
    buf.read(4)
    (count,) = struct.unpack(">H", buf.read(2))
    pool = {}
    i = 1
    while i < count:
        tag = buf.read(1)[0]
        if tag == 1:
            (ln,) = struct.unpack(">H", buf.read(2))
            pool[i] = ("utf8", buf.read(ln).decode("utf-8", "replace"))
        elif tag in (3, 4):
            pool[i] = ("num", buf.read(4))
        elif tag in (5, 6):
            pool[i] = ("wide", buf.read(8))
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            (idx,) = struct.unpack(">H", buf.read(2))
            pool[i] = ("class", idx) if tag == 7 else ("ref", idx)
        elif tag in (9, 10, 11, 12, 17, 18):
            a, b = struct.unpack(">HH", buf.read(4))
            pool[i] = ("pair", a, b, tag)
        elif tag == 15:
            pool[i] = ("mh", buf.read(3))
        else:
            raise ValueError("неизвестный тег пула констант: %d" % tag)
        i += 1
    return pool


def collect_refs(jar_path):
    refs = {}
    with zipfile.ZipFile(jar_path) as jar:
        for name in jar.namelist():
            if not name.endswith(".class"):
                continue
            try:
                pool = read_constant_pool(jar.read(name))
            except (ValueError, struct.error):
                continue
            for entry in pool.values():
                if entry[0] != "pair":
                    continue
                tag = entry[3]
                if tag not in (9, 10, 11):
                    continue
                owner_entry = pool.get(entry[1])
                nat = pool.get(entry[2])
                if not owner_entry or not nat or owner_entry[0] != "class" or nat[0] != "pair":
                    continue
                owner_utf = pool.get(owner_entry[1])
                name_utf = pool.get(nat[1])
                desc_utf = pool.get(nat[2])
                if not all(x and x[0] == "utf8" for x in (owner_utf, name_utf, desc_utf)):
                    continue
                owner = owner_utf[1]
                if not owner.startswith(NAMESPACES):
                    continue
                ref = Ref(owner, name_utf[1], desc_utf[1], tag == 11,
                          "field" if tag == 9 else "method")
                refs[ref.key()] = ref
    return list(refs.values())


# ------------------------------------------------------------------ исходники Paper

def fetch_source(internal_name, ref):
    if "$" in internal_name:
        return None  # вложенный тип: проверяется вместе с владельцем
    path = internal_name
    cache_file = os.path.join(CACHE_DIR, ref.replace("/", "_") + "__"
                              + internal_name.replace("/", "_") + ".java")
    if os.path.exists(cache_file):
        text = open(cache_file, encoding="utf-8").read()
        return text or None
    os.makedirs(CACHE_DIR, exist_ok=True)
    url = API.format(path=path, ref=ref)
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github.raw", "User-Agent": "paper-api-audit"})
    try:
        with urllib.request.urlopen(req, timeout=40) as resp:
            text = resp.read().decode("utf-8", "replace")
    except Exception:
        text = ""
    with open(cache_file, "w", encoding="utf-8") as fh:
        fh.write(text)
    return text or None


def strip_generics(text):
    out, depth = [], 0
    for ch in text:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(ch)
    return "".join(out).strip()


def file_context(source, self_name=None):
    pkg, imports = "", {}
    for line in source.splitlines():
        line = line.strip()
        if line.startswith("package "):
            pkg = line[len("package "):].rstrip(";").strip()
        elif line.startswith("import ") and line.endswith(";"):
            fq = line[len("import "):].rstrip(";").strip()
            if fq.startswith("static "):
                continue
            imports[fq.rsplit(".", 1)[-1]] = fq.replace(".", "/")
    return pkg, imports, self_name, source


def resolve_type(text, ctx):
    pkg, imports, self_name, source = ctx
    text = re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", text or "")
    text = strip_generics(text).replace("...", "[]")
    text = re.sub(r"\s*\[\s*\]", "[]", text)
    array = ""
    while text.endswith("[]"):
        array += "["
        text = text[:-2].strip()
    text = text.replace("? extends ", "").replace("? super ", "").strip()
    if not text:
        return array + "Ljava/lang/Object;"
    if text in PRIMITIVES:
        return array + PRIMITIVES[text]
    if re.fullmatch(r"[A-Z]", text):
        return array + "Ljava/lang/Object;"
    if text in imports:
        internal = imports[text]
    elif text in JAVA_LANG:
        internal = JAVA_LANG[text]
    elif text in JAVA_UTIL:
        internal = JAVA_UTIL[text]
    elif text.startswith(("org.", "java.", "net.", "io.", "com.")):
        internal = text.replace(".", "/")
    else:
        # вложенный тип объявлен в этом же файле → Outer$Inner
        nested = self_name and text != self_name and re.search(
            r"\b(?:class|interface|enum|record)\s+%s\b" % re.escape(text), source)
        if nested:
            internal = (pkg.replace(".", "/") + "/" if pkg else "") + self_name + "$" + text
        else:
            internal = (pkg.replace(".", "/") + "/" if pkg else "") + text
    return array + "L" + internal + ";"


def split_params(params):
    """Список ТИПОВ параметров — без имён, аннотаций и final."""
    params = (params or "").strip()
    if not params:
        return []
    parts, depth, current = [], 0, ""
    for ch in params:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current)
    types = []
    for part in parts:
        part = re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", part).strip()
        part = part.replace("final ", "").strip()
        tokens = part.rsplit(None, 1)
        if len(tokens) == 2 and re.fullmatch(r"\w+", tokens[1]):
            part = tokens[0]
        types.append(part.strip())
    return types


DECL_RE = re.compile(r"\b(public|protected)?\s*(?:(?:final|abstract|sealed|non-sealed|static)\s+)*"
                     r"(class|interface|enum|record|@interface)\s+(\w+)")

CTOR_RE = re.compile(r"^\s*(?:(?:public|protected|private)\s+)*(\w+)\s*\(([^)]*)\)\s*(?:throws [^{;]+)?[;{]")
METHOD_RE = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final|abstract|default|synchronized|native|strictfp)\s+)*"
    r"(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>\s+)?"
    r"([\w.$<>\[\], ?]+?)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [^{;]+)?[;{]"
)
FIELD_RE = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final|transient|volatile)\s+)*"
    r"([\w.$<>\[\], ?]+?)\s+(\w+)\s*(?:=[^;]*)?;"
)
SKIP_LINE = re.compile(r"^\s*(?:\*|/\*|//|return |throw |if |else|for |while |switch |case |import |package |@)")


def declared_kind(source):
    flat = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
    flat = re.sub(r"//[^\n]*", " ", flat)
    flat = re.sub(r"\s+", " ", flat)
    m = DECL_RE.search(flat)
    if not m:
        return None
    kind = m.group(2)
    return "annotation" if kind == "@interface" else kind


class TypeInfo:
    def __init__(self, internal_name, source, ctx):
        self.name = internal_name
        self.source = source
        self.ctx = ctx
        self.kind = declared_kind(source)
        self.simple_name = internal_name.rsplit("/", 1)[-1]
        self.methods = {}   # name -> [(descriptor, is_static)]
        self.fields = {}    # name -> [(descriptor, is_static)]
        self.ctors = set()
        self.supertypes = []
        self._parse()

    def _parse(self):
        flat = re.sub(r"/\*.*?\*/", " ", self.source, flags=re.S)
        flat = re.sub(r"//[^\n]*", " ", flat)
        header = re.search(r"\b(?:class|interface|enum|record)\s+\w+([^{]*)",
                           re.sub(r"\s+", " ", flat))
        if header and ("extends" in header.group(1) or "implements" in header.group(1)):
            for part in re.split(r"\bextends\b|\bimplements\b", header.group(1))[1:]:
                for piece in part.split(","):
                    piece = strip_generics(piece.replace("{", " ").strip())
                    if piece and re.fullmatch(r"[\w.$]+", piece):
                        self.supertypes.append(piece)

        in_enum_constants = False
        for raw in flat.splitlines():
            line = raw.strip()
            if not line or SKIP_LINE.match(line):
                continue
            if self.kind == "enum" and not in_enum_constants and re.match(r"^[A-Z][A-Z0-9_]*\s*[,;(]", line):
                in_enum_constants = True
                continue
            if in_enum_constants and line.startswith(";"):
                in_enum_constants = False
                continue
            line = re.sub(r"@[\w.]+(\([^)]*\))?\s*", "", line).strip()
            if not line:
                continue
            ctor = CTOR_RE.match(line)
            if ctor and ctor.group(1) == self.simple_name:
                desc = "(" + "".join(resolve_type(t, self.ctx) for t in split_params(ctor.group(2))) + ")V"
                self.ctors.add(desc)
                continue
            m = METHOD_RE.match(line)
            if m:
                ret, name, params = m.groups()
                desk = "(" + "".join(resolve_type(t, self.ctx) for t in split_params(params)) + ")"
                desk += resolve_type(ret, self.ctx)
                self.methods.setdefault(name, []).append((desk, "static" in line))
                continue
            f = FIELD_RE.match(line)
            if f:
                typ, name = f.groups()
                if name in ("return", "new", "this", "package"):
                    continue
                static = "static" in line or self.kind in ("interface", "annotation")
                self.fields.setdefault(name, []).append((resolve_type(typ, self.ctx), static))


def make_loader(ref, report):
    types = {}

    def load(name):
        if name in types:
            return types[name]
        src = fetch_source(name, ref)
        if src is None:
            report["unresolved_types"].add(name)
            return None
        info = TypeInfo(name, src, file_context(src, name.rsplit("/", 1)[-1]))
        types[name] = info
        return info

    return types, load


def candidate_parents(info):
    pkg = info.ctx[0].replace(".", "/")
    for parent in info.supertypes:
        if "/" in parent:
            yield parent
        elif "." in parent:
            if parent.startswith(("java.", "net.kyori", "com.google", "org.jetbrains")):
                continue  # внешние библиотеки не сверяем
            yield parent.replace(".", "/")
        elif parent in JAVA_LANG:
            continue
        elif parent in JAVA_UTIL:
            continue
        elif parent in info.ctx[1]:
            yield info.ctx[1][parent]
        elif pkg:
            yield pkg + "/" + parent


def find_member(types, info, name, kind, depth=0, seen=None, loader=None):
    if info is None or depth > 5:
        return None
    seen = seen or set()
    if info.name in seen:
        return None
    seen.add(info.name)
    table = info.methods if kind == "method" else info.fields
    if name in table:
        return info, table[name]
    if kind == "field" and info.kind == "enum" and re.search(r"\b%s\b" % re.escape(name), info.source):
        return info, [("L%s;" % info.name, True)]
    if kind == "method" and info.kind == "enum" and name in ("name", "ordinal", "values", "valueOf"):
        return info, None  # синтетика компилятора/Enum — не проверяем
    for parent in candidate_parents(info):
        if not parent.startswith(NAMESPACES):
            continue
        parent_info = types.get(parent) or (loader(parent) if loader else None)
        if parent_info is None:
            continue
        found = find_member(types, parent_info, name, kind, depth + 1, seen, loader)
        if found:
            return found
    return None


def collect_members(types, info, name, kind, loader=None, depth=0, seen=None, out=None):
    """Все объявления члена name по иерархии типа: [(TypeInfo, descriptor, is_static)]."""
    if info is None or depth > 5:
        return out or []
    seen = seen or set()
    out = out if out is not None else []
    if info.name in seen:
        return out
    seen.add(info.name)
    table = info.methods if kind == "method" else info.fields
    for desc, static in table.get(name, []):
        out.append((info, desc, static))
    if kind == "method" and info.kind == "enum" and name in ("name", "ordinal", "values", "valueOf"):
        return out
    if kind == "field" and info.kind == "enum" and re.search(r"\b%s\b" % re.escape(name), info.source):
        out.append((info, "L%s;" % info.name, True))
    for parent in candidate_parents(info):
        if not parent.startswith(NAMESPACES):
            continue
        parent_info = types.get(parent) or (loader(parent) if loader else None)
        collect_members(types, parent_info, name, kind, loader, depth + 1, seen, out)
    return out


def audit_jar(jar_path, ref):
    refs = collect_refs(jar_path)
    report = {"jar": jar_path, "types": len({r.owner for r in refs}), "refs": len(refs),
              "kind_mismatch": [], "desc_mismatch": [], "static_mismatch": [],
              "missing": [], "unresolved_types": set(), "unverified": 0}
    types, load = make_loader(ref, report)
    for owner in sorted({r.owner for r in refs}):
        load(owner)

    for r in refs:
        info = types.get(r.owner)
        if info is None:
            continue
        expected_interface = info.kind in ("interface", "annotation")
        if r.kind == "method" and r.is_interface_ref != expected_interface:
            report["kind_mismatch"].append((r, info.kind))
            continue
        if r.name == "<init>":
            if not info.ctors:
                report["unverified"] += 1
            elif r.desc not in info.ctors:
                report["desc_mismatch"].append((r, sorted(info.ctors), info.simple_name))
            continue
        members = collect_members(types, info, r.name, r.kind, loader=load)
        if not members:
            report["missing"].append(r)
            continue
        exact = [m for m in members if m[1] == r.desc]
        if not exact:
            report["desc_mismatch"].append((r, sorted({m[1] for m in members}), members[0][0].simple_name))
            continue
        if r.kind == "field":
            static = exact[0][2]
            if not static:
                report["static_mismatch"].append((r, exact[0][0].simple_name))
    return report


def print_report(report):
    print("=" * 72)
    print("jar: %s" % report["jar"])
    print("типов Bukkit/Paper: %d, ссылок на члены: %d" % (report["types"], report["refs"]))
    unresolved = sorted(report["unresolved_types"])
    if unresolved:
        print("не проверено (нет отдельного исходника): %s" % ", ".join(unresolved))
    problems = 0
    if report["kind_mismatch"]:
        problems += len(report["kind_mismatch"])
        print("\n[ОШИБКА] вид типа не совпадает — на сервере IncompatibleClassChangeError:")
        for r, real_kind in report["kind_mismatch"]:
            used = "интерфейс" if r.is_interface_ref else "класс"
            print("  - %s: собрано как %s, в Paper %s" % (r.pretty(), used, real_kind))
    if report["desc_mismatch"]:
        problems += len(report["desc_mismatch"])
        print("\n[ОШИБКА] сигнатура не совпадает — на сервере NoSuchMethodError/NoSuchFieldError:")
        for r, expected, where in report["desc_mismatch"]:
            print("  - %s: в Paper %s → %s" % (r.pretty(), where, ", ".join(expected)))
    if report["static_mismatch"]:
        problems += len(report["static_mismatch"])
        print("\n[ОШИБКА] поле читается как статическое, а в Paper оно обычное:")
        for r, where in report["static_mismatch"]:
            print("  - %s (%s)" % (r.pretty(), where))
    if problems == 0:
        print("\nРасхождений с реальным API не найдено.")
    if report["missing"]:
        print("\n[к сведению] не найдено в исходнике владельца (наследование/синтетика): %d"
              % len(report["missing"]))
        for r in sorted(report["missing"], key=lambda x: (x.owner, x.name))[:12]:
            print("  - %s" % r.pretty())
        if len(report["missing"]) > 12:
            print("  ... ещё %d" % (len(report["missing"]) - 12))
    return problems


def main(argv):
    ref = DEFAULT_REF
    args = list(argv[1:])
    if "--ref" in args:
        i = args.index("--ref")
        ref = args[i + 1]
        del args[i:i + 2]
    if not args:
        print(__doc__)
        return 2
    total, unresolved = 0, False
    for jar in args:
        report = audit_jar(jar, ref)
        total += print_report(report)
        unresolved = unresolved or bool(report["unresolved_types"])
    if total:
        return 1
    return 2 if unresolved else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
