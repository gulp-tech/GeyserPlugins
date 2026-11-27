package ru.voidpe.gifts;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.inventory.EquipmentSlot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * VoidGifts — подарки-головы.
 *
 * /gift create <тип> [кол-во] — админ получает голову подарка с текстурой из gifts.yml.
 * Голова ставится в мире как обычный блок. Игрок ПКМ — награда из gifts.yml (команды
 * от консоли), только ему играет случайный .ogg из папки music/ (8 секунд, настраивается).
 * Награда выдаётся один раз на конкретный подарок для каждого игрока.
 *
 * Bedrock: плагин сам пересобирает VoidGiftsPack.mcpack (manifest + sound_definitions
 * по содержимому music/) и кладёт его в plugins/Geyser-Spigot/packs/.
 * Игрокам нужен перезаход после добавления новых треков.
 */
public final class VoidGifts extends JavaPlugin implements Listener, CommandExecutor {

    private static final String DEFAULT_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWM4NmUzNjFlN2EzMzE5YzQyMGExNTg5MDNkZWRkY2E0YmEyYmNhMjU4NWM1YWVlYzQwYzgxOTVjNGJhOGEyMyJ9fX0=";

    private org.bukkit.NamespacedKey typeKey;

    private FileConfiguration giftsConfig;
    private FileConfiguration dataConfig;
    private File dataFile;
    private File giftsFile;
    private GiftLootStore lootStore;
    private GiftLootMenu lootMenu;

    /** placed: "мир;x;y;z" -> тип подарка */
    private final Map<String, String> placed = new LinkedHashMap<>();
    /** claims: "мир;x;y;z" -> кто открыл */
    private final Map<String, Set<UUID>> claims = new LinkedHashMap<>();
    /** heard (только в памяти, сбрасывается при выходе): игрок -> треки за сессию */
    private final Map<UUID, Set<String>> heard = new HashMap<>();
    /** доступные звуки: имя файла -> sound id (voidpe:gift_*) */
    private final Map<String, String> sounds = new LinkedHashMap<>();

    private float volume;
    private float pitch;
    private int musicSeconds;
    private boolean autoPack;
    private String bedrockPackPath;

    private UUID packUuid;
    private int packVersion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("gifts.yml", false);
        getDataFolder().mkdirs();
        new File(getDataFolder(), "music").mkdirs();
        typeKey = new org.bukkit.NamespacedKey(this, "gift_type");
        loadGifts();
        lootStore = new GiftLootStore(this);
        lootStore.load();
        lootMenu = new GiftLootMenu(this, lootStore);
        getServer().getPluginManager().registerEvents(lootMenu, this);
        loadData();
        loadSettings();
        getCommand("gift").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        rescanSoundsAndRebuildPack();
        ConfigurationSection gTypes = giftTypes();
        getLogger().info("VoidGifts включён: типов=" + (gTypes == null ? 0 : gTypes.getKeys(false).size()) + ", звуков=" + sounds.size()
                + ", размещено подарков=" + placed.size());
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ---------- загрузка / сохранение ----------

    private void loadSettings() {
        reloadConfig();
        volume = (float) getConfig().getDouble("music-volume", 1.0D);
        pitch = (float) getConfig().getDouble("music-pitch", 1.0D);
        musicSeconds = Math.max(1, getConfig().getInt("music-seconds", 8));
        autoPack = getConfig().getBoolean("auto-pack", true);
        bedrockPackPath = String.valueOf(getConfig().get("bedrock-pack-path", "plugins/Geyser-Spigot/packs/VoidGiftsPack.mcpack"));
    }

