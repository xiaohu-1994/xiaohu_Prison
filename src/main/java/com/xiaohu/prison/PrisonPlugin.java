package com.xiaohu.prison;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 监狱插件主类：生命周期（PLAN.md §3）。
 *
 * <p>onEnable：saveDefaultConfig → 读配置 → PrisonWorld.ensureWorld()（失败禁用插件并日志）
 * → PrisonCells.load() → 解析可破坏材质（mine.breakable-material）→ PrisonerManager.load()
 * → startUpkeepTask() → 注册 /prison 命令 executor+tabCompleter → 注册监听器。
 * <p>onDisable：stopUpkeepTask() + 保存 prisoners.yml / mines.yml / cells.yml。
 * <p>v3.14：任务 id 机制移除，/prison send <玩家> <数量> 直接指定挖矿数量，材质固定为可破坏材质。
 */
public final class PrisonPlugin extends JavaPlugin {

    private PrisonWorld prisonWorld;
    private PrisonCells prisonCells;
    private PrisonMines prisonMines;
    private PrisonerManager prisonerManager;
    private PrisonVisit prisonVisit;
    private CourtManager courtManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        // ---- 读取配置（PLAN.md §7），读入字段/传入组件 ----
        String worldName = getConfig().getString("world", "prison");
        String mainWorldName = getConfig().getString("main-world", "world");
        boolean createIfMissing = getConfig().getBoolean("create-world-if-missing", true);
        boolean spawnEnabled = getConfig().getBoolean("world-spawn.enabled", false);
        double spawnX = getConfig().getDouble("world-spawn.x", 0.0);
        double spawnY = getConfig().getDouble("world-spawn.y", 0.0);
        double spawnZ = getConfig().getDouble("world-spawn.z", 0.0);
        float spawnYaw = (float) getConfig().getDouble("world-spawn.yaw", 0.0);
        float spawnPitch = (float) getConfig().getDouble("world-spawn.pitch", 0.0);
        int mineSize = Math.max(1, getConfig().getInt("mine.size", 15));
        int mineRefreshSeconds = Math.max(1, getConfig().getInt("mine.refresh-seconds", 120));
        // 可破坏材质（= 囚犯唯一能挖的方块 = /prison send 的任务材质），默认黑曜石
        Material breakableMaterial = Material.OBSIDIAN;
        try {
            breakableMaterial = Material.valueOf(
                    getConfig().getString("mine.breakable-material", "OBSIDIAN").toUpperCase());
        } catch (IllegalArgumentException ex) {
            getLogger().warning("mine.breakable-material 配置无效（"
                    + getConfig().getString("mine.breakable-material") + "），使用默认黑曜石。");
        }
        String breakableName = PrisonTasks.displayName(breakableMaterial);

        // ---- 监狱世界（v3.32 固定虚空）：失败则禁用插件并日志 ----
        prisonWorld = new PrisonWorld(this, worldName, createIfMissing);
        World world = prisonWorld.ensureWorld();
        if (world == null) {
            getLogger().severe("监狱世界初始化失败，插件将被禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // v3.27：配置了出生点则每次启动强制设定；虚空世界在出生点铺平台防坠落
        if (spawnEnabled) {
            prisonWorld.setWorldSpawn(new Location(world, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch));
            getLogger().info("已按配置设定监狱世界出生点: " + spawnX + "," + spawnY + "," + spawnZ);
        }
        prisonWorld.placeSpawnPlatform();

        // ---- 房间注册表（手动标记的房间位置）----
        prisonCells = new PrisonCells(this, worldName);
        prisonCells.load();

        // ---- 黑曜石矿场（v3.3：/prison obsidian 生成，自动补满）----
        prisonMines = new PrisonMines(this, worldName, mineSize, mineRefreshSeconds);
        prisonMines.load();
        prisonMines.startRefreshTask();
        prisonMines.startBossBarTask(); // v3.17：监狱长刷新倒计时血条

        // ---- 在狱玩家状态（v3：任务进度制，无倒计时）----
        prisonerManager = new PrisonerManager(this, prisonCells, breakableMaterial.name());
        prisonerManager.load();
        prisonerManager.startUpkeepTask();

        // ---- 监狱参观（v4.2：/prison visit，普通玩家可进监狱参观）----
        prisonVisit = new PrisonVisit(this, worldName);
        prisonVisit.load();

        // ---- 命令 ----
        PrisonCommand command = new PrisonCommand(prisonerManager, prisonCells, prisonWorld, prisonMines,
                prisonVisit, breakableMaterial.name(), breakableName, mainWorldName);
        PluginCommand prisonCommand = getCommand("prison");
        if (prisonCommand != null) {
            prisonCommand.setExecutor(command);
            prisonCommand.setTabCompleter(command);
        } else {
            getLogger().warning("plugin.yml 未声明 prison 命令，/prison 将不可用。");
        }

        // ---- 事件 ----
        getServer().getPluginManager().registerEvents(
                new PrisonerListener(prisonerManager, prisonCells, prisonWorld, prisonMines, prisonVisit,
                        breakableMaterial, breakableName), this);

        // ---- 人民法院（v4.x，独立配置文件 court.yml，不修改 config.yml）----
        saveResource("court.yml", false);
        courtManager = new CourtManager(this, prisonerManager, breakableMaterial.name(), breakableName, worldName);
        courtManager.load();
        if (courtManager.isEnabled()) {
            CourtCommand courtCommand = new CourtCommand(courtManager);
            PluginCommand courtCommandHandle = getCommand("court");
            if (courtCommandHandle != null) {
                courtCommandHandle.setExecutor(courtCommand);
                courtCommandHandle.setTabCompleter(courtCommand);
            } else {
                getLogger().warning("plugin.yml 未声明 court 命令，/court 将不可用。");
            }
            getServer().getPluginManager().registerEvents(new CourtListener(this, courtManager), this);
        } else {
            getLogger().info("人民法院模块未启用（court.yml enabled=false），跳过注册。");
        }

        getLogger().info("监狱插件已启用（监狱世界: " + world.getName() + "，可破坏材质: " + breakableName
                + "，已标记房间: " + prisonCells.getCells().size() + " 间，在狱玩家: "
                + prisonerManager.getPrisoners().size() + "，矿场: " + prisonMines.size() + " 个，参观点: "
                + (prisonVisit.isMarked() ? "已标记" : "未标记") + "）。");
    }

    @Override
    public void onDisable() {
        if (prisonerManager != null) {
            prisonerManager.stopUpkeepTask();
            prisonerManager.save();
            prisonerManager.savePendingRestores(); // v3.38：离线释放暂存兜底落盘
        }
        if (prisonMines != null) {
            prisonMines.stopBossBarTask();
            prisonMines.stopRefreshTask();
            prisonMines.save();
        }
        if (prisonCells != null) {
            prisonCells.save();
        }
        if (prisonVisit != null) {
            prisonVisit.save();
        }
        if (courtManager != null) {
            courtManager.shutdown();
        }
        getLogger().info("监狱插件已禁用。");
    }
}
