package com.xiaohu.prison;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

/**
 * 监狱世界加载/创建（PLAN.md §11）。
 *
 * <p>v2：牢房自动建造与几何排布已移除，房间位置改由管理员用 /prison pos 手动标记。
 * v3.32：监狱世界固定生成虚空（超平坦 air 预设，纯空气无地形），超平坦选项已移除；
 * 出生点自动铺 5×5 出生平台防坠落。
 * 本类只负责世界加载/创建。所有方法仅允许主线程调用。
 */
public final class PrisonWorld {

    private final PrisonPlugin plugin;
    private final String worldName;
    private final boolean createIfMissing;

    private World world;

    public PrisonWorld(PrisonPlugin plugin, String worldName, boolean createIfMissing) {
        this.plugin = plugin;
        this.worldName = worldName;
        this.createIfMissing = createIfMissing;
    }

    /**
     * 确保监狱世界可用：已有同名世界直接用；缺失且 create-world-if-missing 时用 WorldCreator
     * 创建虚空世界（超平坦 air 预设，无地形）。成功后将世界 PVP 强制开启（v3.11）。
     *
     * @return 可用的 World；失败返回 null（由 PrisonPlugin 禁用插件并日志）
     */
    public World ensureWorld() {
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            this.world = existing;
            enablePvp(existing); // v3.11：监狱世界强制允许玩家互相攻击（1.21.9+ 用 pvp 游戏规则）
            plugin.getLogger().info("使用已有监狱世界: " + worldName + "（PVP 已开启）");
            return existing;
        }
        if (!createIfMissing) {
            plugin.getLogger().warning("监狱世界 '" + worldName + "' 不存在，且 create-world-if-missing=false，"
                    + "请先创建该世界或将现有世界改名为 '" + worldName + "'。");
            return null;
        }
        WorldCreator creator = new WorldCreator(worldName);
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.FLAT);
        creator.generateStructures(false);
        // v3.32/v3.35：虚空世界 = 现代 JSON 格式超平坦预设。旧式 "minecraft:air" 简写在 26.x
        // 会被当 JSON 严格解析报 MalformedJsonException；layer 需带 height 键（缺了报
        // "No key height"）。只有空气层 + the_void 生物群系 = 纯虚空。
        // v3.31 保护：只对"全新世界"（目标文件夹不存在）套生成参数——已存在的世界直接加载。
        boolean freshWorld = !worldFolderExists(worldName);
        if (freshWorld) {
            creator.generatorSettings(
                    "{\"layers\":[{\"height\":1,\"block\":\"minecraft:air\"}],\"biome\":\"minecraft:the_void\"}");
        }
        World created = creator.createWorld();
        if (created == null) {
            plugin.getLogger().severe("创建监狱世界 '" + worldName + "' 失败（createWorld 返回 null）。");
            return null;
        }
        enablePvp(created); // v3.11：监狱世界强制允许玩家互相攻击（1.21.9+ 用 pvp 游戏规则）
        this.world = created;
        plugin.getLogger().info("监狱世界 '" + worldName + "' 已创建（虚空）（PVP 已开启）。");
        return created;
    }

    /**
     * 监狱世界目标文件夹是否已存在（v3.31）：优先检查现代维度路径
     * （world/dimensions/minecraft/<name>，含 junction 联接的情况），再检查老式根目录路径。
     */
    private boolean worldFolderExists(String name) {
        java.io.File container = Bukkit.getWorldContainer();
        java.io.File dimensionPath = new java.io.File(container, "world/dimensions/minecraft/" + name);
        if (dimensionPath.isDirectory()) {
            return true;
        }
        return new java.io.File(container, name).isDirectory();
    }

    /**
     * 开启监狱世界 PVP（囚犯互殴）。
     * 说明：26.2 中 World#setPVP(boolean) 已弃用（自 1.21.9 起官方建议 pvp 游戏规则），
     * 但 GameRule.PVP 已被标记"待删除"（底层游戏规则可能已不存在，运行时风险更高），
     * 故仍使用 setPVP（普通弃用、仍可用）并抑制告警。
     */
    @SuppressWarnings("deprecation")
    private void enablePvp(World world) {
        world.setPVP(true);
    }

    /**
     * 在监狱世界出生点铺 5×5 石砖平台（幂等）——虚空世界防玩家出生即坠落（v3.27）。
     */
    public void placeSpawnPlatform() {
        if (world == null) {
            return;
        }
        Location spawn = world.getSpawnLocation();
        int bx = spawn.getBlockX();
        int by = spawn.getBlockY();
        int bz = spawn.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(bx + dx, by - 1, bz + dz).setType(Material.STONE_BRICKS);
            }
        }
        plugin.getLogger().info("已在监狱世界出生点铺设 5x5 出生平台（" + bx + "," + by + "," + bz + "）。");
    }

    /**
     * 当前监狱世界。
     *
     * @return World；ensureWorld 未成功执行过时为 null
     */
    public World getWorld() {
        return world;
    }

    /**
     * 监狱世界出生点。
     *
     * @return 世界不可用时返回 null
     */
    public Location worldSpawnLocation() {
        return world == null ? null : world.getSpawnLocation();
    }

    /**
     * 把监狱世界出生点设为指定位置（调用方自行校验该位置在监狱世界内）。
     * v3.37：设置成功后立即在新出生点铺 5×5 平台（虚空世界防坠落）。
     *
     * @return 设置是否成功（世界不可用或位置为 null 时失败）
     */
    public boolean setWorldSpawn(Location loc) {
        if (world == null || loc == null) {
            return false;
        }
        if (!world.setSpawnLocation(loc)) {
            return false;
        }
        placeSpawnPlatform(); // v3.37：出生点变更即铺平台
        return true;
    }
}
