# tools/ — локальная сборка плагинов Paper без Maven

В песочнице нет Maven и `paper-api`, поэтому jar'ы собираются Eclipse Compiler
(ECJ) против заглушек. Такой путь имеет одну опасность: **компилятор не знает,
что в настоящем Bukkit тип объявлен иначе**. Если заглушка сделает
`org.bukkit.Registry` классом, а в Paper это интерфейс, сборка пройдёт, а сервер
упадёт при включении плагина:

```
IncompatibleClassChangeError: Found interface org.bukkit.Registry, but class was expected
```

Ровно это и случилось с VoidAntiCheat 2.0.0. Поэтому пайплайн такой:

```bash
# 1. Заглушки (в них уже правильные class/interface/enum для нужных типов)
python3 tools/localbuild/stubs.py /tmp/acstub
find /tmp/acstub -name '*.java' > /tmp/stubsrc.txt

# 2. Компиляция
find VoidAntiCheat/src/main/java -name '*.java' > /tmp/acsrc.txt
java -jar ecj-3.46.100.jar -source 21 -target 21 -nowarn -proc:none \
     -d /tmp/acout -cp /tmp/acout @/tmp/stubsrc.txt @/tmp/acsrc.txt

# 3. ОБЯЗАТЕЛЬНО: сверка с реальными исходниками Paper
python3 tools/paper_api_audit.py VoidAntiCheat-2.0.1.jar
# 0 — чисто, 1 — есть расхождения (jar нельзя ставить на сервер)

# 4. Поведенческий стенд (честные профили → 0 VL, читы ловятся)
python3 tools/localbuild/gen_config_data.py \
    VoidAntiCheat/src/main/resources/config.yml /tmp/smoke/ConfigData.java
java -cp /tmp/smokeout:/tmp/acout ru.voidpe.anticheat.SmokeIT
```

## Что где

| Файл | Назначение |
|---|---|
| `paper_api_audit.py` | читает пул констант jar и сверяет каждую ссылку на `org.bukkit`/`io.papermc`/`com.destroystokyo` с исходниками PaperMC/Paper: вид типа (class/interface/enum), сигнатура члена с учётом наследования, статичность поля. Кэш — `$PAPER_CACHE` (по умолчанию `/tmp/paper-api-cache`), репозиторий — `main`. |
| `localbuild/stubs.py` | генератор заглушек Bukkit/Paper. **При правке помните про вид типа**: `Registry`, `World`, `Player`, `LivingEntity`, `Entity`, `Block`, `Server`, `PluginManager` — интерфейсы; `PotionEffect`, `ItemStack`, `Location`, `Vector` — классы; `PotionEffectType`, `Enchantment`, `FileConfiguration`, `Event` — абстрактные классы. |
| `localbuild/gen_config_data.py` | разворачивает `config.yml` в `ConfigData.java` (плоская карта) для стенда. Перегенерировать после каждой правки конфига, иначе тесты проверяют старые пороги. |
| `localbuild/smoke/SmokeIT.java` | интеграционный стенд на фейковом Bukkit: 8 честных Bedrock-профилей (ходьба, спринт, прыжки, лёд, лаг-спайк, телепорт, серверный импульс, тач-reach) должны дать **0 VL**, 8 читов — пойматься. |

## Как получить компилятор

```bash
python3 -m venv /tmp/jv2 && /tmp/jv2/bin/pip install jdk4py pyyaml
curl -s -o /tmp/jjit.tgz https://registry.npmjs.org/exoproc-jjit/-/exoproc-jjit-0.6.2.tgz
mkdir -p /tmp/ecjpkg && tar xzf /tmp/jjit.tgz -C /tmp/ecjpkg
J=/tmp/jv2/lib/python3.11/site-packages/jdk4py/java-runtime   # JDK 25
$J/bin/java -jar /tmp/ecjpkg/package/deps/ecj-3.46.100.jar -version
```
