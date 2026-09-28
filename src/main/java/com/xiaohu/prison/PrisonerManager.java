package com.xiaohu.prison;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 在狱玩家状态管理：内存 Map + prisoners.yml 持久化 + 任务进度制（v3，无倒计时）。
 *
 * <p>v3：倒计时机制整体删除（release-at / countdown 任务移除）。刑期 = 在监狱世界挖指定
 * 材质 N 个（任务由管理员用 /prison send <玩家> <任务id> 指定），挖够自动释放（传回原位置）。
 * 囚犯不能用任何指令，进度通过 ActionBar 每 1 秒（20 tick）实时刷新展示。
 * prisoners.yml 位于 getDataFolder()/prisoners.yml，同步主线程读写（文件小、变更频率低）。
 * 所有方法仅允许主线程调用。
 */
public final class PrisonerManager {

    /**
     * 离线释放暂存（v3.38 P3 修复）：释放时玩家不在线，背包/效果/游戏模式/原位置暂存于
     * pending-restore.yml，玩家重进服时由 {@link #applyPendingRestore(Player)} 归还。
     */
    public record PendingRestore(
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            GameMode gameMode,
            Map<Integer, ItemStack> inventory,
            List<PotionEffect> effects,
            String message
    ) {
    }

    private final PrisonPlugin plugin;
    private final PrisonCells prisonCells;
    /** 可破坏材质枚举名（config mine.breakable-material；任务材质固定用它，v3.14）。 */
    private final String breakableMaterialName;
    private final File dataFile;
    /** 离线释放暂存文件（v3.38 P3 修复：pending-restore.yml）。 */
    private final File pendingRestoreFile;
    /** 离线释放暂存：UUID → 待归还快照（v3.38）。 */
    private final Map<UUID, PendingRestore> pendingRestores = new LinkedHashMap<>();
    /** 在狱玩家：UUID → Prisoner（LinkedHashMap 保证 list 输出顺序稳定）。 */
    private final Map<UUID, Prisoner> prisoners = new LinkedHashMap<>();
    /** 挖掘疲劳惩罚（v3.2）：config mining-fatigue.enabled，重复刷新期间玩家无法用牛奶清除。 */
    private final boolean miningFatigue;
    /** 挖掘疲劳等级（amplifier，0=I 级 … 4=V 级）。 */
    private final int miningFatigueLevel;
    /** 任务完成正在庆祝（放烟花）的玩家，庆祝期间不再累计进度、不重复触发（v3.10）。 */
    private final Set<UUID> celebrating = new HashSet<>();
    /** 烟花五颜六色调色板。 */
    private static final List<Color> FIREWORK_COLORS = List.of(
            Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.GREEN,
            Color.AQUA, Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.WHITE);
    private static final FireworkEffect.Type[] FIREWORK_TYPES = {
            FireworkEffect.Type.BURST, FireworkEffect.Type.STAR,
            FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE};
    private final Random random = new Random();
    private BukkitTask upkeepTask;

    public PrisonerManager(PrisonPlugin plugin, PrisonCells prisonCells,
                           String breakableMaterialName) {
        this.plugin = plugin;
        this.prisonCells = prisonCells;
        this.breakableMaterialName = breakableMaterialName;
        this.dataFile = new File(plugin.getDataFolder(), "prisoners.yml");
        this.pendingRestoreFile = new File(plugin.getDataFolder(), "pending-restore.yml");
        this.miningFatigue = plugin.getConfig().getBoolean("mining-fatigue.enabled", true);
        this.miningFatigueLevel = Math.max(0, plugin.getConfig().getInt("mining-fatigue.level", 4));
    }

