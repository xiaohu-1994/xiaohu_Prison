package com.xiaohu.prison;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * 黑曜石矿场（v3.16）：管理员用 /prison obsidian 在脚下生成 size×size×size 黑曜石立方体并注册到
 * mines.yml（getDataFolder()/mines.yml），每隔 refresh-seconds 秒自动补满被挖掉的黑曜石。
 *
 * <p>刷新只补缺失方块（已是黑曜石的跳过），并跳过"玩家周围 2 格"内的缺口——立方体矿场内
 * 挖矿的囚犯不会被补块封死在黑曜石里（防窒息）。
 * 所有方法仅允许主线程调用（同步读写 + 同步 runTaskTimer）。
 */
public final class PrisonMines {

    /** 矿场记录：id 自增编号；x/y/z 为边长 size 立方体的最小角方块坐标（三个方向都是 size）。 */
    public record Mine(int id, String worldName, int x, int y, int z, int size) {

        @Override
        public String toString() {
            return "#" + id + " (" + worldName + " @ " + x + "," + y + "," + z + ", " + size + "x" + size + "x" + size + ")";
        }
    }

    private final PrisonPlugin plugin;
    private final String prisonWorldName;
    private final int defaultSize;
    private final long refreshTicks;
    /** 刷新间隔毫秒（refreshTicks × 50ms/tick）。 */
    private final long refreshMillis;
    private final File dataFile;
    /** 矿场 id → 矿场（LinkedHashMap 保证 list 输出顺序稳定）。 */
    private final Map<Integer, Mine> mines = new LinkedHashMap<>();
    private int nextId = 1;
    private BukkitTask refreshTask;
    /** 下一次自动刷新的 epoch 毫秒（BossBar 倒计时用，v3.17）。 */
    private long nextRefreshAt;
    /** 监狱长 BossBar（紫色末影龙样式）：进监狱世界的管理员显示黑曜石刷新倒计时。 */
    private BossBar mineBossBar;
    private BukkitTask bossBarTask;

    public PrisonMines(PrisonPlugin plugin, String prisonWorldName, int defaultSize, int refreshSeconds) {
        this.plugin = plugin;
        this.prisonWorldName = prisonWorldName;
        this.defaultSize = Math.max(1, defaultSize);
        // 至少 20 tick（1 秒），防配置为 0 时任务疯狂触发
        this.refreshTicks = Math.max(20L, (long) refreshSeconds * 20L);
        this.refreshMillis = this.refreshTicks * 50L;
        this.dataFile = new File(plugin.getDataFolder(), "mines.yml");
    }

