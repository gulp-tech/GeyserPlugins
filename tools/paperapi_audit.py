#!/usr/bin/env python3
"""Сверка собранного jar с настоящими исходниками PaperMC/Paper.

Зачем
-----
Maven и paper-api в песочнице недоступны, поэтому плагины этого репозитория
собираются вручную: Eclipse Compiler против заглушек из
tools/localbuild/bukkit_stubs.py. Опасность такого пути — заглушка может
объявить класс там, где в настоящем API интерфейс. Компилятор этого не видит:
он сгенерирует invokevirtual вместо invokeinterface, сборка пройдёт, а сервер
упадёт при включении плагина:

    IncompatibleClassChangeError: Found interface org.bukkit.Registry, but class was expected

Именно так падали VoidAntiCheat 2.0.0 и VoidPvP 1.0.0. Скрипт читает пул
констант готового jar и сверяет каждую ссылку на org.bukkit / io.papermc /
com.destroystokyo с исходником этого типа:

  * вид владельца: Methodref (тег 10) = класс, InterfaceMethodref (тег 11) =
    интерфейс. Ровно это и даёт IncompatibleClassChangeError на сервере.
  * сигнатура: имя + дескриптор должны найтись в типе или его предках
    (наследование разворачивается по исходникам) — иначе NoSuchMethodError.
  * конструкторы: дескриптор должен быть объявлен.
  * статичность поля: getstatic против обычного поля.

Использование
-------------
    python3 tools/paperapi_audit.py VoidNick-1.0.0.jar
    python3 tools/paperapi_audit.py VoidAntiCheat-2.0.1.jar VoidPvP-1.0.2.jar
    python3 tools/paperapi_audit.py --ref main --verbose Some.jar

Код возврата: 0 — чисто, 1 — есть расхождения, 2 — не удалось проверить
(нет сети или часть исходников недоступна).
Исходники кэшируются в $PAPER_CACHE (по умолчанию /tmp/paper-api-cache).
Токен берётся из $GITHUB_TOKEN / $GH_TOKEN или из `gh auth token`.
"""

import io
import os
import re
import struct
import sys
import urllib.request
import zipfile

RAW_API = "https://api.github.com/repos/PaperMC/Paper/contents/paper-api/src/main/java/{path}.java?ref={ref}"
DEFAULT_REF = "main"
CACHE = os.environ.get("PAPER_CACHE", "/tmp/paper-api-cache")
NAMESPACES = ("org/bukkit", "io/papermc", "com/destroystokyo")

PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}
JAVA_LANG = {
    "String", "Object", "Class", "Runnable", "Iterable", "Comparable", "CharSequence",
    "Throwable", "Exception", "RuntimeException", "Error", "Integer", "Long", "Double",
    "Float", "Boolean", "Byte", "Short", "Character", "Number", "Void", "Enum", "Record",
    "Math", "System", "Thread", "Cloneable", "AutoCloseable", "Appendable", "Readable",
}
JAVA_UTIL = {
    "List": "java/util/List", "Set": "java/util/Set", "Map": "java/util/Map",
    "Collection": "java/util/Collection", "UUID": "java/util/UUID",
    "Optional": "java/util/Optional", "Iterator": "java/util/Iterator",
    "Consumer": "java/util/function/Consumer", "Function": "java/util/function/Function",
    "Predicate": "java/util/function/Predicate", "Supplier": "java/util/function/Supplier",
    "CompletableFuture": "java/util/concurrent/CompletableFuture",
}


# ------------------------------------------------------------------ пул констант

class Member:
    __slots__ = ("owner", "name", "desc", "is_interface", "kind")

    def __init__(self, owner, name, desc, is_interface, kind):
        self.owner = owner
        self.name = name
        self.desc = desc
        self.is_interface = is_interface
        self.kind = kind

    def key(self):
        return (self.owner, self.name, self.desc, self.kind)

    def pretty(self):
        return "%s#%s%s" % (self.owner.replace("/", "."), self.name, self.desc)