    private void loadGifts() {
        giftsFile = new File(getDataFolder(), "gifts.yml");
        giftsConfig = YamlConfiguration.loadConfiguration(giftsFile);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        placed.clear();
        claims.clear();
        ConfigurationSection p = dataConfig.getConfigurationSection("placed");
        if (p != null) {
            for (String k : p.getKeys(false)) {
                placed.put(pathToKey(k), p.getString(k, "present"));
            }
        }
        ConfigurationSection c = dataConfig.getConfigurationSection("claims");
        if (c != null) {
            for (String k : c.getKeys(false)) {
                List<String> uuids = c.getStringList(k);
                Set<UUID> set = new HashSet<>();
                for (String s : uuids) {
                    try { set.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
                }
                claims.put(pathToKey(k), set);
            }
        }
        String u = dataConfig.getString("pack.uuid");
        try { packUuid = u == null ? null : UUID.fromString(u); } catch (IllegalArgumentException ignored) { packUuid = null; }
        if (packUuid == null) packUuid = UUID.randomUUID();
        packVersion = dataConfig.getInt("pack.version", 1);
    }

    private void saveData() {
        try {
            dataConfig = new YamlConfiguration();
            for (Map.Entry<String, String> e : placed.entrySet()) {
                dataConfig.set("placed." + keyToPath(e.getKey()), e.getValue());
            }
            for (Map.Entry<String, Set<UUID>> e : claims.entrySet()) {
                List<String> uuids = e.getValue().stream().map(UUID::toString).collect(Collectors.toList());
                dataConfig.set("claims." + keyToPath(e.getKey()), uuids);
            }
            dataConfig.set("pack.uuid", packUuid.toString());
            dataConfig.set("pack.version", packVersion);
            dataConfig.save(dataFile);
        } catch (Exception e) {
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    /** Ключ вида "мир;x;y;z" -> безопасный путь YAML: точки ломают вложенность, поэтому кодируем %XX */
    private String keyToPath(String key) {
        StringBuilder sb = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '%') sb.append("%25");
            else if (c == '.') sb.append("%2E");
            else sb.append(c);
        }
        return sb.toString();
    }

    private String pathToKey(String path) {
        return path.replace("%2E", ".").replace("%25", "%");
    }

    private String blockKey(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    // ---------- типы подарков ----------

    private ConfigurationSection giftTypes() {
        ConfigurationSection s = giftsConfig == null ? null : giftsConfig.getConfigurationSection("gifts");
        return s;
    }

    private ConfigurationSection giftType(String type) {
        ConfigurationSection all = giftTypes();
        return all == null ? null : all.getConfigurationSection(type);
    }

    private String giftDisplayName(String type) {
        ConfigurationSection t = giftType(type);
        return t == null ? type : t.getString("display-name", type);
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color(getConfig().getString("messages.no-permission", "&cУкажи подкоманду: /gift menu|create|give|list|reload")));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu":
            case "loot":
                return cmdLootMenu(sender);
            case "create":
                return cmdCreate(sender, args);
            case "give":
                return cmdGive(sender, args);
            case "list":
                return cmdList(sender);
            case "reload":
                return cmdReload(sender);
            default:
                sender.sendMessage(color("&cНеизвестная подкоманда. /gift menu|create|give|list|reload"));
                return true;
        }
    }

    private boolean cmdLootMenu(CommandSender sender) {
        if (!sender.hasPermission("voidgifts.admin")) { sender.sendMessage(msg("messages.no-permission")); return true; }
        if (!(sender instanceof Player player)) { sender.sendMessage("Меню можно открыть только в игре."); return true; }
        if (!lootStore.isReady()) { sender.sendMessage(color("&cНе удалось загрузить loot.yml. Проверьте лог сервера.")); return true; }
        lootMenu.openTypes(player, 0);
        return true;
    }

    List<String> configuredGiftTypes() {
        ConfigurationSection all = giftTypes();
        return all == null ? List.of() : new ArrayList<>(all.getKeys(false));
    }

    String menuGiftName(String type) { return giftDisplayName(type); }

    boolean hasGiftType(String type) { return giftType(type) != null; }

