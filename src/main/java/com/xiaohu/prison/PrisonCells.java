package com.xiaohu.prison;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 房间注册表（v2）：管理员手动标记的房间位置，持久化于 getDataFolder()/cells.yml（PLAN.md §11）。
 *
 * <p>schema：
 * <pre>
 * cells:
 *   1:
 *     world: prison
 *     x: 0.5
 *     y: 65.0
 *     z: 0.5
 *     yaw: 0.0
 *     pitch: 0.0
 * </pre>
 * 房间编号即 Prisoner.cellIndex（语义 = 房间编号）。所有方法仅允许主线程调用（同步读写）。
 */
public final class PrisonCells {

    /**
     * 房间位置记录：仅存原始字段；world 字段用于与监狱世界名的一致性校验，
     * 实际传送时用 {@link #cellLocation(int)} 解析到当前加载的监狱 World。
     */
    public record CellLocation(String worldName, double x, double y, double z, float yaw, float pitch) {

        @Override
        public String toString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }
    }

    private final PrisonPlugin plugin;
    private final String prisonWorldName;
    private final File dataFile;
    /** 房间编号 → 房间位置（LinkedHashMap 保证 pos list 输出顺序稳定）。 */
    private final Map<Integer, CellLocation> cells = new LinkedHashMap<>();

    public PrisonCells(PrisonPlugin plugin, String prisonWorldName) {
        this.plugin = plugin;
        this.prisonWorldName = prisonWorldName;
        this.dataFile = new File(plugin.getDataFolder(), "cells.yml");
    }

    /** 监狱世界名（/prison pos 校验所在世界、重生点回退等用）。 */
    public String getPrisonWorldName() {
        return prisonWorldName;
    }

    /**
     * 插件 logger 透传（指令层记录警告日志用；PrisonCommand 构造函数不直接持有 PrisonPlugin）。
     */
    public Logger getLogger() {
        return plugin.getLogger();
    }

    /**
     * 从 cells.yml 加载房间记录。编号非法/字段缺失的条目跳过并 warn，不因坏数据拒绝加载。
     */
    public void load() {
        cells.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = cfg.getConfigurationSection("cells");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int n;
                try {
                    n = Integer.parseInt(key);
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("跳过损坏的房间记录 '" + key + "'：编号不是整数。");
                    continue;
                }
                if (n < 1) {
                    plugin.getLogger().warning("跳过损坏的房间记录 '" + key + "'：编号必须 >= 1。");
                    continue;
                }
                String path = "cells." + key;
                if (!cfg.contains(path + ".world") || !cfg.contains(path + ".x")
                        || !cfg.contains(path + ".y") || !cfg.contains(path + ".z")) {
                    plugin.getLogger().warning("跳过损坏的房间记录 '" + key + "'：字段缺失。");
                    continue;
                }
                String worldName = cfg.getString(path + ".world");
                double x = cfg.getDouble(path + ".x");
                double y = cfg.getDouble(path + ".y");
                double z = cfg.getDouble(path + ".z");
                float yaw = (float) cfg.getDouble(path + ".yaw", 0.0);
                float pitch = (float) cfg.getDouble(path + ".pitch", 0.0);
                cells.put(n, new CellLocation(worldName, x, y, z, yaw, pitch));
            }
        }
        plugin.getLogger().info("已加载 " + cells.size() + " 个监狱房间。");
    }

    /**
     * 全量落盘 cells.yml（同步主线程；mark 即落盘，onDisable 兜底再存一次）。
     */
    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<Integer, CellLocation> entry : cells.entrySet()) {
            CellLocation loc = entry.getValue();
            String path = "cells." + entry.getKey();
            cfg.set(path + ".world", loc.worldName());
            cfg.set(path + ".x", loc.x());
            cfg.set(path + ".y", loc.y());
            cfg.set(path + ".z", loc.z());
            cfg.set(path + ".yaw", loc.yaw());
            cfg.set(path + ".pitch", loc.pitch());
        }
        try {
            cfg.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 cells.yml 失败: " + ex.getMessage());
        }
    }

    /**
     * 标记房间 n 的位置（覆盖已存在记录）。调用方需保证 n >= 1（命令层已校验）。
     *
     * @param n   房间编号
     * @param loc 管理员站立位置（yaw/pitch 一并记录）
     */
    public void mark(int n, Location loc) {
        World world = loc.getWorld();
        cells.put(n, new CellLocation(
                world == null ? prisonWorldName : world.getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch()));
        save();
    }

    /**
     * 房间 n 的传送点。
     *
     * @return 房间位置（世界解析为当前加载的监狱 World）；编号不存在、存储世界与监狱世界名
     *         不一致、或监狱世界未加载时返回 null
     */
    public Location cellLocation(int n) {
        CellLocation loc = cells.get(n);
        if (loc == null) {
            return null;
        }
        if (!prisonWorldName.equals(loc.worldName())) {
            return null;
        }
        World world = Bukkit.getWorld(prisonWorldName);
        if (world == null) {
            return null;
        }
        return new Location(world, loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
    }

    /** 房间只读视图（pos list 用）。 */
    public Map<Integer, CellLocation> getCells() {
        return Collections.unmodifiableMap(cells);
    }

    /**
     * 下一个空余房间：已标记房间中编号最小且不在 occupied 中的房间。
     *
     * @param occupied 当前被占用（在狱玩家）的房间号集合
     * @return 空余房间编号；已标记房间全部被占用时返回 -1
     */
    public int nextFreeRoom(Collection<Integer> occupied) {
        for (int n : new TreeSet<>(cells.keySet())) {
            if (!occupied.contains(n)) {
                return n;
            }
        }
        return -1;
    }

    /** 最大房间编号；无任何已标记房间时返回 0。 */
    public int maxRoom() {
        return cells.isEmpty() ? 0 : Collections.max(cells.keySet());
    }

    /**
     * 删除房间 n 的记录并落盘。
     *
     * @return 是否存在该房间记录（不存在返回 false，不写盘）
     */
    public boolean remove(int n) {
        boolean existed = cells.remove(n) != null;
        if (existed) {
            save();
        }
        return existed;
    }
}