def read_pool(data):
    buf = io.BytesIO(data)
    if struct.unpack(">I", buf.read(4))[0] != 0xCAFEBABE:
        raise ValueError("не class-файл")
    buf.read(4)
    (count,) = struct.unpack(">H", buf.read(2))
    pool, index = {}, 1
    while index < count:
        tag = buf.read(1)[0]
        if tag == 1:
            (length,) = struct.unpack(">H", buf.read(2))
            pool[index] = ("utf8", buf.read(length).decode("utf-8", "replace"))
        elif tag in (3, 4):
            pool[index] = ("const", buf.read(4))
        elif tag in (5, 6):
            pool[index] = ("const", buf.read(8))
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            (ref,) = struct.unpack(">H", buf.read(2))
            pool[index] = ("class", ref) if tag == 7 else ("ref", ref)
        elif tag in (9, 10, 11, 12, 17, 18):
            first, second = struct.unpack(">HH", buf.read(4))
            pool[index] = ("pair", first, second, tag)
        elif tag == 15:
            pool[index] = ("mh", buf.read(3))
        else:
            raise ValueError("неизвестный тег пула: %d" % tag)
        index += 1
    return pool


def nested_block(source, name):
    """Вырезает из файла владельца объявление вложенного типа вместе с телом."""
    match = re.search(r"\b(?:class|interface|enum|record|@interface)\s+" + re.escape(name) + r"\b", source)
    if not match:
        return None
    brace = source.find("{", match.end())
    if brace == -1:
        return None
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[match.start():index + 1]
    return None


def collect_members(jar_path):
    members = {}
    with zipfile.ZipFile(jar_path) as jar:
        for name in jar.namelist():
            if not name.endswith(".class"):
                continue
            try:
                pool = read_pool(jar.read(name))
            except (ValueError, struct.error):
                continue
            for entry in pool.values():
                if entry[0] != "pair" or entry[3] not in (9, 10, 11):
                    continue
                owner_entry, nat = pool.get(entry[1]), pool.get(entry[2])
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
                member = Member(owner, name_utf[1], desc_utf[1], entry[3] == 11,
                                "field" if entry[3] == 9 else "method")
                members[member.key()] = member
    return list(members.values())


# ------------------------------------------------------------------ исходники Paper

_TOKEN = None


def gh_token():
    global _TOKEN
    if _TOKEN is not None:
        return _TOKEN
    _TOKEN = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    if not _TOKEN:
        try:
            import subprocess
            _TOKEN = subprocess.run(["gh", "auth", "token"], capture_output=True,
                                    text=True, timeout=20).stdout.strip()
        except Exception:
            _TOKEN = ""
    return _TOKEN


def fetch_source(internal, ref):
    if "$" in internal:
        return None
    cache_file = os.path.join(CACHE, ref.replace("/", "_") + "__" + internal.replace("/", "_") + ".java")
    if os.path.exists(cache_file):
        with open(cache_file, encoding="utf-8") as handle:
            content = handle.read()
        return content or None
    os.makedirs(CACHE, exist_ok=True)
    headers = {"Accept": "application/vnd.github.raw", "User-Agent": "paperapi-audit"}
    token = gh_token()
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        request = urllib.request.Request(RAW_API.format(path=internal, ref=ref), headers=headers)
        with urllib.request.urlopen(request, timeout=45) as response:
            content = response.read().decode("utf-8", "replace")
    except Exception:
        content = ""
    with open(cache_file, "w", encoding="utf-8") as handle:
        handle.write(content)
    return content or None


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def strip_annotations(text):
    return re.sub(r"@[\w.]+(\s*\([^)]*\))?\s*", "", text)


def strip_generics(text):
    out, depth = [], 0
    for char in text:
        if char == "<":
            depth += 1
        elif char == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(char)
    return "".join(out)


