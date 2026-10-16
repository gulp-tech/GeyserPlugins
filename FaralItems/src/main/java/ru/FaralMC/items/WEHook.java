package ru.FaralMC.items;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Мягкая привязка к WorldEdit/FAWE через рефлексию: загрузка схематики трапки
 * (plugins/FaralItems/schematics/*.schem) и вставка её центром в точку.
 * Центр схематики — где стоял игрок, когда делал //copy в WorldEdit.
 */
public final class WEHook {

    private final Logger log;
    private ClassLoader cl;
    private Object worldEdit;
    private Method adaptWorld;
    private Method vectorAt;

    private Object clipboard;
    private int relMinX, relMinY, relMinZ, relMaxX, relMaxY, relMaxZ;
    private final List<int[]> nonAirCells = new ArrayList<>(); // смещения от центра, где в схематике не воздух

    public WEHook(Logger log) {
        this.log = log;
        init();
    }

    private void init() {
        Plugin we = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we == null) {
            we = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        }
        if (we == null) {
            log.warning("WorldEdit не найден: своя схематика трапки работать не будет (включится встроенная клетка).");
            return;
        }
        try {
            cl = we.getClass().getClassLoader();
            Class<?> weClass = Class.forName("com.sk89q.worldedit.WorldEdit", true, cl);
            worldEdit = weClass.getMethod("getInstance").invoke(null);
            Class<?> adapter = Class.forName("org.sk89q.worldedit.bukkit.BukkitAdapter", true, cl);
            adaptWorld = adapter.getMethod("adapt", World.class);
            Class<?> bv3 = Class.forName("org.sk89q.worldedit.math.BlockVector3", true, cl);
            vectorAt = bv3.getMethod("at", int.class, int.class, int.class);
            log.info("WorldEdit подключён (своя схематика трапки)");
        } catch (Throwable t) {
            log.warning("WorldEdit hook не удался: " + t.getMessage());
            worldEdit = null;
        }
    }

    public boolean available() {
        return worldEdit != null;
    }

    public boolean hasSchematic() {
        return clipboard != null;
    }

    private int coord(Object vector, String getter) throws Exception {
        return (Integer) vector.getClass().getMethod(getter).invoke(vector);
    }

    /** Загрузить .schem файл. @return true, если схематика готова к вставке. */
    public boolean loadSchematic(File file) {
        if (!available() || file == null || !file.exists()) {
            return false;
        }
        try {
            Class<?> formats = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats", true, cl);
            Object format = formats.getMethod("findByFile", File.class).invoke(null, file);
            if (format == null) {
                log.warning("Не понял формат схематики: " + file.getName());
                return false;
            }
            Object reader;
            try (InputStream in = new FileInputStream(file)) {
                reader = format.getClass().getMethod("getReader", InputStream.class).invoke(format, in);
                clipboard = reader.getClass().getMethod("read").invoke(reader);
            }
            Object min = clipboard.getClass().getMethod("getMinimumPoint").invoke(clipboard);
            Object max = clipboard.getClass().getMethod("getMaximumPoint").invoke(clipboard);
            Object origin = clipboard.getClass().getMethod("getOrigin").invoke(clipboard);
            int ox = coord(origin, "getX"), oy = coord(origin, "getY"), oz = coord(origin, "getZ");
            relMinX = coord(min, "getX") - ox; relMinY = coord(min, "getY") - oy; relMinZ = coord(min, "getZ") - oz;
            relMaxX = coord(max, "getX") - ox; relMaxY = coord(max, "getY") - oy; relMaxZ = coord(max, "getZ") - oz;
            nonAirCells.clear();
            Method getBlock = clipboard.getClass().getMethod("getBlock",
                    Class.forName("com.sk89q.worldedit.math.BlockVector3", true, cl));
            for (int x = coord(min, "getX"); x <= coord(max, "getX"); x++) {
                for (int y = coord(min, "getY"); y <= coord(max, "getY"); y++) {
                    for (int z = coord(min, "getZ"); z <= coord(max, "getZ"); z++) {
                        Object point = vectorAt.invoke(null, x, y, z);
                        Object block = getBlock.invoke(clipboard, point);
                        Object type = block.getClass().getMethod("getBlockType").invoke(block);
                        String id = String.valueOf(type.getClass().getMethod("getId").invoke(type));
                        if (!id.endsWith(":air") && !id.endsWith(":cave_air") && !id.endsWith(":void_air")) {
                            nonAirCells.add(new int[]{x - ox, y - oy, z - oz});
                        }
                    }
                }
            }
            log.info("Схематика трапки загружена: " + file.getName()
                    + " (размер " + (relMaxX - relMinX + 1) + "x" + (relMaxY - relMinY + 1) + "x" + (relMaxZ - relMinZ + 1) + ")");
            return true;
        } catch (Throwable t) {
            log.warning("Схематика трапки не загрузилась: " + t.getMessage());
            clipboard = null;
            return false;
        }
    }

    public int relMinX() { return relMinX; }
    public int relMinY() { return relMinY; }
    public int relMinZ() { return relMinZ; }
    public int relMaxX() { return relMaxX; }
    public int relMaxY() { return relMaxY; }
    public int relMaxZ() { return relMaxZ; }
    public List<int[]> nonAirCells() { return nonAirCells; }

    /** Вставить схематику так, чтобы её центр (точка //copy) оказался в loc. */
    public boolean paste(Location loc) {
        if (!hasSchematic() || loc == null || loc.getWorld() == null) {
            return false;
        }
        Object session = null;
        try {
            Object weWorld = adaptWorld.invoke(null, loc.getWorld());
            // EditSession: newEditSession(world) или builder (разные версии WorldEdit)
            try {
                Method newSession = null;
                for (Method m : worldEdit.getClass().getMethods()) {
                    if (m.getName().equals("newEditSession") && m.getParameterCount() == 1) {
                        newSession = m;
                        break;
                    }
                }
                if (newSession != null) {
                    session = newSession.invoke(worldEdit, weWorld);
                }
            } catch (Throwable ignored) {
            }
            if (session == null) {
                Object builder = worldEdit.getClass().getMethod("createEditSessionBuilder").invoke(worldEdit);
                for (Method m : builder.getClass().getMethods()) {
                    if (m.getName().equals("world") && m.getParameterCount() == 1) {
                        m.invoke(builder, weWorld);
                        break;
                    }
                }
                session = builder.getClass().getMethod("build").invoke(builder);
            }
            Class<?> holderClass = Class.forName("com.sk89q.worldedit.session.ClipboardHolder", true, cl);
            Object holder = holderClass.getConstructor(Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard", true, cl))
                    .newInstance(clipboard);
            Object pasteBuilder = holder.getClass().getMethod("createPaste", session.getClass()).invoke(holder, session);
            // createPaste может вернуть билдер, чей метод to() принимает BlockVector3
            Object at = vectorAt.invoke(null, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Object builder2 = pasteBuilder;
            for (Method m : pasteBuilder.getClass().getMethods()) {
                if (m.getName().equals("to") && m.getParameterCount() == 1) {
                    builder2 = m.invoke(pasteBuilder, at);
                    break;
                }
            }
            Object operation = builder2.getClass().getMethod("build").invoke(builder2);
            Class<?> ops = Class.forName("com.sk89q.worldedit.function.operation.Operations", true, cl);
            ops.getMethod("complete", Class.forName("com.sk89q.worldedit.function.operation.Operation", true, cl))
                    .invoke(null, operation);
            return true;
        } catch (Throwable t) {
            log.warning("Вставка схематики трапки не удалась: " + t.getMessage());
            return false;
        } finally {
            if (session != null) {
                try {
                    session.getClass().getMethod("close").invoke(session);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