    private boolean cmdCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voidgifts.admin")) { sender.sendMessage(msg("messages.no-permission")); return true; }
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игрока (голову некуда выдать)."); return true; }
        if (args.length < 2) { sender.sendMessage("/gift create <тип> [кол-во]"); return true; }
        String type = args[1];
        if (giftType(type) == null) {
            sender.sendMessage(msg("messages.unknown-type").replace("{type}", type));
            return true;
        }
        int count = parseCount(args, 2, 1, 64);
        Player player = (Player) sender;
        ItemStack head = createGiftHead(type, count);
        player.getInventory().addItem(head);
        sender.sendMessage(msg("messages.created").replace("{type}", type));
        return true;
    }

    private boolean cmdGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voidgifts.admin")) { sender.sendMessage(msg("messages.no-permission")); return true; }
        if (args.length < 3) { sender.sendMessage("/gift give <игрок> <тип> [кол-во]"); return true; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage("Игрок не в сети: " + args[1]); return true; }
        String type = args[2];
        if (giftType(type) == null) {
            sender.sendMessage(msg("messages.unknown-type").replace("{type}", type));
            return true;
        }
        int count = parseCount(args, 3, 1, 64);
        target.getInventory().addItem(createGiftHead(type, count));
        sender.sendMessage(msg("messages.given").replace("{type}", type).replace("{count}", String.valueOf(count)));
        return true;
    }

    private boolean cmdList(CommandSender sender) {
        ConfigurationSection all = giftTypes();
        sender.sendMessage(color(getConfig().getString("messages.list-header", "&eТипы подарков:")));
        if (all == null) { sender.sendMessage("&7gifts.yml пуст."); return true; }
        for (String type : all.getKeys(false)) {
            String name = color(all.getConfigurationSection(type).getString("display-name", type));
            sender.sendMessage(color(getConfig().getString("messages.list-entry", "&7- &f{type}")
                    .replace("{type}", type).replace("{name}", name)));
        }
        return true;
    }

    private boolean cmdReload(CommandSender sender) {
        if (!sender.hasPermission("voidgifts.admin")) { sender.sendMessage(msg("messages.no-permission")); return true; }
        loadSettings();
        loadGifts();
        lootStore.load();
        rescanSoundsAndRebuildPack();
        sender.sendMessage(msg("messages.reloaded")
                .replace("{types}", String.valueOf(giftTypes() == null ? 0 : giftTypes().getKeys(false).size()))
                .replace("{sounds}", String.valueOf(sounds.size())));
        return true;
    }

    private int parseCount(String[] args, int index, int min, int max) {
        if (args.length <= index) return min;
        try { return Math.min(max, Math.max(min, Integer.parseInt(args[index]))); } catch (NumberFormatException e) { return min; }
    }

    private ItemStack createGiftHead(String type, int count) {
        ConfigurationSection cfg = giftType(type);
        String texture = cfg == null ? null : cfg.getString("texture", null);
        if (texture == null || texture.isBlank()) texture = DEFAULT_TEXTURE;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, count);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        applyTexture(meta, type, texture);
        String display = cfg == null ? type : cfg.getString("display-name", type);
        meta.setDisplayName(color(display));
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        head.setItemMeta(meta);
        return head;
    }

    /**
     * Текстура головы: полностью через рефлексию, чтобы компиляция не зависела
     * от того, какие profile-API оставлены в конкретной версии сервера.
     * Путь 1 — com.destroystokyo.paper.profile (Paper); путь 2 — legacy org.bukkit.profile.
     */
    private void applyTexture(SkullMeta meta, String type, String textureValue) {
        UUID id = UUID.nameUUIDFromBytes(("voidgifts:" + type).getBytes());
        String url = skinUrlFromValue(textureValue);
        boolean done = false;
        // Путь 1: Paper destroystokyo
        try {
            Object profile = Bukkit.class.getMethod("createProfile", UUID.class, String.class).invoke(null, id, "gift");
            Class<?> paperProfile = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            Class<?> propClass = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
            Object prop = propClass.getConstructor(String.class, String.class).newInstance("textures", textureValue);
            paperProfile.getMethod("setProperty", propClass).invoke(profile, prop);
            SkullMeta.class.getMethod("setPlayerProfile", paperProfile).invoke(meta, profile);
            done = true;
        } catch (Throwable t) {
            getLogger().warning("Paper-путь текстуры не сработал (" + t + "), пробую legacy API");
        }
        // Путь 2: legacy Bukkit profile API
        if (!done && url != null) {
            try {
                Class<?> bpp = Class.forName("org.bukkit.profile.PlayerProfile");
                Class<?> texClass = Class.forName("org.bukkit.profile.PlayerTextures");
                Object profile = Bukkit.class.getMethod("createPlayerProfile", UUID.class, String.class).invoke(null, id, "gift");
                Object textures = bpp.getMethod("getTextures").invoke(profile);
                texClass.getMethod("setSkin", java.net.URL.class).invoke(textures, new java.net.URL(url));
                bpp.getMethod("setTextures", texClass).invoke(profile, textures);
                SkullMeta.class.getMethod("setOwnerProfile", bpp).invoke(meta, profile);
                done = true;
            } catch (Throwable t) {
                getLogger().warning("Legacy-путь текстуры тоже не сработал: " + t);
            }
        }
        if (!done) {
            getLogger().warning("Не удалось применить текстуру головы (тип " + type + ") — голова выдана без текстуры");
        }
    }

    private String skinUrlFromValue(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String json = new String(java.util.Base64.getDecoder().decode(value.trim()), "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
            if (m.find()) return m.group(1);
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
        }
        return null;
    }

    // ---------- размещение / поломка ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        String type = itemType(item);
        if (type == null) return;
        placed.put(blockKey(event.getBlockPlaced()), type);
        saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String key = blockKey(event.getBlock());
        if (placed.remove(key) != null) {
            saveData();
        }
    }

    private String itemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String type = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return (type == null || type.isBlank()) ? null : type;
    }

    // ---------- открытие ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        String material = block.getType().name();
        if (!material.contains("PLAYER_HEAD")) return; // PLAYER_HEAD и PLAYER_WALL_HEAD
        String key = blockKey(block);
        String type = placed.get(key);
        if (type == null) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!player.hasPermission("voidgifts.open")) {
            player.sendMessage(msg("messages.no-permission"));
            return;
        }
        if (!lootStore.isReady()) {
            player.sendMessage(color("&cПодарки временно недоступны: ошибка загрузки лута. Сообщите администрации."));
            return;
        }
        Set<UUID> claimed = claims.computeIfAbsent(key, k -> new HashSet<>());
        if (claimed.contains(player.getUniqueId())) {
            player.sendMessage(msg("messages.already-opened"));
            return;
        }
        claimed.add(player.getUniqueId());
        saveData();

        // команды награды от консоли
        ConfigurationSection cfg = giftType(type);
        String display = color(giftDisplayName(type));
        if (cfg != null) {
            List<String> commands = new ArrayList<>();
            if (cfg.isList("commands")) commands.addAll(cfg.getStringList("commands"));
            else if (cfg.isString("commands")) commands.add(cfg.getString("commands"));
            if (cfg.isString("command")) commands.add(cfg.getString("command"));
            for (String raw : commands) {
                if (raw == null || raw.isBlank()) continue;
                String cmd = raw.replace("%player%", player.getName());
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Throwable t) {
                    getLogger().warning("Ошибка команды подарка «" + type + "»: " + cmd + " -> " + t);
                }
            }
        }
        // Existing commands stay guaranteed; each GUI reward rolls independently.
        for (GiftLootStore.Reward reward : lootStore.rewards(type)) {
            if (!LootChance.roll(reward.chance(), ThreadLocalRandomHolder.random().nextDouble())) continue;
            ItemStack prize = reward.item().clone();
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(prize);
            for (ItemStack excess : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), excess);
            }
        }
        player.sendMessage(msg("messages.opened").replace("{name}", display));
        playRandomTrack(player);
    }

    // ---------- музыка ----------

    private void playRandomTrack(Player player) {
        if (sounds.isEmpty()) return; // музыка не настроена — молчим
        Set<String> heardSet = heard.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        List<String> all = new ArrayList<>(sounds.keySet());
        List<String> unheard = all.stream().filter(s -> !heardSet.contains(s)).collect(Collectors.toList());
        if (unheard.isEmpty()) { // всё  heard — начинаем круг заново
            heardSet.clear();
            unheard = all;
        }
        String pick = unheard.get(ThreadLocalRandomHolder.random().nextInt(unheard.size()));
        heardSet.add(pick);
        String soundId = sounds.get(pick);
        try {
            player.playSound(player.getLocation(), soundId, volume, pitch);
        } catch (Throwable t) {
            getLogger().warning("Не удалось воспроизвести звук " + soundId + ": " + t);
            return;
        }
        String sid = soundId;
        int seconds = musicSeconds;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                try { player.stopSound(sid); } catch (Throwable ignored) {}
            }
        }, seconds * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        heard.remove(event.getPlayer().getUniqueId()); // перезаход = новый круг песен
    }

    /** Сканирует music/*.ogg и возвращает соответствие файл -> sound id. */
    private Map<String, String> scanMusic() {
        Map<String, String> result = new LinkedHashMap<>();
        File dir = new File(getDataFolder(), "music");
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".ogg"));
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        Set<String> used = new HashSet<>();
        for (File f : files) {
            String base = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
            String id = base.replaceAll("[^a-z0-9_]", "_");
            if (id.isEmpty()) id = "track";
            String candidate = id;
            int n = 1;
            while (!used.add(candidate)) {
                candidate = id + "_" + (++n);
            }
            result.put(f.getName(), "voidpe:gift_" + candidate);
        }
        return result;
    }

    private void rescanSoundsAndRebuildPack() {
        sounds.clear();
        sounds.putAll(scanMusic());
        if (!autoPack) return;
        String path = bedrockPackPath == null ? "" : bedrockPackPath.trim();
        if (path.isEmpty() || path.equalsIgnoreCase("false") || path.equalsIgnoreCase("none")) return;
        try {
            rebuildPack(sounds, path);
        } catch (Exception e) {
            getLogger().warning("Не удалось собрать Bedrock-пак: " + e.getMessage());
        }
    }

    private void rebuildPack(Map<String, String> soundMap, String path) throws Exception {
        File target = new File(path);
        if (!target.isAbsolute()) {
            target = new File(Bukkit.getWorldContainer(), path);
        }
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();

        packVersion++;
        packUuid = packUuid == null ? UUID.randomUUID() : packUuid;

        StringBuilder defs = new StringBuilder();
        defs.append("{\n  \"format_version\": \"1.14.0\",\n  \"sound_definitions\": {\n");
        int i = 0;
        for (Map.Entry<String, String> e : soundMap.entrySet()) {
            String file = e.getKey();
            String id = e.getValue();
            String inner = file.substring(0, file.length() - 4); // без .ogg
            defs.append("    \"").append(id).append("\": {\n")
                .append("      \"category\": \"music\",\n")
                .append("      \"sounds\": [\n        {\n          \"name\": \"sounds/gifts/").append(escapeJson(inner)).append("\",\n          \"stream\": true\n        }\n      ]\n    }");
            if (++i < soundMap.size()) defs.append(",");
            defs.append("\n");
        }
        defs.append("  }\n}\n");

        String manifest = "{\n"
                + "  \"format_version\": 2,\n"
                + "  \"header\": {\n"
                + "    \"name\": \"VoidPE Gifts\",\n"
                + "    \"description\": \"Звуки подарков VoidPE (музыка из music/)\",\n"
                + "    \"uuid\": \"" + packUuid + "\",\n"
                + "    \"version\": [1, 0, " + packVersion + "],\n"
                + "    \"min_engine_version\": [1, 20, 0]\n"
                + "  },\n"
                + "  \"modules\": [\n"
                + "    {\n"
                + "      \"type\": \"resources\",\n"
                + "      \"uuid\": \"" + UUID.nameUUIDFromBytes((packUuid + ":module").getBytes()) + "\",\n"
                + "      \"version\": [1, 0, " + packVersion + "]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";

        File musicDir = new File(getDataFolder(), "music");
        try (OutputStream fos = new FileOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            writeEntry(zip, "manifest.json", manifest.getBytes("UTF-8"));
            // pack_icon.png из ресурсов плагина
            try (InputStream icon = getResource("pack_icon.png")) {
                if (icon != null) writeEntry(zip, "pack_icon.png", readAll(icon));
            }
            writeEntry(zip, "sounds/sound_definitions.json", defs.toString().getBytes("UTF-8"));
            for (Map.Entry<String, String> e : soundMap.entrySet()) {
                File f = new File(musicDir, e.getKey());
                writeEntry(zip, "sounds/gifts/" + f.getName(), Files.readAllBytes(f.toPath()));
            }
        }
        saveData();
        getLogger().info("Bedrock-пак пересобран: " + target.getAbsolutePath() + " (звуков: " + soundMap.size() + ", версия 1.0." + packVersion + ")");
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int read;
        while ((read = in.read(tmp)) != -1) buffer.write(tmp, 0, read);
        return buffer.toByteArray();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------- утилиты ----------

    private String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String path) {
        return color(getConfig().getString(path, path));
    }

    /** Ленивый доступ к Random без полей */
    private static final class ThreadLocalRandomHolder {
        private static final ThreadLocal<Random> RANDOM = ThreadLocal.withInitial(Random::new);
        private ThreadLocalRandomHolder() {}
        static Random random() { return RANDOM.get(); }
    }
}