class SourceType:
    def __init__(self, internal, source):
        self.internal = internal
        self.source = strip_comments(source)
        self.simple = internal.rsplit("/", 1)[-1].rsplit("$", 1)[-1]
        self.package, self.imports = self._context()
        self.kind = self._kind()
        self.methods = {}
        self.fields = {}
        self.ctors = set()
        self.supertypes = []
        self.enum_constants = set()
        self.nested_names = set()
        self._parse()

    def _context(self):
        package = ""
        match = re.search(r"\bpackage\s+([\w.]+)\s*;", self.source)
        if match:
            package = match.group(1)
        imports = {}
        for full in re.findall(r"\bimport\s+([\w.]+)\s*;", self.source):
            imports[full.rsplit(".", 1)[-1]] = full.replace(".", "/")
        return package, imports

    def _kind(self):
        flattened = re.sub(r"\s+", " ", self.source)
        match = re.search(r"\b(class|interface|enum|record|@interface)\s+" + re.escape(self.simple), flattened)
        if not match:
            return None
        kind = match.group(1)
        return "interface" if kind in ("interface", "@interface") else kind

    def _parse(self):
        # Заголовок типа бывает многострочным — ищем по тексту с схлопнутыми пробелами.
        flattened = re.sub(r"\s+", " ", self.source)
        header = re.search(r"\b(?:class|interface|enum|record)\s+" + re.escape(self.simple) + r"\s*([^{]*)\{", flattened)
        if header:
            for part in re.split(r"\bextends\b|\bimplements\b", header.group(1))[1:]:
                for piece in part.split(","):
                    piece = strip_annotations(strip_generics(piece)).strip()
                    if re.fullmatch(r"[\w.$]+", piece or "x"):
                        resolved = self.resolve(piece)
                        if resolved:
                            self.supertypes.append(resolved)

        for nested in re.finditer(r"\b(?:class|interface|enum|record)\s+(\w+)\b", self.source):
            if nested.group(1) != self.simple:
                self.nested_names.add(nested.group(1))

        body = self.source
        if header:
            # тело — от первого '{' заголовка; берём по исходному тексту, не по flattened
            brace = self.source.find("{", self._header_start())
            body = self.source[brace + 1:]
        if self.kind == "enum":
            semi = body.find(";")
            head = body[:semi] if semi != -1 else body
            for part in head.split(","):
                name = re.match(r"\s*([A-Za-z_$][\w$]*)", part)
                if name and name.group(1) not in ("public", "private", "protected", "static", "final"):
                    self.enum_constants.add(name.group(1))
                    self.fields.setdefault(name.group(1), set()).add("L%s;" % self.internal)
            body = body[semi + 1:] if semi != -1 else ""

        for raw_line in body.splitlines():
            line = strip_annotations(raw_line).strip()
            if not line or line.startswith(("*", "/*", "//")):
                continue
            ctor = re.match(r"(?:public|protected|private)\s+" + re.escape(self.simple) + r"\s*\(([^)]*)\)", line)
            if ctor:
                self.ctors.add("(" + self._params(ctor.group(1)) + ")V")
                continue
            method = re.match(
                r"(?:(?:public|protected|private|static|final|abstract|default|synchronized|native|strictfp)\s+)*"
                r"(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>\s+)?"
                r"([\w.$<>\[\], ?]+?)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [^{;]+)?[;{]", line)
            if method:
                return_type, name, params = method.groups()
                if name in ("return", "new", "if", "for", "while", "switch", "catch"):
                    continue
                self.methods.setdefault(name, set()).add("(" + self._params(params) + ")" + self._type(return_type))
                continue
            field = re.match(
                r"(?:(?:public|protected|private|static|final|transient|volatile)\s+)*"
                r"([\w.$<>\[\], ?]+?)\s+(\w+)\s*(?:=[^;]*)?;", line)
            if field:
                field_type, name = field.groups()
                if name in ("return", "new", "this", "package", "import"):
                    continue
                self.fields.setdefault(name, set()).add(self._type(field_type))

    def _header_start(self):
        match = re.search(r"\b(?:class|interface|enum|record)\s+" + re.escape(self.simple), self.source)
        return match.start() if match else 0

    def _params(self, params):
        params = (params or "").strip()
        if not params:
            return ""
        parts, depth, current = [], 0, ""
        for char in params:
            if char in "<(":
                depth += 1
            elif char in ">)":
                depth -= 1
            if char == "," and depth == 0:
                parts.append(current)
                current = ""
            else:
                current += char
        if current.strip():
            parts.append(current)
        descriptors = ""
        for part in parts:
            part = strip_annotations(part).replace("final ", "").strip()
            tokens = part.rsplit(None, 1)
            if len(tokens) == 2 and re.fullmatch(r"\w+", tokens[1]):
                part = tokens[0]
            descriptors += self._type(part)
        return descriptors

    def _type(self, text):
        text = strip_annotations(strip_generics(text)).strip().replace("...", "[]")
        text = re.sub(r"\[\s*\]", "[]", text)
        array = ""
        while text.endswith("[]"):
            array += "["
            text = text[:-2].strip()
        text = re.sub(r"^\? (extends|super) ", "", text).strip()
        if not text or text == "var":
            return array + "Ljava/lang/Object;"
        if text in PRIMITIVES:
            return array + PRIMITIVES[text]
        if re.fullmatch(r"[A-Z]", text):
            return array + "Ljava/lang/Object;"
        if text in JAVA_LANG:
            return array + "Ljava/lang/" + text + ";"
        if text in JAVA_UTIL:
            return array + "L" + JAVA_UTIL[text] + ";"
        if "." in text:
            head, _, rest = text.partition(".")
            # Вложенный тип, записанный как Owner.Nested внутри того же файла.
            if head == self.simple or head in self.nested_names:
                if head in self.imports:
                    return array + "L" + self.imports[head] + "$" + rest.replace(".", "$") + ";"
                owner = (self.package.replace(".", "/") + "/" if self.package else "") + head
                return array + "L" + owner + "$" + rest.replace(".", "$") + ";"
            return array + "L" + text.replace(".", "/") + ";"
        if text in self.imports:
            return array + "L" + self.imports[text] + ";"
        if text in self.nested_names:
            owner = (self.package.replace(".", "/") + "/" if self.package else "") + self.simple
            return array + "L" + owner + "$" + text + ";"
        return array + "L" + (self.package.replace(".", "/") + "/" if self.package else "") + text + ";"

    def resolve(self, name):
        if "/" in name:
            return name
        if "." in name:
            return name.replace(".", "/")
        if name in self.imports:
            return self.imports[name]
        if name in JAVA_LANG or name in JAVA_UTIL:
            return None
        return (self.package.replace(".", "/") + "/" if self.package else "") + name