    /**
     * 从 mines.yml 加载矿场。单条损坏跳过并 warn，不因坏数据拒绝加载整个插件。
     */
    public void load() {
        mines.clear();
        nextId = 1;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = cfg.getConfigurationSection("mines");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int id;
                try {
                    id = Integer.parseInt(key);
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("跳过损坏的矿场记录 '" + key + "'：编号不是整数。");
                    continue;
                }
                String path = "mines." + key;
                String worldName = cfg.getString(path + ".world");
                int x = cfg.getInt(path + ".x");
                int y = cfg.getInt(path + ".y");
                int z = cfg.getInt(path + ".z");
                int size = cfg.getInt(path + ".size", defaultSize);
                if (worldName == null || size < 1) {
                    plugin.getLogger().warning("跳过损坏的矿场记录 '" + key + "'：字段缺失或 size 非法。");
                    continue;
                }
                mines.put(id, new Mine(id, worldName, x, y, z, size));
                if (id >= nextId) {
                    nextId = id + 1;
                }
            }
        }
        plugin.getLogger().info("已加载 " + mines.size() + " 个黑曜石矿场。");
    }

    /** 全量落盘 mines.yml（同步主线程，变更即保存，onDisable 兜底再存一次）。 */
    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Mine mine : mines.values()) {
            String path = "mines." + mine.id();
            cfg.set(path + ".world", mine.worldName());
            cfg.set(path + ".x", mine.x());
            cfg.set(path + ".y", mine.y());
            cfg.set(path + ".z", mine.z());
            cfg.set(path + ".size", mine.size());
        }
        try {
            cfg.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 mines.yml 失败: " + ex.getMessage());
        }
    }

    /**
     * 在管理员脚下生成并注册矿场（v3.16 立方体 + v3.34 脚下锚定）：
     * size×size×size 黑曜石立方体，顶面紧贴管理员脚底（y ∈ [feetY-size, feetY-1]），
     * 水平以管理员为中心。
     * 虚空世界没有地形，getHighestBlockAt 会返回世界底部——所以固定以管理员脚下为准，
     * 生成位置就是管理员站立/悬浮的位置（生成在脚下，向下 15 层）。
     * 调用方需保证管理员在监狱世界内（本类以 prisonWorldName 记录矿场世界）。
     *
     * @param center 管理员站立位置
     * @return 新生成的矿场记录
     */
    public Mine add(Location center) {
        int size = defaultSize;
        int half = size / 2;
        Mine mine = new Mine(nextId++, prisonWorldName,
                center.getBlockX() - half,
                center.getBlockY() - size,
                center.getBlockZ() - half,
                size);
        mines.put(mine.id(), mine);
        fill(mine, Material.OBSIDIAN);
        save();
        return mine;
    }

    /**
     * 删除矿场：移除注册并把区域内方块清空为空气。
     *
     * @return 是否存在该矿场（不存在返回 false，不做任何变更）
     */
    public boolean remove(int id) {
        Mine mine = mines.remove(id);
        if (mine == null) {
            return false;
        }
        fill(mine, Material.AIR);
        save();
        return true;
    }

    /** 矿场是否存在。 */
    public boolean contains(int id) {
        return mines.containsKey(id);
    }

    /** 矿场只读列表（obsidian list 用）。 */
    public List<Mine> list() {
        return new ArrayList<>(mines.values());
    }

    /** 矿场数量。 */
    public int size() {
        return mines.size();
    }

    /**
     * 第一个矿场顶面中央的安全站位点（v3.30 囚犯房间缺失时的兜底位置，
     * 禁止把囚犯传送到世界出生点——出生点只属于管理员）。
     *
     * @return 顶面站位；无矿场或世界未加载返回 null
     */
    public Location firstMineTopLocation() {
        for (Mine mine : mines.values()) {
            World world = Bukkit.getWorld(mine.worldName());
            if (world == null) {
                continue;
            }
            return new Location(world,
                    mine.x() + mine.size() / 2.0 + 0.5,
                    mine.y() + mine.size(),
                    mine.z() + mine.size() / 2.0 + 0.5);
        }
        return null;
    }

    /**
     * 立即补满所有矿场被挖掉的黑曜石。
     * v3.19：刷新前先把"仍在矿体内部"的玩家传送到矿体顶面（安全位置），再补块——
     * 防止把挖洞的囚犯封死在黑曜石立方体里（即使周围 2 格留空也会被困住无路可出）。
     */
    public void refreshAll() {
        for (Mine mine : mines.values()) {
            World world = Bukkit.getWorld(mine.worldName());
            if (world == null) {
                continue;
            }
            ejectPlayersInside(world, mine);
            fillMissing(world, mine);
        }
    }

    /** 把位于矿体立方体内部的玩家传送到矿体顶面中央（安全位置）。 */
    private void ejectPlayersInside(World world, Mine mine) {
        int minX = mine.x(), minY = mine.y(), minZ = mine.z();
        int maxX = minX + mine.size() - 1;
        int maxY = minY + mine.size() - 1;
        int maxZ = minZ + mine.size() - 1;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(world)) {
                continue;
            }
            // v3.38：管理员免疫——矿场刷新传送不作用于管理员（补块时会为其周围 2 格留空，可自行挖出）
            if (p.isOp() || p.hasPermission("prison.admin")) {
                continue;
            }
            Location loc = p.getLocation();
            int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
            if (bx < minX || bx > maxX || by < minY || by > maxY || bz < minZ || bz > maxZ) {
                continue;
            }
            // 安全位置：矿体顶面中央上方一格（站在矿体顶上）
            Location safe = new Location(world,
                    minX + mine.size() / 2.0 + 0.5,
                    maxY + 1.0,
                    minZ + mine.size() / 2.0 + 0.5);
            p.teleport(safe);
            p.sendMessage("§c黑曜石矿场即将刷新，你已被传送到安全位置！");
        }
    }

    /** 把矿场区域全部铺成指定材质（生成/清空共用；v3.16 三维立方体）。 */
    private void fill(Mine mine, Material mat) {
        World world = Bukkit.getWorld(mine.worldName());
        if (world == null) {
            return;
        }
        for (int dx = 0; dx < mine.size(); dx++) {
            for (int dy = 0; dy < mine.size(); dy++) {
                for (int dz = 0; dz < mine.size(); dz++) {
                    world.getBlockAt(mine.x() + dx, mine.y() + dy, mine.z() + dz).setType(mat);
                }
            }
        }
    }

    /**
     * 只补缺失方块（!= OBSIDIAN）；跳过玩家周围 2 格（切比雪夫距离）内的缺口——
     * 立方体矿场内挖洞的囚犯不会被补块封死窒息。
     */
    private void fillMissing(World world, Mine mine) {
        List<Block> playerBlocks = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            // v3.38：只收集本矿场世界内的玩家（跨世界坐标永远不会命中，纯浪费）
            if (!p.getWorld().equals(world)) {
                continue;
            }
            playerBlocks.add(p.getLocation().getBlock());
        }
        for (int dx = 0; dx < mine.size(); dx++) {
            for (int dy = 0; dy < mine.size(); dy++) {
                for (int dz = 0; dz < mine.size(); dz++) {
                    Block block = world.getBlockAt(mine.x() + dx, mine.y() + dy, mine.z() + dz);
                    if (block.getType() == Material.OBSIDIAN) {
                        continue;
                    }
                    if (nearPlayer(block, playerBlocks)) {
                        continue; // 玩家附近留空，防闷死
                    }
                    block.setType(Material.OBSIDIAN);
                }
            }
        }
    }

    /** 方块是否在任一在线玩家周围 2 格内（切比雪夫距离）。 */
    private boolean nearPlayer(Block block, List<Block> playerBlocks) {
        for (Block p : playerBlocks) {
            if (Math.abs(p.getX() - block.getX()) <= 2
                    && Math.abs(p.getY() - block.getY()) <= 2
                    && Math.abs(p.getZ() - block.getZ()) <= 2) {
                return true;
            }
        }
        return false;
    }

    /** 启动自动刷新循环（每隔 refreshTicks 补满一次）；幂等。 */
    public void startRefreshTask() {
        if (refreshTask != null) {
            return;
        }
        nextRefreshAt = System.currentTimeMillis() + refreshMillis;
        refreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                refreshAll();
                nextRefreshAt = System.currentTimeMillis() + refreshMillis;
            }
        }.runTaskTimer(plugin, refreshTicks, refreshTicks);
    }

    /**
     * 启动监狱长 BossBar 倒计时任务（v3.17 + v3.36 全员可见）：每秒刷新紫色血条——
     * 显示离下次黑曜石补满的剩余时间；监狱世界内的所有玩家（管理员与囚犯）都显示；
     * 没有矿场时隐藏。幂等。
     */
    public void startBossBarTask() {
        if (bossBarTask != null) {
            return;
        }
        if (mineBossBar == null) {
            mineBossBar = Bukkit.createBossBar("黑曜石矿场刷新", BarColor.PURPLE, BarStyle.SOLID);
            mineBossBar.setVisible(false);
        }
        bossBarTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateBossBar();
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /** 刷新 BossBar：进度 = 剩余时间/总间隔；给"监狱世界内的所有玩家"显示，其余移除。 */
    private void updateBossBar() {
        boolean hasMines = !mines.isEmpty();
        long remaining = Math.max(0L, nextRefreshAt - System.currentTimeMillis());
        double progress = refreshMillis <= 0L ? 0.0
                : Math.max(0.0, Math.min(1.0, (double) remaining / (double) refreshMillis));
        mineBossBar.setTitle("§d黑曜石矿场刷新: " + formatCountdown(remaining));
        mineBossBar.setProgress(progress);

        Set<Player> shown = new HashSet<>();
        if (hasMines) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                // v3.36：监狱世界内所有玩家（管理员 + 囚犯）都看得到刷新倒计时
                if (p.getWorld().getName().equals(prisonWorldName)) {
                    shown.add(p);
                }
            }
        }
        for (Player p : mineBossBar.getPlayers()) {
            if (!shown.contains(p)) {
                mineBossBar.removePlayer(p);
            }
        }
        for (Player p : shown) {
            if (!mineBossBar.getPlayers().contains(p)) {
                mineBossBar.addPlayer(p);
            }
        }
        mineBossBar.setVisible(!shown.isEmpty());
    }

    /** 倒计时格式化：mm:ss；超过 1 小时显示 h:mm:ss。 */
    private static String formatCountdown(long millis) {
        long totalSecs = millis / 1000L;
        long hours = totalSecs / 3600L;
        long minutes = (totalSecs % 3600L) / 60L;
        long secs = totalSecs % 60L;
        return hours > 0L
                ? String.format("%d:%02d:%02d", hours, minutes, secs)
                : String.format("%02d:%02d", minutes, secs);
    }

    /** 取消自动刷新任务（onDisable 必须调用，防热重载残留）。 */
    public void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    /** 取消 BossBar 任务并清空观众（onDisable 必须调用）。 */
    public void stopBossBarTask() {
        if (bossBarTask != null) {
            bossBarTask.cancel();
            bossBarTask = null;
        }
        if (mineBossBar != null) {
            mineBossBar.removeAll();
        }
    }
}