    /**
     * 从 prisoners.yml 加载在狱记录。单条损坏跳过并 warn，不因坏数据拒绝加载整个插件。
     * 旧版（v2 倒计时）记录没有 task-key 字段 → 保留在狱状态、taskKey=null，warn 提示需手动释放。
     */
    public void load() {
        prisoners.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection section = cfg.getConfigurationSection("prisoners");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String path = "prisoners." + key;
                    String name = cfg.getString(path + ".name", "?");
                    String worldName = cfg.getString(path + ".world", "");
                    double x = cfg.getDouble(path + ".x", 0.0);
                    double y = cfg.getDouble(path + ".y", 0.0);
                    double z = cfg.getDouble(path + ".z", 0.0);
                    float yaw = (float) cfg.getDouble(path + ".yaw", 0.0);
                    float pitch = (float) cfg.getDouble(path + ".pitch", 0.0);
                    String taskKey = cfg.getString(path + ".task-key");
                    int taskTarget = cfg.getInt(path + ".task-target", 0);
                    int taskProgress = cfg.getInt(path + ".task-progress", 0);
                    int cell = cfg.getInt(path + ".cell", -1);
                    // v3.24 入狱前游戏模式（旧记录缺省 SURVIVAL）
                    GameMode gameMode = GameMode.SURVIVAL;
                    try {
                        gameMode = GameMode.valueOf(cfg.getString(path + ".gamemode", "SURVIVAL"));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("犯人记录 " + key + "（" + name + "）gamemode 非法，按 SURVIVAL 处理。");
                    }
                    // v3.25 入狱前背包快照（槽位 → ItemStack；旧记录为空 Map）
                    Map<Integer, ItemStack> invSnapshot = new LinkedHashMap<>();
                    ConfigurationSection invSec = cfg.getConfigurationSection(path + ".inventory");
                    if (invSec != null) {
                        for (String slotKey : invSec.getKeys(false)) {
                            ItemStack item = invSec.getItemStack(slotKey);
                            if (item == null) {
                                continue;
                            }
                            try {
                                invSnapshot.put(Integer.parseInt(slotKey), item);
                            } catch (NumberFormatException ex) {
                                // 忽略非法槽位键
                            }
                        }
                    }
                    // v3.25 入狱前药水效果快照（旧记录为空 List）
                    List<PotionEffect> effectsSnapshot = new java.util.ArrayList<>();
                    for (Object o : cfg.getList(path + ".effects", java.util.Collections.emptyList())) {
                        if (o instanceof Map<?, ?> raw) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> effectMap = (Map<String, Object>) raw;
                                effectsSnapshot.add(new PotionEffect(effectMap));
                            } catch (Exception ex) {
                                plugin.getLogger().warning("犯人记录 " + key + "（" + name + "）药水效果快照损坏，已跳过。");
                            }
                        }
                    }
                    // cell 语义 = 房间编号，必须 >= 1 才合法
                    if (cell < 1) {
                        plugin.getLogger().warning("跳过损坏的犯人记录 " + key + "：cell（房间编号）字段缺失或非法。");
                        continue;
                    }
                    if (taskKey == null) {
                        // v2 倒计时遗留记录：无任务，保留在狱，需管理员手动释放
                        plugin.getLogger().warning("犯人记录 " + key + "（" + name + "）为旧版倒计时记录（无 task-key 字段），"
                                + "已保留在狱状态，任务为空，需管理员手动释放：/prison release " + name);
                    } else if (!isValidMaterialName(taskKey)) {
                        // v3.14 迁移：旧 config 任务 id（如 mine-obsidian）已废弃 → 改用当前可破坏材质
                        plugin.getLogger().info("犯人记录 " + key + "（" + name + "）任务 id '" + taskKey
                                + "' 已过期，改用当前可破坏材质 " + breakableMaterialName + "（任务数量不变）。");
                        taskKey = breakableMaterialName;
                    }
                    if (taskKey != null && taskTarget < 1) {
                        // 任务目标非法：置为无任务，避免进度 >= 0 目标导致任何一次挖矿即触发释放
                        plugin.getLogger().warning("犯人记录 " + key + "（" + name + "）任务目标非法（task-target="
                                + taskTarget + "），已按无任务保留，需管理员手动释放。");
                        taskKey = null;
                        taskTarget = 0;
                        taskProgress = 0;
                    }
                    // v4.11 离线入狱标记：未暂扣（缺省 true，兼容旧记录——旧记录均为在线入狱快照齐全）
                    boolean confiscated = cfg.getBoolean(path + ".confiscated", true);
                    prisoners.put(uuid, new Prisoner(uuid, name, worldName, x, y, z, yaw, pitch,
                            taskKey, taskTarget, taskProgress, cell, gameMode, invSnapshot, effectsSnapshot,
                            confiscated));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("跳过损坏的犯人记录 " + key + "：" + ex.getMessage());
                }
            }
        }

        // v3.38（P4 修复）：任务进度已满但未完成释放的记录（上次服务器中断于庆祝期间）→ 自动释放。
        // 玩家此时必然离线（onEnable 阶段无人在线）→ 走 v3.38 离线暂存路径归还背包。
        List<UUID> completed = new java.util.ArrayList<>();
        for (Prisoner p : prisoners.values()) {
            if (p.isTaskComplete()) {
                completed.add(p.uuid());
            }
        }
        for (UUID uuid : completed) {
            Prisoner p = prisoners.get(uuid);
            plugin.getLogger().info("犯人 " + p.name() + "（" + uuid + "）任务进度已满但未完成释放"
                    + "（可能上次服务器中断），自动释放。");
            release(uuid, "§a任务完成！你已被释放，欢迎回来！（上次服务器中断导致释放延迟）");
        }

        plugin.getLogger().info("已加载 " + prisoners.size() + " 名在狱玩家。");

        loadPendingRestores(); // v3.38 P3 修复：离线释放暂存的背包记录
    }

    /**
     * 从 pending-restore.yml 加载待归还记录（v3.38 P3 修复）。
     * 单条损坏跳过并 warn，不因坏数据拒绝加载。
     */
    private void loadPendingRestores() {
        pendingRestores.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(pendingRestoreFile);
        ConfigurationSection section = cfg.getConfigurationSection("pending-restore");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String path = "pending-restore." + key;
                // 原位置字段（入狱前坐标，重进服时传送回去）
                String worldName = cfg.getString(path + ".world", "");
                double x = cfg.getDouble(path + ".x", 0.0);
                double y = cfg.getDouble(path + ".y", 0.0);
                double z = cfg.getDouble(path + ".z", 0.0);
                float yaw = (float) cfg.getDouble(path + ".yaw", 0.0);
                float pitch = (float) cfg.getDouble(path + ".pitch", 0.0);
                GameMode gameMode = GameMode.SURVIVAL;
                try {
                    gameMode = GameMode.valueOf(cfg.getString(path + ".gamemode", "SURVIVAL"));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("待归还记录 " + key + " gamemode 非法，按 SURVIVAL 处理。");
                }
                String message = cfg.getString(path + ".message");
                // 背包快照（槽位 → ItemStack）
                Map<Integer, ItemStack> inv = new LinkedHashMap<>();
                ConfigurationSection invSec = cfg.getConfigurationSection(path + ".inventory");
                if (invSec != null) {
                    for (String slotKey : invSec.getKeys(false)) {
                        ItemStack item = invSec.getItemStack(slotKey);
                        if (item == null) {
                            continue;
                        }
                        try {
                            inv.put(Integer.parseInt(slotKey), item);
                        } catch (NumberFormatException ex) {
                            // 忽略非法槽位键
                        }
                    }
                }
                // 药水效果快照
                List<PotionEffect> effects = new java.util.ArrayList<>();
                for (Object o : cfg.getList(path + ".effects", java.util.Collections.emptyList())) {
                    if (o instanceof Map<?, ?> raw) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> effectMap = (Map<String, Object>) raw;
                            effects.add(new PotionEffect(effectMap));
                        } catch (Exception ex) {
                            plugin.getLogger().warning("待归还记录 " + key + " 药水效果快照损坏，已跳过该条效果。");
                        }
                    }
                }
                pendingRestores.put(uuid, new PendingRestore(
                        worldName, x, y, z, yaw, pitch, gameMode, inv, effects, message));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("跳过损坏的待归还记录 " + key + "：" + ex.getMessage());
            }
        }
        if (!pendingRestores.isEmpty()) {
            plugin.getLogger().info("已加载 " + pendingRestores.size()
                    + " 条待归还背包记录（离线释放的玩家，重进服时归还）。");
        }
    }

    /** 全量落盘 pending-restore.yml（v3.38，变更即保存，onDisable 兜底再存一次）。 */
    public void savePendingRestores() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, PendingRestore> entry : pendingRestores.entrySet()) {
            PendingRestore pr = entry.getValue();
            String path = "pending-restore." + entry.getKey();
            cfg.set(path + ".world", pr.worldName());
            cfg.set(path + ".x", pr.x());
            cfg.set(path + ".y", pr.y());
            cfg.set(path + ".z", pr.z());
            cfg.set(path + ".yaw", pr.yaw());
            cfg.set(path + ".pitch", pr.pitch());
            cfg.set(path + ".gamemode", pr.gameMode().name());
            if (pr.message() != null) {
                cfg.set(path + ".message", pr.message());
            }
            for (Map.Entry<Integer, ItemStack> slotEntry : pr.inventory().entrySet()) {
                cfg.set(path + ".inventory." + slotEntry.getKey(), slotEntry.getValue());
            }
            List<Map<String, Object>> effectList = new java.util.ArrayList<>();
            for (PotionEffect effect : pr.effects()) {
                effectList.add(effect.serialize());
            }
            cfg.set(path + ".effects", effectList);
        }
        try {
            cfg.save(pendingRestoreFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 pending-restore.yml 失败: " + ex.getMessage());
        }
    }

    /**
     * 入狱统一路径（v3）：取编号最小的空余房间 → 记录原位置快照与任务 → 传送 → 落盘。
     * 已在狱则不做任何变更（返回现有记录；调用方应先 isImprisoned 提示）。
     *
     * @param taskKey    任务 id（必须已存在于 PrisonTasks，否则调用方应先校验）
     * @param taskTarget 任务目标数量（= PrisonTasks.TaskDef.amount）
     * @return 入狱后的 Prisoner 记录；无空余房间（已标记房间全部被占用）时返回 null
     */
    public Prisoner imprison(Player player, String taskKey, int taskTarget) {
        UUID uuid = player.getUniqueId();
        Prisoner existing = prisoners.get(uuid);
        if (existing != null) {
            return existing;
        }
        Collection<Integer> occupied = new HashSet<>();
        for (Prisoner p : prisoners.values()) {
            occupied.add(p.cellIndex());
        }
        int room = prisonCells.nextFreeRoom(occupied);
        if (room == -1) {
            return null; // 房间已满，由命令层提示
        }
        // v3.25 快照入狱前背包（全部槽位）与药水效果，入狱期间只保留监狱装备
        Map<Integer, ItemStack> invSnapshot = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                invSnapshot.put(slot, item.clone());
            }
        }
        List<PotionEffect> effectsSnapshot = new java.util.ArrayList<>(player.getActivePotionEffects());

        Prisoner prisoner = Prisoner.fromLocation(uuid, player.getName(), player.getLocation(),
                taskKey, taskTarget, room, player.getGameMode(), invSnapshot, effectsSnapshot);
        prisoners.put(uuid, prisoner);

        Location cell = prisonCells.cellLocation(room);
        if (cell != null) {
            player.teleport(cell);
        }
        // v3.24/25：清空背包与效果 → 强制生存 → 发放监狱装备
        player.getInventory().clear();
        for (PotionEffect effect : new java.util.ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setGameMode(GameMode.SURVIVAL);
        givePrisonGear(player);
        save();
        return prisoner;
    }

    /**
     * 离线入狱（v4.11，PLAN.md §20）：记录离线玩家为在狱状态，背包/位置延后到重进服时处理。
     * 已在狱则不做任何变更（返回现有记录；调用方应先 isImprisoned 提示）。
     *
     * @return 入狱后的 Prisoner 记录；无空余房间（已标记房间全部被占用）时返回 null
     */
    public Prisoner imprisonOffline(UUID uuid, String name, String taskKey, int taskTarget) {
        Prisoner existing = prisoners.get(uuid);
        if (existing != null) {
            return existing;
        }
        Collection<Integer> occupied = new HashSet<>();
        for (Prisoner p : prisoners.values()) {
            occupied.add(p.cellIndex());
        }
        int room = prisonCells.nextFreeRoom(occupied);
        if (room == -1) {
            return null; // 房间已满，由命令层提示
        }
        Prisoner prisoner = Prisoner.fromOffline(uuid, name, taskKey, taskTarget, room);
        prisoners.put(uuid, prisoner);
        save();
        plugin.getLogger().info("已把离线玩家 " + name + " 送入监狱（房间 #" + room + "，任务: 挖 "
                + taskTarget + " 个" + taskKey + "），重进服时自动收监。");
        return prisoner;
    }

    /**
     * 离线入狱玩家重进服的延后暂扣（v4.11）：记录未暂扣（confiscated=false）时——
     * 以此刻位置为"入狱前位置"、此刻背包/效果/游戏模式为快照写入记录，然后清空背包、
     * 强制生存、发放监狱装备。已在在线路径暂扣过的记录不做任何事。
     */
    public void confiscateOnJoin(Player player) {
        Prisoner p = prisoners.get(player.getUniqueId());
        if (p == null || p.confiscated()) {
            return;
        }
        Map<Integer, ItemStack> invSnapshot = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                invSnapshot.put(slot, item.clone());
            }
        }
        List<PotionEffect> effectsSnapshot = new java.util.ArrayList<>(player.getActivePotionEffects());
        Location loc = player.getLocation();
        World locWorld = loc.getWorld();
        Prisoner updated = new Prisoner(p.uuid(), p.name(),
                locWorld == null ? "" : locWorld.getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                p.taskKey(), p.taskTarget(), p.taskProgress(), p.cellIndex(),
                player.getGameMode(), invSnapshot, effectsSnapshot, true);
        prisoners.put(player.getUniqueId(), updated);
        player.getInventory().clear();
        for (PotionEffect effect : new java.util.ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setGameMode(GameMode.SURVIVAL);
        givePrisonGear(player);
        save();
        plugin.getLogger().info("离线入狱玩家 " + player.getName() + " 重进服，已暂扣背包并收监（房间 #"
                + updated.cellIndex() + "）。");
    }

    /**
     * 发放狱中装备（v3.6）：皮革套装（头盔/胸甲/护腿/靴子）+ 无法破坏的钻石镐（主手）
     * + 饱和效果（持续刷新由维护循环承担，这里先挂一个 5 秒）。
     * 入狱、重进服、死亡重生时调用；装备槽直接覆盖（原装备被替换）。
     */
    public void givePrisonGear(Player player) {
        player.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        player.getInventory().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
        player.getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));

        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        meta.setUnbreakable(true);
        // 无附魔（v3.21：移除效率 II，狱中镐子为纯不坏钻石镐）
        pickaxe.setItemMeta(meta);
        player.getInventory().setItemInMainHand(pickaxe);

        PotionEffectType saturation = Registry.EFFECT.get(NamespacedKey.minecraft("saturation"));
        if (saturation != null) {
            player.addPotionEffect(new PotionEffect(saturation, 100, 0, false, true, true));
        }
    }

    /** 是否在狱。 */
    public boolean isImprisoned(UUID uuid) {
        return prisoners.containsKey(uuid);
    }

    /** 获取在狱记录；不在狱返回 null。 */
    public Prisoner getPrisoner(UUID uuid) {
        return prisoners.get(uuid);
    }

    /** 在狱玩家只读视图（/prison list 用）。 */
    public Map<UUID, Prisoner> getPrisoners() {
        return Collections.unmodifiableMap(prisoners);
    }

    /**
     * 按玩家名查找在狱记录：先按存储名精确匹配（不区分大小写）；
     * 未命中时按 UUID 解析当前名再比一次（覆盖入狱后改名场景）。
     */
    public Prisoner findPrisonerByName(String name) {
        for (Prisoner p : prisoners.values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        for (Prisoner p : prisoners.values()) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(p.uuid());
            String current = offline.getName();
            if (current != null && current.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 直接设置任务进度（v3.11 PvP 败者扣半用）：无记录或无任务忽略；进度夹在 [0, target]。
     * 仅用于减少进度，不触发完成任务逻辑（增加进度走 addProgress）。
     */
    public void setProgress(UUID uuid, int progress) {
        Prisoner p = prisoners.get(uuid);
        if (p == null || !p.hasTask()) {
            return;
        }
        int clamped = Math.max(0, Math.min(progress, p.taskTarget()));
        prisoners.put(uuid, p.withProgress(clamped));
        save();
    }

    /**
     * 任务进度累计（v3）：无记录或无任务则忽略；进度达到目标 → 自动释放（传回原位置）。
     *
     * @param delta 本次增加的进度（挖到一个匹配方块 = 1）
     */
    public void addProgress(UUID uuid, int delta) {
        Prisoner p = prisoners.get(uuid);
        if (p == null || !p.hasTask()) {
            return;
        }
        // v3.38（P2 修复）：庆祝期间不累计进度、不重复触发（进度已封顶，直接忽略）
        if (celebrating.contains(uuid)) {
            return;
        }
        int progress = p.taskProgress() + delta;
        if (progress >= p.taskTarget()) {
            // v3.10 任务完成：记录进度封顶（ActionBar 显示 X/X）→ 四周放彩色烟花 5 秒 → 自动释放回原位置
            prisoners.put(uuid, p.withProgress(p.taskTarget()));
            save(); // v3.38（P4 修复）：进度封顶立即落盘，防庆祝期间服务器中断后状态丢失
            celebrating.add(uuid);
            startCelebration(uuid, p.name());
            return;
        }
        prisoners.put(uuid, p.withProgress(progress));
        save();
    }

    /**
     * 任务完成庆祝（v3.10 + v3.24 加固）：每 0.5 秒（10 tick）在玩家四周放 3 发随机彩色烟花，
     * 持续 5 秒（10 次）；结束后自动释放（传回入狱前坐标）并全服通报。
     * 整个循环包在 try/catch 里：烟花异常只记录、不中断，5 秒后必定释放。
     */
    private void startCelebration(UUID uuid, String name) {
        plugin.getLogger().info("玩家 " + name + " 任务完成，开始放烟花庆祝（5 秒后释放）。");
        final int[] count = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (++count[0] > 10) { // 10 次 × 10 tick = 5 秒
                        this.cancel();
                        celebrating.remove(uuid);
                        if (release(uuid, "§a任务完成！你已被释放，欢迎回来！")) {
                            plugin.getLogger().info("庆祝结束，玩家 " + name + " 已释放并传送回原位置。");
                            Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection().deserialize(
                                    "§e[监狱] §a" + name + " §f囚犯完成了任务回到正常主世界！"));
                        }
                        return;
                    }
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        spawnFireworks(player);
                    }
                } catch (Throwable t) {
                    // 烟花等异常只记录，不中断循环——5 秒后必定执行释放
                    plugin.getLogger().warning("任务完成庆祝异常（不影响释放）: " + t.getMessage());
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    /** 在玩家四周随机位置放 3 发五颜六色烟花。 */
    private void spawnFireworks(Player player) {
        World world = player.getWorld();
        for (int i = 0; i < 3; i++) {
            Location loc = player.getLocation().clone().add(
                    random.nextDouble() * 6.0 - 3.0,
                    random.nextDouble() * 2.0 + 1.0,
                    random.nextDouble() * 6.0 - 3.0);
            Firework firework = (Firework) world.spawnEntity(loc, EntityType.FIREWORK_ROCKET);
            FireworkMeta meta = firework.getFireworkMeta();
            Color color = FIREWORK_COLORS.get(random.nextInt(FIREWORK_COLORS.size()));
            Color fade = FIREWORK_COLORS.get(random.nextInt(FIREWORK_COLORS.size()));
            meta.addEffect(FireworkEffect.builder()
                    .with(FIREWORK_TYPES[random.nextInt(FIREWORK_TYPES.length)])
                    .withColor(color)
                    .withFade(fade)
                    .trail(random.nextBoolean())
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        }
    }

    /**
     * 任务进度文本（ActionBar / 提示 / 列表共用）：
     * "任务: 挖<中文名> <progress>/<target>"；taskKey 解析失败时中文名回退 taskKey 原文。
     * 无任务返回 null（调用方自行显示「无任务」）。
     */
    public String taskProgressText(Prisoner p) {
        if (!p.hasTask()) {
            return null;
        }
        Material mat = taskMaterial(p.taskKey());
        String matName = mat == null ? p.taskKey() : PrisonTasks.displayName(mat);
        return "§e任务: §a挖" + matName + " §f" + p.taskProgress() + "/" + p.taskTarget();
    }

    /** 任务材质枚举名是否合法（Material.valueOf 可解析）。 */
    private static boolean isValidMaterialName(String name) {
        try {
            Material.valueOf(name.toUpperCase());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 解析任务材质（BlockBreakEvent 匹配用）。taskKey 为材质枚举名；
     * 解析失败（旧版任务 id / 损坏记录）时回退到当前可破坏材质。
     */
    public Material taskMaterial(String taskKey) {
        if (taskKey == null || taskKey.isEmpty()) {
            return null;
        }
        if (isValidMaterialName(taskKey)) {
            return Material.valueOf(taskKey.toUpperCase());
        }
        try {
            return Material.valueOf(breakableMaterialName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 释放统一路径（手动 release 与任务完成共用）：移除记录 → 落盘 → 若在线传回原位置
     * （原世界缺失回默认世界出生点）。
     *
     * @return true 表示确有记录被释放
     */
    public boolean release(UUID uuid) {
        return release(uuid, null);
    }

    /**
     * 带提示的释放。
     *
     * @param message 非 null 时发送给被释放的在线玩家
     */
    public boolean release(UUID uuid, String message) {
        Prisoner prisoner = prisoners.remove(uuid);
        if (prisoner == null) {
            return false;
        }
        save();

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            restorePlayer(player, prisoner, message);
        } else if (prisoner.confiscated()) {
            // v3.38（P3 修复）：释放时玩家离线 → 快照暂存 pending-restore.yml，重进服时归还。
            // 原背包在入狱时已被清空，prisoners.yml 快照是唯一副本，绝不能随记录删除而丢失
            // （覆盖庆祝结束掉线、管理员离线释放两条路径）。
            pendingRestores.put(uuid, new PendingRestore(
                    prisoner.worldName(), prisoner.x(), prisoner.y(), prisoner.z(),
                    prisoner.yaw(), prisoner.pitch(), prisoner.gameMode(),
                    new LinkedHashMap<>(prisoner.inventorySnapshot()),
                    new java.util.ArrayList<>(prisoner.effectsSnapshot()),
                    message));
            savePendingRestores();
            plugin.getLogger().info("玩家 " + prisoner.name() + " 释放时不在线，"
                    + "背包快照已暂存（pending-restore.yml），重进服时自动归还。");
        } else {
            // v4.11：未暂扣记录（离线入狱且从未上线）→ 背包从未收走，无需暂存归还
            plugin.getLogger().info("玩家 " + prisoner.name() + "（离线入狱且从未上线，背包未暂扣）释放时不在线，无需归还。");
        }
        return true;
    }

    /** 在线释放的统一归还路径：传送回原位置（缺失回默认世界出生点）+ 恢复背包/效果/游戏模式 + 提示。 */
    private void restorePlayer(Player player, Prisoner prisoner, String message) {
        Location target = prisoner.originalLocation();
        if (target == null) {
            World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            target = defaultWorld == null ? null : defaultWorld.getSpawnLocation();
        }
        if (target != null) {
            player.teleport(target);
        }
        // v3.25：恢复入狱前的背包、药水效果与游戏模式（监狱装备/效果清掉）；
        // v4.11：未暂扣记录跳过——背包从未收走，空快照会把玩家背包清空
        if (prisoner.confiscated()) {
            player.getInventory().clear();
            for (Map.Entry<Integer, ItemStack> entry : prisoner.inventorySnapshot().entrySet()) {
                player.getInventory().setItem(entry.getKey(), entry.getValue());
            }
            for (PotionEffect effect : new java.util.ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            for (PotionEffect effect : prisoner.effectsSnapshot()) {
                player.addPotionEffect(effect);
            }
            player.setGameMode(prisoner.gameMode());
        }
        if (message != null) {
            player.sendMessage(message);
        }
    }

    /**
     * 归还离线释放玩家的背包（v3.38 P3 修复）：玩家重进服时由 PlayerJoinEvent 调用。
     * 恢复入狱前背包/效果/游戏模式，并传送回入狱前位置（原世界缺失回默认世界出生点）；
     * 归还完成后移除暂存记录并落盘。
     */
    public void applyPendingRestore(Player player) {
        PendingRestore pending = pendingRestores.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        savePendingRestores();
        player.getInventory().clear();
        for (Map.Entry<Integer, ItemStack> entry : pending.inventory().entrySet()) {
            player.getInventory().setItem(entry.getKey(), entry.getValue());
        }
        for (PotionEffect effect : new java.util.ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : pending.effects()) {
            player.addPotionEffect(effect);
        }
        player.setGameMode(pending.gameMode());
        World targetWorld = Bukkit.getWorld(pending.worldName());
        Location target = targetWorld == null ? null
                : new Location(targetWorld, pending.x(), pending.y(), pending.z(), pending.yaw(), pending.pitch());
        if (target == null && !Bukkit.getWorlds().isEmpty()) {
            target = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        if (target != null) {
            player.teleport(target);
        }
        player.sendMessage("§a你入狱前的背包已被保管，现已全部归还！");
        if (pending.message() != null) {
            player.sendMessage(pending.message());
        }
        plugin.getLogger().info("已归还玩家 " + player.getName() + " 入狱前背包（离线释放暂存记录已清除）。");
    }

    /**
     * 启动狱内玩家维护循环（v3.2）：每 1 秒（20 tick）——
     * 1) 给所有在线在狱玩家推送任务进度 ActionBar（囚犯不能用指令，ActionBar 是唯一实时进度通道）；
     * 2) 若启用挖掘疲劳（config mining-fatigue），重挂 Mining Fatigue 效果（5 秒时长，常驻）。
     * 幂等（重复调用无副作用）。
     */
    public void startUpkeepTask() {
        if (upkeepTask != null) {
            return;
        }
        upkeepTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // v3.26：监狱世界内任何人（含管理员）若被切成冒险模式 → 切回生存（创造不受影响）
                    if (player.getGameMode() == GameMode.ADVENTURE
                            && player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
                        player.setGameMode(GameMode.SURVIVAL);
                    }
                    // 管理员免疫（v3.5）：OP / prison.admin 不受监狱机制影响（无 ActionBar 进度、无挖掘疲劳）
                    if (player.isOp() || player.hasPermission("prison.admin")) {
                        continue;
                    }
                    Prisoner p = prisoners.get(player.getUniqueId());
                    if (p == null) {
                        continue;
                    }
                    // v3.24：囚犯强制生存模式（防止任何来源把囚犯切成冒险/创造导致无法挖黑曜石）
                    if (player.getGameMode() != GameMode.SURVIVAL) {
                        player.setGameMode(GameMode.SURVIVAL);
                    }
                    // v3.18：任务完成庆祝期间 ActionBar 显示庆祝文案
                    String text;
                    if (celebrating.contains(player.getUniqueId())) {
                        text = "§a§l任务完成！正在放烟花庆祝，即将出狱...";
                    } else {
                        text = p.hasTask() ? taskProgressText(p) : "§7无任务（需管理员释放）";
                    }
                    // v3.38（P1 修复）：ActionBar 消息含 § 颜色码，必须用 LegacyComponentSerializer
                    // 解析（Component.text 会把 § 码当字面量显示）
                    player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(text));
                    // v3.2 挖掘疲劳：每 1 秒重挂 5 秒效果，全程常驻（牛奶清除后 1 秒内恢复）
                    if (miningFatigue) {
                        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft("mining_fatigue"));
                        if (type != null) {
                            player.addPotionEffect(new PotionEffect(type, 100, miningFatigueLevel, false, true, true));
                        }
                    }
                    // v3.6 饱和 buff：同样常驻（干活不掉饥饿，牛奶清除 1 秒内恢复）
                    PotionEffectType saturation = Registry.EFFECT.get(NamespacedKey.minecraft("saturation"));
                    if (saturation != null) {
                        player.addPotionEffect(new PotionEffect(saturation, 100, 0, false, true, true));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /** 取消维护循环任务（onDisable 必须调用，防热重载残留）。 */
    public void stopUpkeepTask() {
        if (upkeepTask != null) {
            upkeepTask.cancel();
            upkeepTask = null;
        }
    }

    /** 全量落盘 prisoners.yml（同步主线程，变更即保存，避免长时间内存态丢失）。 */
    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Prisoner> entry : prisoners.entrySet()) {
            Prisoner p = entry.getValue();
            String path = "prisoners." + entry.getKey();
            cfg.set(path + ".name", p.name());
            cfg.set(path + ".world", p.worldName());
            cfg.set(path + ".x", p.x());
            cfg.set(path + ".y", p.y());
            cfg.set(path + ".z", p.z());
            cfg.set(path + ".yaw", p.yaw());
            cfg.set(path + ".pitch", p.pitch());
            // v3 schema：task-key / task-target / task-progress（无任务时 task-key 不写，加载时按 null 处理）
            cfg.set(path + ".task-key", p.taskKey());
            cfg.set(path + ".task-target", p.taskTarget());
            cfg.set(path + ".task-progress", p.taskProgress());
            cfg.set(path + ".cell", p.cellIndex());
            cfg.set(path + ".gamemode", p.gameMode().name());
            cfg.set(path + ".confiscated", p.confiscated()); // v4.11：背包是否已暂扣（离线入狱=false）
            // v3.25 背包快照：槽位 → ItemStack（Bukkit 原生序列化）
            for (Map.Entry<Integer, ItemStack> slotEntry : p.inventorySnapshot().entrySet()) {
                cfg.set(path + ".inventory." + slotEntry.getKey(), slotEntry.getValue());
            }
            // v3.25 药水效果快照：serialize 列表
            List<Map<String, Object>> effectList = new java.util.ArrayList<>();
            for (PotionEffect effect : p.effectsSnapshot()) {
                effectList.add(effect.serialize());
            }
            cfg.set(path + ".effects", effectList);
        }
        try {
            cfg.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 prisoners.yml 失败: " + ex.getMessage());
        }
    }
}