class Loader:
    def __init__(self, ref):
        self.ref = ref
        self.types = {}
        self.unresolved = set()

    def get(self, internal):
        if not internal:
            return None
        if internal in self.types:
            return self.types[internal]
        source = fetch_source(internal, self.ref)
        if source is None and "$" in internal:
            owner_internal, nested = internal.rsplit("$", 1)
            owner_type = self.get(owner_internal)
            if owner_type is not None:
                block = nested_block(owner_type.source, nested)
                if block:
                    imports = "\n".join("import %s;" % imp.replace("/", ".")
                                         for imp in sorted(set(owner_type.imports.values())))
                    source = "package %s;\n%s\n%s" % (owner_type.package, imports, block)
        if source is None:
            self.unresolved.add(internal)
            return None
        parsed = SourceType(internal, source)
        self.types[internal] = parsed
        return parsed

    def matches(self, internal, name, kind, depth=0, seen=None):
        """Все объявления члена по иерархии предков (включая интерфейсы)."""
        if depth > 8:
            return []
        seen = seen or set()
        if internal in seen:
            return []
        seen.add(internal)
        source_type = self.get(internal)
        if source_type is None:
            return []
        table = source_type.methods if kind == "method" else source_type.fields
        found = []
        if name in table:
            found.append((source_type, table[name]))
        if kind == "method" and name == "<init>" and source_type.ctors:
            found.append((source_type, source_type.ctors))
        for parent in source_type.supertypes:
            found.extend(self.matches(parent, name, kind, depth + 1, seen))
        return found

    def has_unresolved_parent(self, internal, seen=None):
        """Есть ли в иерархии тип, исходник которого недоступен (например adventure)."""
        seen = seen or set()
        if internal in seen:
            return False
        seen.add(internal)
        if internal in self.unresolved:
            return True
        source_type = self.get(internal)
        if source_type is None:
            return True
        return any(self.has_unresolved_parent(parent, seen) for parent in source_type.supertypes)

    def find(self, internal, name, kind, depth=0, seen=None):
        if depth > 6:
            return None
        seen = seen or set()
        if internal in seen:
            return None
        seen.add(internal)
        source_type = self.get(internal)
        if source_type is None:
            return None
        table = source_type.methods if kind == "method" else source_type.fields
        if name in table:
            return source_type, table[name]
        if kind == "method" and name == "<init>" and source_type.ctors:
            return source_type, source_type.ctors
        for parent in source_type.supertypes:
            found = self.find(parent, name, kind, depth + 1, seen)
            if found:
                return found
        return None


# ------------------------------------------------------------------ проверка

def member_is_static(source_type, name):
    for line in source_type.source.splitlines():
        if re.search(r"\b%s\s*(=|;)" % re.escape(name), line):
            if "==" in line or line.strip().startswith(("return", "case")):
                continue
            return "static" in line
    return True


OBJECT_METHODS = ("equals", "hashCode", "toString", "getClass", "clone", "finalize",
                  "notify", "notifyAll", "wait")


def audit(jar_path, ref):
    members = collect_members(jar_path)
    loader = Loader(ref)
    problems = {"kind": [], "signature": [], "constructor": [], "static": []}
    missing = []
    inherited = []

    for member in members:
        source_type = loader.get(member.owner)
        if source_type is None:
            continue
        real_interface = source_type.kind in ("interface", "annotation")
        if member.kind == "method" and member.is_interface != real_interface:
            problems["kind"].append((member, source_type.kind))
            continue
        if member.kind == "method" and source_type.kind == "enum" and member.name in ("name", "ordinal", "values", "valueOf"):
            continue
        if member.name == "<init>":
            if source_type.ctors and member.desc not in source_type.ctors:
                problems["constructor"].append((member, sorted(source_type.ctors)))
            continue

        found = loader.matches(member.owner, member.name, member.kind)
        if not found:
            if member.kind == "method" and member.name in OBJECT_METHODS:
                continue
            if loader.has_unresolved_parent(member.owner):
                inherited.append(member)
                continue
            missing.append(member)
            continue
        exact = [(owner, descs) for owner, descs in found if member.desc in descs]
        if not exact:
            # У Player/LivingEntity часть методов приходит из adventure/JDK-интерфейсов,
            # которых нет в paper-api (Audience#sendActionBar(Component) и т.п.) —
            # такую подпись проверить нечем, это не ошибка сборки.
            if member.kind == "method" and loader.has_unresolved_parent(member.owner):
                inherited.append(member)
                continue
            union = sorted(set().union(*[descs for _, descs in found]))
            problems["signature"].append((member, union, found[0][0].simple))
            continue
        if member.kind == "field":
            declared_in = exact[0][0]
            if declared_in.kind in ("enum", "interface", "annotation"):
                continue  # константы enum и поля-константы интерфейсов статичны по определению
            if not member_is_static(declared_in, member.name):
                problems["static"].append((member, declared_in.simple))
    return members, problems, missing, loader.unresolved, inherited


def report(jar_path, members, problems, missing, unresolved, inherited, verbose):
    print("=" * 78)
    print("jar: %s" % jar_path)
    print("ссылок на Bukkit/Paper: %d; типов: %d" % (len(members), len({m.owner for m in members})))
    if unresolved:
        print("не проверено (исходник недоступен): %s" % ", ".join(sorted(unresolved)[:8]))

    total = 0
    if problems["kind"]:
        total += len(problems["kind"])
        print("\n[ОШИБКА] вид владельца не совпадает — на сервере IncompatibleClassChangeError:")
        for member, real in problems["kind"]:
            print("  - %s: в jar %s, в Paper %s"
                  % (member.pretty(), "интерфейс" if member.is_interface else "класс", real))
    if problems["signature"]:
        total += len(problems["signature"])
        print("\n[ОШИБКА] сигнатура не найдена — на сервере NoSuchMethodError/NoSuchFieldError:")
        for member, descriptors, where in problems["signature"]:
            print("  - %s: у %s объявлено %s" % (member.pretty(), where, ", ".join(descriptors)))
    if problems["constructor"]:
        total += len(problems["constructor"])
        print("\n[ОШИБКА] конструктор с таким дескриптором не объявлен:")
        for member, descriptors in problems["constructor"]:
            print("  - %s: есть %s" % (member.pretty(), ", ".join(descriptors)))
    if problems["static"]:
        total += len(problems["static"])
        print("\n[ОШИБКА] поле читается как статическое, а оно обычное:")
        for member, where in problems["static"]:
            print("  - %s (%s)" % (member.pretty(), where))

    if total == 0:
        print("\nРасхождений с настоящим API не найдено.")

    if missing:
        print("\n[к сведению] не нашлось в исходнике владельца и предках (%d):" % len(missing))
        for member in missing[:10]:
            print("  - %s" % member.pretty())
        if len(missing) > 10:
            print("  ... ещё %d" % (len(missing) - 10))
    if inherited:
        print("\n[к сведению] наследуется из внешней библиотеки, проверить нечем (%d):" % len(inherited))
        for member in inherited[:10]:
            print("  - %s" % member.pretty())
        if len(inherited) > 10:
            print("  ... ещё %d" % (len(inherited) - 10))
    if verbose:
        print("\nвсе ссылки:")
        for member in sorted(members, key=lambda m: (m.owner, m.name)):
            print("  %s" % member.pretty())
    return total


# ---------------------------------------------------------------------------
# Проверка «класс vs интерфейс» для библиотек вне paper-api (adventure).
# Ошибка из этой серии выглядит так:
#   IncompatibleClassChangeError: Method '...LegacyComponentSerializer.legacySection()'
#   must be InterfaceMethodref constant
# Возникает, когда заглушка объявляет классом то, что в реальности интерфейс
# (или наоборот), и javac ставит Methodref вместо InterfaceMethodref.
# ---------------------------------------------------------------------------

ADVENTURE_INTERFACES = {
    "net/kyori/adventure/text/Component",
    "net/kyori/adventure/text/ComponentLike",
    "net/kyori/adventure/audience/Audience",
    "net/kyori/adventure/audience/ForwardingAudience",
    "net/kyori/adventure/bossbar/BossBar",
    "net/kyori/adventure/title/Title",
    "net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer",
    "net/kyori/adventure/text/serializer/plain/PlainTextComponentSerializer",
    "net/kyori/adventure/key/Key",
    "net/kyori/adventure/key/Keyed",
}
ADVENTURE_CLASSES = {
    "net/kyori/adventure/text/event/ClickEvent",
    "net/kyori/adventure/text/event/HoverEvent",
    "net/kyori/adventure/text/format/TextDecoration",
}

# Точные дескрипторы методов adventure, которые мы вызываем. Ошибка в них не
# ловится компилятором (заглушка «угадала» сигнатуру), но на сервере даёт
# NoSuchMethodError — именно так упал VoidTab 1.16.2:
#   NoSuchMethodError: LegacyComponentSerializer.deserialize(String)Component
# (в adventure возвращается TextComponent, а не Component).
ADVENTURE_DESCRIPTORS = {
    ("net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer", "deserialize"):
        "(Ljava/lang/String;)Lnet/kyori/adventure/text/TextComponent;",
    ("net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer", "serialize"):
        "(Lnet/kyori/adventure/text/Component;)Ljava/lang/String;",
    ("net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer", "legacySection"):
        "()Lnet/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer;",
    ("net/kyori/adventure/text/serializer/plain/PlainTextComponentSerializer", "serialize"):
        "(Lnet/kyori/adventure/text/Component;)Ljava/lang/String;",
    ("net/kyori/adventure/text/Component", "text"):
        "(Ljava/lang/String;)Lnet/kyori/adventure/text/TextComponent;",
    ("net/kyori/adventure/text/Component", "empty"):
        "()Lnet/kyori/adventure/text/TextComponent;",
}


def _constant_pool(data):
    """Возвращает {индекс: (tag, значение)} для constant pool class-файла."""
    pos = 8
    size = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    pool = {}
    index = 1
    while index < size:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            pool[index] = (tag, data[pos:pos + length].decode("utf-8", "replace"))
            pos += length
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag == 15:
            pool[index] = (tag, None)
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pool[index] = (tag, struct.unpack_from(">HH", data, pos))
            pos += 4
        elif tag in (5, 6):
            pool[index] = (tag, None)
            pos += 8
            index += 1
        else:
            raise ValueError("неизвестный тег constant pool: %d" % tag)
        index += 1
    return pool


def check_library_kinds(jar_path):
    """Ищет Methodref к интерфейсам adventure и InterfaceMethodref к их классам."""
    problems = []
    with zipfile.ZipFile(jar_path) as jar:
        for entry in jar.namelist():
            if not entry.endswith(".class"):
                continue
            try:
                pool = _constant_pool(jar.read(entry))
            except Exception as error:  # pragma: no cover — битый класс
                problems.append("%s: не разобрать класс (%s)" % (entry, error))
                continue
            for tag, value in pool.values():
                if tag not in (10, 11) or not isinstance(value, tuple):
                    continue
                owner_entry = pool.get(value[0])
                if not owner_entry or owner_entry[0] != 7:
                    continue
                name_entry = pool.get(owner_entry[1])
                if not name_entry:
                    continue
                name = name_entry[1]
                if name in ADVENTURE_INTERFACES and tag != 11:
                    problems.append("%s: %s должен быть InterfaceMethodref" % (entry, name))
                elif name in ADVENTURE_CLASSES and tag != 10:
                    problems.append("%s: %s должен быть Methodref" % (entry, name))
                nat = pool.get(value[1])
                if not nat or nat[0] != 12:
                    continue
                member_name = pool.get(nat[1][0], ("", ""))[1]
                member_desc = pool.get(nat[1][1], ("", ""))[1]
                expected = ADVENTURE_DESCRIPTORS.get((name, member_name))
                if expected and member_desc != expected:
                    problems.append("%s: %s.%s%s — в adventure %s"
                                    % (entry, name.rsplit("/", 1)[-1], member_name, member_desc, expected))
    return problems


def main(argv):
    ref = DEFAULT_REF
    verbose = False
    jars = []
    args = list(argv[1:])
    while args:
        arg = args.pop(0)
        if arg == "--ref":
            ref = args.pop(0)
        elif arg == "--verbose":
            verbose = True
        else:
            jars.append(arg)
    if not jars:
        print(__doc__)
        return 2
    failed = False
    unresolved_any = False
    for jar in jars:
        if not os.path.exists(jar):
            print("нет файла: %s" % jar)
            failed = True
            continue
        members, problems, missing, unresolved, inherited = audit(jar, ref)
        failed = report(jar, members, problems, missing, unresolved, inherited, verbose) > 0 or failed
        library = check_library_kinds(jar)
        if library:
            failed = True
            print("\n[ОШИБКА] класс/интерфейс в библиотеке — на сервере IncompatibleClassChangeError:")
            for line in library[:10]:
                print("  - " + line)
            if len(library) > 10:
                print("  ... ещё %d" % (len(library) - 10))
        unresolved_any = unresolved_any or bool(unresolved)
    if failed:
        return 1
    return 2 if unresolved_any else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
