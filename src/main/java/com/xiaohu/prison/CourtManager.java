package com.xiaohu.prison;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 人民法院模块中枢（v4.x）：案件注册表 + cases.yml/courtroom.yml 读写 + 状态迁移 + 入狱执行
 * （调 {@link PrisonerManager#imprison}）+ 法庭实体化（开庭传送/回传/定身/名牌）+ 法官桌按钮系统
 * （发言轮次/直接审判/悬浮字 TextDisplay）+ 宣判输入截获 + 轮次禁言 + 庭审指令限制。
 *
 * <p>court.yml 的默认模板由 {@code PrisonPlugin.onEnable} 中 {@code saveResource("court.yml", false)}
 * 首启复制；本类直接读取数据目录 court.yml（独立配置文件，不修改 config.yml）。
 * 所有方法仅允许主线程调用（同步读写，变更即落盘；仅 AsyncChatEvent 轮次禁言/宣判文本捕获从异步读取字段）。
 * 内存态（候选人池/座位占用/发言轮次/开庭确认/传送快照）重启即重置。
 */
public final class CourtManager {

    // ============================================================== 内部模型
    /** 法庭单点（judge/defendant/plaintiff/律师席/观众席）：世界名 + 坐标 + 朝向。 */
    private record Point(String world, double x, double y, double z, float yaw, float pitch) {
        @Override
        public String toString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }
    }

    /** 法官桌石头按钮标记：世界名 + 方块整数坐标。 */
    private record ButtonPoint(String world, int x, int y, int z) {
        @Override
        public String toString() {
            return String.format("(%d, %d, %d)", x, y, z);
        }
    }

    /** 开庭前名牌快照（customName + visible），结案回传时恢复。 */
    private record NameTagSnapshot(String customName, boolean visible) {
    }

    /** 发言轮次（作用于最近 TRIAL 案件）：NONE 无人发言。 */
    private enum Speaking { NONE, PLAINTIFF, DEFENDANT }

    // ============================================================== 依赖与字段
    private final PrisonPlugin plugin;
    private final PrisonerManager prisonerManager;
    /** 任务材质枚举名（PrisonerManager.imprison 的 taskKey）。 */
    private final String breakableMaterialName;
    /** 任务材质中文名（消息提示，如「黑曜石」）。 */
    private final String breakableDisplayName;
    private final String prisonWorldName;
    private final File casesFile;
    private final File courtroomFile;
    /** v4.18 法庭背包托管快照文件（持久化，重启不丢）。 */
    private final File courtInvFile;

    // ---- 配置（court.yml）----
    private boolean enabled;
    private int defaultSentence;
    private int maxSentence;
    private int accuseCooldownSeconds;
    private boolean autoExecute;
    private boolean courtroomEnabled;
    private int spectatorsMax;
    private boolean freezeSeats;
    private boolean buttonsEnabled;
    private int turnSeconds;
    private boolean muteOpposing;
    private boolean restrictCommands;
    /** v4.21 判决展示后多少秒自动结束庭审（默认 5）。 */
    private int verdictEndSeconds;
    private Set<String> allowedCommands = new LinkedHashSet<>();

    // ---- 案件注册表 ----
    private final Map<Integer, CourtCase> cases = new LinkedHashMap<>();
    private int nextCaseId = 1;

    // ---- 律师候选人池（内存态，caseId -> 有序 uuid->名）----
    private final Map<Integer, LinkedHashMap<UUID, String>> lawyerCandidates = new LinkedHashMap<>();

    // ---- 观众报名（caseId -> 有序 UUID 集合）----
    private final Map<Integer, LinkedHashSet<UUID>> watchers = new LinkedHashMap<>();

    // ---- 法庭位置（courtroom.yml）----
    private String courtroomWorld;
    private Point judgePoint;
    private Point defendantPoint;
    private Point plaintiffPoint;
    private final Map<Integer, Point> spectatorSeats = new LinkedHashMap<>();
    private final Map<String, ButtonPoint> buttons = new LinkedHashMap<>();
    /** v4.14 法院区域两个角点（region.1 / region.2，禁破坏/放置；任一为空则区域未启用）。 */
    private Point regionCorner1;
    private Point regionCorner2;
    private boolean protectRegion;

    // ---- 开庭传送快照 / 座位占用 / 定身 / 名牌（内存态）----
    /** 开庭前位置快照（v4.19：持久化，中途退出不清除——庭审结束后重进服传回原位）。 */
    private final Map<UUID, CourtReturn> teleportSnapshots = new LinkedHashMap<>();
    /** v4.18 法庭背包快照（槽位 -> ItemStack；进入法庭清空，结案/离开/退出还原；持久化 court-inv.yml）。 */
    private final Map<UUID, ItemStack[]> courtInvSnapshots = new LinkedHashMap<>();

    /** 开庭前位置快照条目：位置 + 所属案件号（案件结束后重进服回传判定用）。 */
    private record CourtReturn(Location location, int caseId) {
    }
    private final Map<Integer, Map<Integer, UUID>> seatOccupancy = new LinkedHashMap<>();
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, NameTagSnapshot> nameTagSnapshots = new LinkedHashMap<>();

    // ---- 发言轮次 / 倒计时 / 悬浮字（内存态）----
    private volatile Speaking speaking = Speaking.NONE;
    private int speakingCaseId = -1;
    private int turnRemaining = 0;
    private boolean turnTimedOut = false;
    private BukkitTask turnTask;
    /** 当前悬浮字对应的案件（最近 TRIAL，作中按钮确认态用）。 */
    private int displayCaseId = -1;
    private boolean displaysCreated = false;
    private TextDisplay leftDisplay;
    private TextDisplay middleDisplay;
    private TextDisplay rightDisplay;
    /** v4.19 发言完毕按钮悬浮字（原告席/被告席各一）。 */
    private TextDisplay donePlaintiffDisplay;
    private TextDisplay doneDefendantDisplay;
    /** 最近开庭（TRIAL）的案件：异步轮次禁言读取用（volatile 快照）。 */
    private volatile CourtCase activeTrial;

    // ---- 开庭确认（内存态，案件维度）----
    private final Set<Integer> confirmedCases = new HashSet<>();

    // ---- 宣判输入模式：法官 UUID -> 案件号（内存态；异步聊天线程读、主线程写 → 并发容器）----
    private final Map<UUID, Integer> verdictInput = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- 指控冷却：原告 UUID -> 上次指控毫秒 ----
    private final Map<UUID, Long> accuseCooldowns = new LinkedHashMap<>();

    public CourtManager(PrisonPlugin plugin, PrisonerManager prisonerManager,
                        String breakableMaterialName, String breakableDisplayName, String prisonWorldName) {
        this.plugin = plugin;
        this.prisonerManager = prisonerManager;
        this.breakableMaterialName = breakableMaterialName;
        this.breakableDisplayName = breakableDisplayName;
        this.prisonWorldName = prisonWorldName;
        this.casesFile = new File(plugin.getDataFolder(), "cases.yml");
        this.courtroomFile = new File(plugin.getDataFolder(), "courtroom.yml");
        this.courtInvFile = new File(plugin.getDataFolder(), "court-inv.yml");
    }

    // ============================================================== 生命周期
    /** 加载配置 + 案件 + 法庭位置 + 法庭背包快照；存在进行中案件时全服广播恢复提示。 */
    public void load() {
        loadConfig();
        loadCases();
        loadCourtroom();
        loadCourtInv(); // v4.18：法庭背包托管快照（重启恢复）
        plugin.getLogger().info("人民法院已加载（" + cases.size() + " 个案件，观众席 "
                + spectatorSeats.size() + " 座，按钮: " + buttons.size() + "）。");
        if (enabled) {
            for (CourtCase c : cases.values()) {
                if (c.isActive()) {
                    Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection().deserialize(
                            "§e[法院] 案件 #" + c.id() + " 庭审状态已恢复（可 /court lb 查看）"));
                }
            }
        }
    }

    /** 落盘 cases.yml + courtroom.yml + court-inv.yml（onDisable 兜底调用）。 */
    public void save() {
        saveCases();
        saveCourtroom();
        saveCourtInv(); // v4.18
    }

    /** onDisable：落盘 + 取消倒计时 + 移除悬浮字 + 恢复所有定身玩家。 */
    public void shutdown() {
        save();
        if (turnTask != null) {
            turnTask.cancel();
            turnTask = null;
        }
        removeDisplays();
        for (UUID uuid : frozenPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                try {
                    p.setWalkSpeed(0.2f);
                } catch (Exception ignored) {
                    // 玩家可能正在退出，忽略
                }
            }
        }
        frozenPlayers.clear();
        plugin.getLogger().info("人民法院已禁用。");
    }

    // ============================================================== 配置读取（court.yml）
    private void loadConfig() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "court.yml"));
        enabled = cfg.getBoolean("enabled", true);
        defaultSentence = cfg.getInt("default-sentence", 64);
        maxSentence = cfg.getInt("max-sentence", 10000);
        accuseCooldownSeconds = cfg.getInt("accuse-cooldown-seconds", 0);
        autoExecute = cfg.getBoolean("auto-execute", true);
        courtroomEnabled = cfg.getBoolean("courtroom-enabled", true);
        spectatorsMax = cfg.getInt("spectators-max", 20);
        freezeSeats = cfg.getBoolean("freeze-seats", true);
        buttonsEnabled = cfg.getBoolean("buttons-enabled", true);
        turnSeconds = cfg.getInt("turn-seconds", 120);
        muteOpposing = cfg.getBoolean("mute-opposing", true);
        restrictCommands = cfg.getBoolean("restrict-commands", true);
        verdictEndSeconds = cfg.getInt("verdict-end-seconds", 5); // v4.21
        protectRegion = cfg.getBoolean("protect-region", true); // v4.14：法院区域禁破坏/放置
        allowedCommands = new LinkedHashSet<>(cfg.getStringList("allowed-commands"));
        if (allowedCommands.isEmpty()) {
            allowedCommands.add("court");
            allowedCommands.add("fy");
            allowedCommands.add("ct");
        }
        allowedCommands = allowedCommands.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ============================================================== 访问器（命令/监听器用）
    public boolean isJudge(CommandSender sender) {
        return sender.hasPermission("court.judge") || sender.hasPermission("prison.admin") || sender.isOp();
    }

    public boolean isManagerImmune(Player p) {
        return p.isOp() || p.hasPermission("prison.admin");
    }

    public boolean isAccusableTarget(Player p) {
        return !isManagerImmune(p) && !prisonerManager.isImprisoned(p.getUniqueId());
    }

    public boolean isInPrisonWorld(Player p) {
        return p.getWorld().getName().equals(prisonWorldName);
    }

    public String getPrisonWorldName() {
        return prisonWorldName;
    }

    public boolean isImprisoned(UUID uuid) {
        return prisonerManager.isImprisoned(uuid);
    }

    /** 在线玩家名列表；accusableOnly=true 时排除在狱玩家与管理员/OP。 */
    public List<String> onlinePlayerNames(boolean accusableOnly) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> !accusableOnly || isAccusableTarget(p))
                .map(Player::getName)
                .toList();
    }

    public List<CourtCase> activeCases() {
        List<CourtCase> result = new ArrayList<>();
        for (CourtCase c : cases.values()) {
            if (c.isActive()) {
                result.add(c);
            }
        }
        return result;
    }

    public int activeCaseCount() {
        return activeCases().size();
    }

    /** 进行中案件恰好 1 个时返回其编号；零个/多个返回 -1（指令层决定提示）。 */
    public int singleActiveCaseId() {
        List<CourtCase> act = activeCases();
        return act.size() == 1 ? act.get(0).id() : -1;
    }

    public CourtCase getCase(int id) {
        return cases.get(id);
    }

    // ---- Tab 补全辅助 ----
    public List<Integer> pendingCaseIds() {
        List<Integer> r = new ArrayList<>();
        for (CourtCase c : cases.values()) {
            if (c.state() == CourtCase.State.PENDING) {
                r.add(c.id());
            }
        }
        return r;
    }

    public List<Integer> trialCaseIds() {
        List<Integer> r = new ArrayList<>();
        for (CourtCase c : cases.values()) {
            if (c.state() == CourtCase.State.TRIAL) {
                r.add(c.id());
            }
        }
        return r;
    }

    public List<Integer> activeCaseIds() {
        List<Integer> r = new ArrayList<>();
        for (CourtCase c : cases.values()) {
            if (c.isActive()) {
                r.add(c.id());
            }
        }
        return r;
    }

    public List<Integer> watchableCaseIds() {
        List<Integer> r = new ArrayList<>();
        for (CourtCase c : cases.values()) {
            if (c.state() == CourtCase.State.PENDING || c.state() == CourtCase.State.TRIAL) {
                r.add(c.id());
            }
        }
        return r;
    }

    public List<Integer> watcherCaseIds(UUID uuid) {
        List<Integer> r = new ArrayList<>();
        for (Map.Entry<Integer, LinkedHashSet<UUID>> e : watchers.entrySet()) {
            if (e.getValue().contains(uuid)) {
                r.add(e.getKey());
            }
        }
        return r;
    }

    public List<Integer> plaintiffActiveCaseIds(UUID uuid) {
        List<Integer> r = new ArrayList<>();
        for (CourtCase c : cases.values()) {
            if (c.isActive() && uuid.equals(c.plaintiffUuid())) {
                r.add(c.id());
            }
        }
        return r;
    }

    public List<String> candidateNames(int caseId) {
        LinkedHashMap<UUID, String> pool = lawyerCandidates.get(caseId);
        return pool == null ? List.of() : new ArrayList<>(pool.values());
    }

    public List<Integer> markedSpectatorSeats() {
        return new ArrayList<>(spectatorSeats.keySet());
    }

    public int spectatorMarkedCount() {
        return spectatorSeats.size();
    }

    public int mostRecentTrialCaseId() {
        int best = -1;
        for (CourtCase c : cases.values()) {
            if (c.state() == CourtCase.State.TRIAL && c.id() > best) {
                best = c.id();
            }
        }
        return best;
    }

    public boolean isCourtroomEnabled() {
        return courtroomEnabled;
    }

    public int getSpectatorsMax() {
        return spectatorsMax;
    }

    public boolean isFreezeSeats() {
        return freezeSeats;
    }

    public boolean isButtonsEnabled() {
        return buttonsEnabled;
    }

    public int getTurnSeconds() {
        return turnSeconds;
    }

    public boolean isMuteOpposing() {
        return muteOpposing;
    }

    public boolean isRestrictCommands() {
        return restrictCommands;
    }

    // ============================================================== cases.yml 读写
    private void loadCases() {
        cases.clear();
        nextCaseId = 1;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(casesFile);
        ConfigurationSection sec = cfg.getConfigurationSection("cases");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    int id = Integer.parseInt(key);
                    String path = "cases." + key;
                    CourtCase.State state = CourtCase.State.fromStateName(cfg.getString(path + ".state"));
                    if (state == null) {
                        plugin.getLogger().warning("跳过损坏的案件记录 '" + key + "'：state 非法。");
                        continue;
                    }
                    String du = cfg.getString(path + ".defendant-uuid");
                    if (du == null) {
                        plugin.getLogger().warning("跳过损坏的案件记录 '" + key + "'：defendant-uuid 缺失。");
                        continue;
                    }
                    UUID defUuid = UUID.fromString(du);
                    String defName = cfg.getString(path + ".defendant-name", "?");
                    String pu = cfg.getString(path + ".plaintiff-uuid");
                    UUID plUuid = pu == null ? null : UUID.fromString(pu);
                    String plName = cfg.getString(path + ".plaintiff-name", plUuid == null ? "法院公诉" : "?");
                    String dlu = cfg.getString(path + ".defense-lawyer-uuid");
                    UUID defLawyerUuid = dlu == null ? null : UUID.fromString(dlu);
                    String defLawyerName = cfg.getString(path + ".defense-lawyer-name", "?");
                    String plu = cfg.getString(path + ".prosecution-lawyer-uuid");
                    UUID prosLawyerUuid = plu == null ? null : UUID.fromString(plu);
                    String prosLawyerName = cfg.getString(path + ".prosecution-lawyer-name", "?");
                    String reason = cfg.getString(path + ".reason", "");
                    long filedAt = cfg.getLong(path + ".filed-at", 0L);
                    String ju = cfg.getString(path + ".judge-uuid");
                    UUID judgeUuid = ju == null ? null : UUID.fromString(ju);
                    String judgeName = cfg.getString(path + ".judge-name", "?");
                    long trialAt = cfg.getLong(path + ".trial-started-at", 0L);
                    int sentence = cfg.getInt(path + ".sentence-amount", 0);
                    long verdictAt = cfg.getLong(path + ".verdict-at", 0L);
                    CourtCase caseObj = new CourtCase(id, state, defUuid, defName, plUuid, plName,
                            defLawyerUuid, defLawyerName, prosLawyerUuid, prosLawyerName,
                            reason, filedAt, judgeUuid, judgeName, trialAt, sentence, verdictAt);
                    cases.put(id, caseObj);
                    if (id >= nextCaseId) {
                        nextCaseId = id + 1;
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("跳过损坏的案件记录 '" + key + "'：" + ex.getMessage());
                }
            }
        }
        int stored = cfg.getInt("next-case-id", nextCaseId);
        if (stored >= 1) {
            nextCaseId = Math.max(nextCaseId, stored);
        }
        plugin.getLogger().info("已加载 " + cases.size() + " 个法院案件。");
    }

    /** 全量落盘 cases.yml（同步主线程，变更即保存）。 */
    public void saveCases() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (CourtCase c : cases.values()) {
            String path = "cases." + c.id();
            cfg.set(path + ".state", c.state().name());
            cfg.set(path + ".defendant-uuid", c.defendantUuid().toString());
            cfg.set(path + ".defendant-name", c.defendantName());
            if (c.plaintiffUuid() != null) {
                cfg.set(path + ".plaintiff-uuid", c.plaintiffUuid().toString());
            }
            cfg.set(path + ".plaintiff-name", c.plaintiffName());
            if (c.defenseLawyerUuid() != null) {
                cfg.set(path + ".defense-lawyer-uuid", c.defenseLawyerUuid().toString());
                cfg.set(path + ".defense-lawyer-name", c.defenseLawyerName());
            }
            if (c.prosecutionLawyerUuid() != null) {
                cfg.set(path + ".prosecution-lawyer-uuid", c.prosecutionLawyerUuid().toString());
                cfg.set(path + ".prosecution-lawyer-name", c.prosecutionLawyerName());
            }
            cfg.set(path + ".reason", c.reason());
            cfg.set(path + ".filed-at", c.filedAt());
            if (c.judgeUuid() != null) {
                cfg.set(path + ".judge-uuid", c.judgeUuid().toString());
                cfg.set(path + ".judge-name", c.judgeName());
            }
            if (c.trialStartedAt() > 0L) {
                cfg.set(path + ".trial-started-at", c.trialStartedAt());
            }
            if (c.sentenceAmount() > 0) {
                cfg.set(path + ".sentence-amount", c.sentenceAmount());
            }
            if (c.verdictAt() > 0L) {
                cfg.set(path + ".verdict-at", c.verdictAt());
            }
        }
        cfg.set("next-case-id", nextCaseId);
        try {
            cfg.save(casesFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 cases.yml 失败: " + ex.getMessage());
        }
    }

    // ============================================================== courtroom.yml 读写
    public boolean isCourtroomReady() {
        return judgePoint != null && defendantPoint != null && plaintiffPoint != null;
    }

    private void loadCourtroom() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(courtroomFile);
        ConfigurationSection sec = cfg.getConfigurationSection("courtroom");
        if (sec == null) {
            return;
        }
        courtroomWorld = sec.getString("world");
        judgePoint = readPoint(sec, "judge", courtroomWorld);
        defendantPoint = readPoint(sec, "defendant", courtroomWorld);
        plaintiffPoint = readPoint(sec, "plaintiff", courtroomWorld);
        spectatorSeats.clear();
        ConfigurationSection sp = sec.getConfigurationSection("spectator");
        if (sp != null) {
            for (String key : sp.getKeys(false)) {
                try {
                    int n = Integer.parseInt(key);
                    if (n < 1) {
                        continue;
                    }
                    spectatorSeats.put(n, readPoint(sp, key, courtroomWorld));
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("跳过损坏的观众席记录 '" + key + "'：编号不是整数。");
                }
            }
        }
        buttons.clear();
        ConfigurationSection bt = sec.getConfigurationSection("buttons");
        if (bt != null) {
            for (String key : bt.getKeys(false)) {
                if (!key.equals("left") && !key.equals("middle") && !key.equals("right")) {
                    continue;
                }
                String w = bt.getString(key + ".world");
                if (w == null
                        || !bt.contains(key + ".x") || !bt.contains(key + ".y") || !bt.contains(key + ".z")) {
                    plugin.getLogger().warning("跳过损坏的按钮记录 '" + key + "'：字段缺失。");
                    continue;
                }
                buttons.put(key, new ButtonPoint(w, bt.getInt(key + ".x"), bt.getInt(key + ".y"), bt.getInt(key + ".z")));
            }
        }
        // v4.14 法院区域（两个角点，禁破坏/放置）
        regionCorner1 = readPoint(sec, "region.1", courtroomWorld);
        regionCorner2 = readPoint(sec, "region.2", courtroomWorld);
    }

    private Point readPoint(ConfigurationSection parent, String path, String defaultWorld) {
        if (!parent.contains(path + ".x") || !parent.contains(path + ".y") || !parent.contains(path + ".z")) {
            return null;
        }
        String w = parent.getString(path + ".world");
        if (w == null) {
            w = defaultWorld; // schema 只在 courtroom 顶层写 world，单点/观众席缺省沿用
        }
        return new Point(w, parent.getDouble(path + ".x"), parent.getDouble(path + ".y"), parent.getDouble(path + ".z"),
                (float) parent.getDouble(path + ".yaw", 0.0), (float) parent.getDouble(path + ".pitch", 0.0));
    }

    private void saveCourtroom() {
        YamlConfiguration cfg = new YamlConfiguration();
        if (courtroomWorld != null) {
            cfg.set("courtroom.world", courtroomWorld);
        }
        writePoint(cfg, "courtroom.judge", judgePoint);
        writePoint(cfg, "courtroom.defendant", defendantPoint);
        writePoint(cfg, "courtroom.plaintiff", plaintiffPoint);
        for (Map.Entry<Integer, Point> e : spectatorSeats.entrySet()) {
            writePoint(cfg, "courtroom.spectator." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, ButtonPoint> e : buttons.entrySet()) {
            ButtonPoint bp = e.getValue();
            cfg.set("courtroom.buttons." + e.getKey() + ".world", bp.world());
            cfg.set("courtroom.buttons." + e.getKey() + ".x", bp.x());
            cfg.set("courtroom.buttons." + e.getKey() + ".y", bp.y());
            cfg.set("courtroom.buttons." + e.getKey() + ".z", bp.z());
        }
        writePoint(cfg, "courtroom.region.1", regionCorner1); // v4.14 法院区域角点
        writePoint(cfg, "courtroom.region.2", regionCorner2);
        try {
            cfg.save(courtroomFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 courtroom.yml 失败: " + ex.getMessage());
        }
    }

    private void writePoint(YamlConfiguration cfg, String path, Point p) {
        if (p == null) {
            return;
        }
        cfg.set(path + ".world", p.world());
        cfg.set(path + ".x", p.x());
        cfg.set(path + ".y", p.y());
        cfg.set(path + ".z", p.z());
        cfg.set(path + ".yaw", p.yaw());
        cfg.set(path + ".pitch", p.pitch());
    }

    // ============================================================== 案件流程
    /** accuse：发起指控（PENDING）。返回 true 表示立案。 */
    public boolean accuse(Player plaintiff, String defendantName, String reason) {
        // 指控冷却（按原告 UUID，0 = 关闭）
        if (accuseCooldownSeconds > 0) {
            Long last = accuseCooldowns.get(plaintiff.getUniqueId());
            if (last != null) {
                long elapsed = (System.currentTimeMillis() - last) / 1000L;
                if (elapsed < accuseCooldownSeconds) {
                    plaintiff.sendMessage("§c指控冷却中，还需等待 " + (accuseCooldownSeconds - elapsed) + " 秒。");
                    return false;
                }
            }
        }
        Player defendant = Bukkit.getPlayerExact(defendantName);
        if (defendant == null) {
            plaintiff.sendMessage("§c被告 " + defendantName + " 不在线，无法起诉。");
            return false;
        }
        if (isManagerImmune(defendant)) {
            plaintiff.sendMessage("§c不能起诉管理员/法官。");
            return false;
        }
        if (isImprisoned(defendant.getUniqueId())) {
            plaintiff.sendMessage("§c被告 " + defendant.getName() + " 正在服刑，无法起诉。");
            return false;
        }
        int existing = activeCaseForDefendant(defendant.getUniqueId());
        if (existing != -1) {
            plaintiff.sendMessage("§c被告 " + defendant.getName() + " 已有进行中案件 #" + existing + "，无法重复立案。");
            return false;
        }
        int caseId = nextCaseId++;
        CourtCase c = new CourtCase(caseId, CourtCase.State.PENDING,
                defendant.getUniqueId(), defendant.getName(),
                plaintiff.getUniqueId(), plaintiff.getName(),
                null, null, null, null,
                reason, System.currentTimeMillis(), null, null, 0L, 0, 0L);
        cases.put(caseId, c);
        saveCases();
        accuseCooldowns.put(plaintiff.getUniqueId(), System.currentTimeMillis());
        plaintiff.sendMessage("§a你已对 §c" + defendant.getName() + " §a提起诉讼（案件 #" + caseId + "），等待法官开庭");
        defendant.sendMessage("§c你被 §b" + plaintiff.getName() + " §c起诉了！（案件 #" + caseId + "）案由: " + reason
                + "——可 /court ls 招募律师");
        broadcast("§e[法院] §b" + plaintiff.getName() + " §f起诉了 §c" + defendant.getName() + " §f（案件 #" + caseId + "）");
        // v4.14：立案后通知所有在线法官/管理员
        notifyJudges("§e[法院] §6【法官/管理员提醒】§f新案件 #" + caseId + " 已立案：§b" + plaintiff.getName()
                + " §f起诉了 §c" + defendant.getName() + "§f（案由: " + reason + "）——请安排开庭: §a/court kt " + caseId);
        globalSound(org.bukkit.Sound.BLOCK_BELL_USE, 1.0f); // v4.20 立案钟声
        return true;
    }

    /** prosecute：公诉（court.judge）——立案并立即开庭。 */
    public boolean prosecute(Player prosecutor, String defendantName, String reason) {
        Player defendant = Bukkit.getPlayerExact(defendantName);
        if (defendant == null) {
            prosecutor.sendMessage("§c被告 " + defendantName + " 不在线，无法公诉。");
            return false;
        }
        if (isManagerImmune(defendant)) {
            prosecutor.sendMessage("§c不能对管理员/法官提起公诉。");
            return false;
        }
        int existing = activeCaseForDefendant(defendant.getUniqueId());
        if (existing != -1) {
            prosecutor.sendMessage("§c被告 " + defendant.getName() + " 已有进行中案件 #" + existing + "，无法重复公诉。");
            return false;
        }
        int caseId = nextCaseId++;
        long now = System.currentTimeMillis();
        CourtCase c = new CourtCase(caseId, CourtCase.State.TRIAL,
                defendant.getUniqueId(), defendant.getName(),
                null, "法院公诉",
                null, null, null, null,
                reason, now, prosecutor.getUniqueId(), prosecutor.getName(), now, 0, 0L);
        cases.put(caseId, c);
        saveCases();
        defendant.sendMessage("§c你被法院公诉了！（案件 #" + caseId + "）案由: " + reason);
        broadcast("§e[法院] §6法院 §f对 §c" + defendant.getName() + " §f提起公诉（案件 #" + caseId + "）");
        // v4.14：公诉同样通知法官/管理员
        notifyJudges("§e[法院] §6【法官/管理员提醒】§f新公诉案件 #" + caseId + "：§6法院 §f对 §c"
                + defendant.getName() + "§f（案由: " + reason + "）——已自动开庭");
        globalSound(org.bukkit.Sound.BLOCK_BELL_USE, 1.2f); // v4.20 公诉钟声（高）
        // 公诉 = 立案即开庭：被告在线 → 直接进入开庭传送
        openTrial(caseId, prosecutor);
        return true;
    }

    /** v4.14：立案/公诉后通知所有在线法官/管理员（OP / prison.admin / court.judge）。 */
    private void notifyJudges(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("prison.admin") || p.hasPermission("court.judge")) {
                p.sendMessage(message);
            }
        }
    }

    /** trial：开庭（court.judge；被告须在线）。返回 true 表示开庭成功。 */
    public boolean trial(int caseId, Player judge) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            judge.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (c.state() != CourtCase.State.PENDING) {
            judge.sendMessage("§c案件 #" + caseId + " 当前不可开庭（状态: " + stateCn(c.state()) + "）。");
            return false;
        }
        Player defendant = Bukkit.getPlayer(c.defendantUuid());
        if (defendant == null || !defendant.isOnline()) {
            judge.sendMessage("§c被告 " + c.defendantName() + " 不在线，无法开庭。");
            return false;
        }
        openTrial(caseId, judge);
        return true;
    }

    /** 开庭公用的收尾：记录法官/开庭时间 → 广播 → 法庭传送。 */
    private void openTrial(int caseId, Player judge) {
        CourtCase c = cases.get(caseId);
        long now = System.currentTimeMillis();
        CourtCase trial = c.startTrial(judge.getUniqueId(), judge.getName(), now);
        cases.put(caseId, trial);
        activeTrial = trial;
        saveCases();
        String plaintiff = trial.plaintiffUuid() == null
                ? "法院公诉（" + trial.judgeName() + "）" : trial.plaintiffName();
        broadcast("§e[法院] §6案件 #" + caseId + " §f正式开庭！§b被告: §a" + trial.defendantName()
                + " §b原告: §a" + plaintiff + " §f案由: §7" + trial.reason());
        broadcast("§e[法院] §6庭审进行中，欢迎各位玩家前来旁听！§f/court watch " + caseId + " 报名");
        broadcast("§e[法院] §f旁听观众将传送至法庭观众席（限 " + spectatorCapacity() + " 人，先到先得）；本庭由法官独审，判决以法官宣判为准");
        globalSound(org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f); // v4.20 开庭龙吼（威慑全场）
        teleportParticipants(caseId, judge, trial);
    }

    /** verdict：宣判（court.judge）。返回 true 表示宣判成功（含结案）。 */
    public boolean verdict(int caseId, Player judge, String verdictStr, Integer amount) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            judge.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (c.state() != CourtCase.State.TRIAL) {
            judge.sendMessage("§c案件 #" + caseId + " 当前不可宣判（状态: " + stateCn(c.state()) + "）。");
            return false;
        }
        String v = verdictStr == null ? "" : verdictStr.trim().toLowerCase();
        if (v.equals("guilty")) {
            int sentence = amount != null ? amount : defaultSentence;
            if (sentence <= 0) {
                judge.sendMessage("§c刑期必须大于 0。");
                return false;
            }
            if (sentence > maxSentence) {
                sentence = maxSentence;
                judge.sendMessage("§c刑期超过上限，已按 " + maxSentence + " 处理。");
            }
            Player defendant = Bukkit.getPlayer(c.defendantUuid());
            if (defendant != null && defendant.isOnline()) {
                // v4.18：先还原法庭背包快照，监狱系统的入狱快照才能拍到真正的原背包
                restoreCourtInventory(defendant);
                Prisoner pr = prisonerManager.imprison(defendant, breakableMaterialName, sentence);
                if (pr == null) {
                    judge.sendMessage("§c监狱房间已满，无法收押被告 " + c.defendantName() + "，宣判未执行。");
                    return false;
                }
                CourtCase updated = c.closeVerdict(CourtCase.State.GUILTY, sentence, System.currentTimeMillis());
                cases.put(caseId, updated);
                saveCases();
                defendant.sendMessage("§c[法院] 你被判处挖 " + sentence + " 个" + breakableDisplayName + "，立即收监！");
                broadcast("§e[法院] §6判决 §c" + defendant.getName() + " §f有罪，判处挖 " + sentence
                        + " 个" + breakableDisplayName + "，立即收监！");
                globalSound(org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f); // v4.20 有罪天雷
                closeCaseAfterVerdict(caseId, updated);
            } else {
                // 被告离线
                if (autoExecute) {
                    CourtCase awaiting = c.awaitingSentence(sentence, System.currentTimeMillis());
                    cases.put(caseId, awaiting);
                    saveCases();
                    broadcast("§e[法院] §6判决 §c" + c.defendantName() + " §f有罪，判处挖 " + sentence
                            + " 个" + breakableDisplayName + "（被告离线，重进服自动收监）。");
                    globalSound(org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f); // v4.20 有罪天雷（离线）
                    closeCaseAfterVerdict(caseId, awaiting);
                } else {
                    judge.sendMessage("§c被告不在线，无法宣判。");
                    return false;
                }
            }
        } else if (v.equals("innocent")) {
            CourtCase updated = c.closeVerdict(CourtCase.State.INNOCENT, 0, System.currentTimeMillis());
            cases.put(caseId, updated);
            saveCases();
            broadcast("§e[法院] §6案件 #" + caseId + " §f法官宣判 §a被告 " + c.defendantName() + " §f无罪，当庭释放。");
            globalSound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f); // v4.20 无罪胜利号角
            closeCaseAfterVerdict(caseId, updated);
        } else {
            judge.sendMessage("§c用法: /court verdict <编号> <guilty|innocent> [数量]");
            return false;
        }
        return true;
    }

    /** dismiss：驳回（court.judge）。 */
    public boolean dismiss(int caseId, Player judge, String reason) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            judge.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (c.state() != CourtCase.State.PENDING && c.state() != CourtCase.State.TRIAL) {
            judge.sendMessage("§c案件 #" + caseId + " 当前不可驳回。");
            return false;
        }
        CourtCase updated = c.closeVerdict(CourtCase.State.DISMISSED, c.sentenceAmount(), System.currentTimeMillis());
        cases.put(caseId, updated);
        saveCases();
        broadcast("§e[法院] §6案件 #" + caseId + " §f已被驳回：§7" + reason);
        globalSound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f); // v4.20 驳回沉重鼓声
        closeCaseAfterVerdict(caseId, updated);
        return true;
    }

    /** withdraw：撤诉（仅原告本人；公诉不可撤）。 */
    public boolean withdraw(int caseId, Player plaintiff) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            plaintiff.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (c.isProsecution()) {
            plaintiff.sendMessage("§c公诉案件不可撤诉。");
            return false;
        }
        if (c.plaintiffUuid() == null || !c.plaintiffUuid().equals(plaintiff.getUniqueId())) {
            plaintiff.sendMessage("§c只有原告本人可以撤诉。");
            return false;
        }
        if (c.state() != CourtCase.State.PENDING && c.state() != CourtCase.State.TRIAL) {
            plaintiff.sendMessage("§c案件 #" + caseId + " 当前不可撤诉。");
            return false;
        }
        CourtCase updated = c.closeVerdict(CourtCase.State.DISMISSED, c.sentenceAmount(), System.currentTimeMillis());
        cases.put(caseId, updated);
        saveCases();
        broadcast("§e[法院] §6案件 #" + caseId + " §f原告已撤诉，案件终止");
        globalSound(org.bukkit.Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f); // v4.20 撤诉按钮音
        closeCaseAfterVerdict(caseId, updated);
        return true;
    }

    /** 结案收尾：回传所有参与者 + 清空内存态（座位/候选人/快照/轮次/悬浮字）。 */
    private void closeCaseAfterVerdict(int caseId, CourtCase updated) {
        returnAllParticipants();
        clearCaseState(caseId);
        saveCases();
    }

    // ============================================================== 律师系统
    /** lawyer <编号>：广播招募（仅 PENDING，当事人本人）。 */
    public boolean lawyerRecruit(int caseId, Player party) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            party.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (c.state() != CourtCase.State.PENDING) {
            party.sendMessage("§c只有在立案后尚未开庭时才能招募律师。");
            return false;
        }
        if (!isPartyOf(c, party.getUniqueId())) {
            party.sendMessage("§c只有当事人（被告或原告）可以招募律师。");
            return false;
        }
        String side = c.defendantUuid().equals(party.getUniqueId()) ? "被告 " + c.defendantName()
                : "原告 " + c.plaintiffName();
        broadcast("§e[法院] §6案件 #" + caseId + " §f" + side + " §f开庭前需要律师！愿意担任的玩家请用 §a/court volunteer "
                + caseId + " §f报名");
        globalSound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f); // v4.20 招募提示音
        return true;
    }

    /** ls pick <编号> <玩家>：当事人从候选人池中指定律师。 */
    public boolean lawyerPick(int caseId, Player party, String lawyerName) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            party.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (c.state() != CourtCase.State.PENDING) {
            party.sendMessage("§c案件已开庭，无法指定律师。");
            return false;
        }
        boolean isDefendantSide = c.defendantUuid().equals(party.getUniqueId());
        boolean isPlaintiffSide = c.plaintiffUuid() != null && c.plaintiffUuid().equals(party.getUniqueId());
        if (!isDefendantSide && !isPlaintiffSide) {
            party.sendMessage("§c只有当事人可以指定律师。");
            return false;
        }
        LinkedHashMap<UUID, String> pool = lawyerCandidates.get(caseId);
        if (pool == null || pool.isEmpty()) {
            party.sendMessage("§c该案件当前没有律师候选人。");
            return false;
        }
        UUID lawyerUuid = null;
        String candidateName = null;
        for (Map.Entry<UUID, String> e : pool.entrySet()) {
            if (e.getValue().equalsIgnoreCase(lawyerName)) {
                lawyerUuid = e.getKey();
                candidateName = e.getValue();
                break;
            }
        }
        if (lawyerUuid == null) {
            party.sendMessage("§c候选人 " + lawyerName + " 不在该案候选人池中。");
            return false;
        }
        if (lawyerUuid.equals(c.defenseLawyerUuid()) || lawyerUuid.equals(c.prosecutionLawyerUuid())) {
            party.sendMessage("§c该玩家已是本案律师。");
            return false;
        }
        CourtCase updated = isDefendantSide
                ? c.withDefenseLawyer(lawyerUuid, candidateName)
                : c.withProsecutionLawyer(lawyerUuid, candidateName);
        cases.put(caseId, updated);
        saveCases();
        String sideLabel = isDefendantSide ? "被告方" : "原告方";
        Player lawyer = Bukkit.getPlayer(lawyerUuid);
        if (lawyer != null) {
            lawyer.sendMessage("§a你已被聘请为案件 #" + caseId + " 的 " + sideLabel + " 律师，开庭时请到场");
            soundTo(lawyer, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f); // v4.16 被聘请提示音
        }
        pool.remove(lawyerUuid);
        return true;
    }

    /** ls cancel <编号>：当事人取消招募（清空候选人池 + 解聘已选律师）。 */
    public boolean lawyerCancel(int caseId, Player party) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            party.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (!isPartyOf(c, party.getUniqueId())) {
            party.sendMessage("§c只有当事人可以取消律师招募。");
            return false;
        }
        if (c.defenseLawyerUuid() != null) {
            Player l = Bukkit.getPlayer(c.defenseLawyerUuid());
            if (l != null) {
                l.sendMessage("§c你已被解聘（案件 #" + caseId + " 律师招募取消）。");
            }
        }
        if (c.prosecutionLawyerUuid() != null) {
            Player l = Bukkit.getPlayer(c.prosecutionLawyerUuid());
            if (l != null) {
                l.sendMessage("§c你已被解聘（案件 #" + caseId + " 律师招募取消）。");
            }
        }
        lawyerCandidates.remove(caseId);
        CourtCase updated = c.withDefenseLawyer(null, null).withProsecutionLawyer(null, null);
        cases.put(caseId, updated);
        saveCases();
        party.sendMessage("§a已取消案件 #" + caseId + " 的律师招募");
        return true;
    }

    /** volunteer <编号>：报名担任律师（候选人池）。 */
    public boolean volunteer(int caseId, Player player) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            player.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        if (c.state() != CourtCase.State.PENDING) {
            player.sendMessage("§c该案件尚未到律师报名阶段。");
            return false;
        }
        if (isImprisoned(player.getUniqueId())) {
            player.sendMessage("§c在狱玩家不能担任律师。");
            return false;
        }
        if (isJudge(player)) {
            player.sendMessage("§c法官不能报名担任律师。");
            return false;
        }
        if (isPartyOf(c, player.getUniqueId())) {
            player.sendMessage("§c当事人不能报名自己案件的律师。");
            return false;
        }
        LinkedHashMap<UUID, String> pool = lawyerCandidates.computeIfAbsent(caseId, k -> new LinkedHashMap<>());
        if (pool.containsKey(player.getUniqueId())) {
            player.sendMessage("§c你已报名案件 #" + caseId + " 的律师，等待当事人指定。");
            return false;
        }
        pool.put(player.getUniqueId(), player.getName());
        player.sendMessage("§a你已报名案件 #" + caseId + " 律师，等待当事人指定");
        return true;
    }

    // ============================================================== 旁听
    /** watch <编号>：PENDING 报名（开庭自动传送）；TRIAL 报名（立即分配空位并传送）。 */
    public boolean watch(int caseId, Player player) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            player.sendMessage("§c案件 #" + caseId + " 不存在。");
            return false;
        }
        Set<UUID> watcherSet = watcherSet(caseId);
        if (watcherSet.contains(player.getUniqueId())) {
            player.sendMessage("§c你已报名旁听案件 #" + caseId + "。");
            return false;
        }
        if (c.state() == CourtCase.State.PENDING) {
            watcherSet.add(player.getUniqueId());
            player.sendMessage("§a你已报名旁听案件 #" + caseId + "，开庭时自动传送。");
            soundTo(player, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f); // v4.16 报名音效
            return true;
        }
        if (c.state() == CourtCase.State.TRIAL) {
            if (!courtroomEnabled || !isCourtroomReady()) {
                player.sendMessage("§c法庭未启用或未标记，无法传送旁听。");
                return false;
            }
            int seat = nextFreeSeat(caseId);
            if (seat == -1) {
                player.sendMessage("§c观众席已满");
                return false;
            }
            seatOccupancyFor(caseId).put(seat, player.getUniqueId());
            watcherSet.add(player.getUniqueId());
            Point seatPoint = spectatorSeats.get(seat);
            snapshotLocation(player, caseId); // v4.19
            snapshotCourtInventory(player); // v4.18：中途报名同样清空背包托管
            teleportTo(player, seatPoint);
            if (freezeSeats) {
                freeze(player);
            }
            player.sendMessage("§a你已报名旁听案件 #" + caseId + "，分配座位 #" + seat + "，已传送至观众席。");
            return true;
        }
        player.sendMessage("§c该案件已结案，无法旁听。");
        return false;
    }

    /** unwatch <编号>：取消旁听（释放座位、解除定身、传回开庭前位置）。 */
    public boolean unwatch(int caseId, Player player) {
        Set<UUID> watcherSet = watchers.get(caseId);
        if (watcherSet == null || !watcherSet.contains(player.getUniqueId())) {
            player.sendMessage("§c你没有报名旁听案件 #" + caseId + "。");
            return false;
        }
        watcherSet.remove(player.getUniqueId());
        releaseSeat(caseId, player.getUniqueId());
        restoreCourtInventory(player); // v4.18
        teleportBack(player);
        unfreeze(player);
        player.sendMessage("§a你已离开旁听案件 #" + caseId + "。");
        return true;
    }

    /** leave <tc>：一键退出当前所有旁听。 */
    public boolean leave(Player player) {
        boolean any = false;
        for (Map.Entry<Integer, LinkedHashSet<UUID>> e : watchers.entrySet()) {
            if (e.getValue().remove(player.getUniqueId())) {
                releaseSeat(e.getKey(), player.getUniqueId());
                restoreCourtInventory(player); // v4.18
                teleportBack(player);
                unfreeze(player);
                player.sendMessage("§a你已离开旁听案件 #" + e.getKey() + "。");
                any = true;
            }
        }
        if (!any) {
            player.sendMessage("§c你当前不在旁听任何案件。");
        }
        return any;
    }

    // ============================================================== 查询
    /** list：进行中案件。 */
    public void list(CommandSender sender) {
        List<CourtCase> act = activeCases();
        if (act.isEmpty()) {
            sender.sendMessage("§e当前没有进行中的案件。");
            return;
        }
        sender.sendMessage("§e===== 进行中案件 (" + act.size() + ") =====");
        for (CourtCase c : act) {
            sender.sendMessage("§a#" + c.id() + " §7[" + stateCn(c.state()) + "] §f被告: " + c.defendantName()
                    + " §f原告: " + c.plaintiffDisplayName() + " §7案由: " + c.reason());
        }
    }

    /** case <编号>：案件详情。 */
    public void showCase(CommandSender sender, int caseId) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            sender.sendMessage("§c案件 #" + caseId + " 不存在。");
            return;
        }
        sender.sendMessage("§e===== 案件 #" + c.id() + " =====");
        sender.sendMessage("§7状态: §f" + stateCn(c.state()));
        sender.sendMessage("§7被告: §f" + c.defendantName());
        sender.sendMessage("§7原告: §f" + c.plaintiffDisplayName() + (c.judgeUuid() == null ? "" : "（" + c.judgeName() + " 公诉）"));
        if (c.defenseLawyerUuid() != null) {
            sender.sendMessage("§7被告律师: §f" + c.defenseLawyerName());
        }
        if (c.prosecutionLawyerUuid() != null) {
            sender.sendMessage("§7原告律师: §f" + c.prosecutionLawyerName());
        }
        sender.sendMessage("§7案由: §f" + c.reason());
        if (c.judgeUuid() != null) {
            sender.sendMessage("§7法官: §f" + c.judgeName());
        }
        if (c.sentenceAmount() > 0) {
            sender.sendMessage("§7刑期: §f挖 " + c.sentenceAmount() + " 个" + breakableDisplayName);
        }
    }

    /** history [玩家]：已结案记录。 */
    public void history(CommandSender sender, String playerName) {
        boolean filter = playerName != null && !playerName.isEmpty();
        int shown = 0;
        sender.sendMessage("§e===== 历史案件 =====");
        for (CourtCase c : cases.values()) {
            if (c.isActive()) {
                continue;
            }
            if (filter && !c.defendantName().equalsIgnoreCase(playerName)
                    && !c.plaintiffName().equalsIgnoreCase(playerName)) {
                continue;
            }
            sender.sendMessage("§a#" + c.id() + " §7[" + stateCn(c.state()) + "] §f被告: " + c.defendantName()
                    + " §f原告: " + c.plaintiffDisplayName() + " §7案由: " + c.reason());
            shown++;
        }
        if (shown == 0) {
            sender.sendMessage("§e（无符合条件的已结案案件）");
        }
    }

    // ============================================================== 单点/按钮标记
    /** pos <judge|defendant|plaintiff>：站在法庭世界标记单点。 */
    public boolean markPoint(String kind, Player p) {
        if (!isInPrisonWorld(p)) {
            p.sendMessage("§c请在监狱世界（法庭）内标记位置。");
            return false;
        }
        Location loc = p.getLocation();
        Point point = new Point(prisonWorldName, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        switch (kind) {
            case "judge" -> judgePoint = point;
            case "defendant" -> defendantPoint = point;
            case "plaintiff" -> plaintiffPoint = point;
            default -> {
                p.sendMessage("§c标记类型须为 judge|defendant|plaintiff。");
                return false;
            }
        }
        if (courtroomWorld == null) {
            courtroomWorld = prisonWorldName;
        }
        saveCourtroom();
        p.sendMessage("§a已标记法庭位置: " + kindLabel(kind));
        return true;
    }

    /** pos spectator <编号>：标记观众席（覆盖重标）。 */
    public boolean markSpectator(int n, Player p) {
        if (n < 1) {
            p.sendMessage("§c座位号必须 >= 1。");
            return false;
        }
        if (!isInPrisonWorld(p)) {
            p.sendMessage("§c请在监狱世界（法庭）内标记位置。");
            return false;
        }
        Location loc = p.getLocation();
        spectatorSeats.put(n, new Point(prisonWorldName, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
        if (courtroomWorld == null) {
            courtroomWorld = prisonWorldName;
        }
        saveCourtroom();
        p.sendMessage("§a已标记观众席 #" + n);
        return true;
    }

    /** pos del spectator <编号>：删除观众席（被占用时拒绝）。 */
    public boolean delSpectator(int n, Player p) {
        if (!spectatorSeats.containsKey(n)) {
            p.sendMessage("§c观众席 #" + n + " 未标记。");
            return false;
        }
        if (seatOccupied(n)) {
            p.sendMessage("§c观众席 #" + n + " 正在被使用，无法删除。");
            return false;
        }
        spectatorSeats.remove(n);
        saveCourtroom();
        p.sendMessage("§a已删除观众席 #" + n);
        return true;
    }

    /** v4.14 pos region <1|2>：站在法院区域角点标记（两个角点齐全后区域生效，禁破坏/放置）。 */
    public boolean markRegionCorner(int which, Player p) {
        if (which != 1 && which != 2) {
            p.sendMessage("§c用法: /court pos region <1|2>");
            return false;
        }
        if (!isInPrisonWorld(p)) {
            p.sendMessage("§c请在监狱世界（法庭）内标记区域角点。");
            return false;
        }
        Location loc = p.getLocation();
        Point point = new Point(prisonWorldName, loc.getX(), loc.getY(), loc.getZ(), 0.0f, 0.0f);
        if (which == 1) {
            regionCorner1 = point;
        } else {
            regionCorner2 = point;
        }
        if (courtroomWorld == null) {
            courtroomWorld = prisonWorldName;
        }
        saveCourtroom();
        p.sendMessage("§a已标记法院区域角点 " + which + "（" + pointText(point)
                + (regionReady() ? "，区域已生效——区域内所有人禁破坏/放置方块" : "，还需标记另一个角点") + "）");
        return true;
    }

    /** v4.14 pos del region：删除法院区域（两个角点清空）。 */
    public boolean delRegion(Player p) {
        if (regionCorner1 == null && regionCorner2 == null) {
            p.sendMessage("§c法院区域尚未标记。");
            return false;
        }
        regionCorner1 = null;
        regionCorner2 = null;
        saveCourtroom();
        p.sendMessage("§a已删除法院区域（区域内恢复可破坏/放置）。");
        return true;
    }

    /** 法院区域是否两个角点齐全。 */
    private boolean regionReady() {
        return regionCorner1 != null && regionCorner2 != null;
    }

    /** v4.14 法院区域保护开关（court.yml protect-region）。 */
    public boolean isProtectRegionEnabled() {
        return protectRegion;
    }

    /** v4.14 位置是否在法院区域内（区域未标记/世界不符 → false）。 */
    public boolean isInCourtRegion(Location loc) {
        if (!regionReady() || loc == null || loc.getWorld() == null) {
            return false;
        }
        Point a = regionCorner1;
        Point b = regionCorner2;
        if (!a.world().equals(b.world()) || !a.world().equals(loc.getWorld().getName())) {
            return false;
        }
        double minX = Math.min(a.x(), b.x());
        double maxX = Math.max(a.x(), b.x());
        double minY = Math.min(a.y(), b.y());
        double maxY = Math.max(a.y(), b.y());
        double minZ = Math.min(a.z(), b.z());
        double maxZ = Math.max(a.z(), b.z());
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** pos list：显示法庭标记（观众席含占用者、按钮）。 */
    public void posList(CommandSender sender) {
        sender.sendMessage("§e===== 法庭位置 =====");
        sender.sendMessage("§7世界: §f" + (courtroomWorld == null ? "未设置" : courtroomWorld));
        sender.sendMessage("§7法官席: " + pointText(judgePoint));
        sender.sendMessage("§7被告席: " + pointText(defendantPoint));
        sender.sendMessage("§7原告席: " + pointText(plaintiffPoint));
        sender.sendMessage("§7律师: §f开庭时自动站在委托人旁边（v4.17，无需标记律师席）");
        sender.sendMessage("§6观众席 (" + spectatorSeats.size() + " 座):");
        if (spectatorSeats.isEmpty()) {
            sender.sendMessage("§7（尚未标记观众席，用 /court pos spectator <编号> 标记）");
        } else {
            for (Map.Entry<Integer, Point> e : spectatorSeats.entrySet()) {
                String occ = occupantName(e.getKey());
                sender.sendMessage("§7#" + e.getKey() + " §f" + e.getValue()
                        + (occ == null ? " §a空闲" : " §c占用: " + occ));
            }
        }
        sender.sendMessage("§6按钮:");
        for (String k : List.of("left", "middle", "right", "done-plaintiff", "done-defendant")) {
            ButtonPoint bp = buttons.get(k);
            String label = switch (k) {
                case "left" -> "左(原告发言)";
                case "middle" -> "中(确认开庭/宣判)";
                case "right" -> "右(被告发言)";
                case "done-plaintiff" -> "原告发言完毕";
                case "done-defendant" -> "被告发言完毕";
                default -> k;
            };
            sender.sendMessage("§7" + label + (bp == null ? " §a未标记" : " §f" + bp));
        }
        // v4.14 法院区域
        if (regionReady()) {
            sender.sendMessage("§6法院区域: §f" + pointText(regionCorner1) + " ~ " + pointText(regionCorner2)
                    + " §a（已生效，区域内禁破坏/放置）");
        } else {
            sender.sendMessage("§6法院区域: §7未标记（/court pos region <1|2> 标两个角点，禁破坏/放置）");
        }
    }

    /** btn <left|middle|right|done-plaintiff|done-defendant>：看着石头按钮标记。 */
    public boolean markButton(String name, Player p) {
        if (!name.equals("left") && !name.equals("middle") && !name.equals("right")
                && !name.equals("done-plaintiff") && !name.equals("done-defendant")) {
            p.sendMessage("§c按钮名须为 left|middle|right|done-plaintiff|done-defendant。");
            return false;
        }
        Block target = p.getTargetBlockExact(5);
        if (target == null || target.getType() != Material.STONE_BUTTON) {
            p.sendMessage("§c请看着石头按钮再标记（距离 5 格内）。");
            return false;
        }
        buttons.put(name, new ButtonPoint(target.getWorld().getName(), target.getX(), target.getY(), target.getZ()));
        if (courtroomWorld == null) {
            courtroomWorld = target.getWorld().getName();
        }
        saveCourtroom();
        p.sendMessage("§a已标记 " + name + " 按钮");
        return true;
    }

    /** btn del <left|middle|right>：删除按钮标记。 */
    public boolean delButton(String name, Player p) {
        if (buttons.remove(name) != null) {
            saveCourtroom();
            p.sendMessage("§a已删除 " + name + " 按钮标记");
            return true;
        }
        p.sendMessage("§c按钮 " + name + " 未标记。");
        return false;
    }

    // ============================================================== 法庭传送与回传
    /** 开庭传送：快照 + 传送 + 定身 + 角色名牌 + 就位广播。 */
    private void teleportParticipants(int caseId, Player judge, CourtCase c) {
        if (!courtroomEnabled) {
            return;
        }
        if (!isCourtroomReady()) {
            plugin.getLogger().warning("法院开庭 #" + caseId + "：法庭单点不齐（需法官/被告/原告席），退化为聊天庭审，不传送。");
            return;
        }
        World world = Bukkit.getWorld(courtroomWorld);
        if (world == null) {
            plugin.getLogger().warning("法院开庭 #" + caseId + "：法庭世界 '" + courtroomWorld + "' 未加载，不传送。");
            return;
        }
        int count = 0;

        // 法官席（不限制）
        teleportSeed(caseId, judge, judgePoint, "法官席", "法官", false);
        count++;

        // 被告席
        Player defendant = Bukkit.getPlayer(c.defendantUuid());
        if (defendant != null && defendant.isOnline()) {
            teleportSeed(caseId, defendant, defendantPoint, "被告席", "被告", true);
            count++;
        }

        // 原告席
        Player plaintiff = c.plaintiffUuid() == null ? null : Bukkit.getPlayer(c.plaintiffUuid());
        if (plaintiff != null && plaintiff.isOnline()) {
            teleportSeed(caseId, plaintiff, plaintiffPoint, "原告席", "原告", true);
            count++;
        }

        // 被告律师（v4.17：始终站在委托人旁边，无需标记律师席）
        Player defLawyer = c.defenseLawyerUuid() == null ? null : Bukkit.getPlayer(c.defenseLawyerUuid());
        if (defLawyer != null && defLawyer.isOnline()) {
            Point seat = beside(defendantPoint);
            teleportSeed(caseId, defLawyer, seat, "辩护位（被告律师）", "被告律师", true);
            count++;
        }

        // 原告律师
        Player prosLawyer = c.prosecutionLawyerUuid() == null ? null : Bukkit.getPlayer(c.prosecutionLawyerUuid());
        if (prosLawyer != null && prosLawyer.isOnline()) {
            Point seat = beside(plaintiffPoint);
            teleportSeed(caseId, prosLawyer, seat, "原告律师位", "原告律师", true);
            count++;
        }

        // 已报名且在线观众
        Set<UUID> watcherSet = watchers.get(caseId);
        if (watcherSet != null) {
            for (UUID watcherUuid : new ArrayList<>(watcherSet)) {
                Player watcher = Bukkit.getPlayer(watcherUuid);
                if (watcher == null || !watcher.isOnline()) {
                    continue;
                }
                int seat = nextFreeSeat(caseId);
                if (seat == -1) {
                    watcher.sendMessage("§c观众席已满，未能就座。");
                    continue;
                }
                seatOccupancyFor(caseId).put(seat, watcher.getUniqueId());
                teleportSeed(caseId, watcher, spectatorSeats.get(seat), "观众席 #" + seat, null, true);
                count++;
            }
        }
        broadcast("§e[法院] 庭审人员已就位：法官/被告/原告/律师/观众 " + count + " 人");
        globalSound(org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f); // v4.16 就位音效
    }

    /** 传送单个参与者：快照 + 传送 + 定身(可选) + 名牌(可选) + 私聊就位提示。 */
    private void teleportSeed(int caseId, Player p, Point point, String seatDisplay, String tagPrefix, boolean frozen) {
        if (point == null) {
            return;
        }
        snapshotLocation(p, caseId); // v4.19：位置快照带案件号
        snapshotCourtInventory(p); // v4.18：进入法庭清空背包（快照托管）
        teleportTo(p, point);
        if (frozen && freezeSeats) {
            freeze(p);
        }
        if (tagPrefix != null) {
            setRoleNameTag(p, tagPrefix);
        }
        p.sendMessage("§a你已就位（" + seatDisplay + "），庭审即将开始");
        soundTo(p, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f); // v4.16 个人就位音效
    }

    /** 结案回传：在线参与者传送回原位置，解除定身、恢复名牌；离线参与者保留快照（v4.19：重进服时回传）。 */
    public void returnAllParticipants() {
        for (Map.Entry<UUID, CourtReturn> e : new ArrayList<>(teleportSnapshots.entrySet())) {
            UUID uuid = e.getKey();
            CourtReturn ret = e.getValue();
            Location target = ret.location();
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                if (prisonerManager.isImprisoned(uuid)) {
                    // v4.15 修复：宣判有罪的被告已被收监（牢房在监狱世界=法庭世界），
                    // 世界检查会误判放行——禁止回传，防止"宣判即越狱"
                    unfreeze(p);
                    restoreNameTag(p);
                    p.sendMessage("§c你已被收监，服刑期间请安心改造。");
                    teleportSnapshots.remove(uuid); // 已收监，快照作废
                    continue;
                }
                restoreCourtInventory(p); // v4.18：审判结束还原背包
                if (target != null && target.getWorld() != null
                        && p.getWorld().getName().equals(courtroomWorld)) {
                    p.teleport(target);
                    soundTo(p, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f); // v4.16 回传音效
                }
                unfreeze(p);
                restoreNameTag(p);
                p.sendMessage("§a庭审结束，已传送回原位置");
                teleportSnapshots.remove(uuid); // 在线已回传
            }
            // 离线参与者：保留快照（court-inv.yml 持久化），重进服时由 onPlayerJoin 回传
        }
        saveCourtInv();
        nameTagSnapshots.clear();
        seatOccupancy.clear();
        watchers.clear();
        lawyerCandidates.clear();
        frozenPlayers.clear();
        activeTrial = null;
        speaking = Speaking.NONE;
        speakingCaseId = -1;
        turnTimedOut = false;
        if (turnTask != null) {
            turnTask.cancel();
            turnTask = null;
        }
        turnRemaining = 0;
        hideTurnDisplays();
    }

    /** 清空某案件的内存态（座位/候选人/旁听报名/宣判输入模式）。 */
    private void clearCaseState(int caseId) {
        watchers.remove(caseId);
        seatOccupancy.remove(caseId);
        lawyerCandidates.remove(caseId);
        confirmedCases.remove(caseId);
        // v4.20：清理指向该案件的宣判输入模式（防结案后残留误触发）
        verdictInput.entrySet().removeIf(e -> e.getValue() == caseId);
    }

    // ============================================================== 定身 / 名牌
    private void freeze(Player p) {
        if (frozenPlayers.add(p.getUniqueId())) {
            p.setWalkSpeed(0f);
            p.leaveVehicle();
        }
    }

    private void unfreeze(Player p) {
        if (frozenPlayers.remove(p.getUniqueId())) {
            p.setWalkSpeed(0.2f);
        }
    }

    public boolean isFrozen(Player p) {
        return frozenPlayers.contains(p.getUniqueId());
    }

    private void setRoleNameTag(Player p, String role) {
        if (!nameTagSnapshots.containsKey(p.getUniqueId())) {
            nameTagSnapshots.put(p.getUniqueId(), new NameTagSnapshot(p.getCustomName(), p.isCustomNameVisible()));
        }
        String color = switch (role) {
            case "法官" -> "§6";
            case "被告", "被告律师" -> "§c";
            case "原告", "原告律师" -> "§b";
            default -> "§f";
        };
        p.setCustomName(color + "[" + role + "] " + p.getName());
        p.setCustomNameVisible(true);
    }

    private void restoreNameTag(Player p) {
        NameTagSnapshot snap = nameTagSnapshots.remove(p.getUniqueId());
        if (snap != null) {
            p.setCustomName(snap.customName());
            p.setCustomNameVisible(snap.visible());
        } else {
            p.setCustomName(null);
            p.setCustomNameVisible(false);
        }
    }

    // ============================================================== 座位 / 快照辅助
    private Map<Integer, UUID> seatOccupancyFor(int caseId) {
        return seatOccupancy.computeIfAbsent(caseId, k -> new LinkedHashMap<>());
    }

    private Set<UUID> watcherSet(int caseId) {
        return watchers.computeIfAbsent(caseId, k -> new LinkedHashSet<>());
    }

    /** 观众容量 = min(spectators-max, 已标记观众席数)。 */
    private int spectatorCapacity() {
        return Math.min(spectatorsMax, spectatorSeats.size());
    }

    private int nextFreeSeat(int caseId) {
        Map<Integer, UUID> occ = seatOccupancyFor(caseId);
        int cap = spectatorCapacity();
        int count = 0;
        for (int seat : new java.util.TreeSet<>(spectatorSeats.keySet())) {
            if (count++ >= cap) {
                break;
            }
            if (!occ.containsKey(seat)) {
                return seat;
            }
        }
        return -1;
    }

    private void releaseSeat(int caseId, UUID uuid) {
        Map<Integer, UUID> occ = seatOccupancy.get(caseId);
        if (occ != null) {
            occ.values().remove(uuid);
        }
    }

    private boolean seatOccupied(int seat) {
        for (Map<Integer, UUID> occ : seatOccupancy.values()) {
            if (occ.containsKey(seat)) {
                return true;
            }
        }
        return false;
    }

    private String occupantName(int seat) {
        for (Map<Integer, UUID> occ : seatOccupancy.values()) {
            UUID uuid = occ.get(seat);
            if (uuid != null) {
                Player on = Bukkit.getPlayer(uuid);
                if (on != null) {
                    return on.getName();
                }
                OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
                return off.getName();
            }
        }
        return null;
    }

    private void snapshotLocation(Player p, int caseId) {
        teleportSnapshots.putIfAbsent(p.getUniqueId(), new CourtReturn(p.getLocation(), caseId));
        saveCourtInv(); // v4.19：位置快照持久化
    }

    private void teleportTo(Player p, Point point) {
        if (point == null || point.world() == null) {
            return;
        }
        World w = Bukkit.getWorld(point.world());
        if (w == null) {
            return;
        }
        p.teleport(new Location(w, point.x(), point.y(), point.z(), point.yaw(), point.pitch()));
    }

    private void teleportBack(Player p) {
        CourtReturn ret = teleportSnapshots.remove(p.getUniqueId());
        saveCourtInv();
        if (ret != null && ret.location() != null && ret.location().getWorld() != null) {
            p.teleport(ret.location());
        }
    }

    // ============================================================== 法庭暂存（v4.18 背包 + v4.19 位置，court-inv.yml）
    /** 从 court-inv.yml 加载法庭背包与位置快照（重启恢复；单条损坏跳过并 warn）。 */
    private void loadCourtInv() {
        courtInvSnapshots.clear();
        teleportSnapshots.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(courtInvFile);
        ConfigurationSection sec = cfg.getConfigurationSection("court-inv");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ItemStack[] slots = new ItemStack[41];
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s != null) {
                    for (String slotKey : s.getKeys(false)) {
                        if (slotKey.equals("loc") || slotKey.equals("case")) {
                            continue;
                        }
                        ItemStack item = s.getItemStack(slotKey);
                        if (item == null) {
                            continue;
                        }
                        try {
                            slots[Integer.parseInt(slotKey)] = item;
                        } catch (NumberFormatException ex) {
                            // 忽略非法槽位键
                        }
                    }
                }
                courtInvSnapshots.put(uuid, slots);
                // v4.19：位置快照（世界缺失则 loc=null，回传时跳过传送）
                int caseId = cfg.getInt("court-inv." + key + ".case", -1);
                Location loc = null;
                String worldName = cfg.getString("court-inv." + key + ".loc.world");
                if (worldName != null && cfg.contains("court-inv." + key + ".loc.x")) {
                    World w = Bukkit.getWorld(worldName);
                    if (w != null) {
                        loc = new Location(w,
                                cfg.getDouble("court-inv." + key + ".loc.x"),
                                cfg.getDouble("court-inv." + key + ".loc.y"),
                                cfg.getDouble("court-inv." + key + ".loc.z"),
                                (float) cfg.getDouble("court-inv." + key + ".loc.yaw", 0.0),
                                (float) cfg.getDouble("court-inv." + key + ".loc.pitch", 0.0));
                    }
                }
                if (caseId > 0) {
                    teleportSnapshots.put(uuid, new CourtReturn(loc, caseId));
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("跳过损坏的法庭暂存记录 '" + key + "'。");
            }
        }
        if (!courtInvSnapshots.isEmpty() || !teleportSnapshots.isEmpty()) {
            plugin.getLogger().info("已恢复法庭暂存（背包 " + courtInvSnapshots.size() + " 份，位置 "
                    + teleportSnapshots.size() + " 份）。");
        }
    }

    /** 全量落盘 court-inv.yml（变更即保存）。 */
    private void saveCourtInv() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, ItemStack[]> e : courtInvSnapshots.entrySet()) {
            String path = "court-inv." + e.getKey();
            ItemStack[] slots = e.getValue();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null) {
                    cfg.set(path + "." + i, slots[i]);
                }
            }
        }
        // v4.19：位置快照（案件号 + 坐标）
        for (Map.Entry<UUID, CourtReturn> e : teleportSnapshots.entrySet()) {
            String path = "court-inv." + e.getKey();
            CourtReturn ret = e.getValue();
            cfg.set(path + ".case", ret.caseId());
            if (ret.location() != null && ret.location().getWorld() != null) {
                cfg.set(path + ".loc.world", ret.location().getWorld().getName());
                cfg.set(path + ".loc.x", ret.location().getX());
                cfg.set(path + ".loc.y", ret.location().getY());
                cfg.set(path + ".loc.z", ret.location().getZ());
                cfg.set(path + ".loc.yaw", ret.location().getYaw());
                cfg.set(path + ".loc.pitch", ret.location().getPitch());
            }
        }
        try {
            cfg.save(courtInvFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 court-inv.yml 失败: " + ex.getMessage());
        }
    }

    /** 进入法庭：快照当前背包（全槽位克隆）→ 清空。重复进入以最新为准。 */
    private void snapshotCourtInventory(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            copy[i] = (it == null || it.getType() == Material.AIR) ? null : it.clone();
        }
        courtInvSnapshots.put(p.getUniqueId(), copy);
        p.getInventory().clear();
        saveCourtInv();
    }

    /**
     * 还原法庭背包（结案回传/提前离开/宣判收监前/退出兜底调用）。
     *
     * @return 是否确有快照被还原
     */
    public boolean restoreCourtInventory(Player p) {
        ItemStack[] snap = courtInvSnapshots.remove(p.getUniqueId());
        if (snap == null) {
            return false;
        }
        p.getInventory().clear();
        for (int i = 0; i < snap.length && i < p.getInventory().getSize(); i++) {
            if (snap[i] != null) {
                p.getInventory().setItem(i, snap[i]);
            }
        }
        saveCourtInv();
        return true;
    }

    /** v4.17 律师站位：委托人座位的右侧 1 格（按委托人朝向计算），与委托人同朝向。 */
    private Point beside(Point anchor) {
        if (anchor == null) {
            return null;
        }
        double yawRad = Math.toRadians(anchor.yaw());
        double rx = Math.cos(yawRad);
        double rz = -Math.sin(yawRad);
        return new Point(anchor.world(),
                anchor.x() + rx, anchor.y(), anchor.z() + rz,
                anchor.yaw(), anchor.pitch());
    }

    // ============================================================== 法官桌按钮系统
    /** PlayerInteractEvent 处理器：右键点中已标记按钮。 */
    public void onButtonInteract(Player p, Block block) {
        if (!buttonsEnabled) {
            return;
        }
        validateButtonBlocks(); // v4.19：按钮被拆除自动失效
        String which = matchButton(block);
        if (which == null) {
            return;
        }
        if (!isJudge(p)) {
            p.sendMessage("§c只有审判长能操作");
            return;
        }
        int caseId = mostRecentTrialCaseId();
        if (caseId == -1) {
            p.sendMessage("§c当前没有进行中的庭审。");
            return;
        }
        displayCaseId = caseId;
        CourtCase c = cases.get(caseId);
        if (c == null || c.state() != CourtCase.State.TRIAL) {
            p.sendMessage("§c该案件已结案。");
            return;
        }
        switch (which) {
            case "left" -> handleTurn(caseId, Speaking.PLAINTIFF);
            case "right" -> handleTurn(caseId, Speaking.DEFENDANT);
            case "middle" -> handleMiddle(caseId, p);
            case "done-plaintiff" -> handleDone(caseId, Speaking.PLAINTIFF, p);
            case "done-defendant" -> handleDone(caseId, Speaking.DEFENDANT, p);
            default -> {
            }
        }
    }

    private String matchButton(Block block) {
        // v4.19：方块必须仍是石头按钮——被拆除/替换后按钮自动失效（无需指令取消）
        if (block.getType() != Material.STONE_BUTTON) {
            return null;
        }
        for (Map.Entry<String, ButtonPoint> e : buttons.entrySet()) {
            ButtonPoint bp = e.getValue();
            if (bp.world().equals(block.getWorld().getName())
                    && bp.x() == block.getX() && bp.y() == block.getY() && bp.z() == block.getZ()) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * v4.19 按钮自动失效：检查已标记按钮的方块是否仍是石头按钮，
     * 被拆除/替换的按钮标记自动移除（无需 /court btn del）并重建悬浮字。
     *
     * @return 是否有标记被清理
     */
    private boolean validateButtonBlocks() {
        boolean changed = false;
        for (java.util.Iterator<Map.Entry<String, ButtonPoint>> it = buttons.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ButtonPoint> e = it.next();
            ButtonPoint bp = e.getValue();
            World w = Bukkit.getWorld(bp.world());
            Block b = (w == null) ? null : w.getBlockAt(bp.x(), bp.y(), bp.z());
            if (b == null || b.getType() != Material.STONE_BUTTON) {
                it.remove();
                changed = true;
                plugin.getLogger().info("法官桌 " + e.getKey() + " 按钮方块已不存在/被替换，自动取消标记（无需指令）。");
            }
        }
        if (changed) {
            saveCourtroom();
            removeDisplays();
            displaysCreated = false;
        }
        return changed;
    }

    /** 左/右按钮：切轮次 / 结束发言。 */
    private void handleTurn(int caseId, Speaking newSide) {
        if (speaking == newSide) {
            endTurn();
            broadcast("§e[法院] §f" + sideCn(newSide) + " 发言结束");
            globalSound(org.bukkit.Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f); // v4.20 结束发言音效
        } else {
            endTurn();
            speaking = newSide;
            speakingCaseId = caseId;
            broadcast("§e[法院] §f轮到 §b" + sideCn(newSide) + " §f发言（限时 " + formatTurnLimit(turnSeconds) + "）");
            globalSound(org.bukkit.Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f); // v4.20 切换轮次音效
            remindSpeakingSide(newSide, caseId);
            updateTurnDisplays();
            startTurnCountdown(caseId);
        }
    }

    /** 发言完毕按钮（v4.19 增补：原告/被告席位各一个，发言人自己按）：结束当前本方发言。 */
    private void handleDone(int caseId, Speaking side, Player p) {
        if (speaking != side || speakingCaseId != caseId) {
            p.sendMessage("§c现在不是" + sideCn(side) + "的发言时间，不能结束发言。");
            return;
        }
        // 仅当前发言方（或其律师）可按；法官可代按
        if (!isJudge(p)) {
            CourtCase c = cases.get(caseId);
            UUID uuid = p.getUniqueId();
            boolean onSide = side == Speaking.DEFENDANT
                    ? (uuid.equals(c.defendantUuid()) || uuid.equals(c.defenseLawyerUuid()))
                    : ((c.plaintiffUuid() != null && uuid.equals(c.plaintiffUuid()))
                        || uuid.equals(c.prosecutionLawyerUuid()));
            if (!onSide) {
                p.sendMessage("§c只有" + sideCn(side) + "（或其律师）可以按发言完毕按钮。");
                return;
            }
        }
        endTurn();
        broadcast("§e[法院] §f" + sideCn(side) + " 发言完毕");
        globalSound(org.bukkit.Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.2f); // v4.20 发言完毕按钮音
    }

    /** 结束发言（NONE）：停止倒计时，悬浮字隐藏。 */
    private void endTurn() {
        speaking = Speaking.NONE;
        speakingCaseId = -1;
        turnTimedOut = false;
        turnRemaining = 0;
        if (turnTask != null) {
            turnTask.cancel();
            turnTask = null;
        }
        updateTurnDisplays();
    }

    private void remindSpeakingSide(Speaking side, int caseId) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            return;
        }
        Player target = null;
        if (side == Speaking.DEFENDANT) {
            Player def = Bukkit.getPlayer(c.defendantUuid());
            if (def != null && def.isOnline()) {
                target = def;
            }
        } else {
            if (c.plaintiffUuid() != null) {
                Player pl = Bukkit.getPlayer(c.plaintiffUuid());
                if (pl != null && pl.isOnline()) {
                    target = pl;
                }
            }
        }
        if (target != null) {
            target.sendMessage("§e轮到你发言了，请陈述。");
            soundTo(target, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f); // v4.20 轮到你发言清脆提醒
        }
    }

    /** 轮次倒计时（turn-seconds，0=关闭）：到点只提醒不自动切换。 */
    private void startTurnCountdown(int caseId) {
        if (turnSeconds <= 0) {
            return;
        }
        turnRemaining = turnSeconds;
        turnTimedOut = false;
        if (turnTask != null) {
            turnTask.cancel();
        }
        turnTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (speaking == Speaking.NONE || speakingCaseId != caseId) {
                    this.cancel();
                    return;
                }
                CourtCase c = cases.get(caseId);
                if (c == null || c.state() != CourtCase.State.TRIAL) {
                    this.cancel();
                    return;
                }
                validateButtonBlocks(); // v4.19：庭审中按钮被拆除 → 每秒检查自动失效
                if (turnRemaining > 0) {
                    turnRemaining--;
                    updateTurnDisplays();
                } else if (!turnTimedOut) {
                    turnTimedOut = true;
                    this.cancel();
                    broadcast("§e[法院] §f" + sideCn(speaking) + " 发言时间已到");
                    globalSound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f); // v4.20 时间到低音警示
                    updateTurnDisplays();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** 中按钮两段式：第一次确认开庭，之后切换宣判输入模式。 */
    private void handleMiddle(int caseId, Player judge) {
        CourtCase c = cases.get(caseId);
        if (c == null || c.state() != CourtCase.State.TRIAL) {
            judge.sendMessage("§c该案件已结案。");
            return;
        }
        displayCaseId = caseId;
        if (!confirmedCases.contains(caseId)) {
            confirmedCases.add(caseId);
            sendTitleToParticipants(caseId, judge, "§6案件 #" + caseId + " §f正式开庭！", "§7庭审开始，请保持肃静");
            broadcast("§e[法院] §f审判长确认人到齐，开庭！");
            globalSound(org.bukkit.Sound.BLOCK_BELL_RESONATE, 1.0f); // v4.20 确认开庭钟声回荡
            updateMiddleDisplay();
        } else {
            Integer cur = verdictInput.get(judge.getUniqueId());
            if (cur != null && cur == caseId) {
                verdictInput.remove(judge.getUniqueId());
                judge.sendMessage("§e已取消宣判输入模式。");
            } else {
                verdictInput.put(judge.getUniqueId(), caseId);
                broadcast("§e[法院] 审判长即将宣判！");
                soundTo(judge, org.bukkit.Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.8f); // v4.20 宣判模式音效
                judge.sendMessage("§e请在聊天框写入最终判决文字或指令（如 /ban、/prison send），提交后全场屏幕标题展示：");
                judge.sendMessage("§e  标题展示后 " + verdictEndSeconds + " 秒庭审自动结束，全员传回原位");
                judge.sendMessage("§e  含「无罪」记录为无罪结案，其余记录为有罪（惩罚由你写入的指令执行）");
                judge.sendMessage("§e  再点一次中间按钮可取消");
            }
        }
    }

    /** 给该案全部在线参与者发送屏幕标题。 */
    private void sendTitleToParticipants(int caseId, Player judge, String title, String subtitle) {
        CourtCase c = cases.get(caseId);
        if (c == null) {
            return;
        }
        Set<UUID> ids = new HashSet<>();
        if (judge != null) {
            ids.add(judge.getUniqueId());
        }
        ids.add(c.defendantUuid());
        if (c.plaintiffUuid() != null) {
            ids.add(c.plaintiffUuid());
        }
        if (c.defenseLawyerUuid() != null) {
            ids.add(c.defenseLawyerUuid());
        }
        if (c.prosecutionLawyerUuid() != null) {
            ids.add(c.prosecutionLawyerUuid());
        }
        Set<UUID> watcherSet = watchers.get(caseId);
        if (watcherSet != null) {
            ids.addAll(watcherSet);
        }
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.sendTitle(title, subtitle, 10, 60, 10);
            }
        }
    }

    /**
     * 宣判输入提交（主线程 runTask 后调用）——v4.21 定稿流程：
     * 法官在聊天框写入判决文字/指令并提交 → 这段字以屏幕标题展示给在场的所有人 → 5 秒后庭审自动结束
     * （历史状态按文字判定：含「无罪」= INNOCENT，否则 GUILTY）→ 全员传回进入法庭前的状态与坐标。
     * 插件不代执行任何惩罚——惩罚（/ban、/prison send 等）由法官自己写入的指令执行（命令通道放行）。
     */
    public void onVerdictText(UUID judgeUuid, String text) {
        Integer caseId = verdictInput.remove(judgeUuid);
        if (caseId == null) {
            return;
        }
        CourtCase c = cases.get(caseId);
        Player judge = Bukkit.getPlayer(judgeUuid);
        if (c == null || c.state() != CourtCase.State.TRIAL) {
            if (judge != null) {
                judge.sendMessage("§c该案件已结案，宣判输入已取消。");
            }
            return;
        }
        // 1) 标题展示给在场所有人 + 判决书广播 + 法槌音
        String wrapped = wrapText(text, 20);
        sendTitleToParticipants(caseId, judge, "§6⚖ 最终判决", wrapped);
        broadcast("§e[法院] §6判决书 §f" + text);
        globalSound(org.bukkit.Sound.BLOCK_ANVIL_LAND, 1.0f); // v4.20 判决法槌落槌
        // 2) 历史状态按文字判定（不代执行惩罚）
        boolean innocent = text.contains("无罪") || text.toLowerCase().contains("innocent");
        CourtCase.State finalState = innocent ? CourtCase.State.INNOCENT : CourtCase.State.GUILTY;
        // 3) 显示标题后 verdict-end-seconds 秒自动结束庭审并回传
        long delayTicks = Math.max(0L, (long) verdictEndSeconds * 20L);
        new BukkitRunnable() {
            @Override
            public void run() {
                CourtCase cur = cases.get(caseId);
                if (cur == null || cur.state() != CourtCase.State.TRIAL) {
                    return; // 已被其他路径（如 /court verdict）结案
                }
                CourtCase updated = cur.closeVerdict(finalState, 0, System.currentTimeMillis());
                cases.put(caseId, updated);
                saveCases();
                if (innocent) {
                    broadcast("§e[法院] §6案件 #" + caseId + " §f庭审结束——§a判决：无罪");
                } else {
                    broadcast("§e[法院] §6案件 #" + caseId + " §f庭审结束——判决：有罪（惩罚由法官指令执行）");
                }
                closeCaseAfterVerdict(caseId, updated);
            }
        }.runTaskLater(plugin, delayTicks);
        if (judge != null) {
            judge.sendMessage("§a判决已提交，全场标题已展示——" + verdictEndSeconds
                    + " 秒后庭审结束，所有人员传回进入法庭前的状态和坐标。");
        }
    }

    // ============================================================== TextDisplay 悬浮字
    private void ensureDisplays() {
        if (!buttonsEnabled) {
            return;
        }
        validateButtonBlocks(); // v4.19：方块被拆除 → 标记自动失效（先清理再重建）
        if (displaysCreated) {
            return;
        }
        ButtonPoint left = buttons.get("left");
        if (left != null) {
            leftDisplay = spawnTextDisplay(left, "");
        }
        ButtonPoint middle = buttons.get("middle");
        if (middle != null) {
            middleDisplay = spawnTextDisplay(middle, "");
        }
        ButtonPoint right = buttons.get("right");
        if (right != null) {
            rightDisplay = spawnTextDisplay(right, "");
        }
        // v4.19：发言完毕按钮悬浮字（原告席/被告席）
        ButtonPoint doneP = buttons.get("done-plaintiff");
        if (doneP != null) {
            donePlaintiffDisplay = spawnTextDisplay(doneP, "");
        }
        ButtonPoint doneD = buttons.get("done-defendant");
        if (doneD != null) {
            doneDefendantDisplay = spawnTextDisplay(doneD, "");
        }
        displaysCreated = true;
        updateMiddleDisplay();
        updateTurnDisplays();
    }

    private TextDisplay spawnTextDisplay(ButtonPoint bp, String text) {
        World w = Bukkit.getWorld(bp.world());
        if (w == null) {
            return null;
        }
        try {
            Location loc = new Location(w, bp.x() + 0.5, bp.y() + 1.0, bp.z() + 0.5);
            TextDisplay d = w.spawn(loc, TextDisplay.class);
            d.setText(text);
            d.setBillboard(Display.Billboard.CENTER);
            // v4.x：26.2 的 Display/TextDisplay 没有 setMarker(boolean)，已移除（原想让悬浮字无形无碰撞）。
            d.setSeeThrough(true);
            d.setShadowed(false);
            d.setViewRange(0.5f);
            return d;
        } catch (Throwable t) {
            plugin.getLogger().warning("生成法庭按钮悬浮字失败: " + t.getMessage());
            return null;
        }
    }

    private void updateTurnDisplays() {
        ensureDisplays();
        boolean leftOn = speaking == Speaking.PLAINTIFF;
        boolean rightOn = speaking == Speaking.DEFENDANT;
        if (leftDisplay != null) {
            leftDisplay.setText(leftOn ? turnText("原告", Speaking.PLAINTIFF) : "");
        }
        if (rightDisplay != null) {
            rightDisplay.setText(rightOn ? turnText("被告", Speaking.DEFENDANT) : "");
        }
        // v4.19：发言完毕悬浮字——轮到哪方，哪方的"发言完毕"亮起
        if (donePlaintiffDisplay != null) {
            donePlaintiffDisplay.setText(leftOn ? "§a发言完毕 §7（点击结束）" : "");
        }
        if (doneDefendantDisplay != null) {
            doneDefendantDisplay.setText(rightOn ? "§a发言完毕 §7（点击结束）" : "");
        }
    }

    private void updateMiddleDisplay() {
        if (middleDisplay == null) {
            return;
        }
        boolean confirmed = displayCaseId != -1 && confirmedCases.contains(displayCaseId);
        middleDisplay.setText(confirmed ? "§6直接审判 §7（点击后宣判）" : "§6开庭确认 §7（人到齐后点击）");
    }

    /** 切换到 NONE 时隐藏左右悬浮字。 */
    private void hideTurnDisplays() {
        if (leftDisplay != null) {
            leftDisplay.setText("");
        }
        if (rightDisplay != null) {
            rightDisplay.setText("");
        }
        if (donePlaintiffDisplay != null) {
            donePlaintiffDisplay.setText("");
        }
        if (doneDefendantDisplay != null) {
            doneDefendantDisplay.setText("");
        }
    }

    private void removeDisplays() {
        for (TextDisplay d : new TextDisplay[]{leftDisplay, middleDisplay, rightDisplay,
                donePlaintiffDisplay, doneDefendantDisplay}) {
            if (d != null && !d.isDead()) {
                d.remove();
            }
        }
        leftDisplay = middleDisplay = rightDisplay = null;
        donePlaintiffDisplay = doneDefendantDisplay = null;
        displaysCreated = false;
    }

    private String turnText(String cn, Speaking side) {
        if (turnTimedOut) {
            return "§c" + cn + "发言时间到！";
        }
        String color = side == Speaking.PLAINTIFF ? "§b" : "§c";
        return color + cn + "发言中 §7剩余 " + formatCountdown(turnRemaining);
    }

    private static String formatCountdown(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    private static String formatTurnLimit(int seconds) {
        if (seconds <= 0) {
            return "不限时";
        }
        if (seconds >= 60) {
            return (seconds / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    /** 长文本折行（每 perLine 字符一个 \n），标题副标题用。 */
    private static String wrapText(String text, int perLine) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int line = 0;
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            line++;
            if (line >= perLine) {
                sb.append('\n');
                line = 0;
            }
        }
        return sb.toString().trim();
    }

    // ============================================================== 异步聊天相关（轮次禁言 / 宣判输入）
    /** 玩家是否处于宣判输入模式（AsyncChatEvent 异步读取用）：返回对应案件号或 null。 */
    public Integer verdictInputCase(UUID uuid) {
        return verdictInput.get(uuid);
    }

    /** 是否应禁言该玩家（AsyncChatEvent 异步调用）：最近 TRIAL 案件中对方发言时拦截。 */
    public boolean shouldMute(Player p) {
        if (!muteOpposing) {
            return false;
        }
        if (isJudge(p)) {
            return false;
        }
        CourtCase c = activeTrial;
        if (c == null) {
            return false;
        }
        Speaking s = speaking;
        if (s == Speaking.NONE) {
            return false;
        }
        UUID uuid = p.getUniqueId();
        boolean isPlaintiffSide = uuid.equals(c.plaintiffUuid()) || uuid.equals(c.prosecutionLawyerUuid());
        boolean isDefendantSide = uuid.equals(c.defendantUuid()) || uuid.equals(c.defenseLawyerUuid());
        if (s == Speaking.DEFENDANT && isPlaintiffSide) {
            return true;
        }
        if (s == Speaking.PLAINTIFF && isDefendantSide) {
            return true;
        }
        return false;
    }

    /** 是否应限制该玩家指令（PlayerCommandPreprocessEvent，主线程）：任一 TRIAL 案件当事人/律师。 */
    public boolean shouldRestrictCommand(Player p, String commandLabel) {
        if (!restrictCommands) {
            return false;
        }
        if (isJudge(p)) {
            return false;
        }
        if (!isTrialParty(p)) {
            return false;
        }
        String label = commandLabel.toLowerCase();
        return !allowedCommands.contains(label);
    }

    private boolean isTrialParty(Player p) {
        UUID uuid = p.getUniqueId();
        for (CourtCase c : cases.values()) {
            if (c.state() != CourtCase.State.TRIAL) {
                continue;
            }
            if (uuid.equals(c.defendantUuid()) || uuid.equals(c.plaintiffUuid())
                    || uuid.equals(c.defenseLawyerUuid()) || uuid.equals(c.prosecutionLawyerUuid())) {
                return true;
            }
        }
        return false;
    }

    // ============================================================== 监听器回调
    /** PlayerJoinEvent：执行 AWAITING_EXECUTION 判决（重进服自动收监）。 */
    public void onPlayerJoin(Player p) {
        for (CourtCase c : cases.values()) {
            if (c.state() == CourtCase.State.AWAITING_EXECUTION && c.defendantUuid().equals(p.getUniqueId())) {
                int sentence = c.sentenceAmount();
                // v4.18：先还原法庭背包快照，入狱快照才能拍到真正的原背包
                restoreCourtInventory(p);
                Prisoner pr = prisonerManager.imprison(p, breakableMaterialName, sentence);
                if (pr == null) {
                    p.sendMessage("§c监狱房间已满，无法执行收监。");
                    return;
                }
                CourtCase updated = c.executeAwaiting(System.currentTimeMillis());
                cases.put(c.id(), updated);
                saveCases();
                p.sendMessage("§c你有一宗判决已生效，已自动收监（挖 " + sentence + " 个" + breakableDisplayName + "）");
                soundTo(p, org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f); // v4.20 自动收监天雷
            }
        }
        // v4.19：中途退出玩家重进服 → 庭审已结束时传回开庭前坐标；仍在审理则保留快照
        CourtReturn ret = teleportSnapshots.get(p.getUniqueId());
        if (ret != null) {
            if (prisonerManager.isImprisoned(p.getUniqueId())) {
                // 已被收监：快照作废，不回传
                teleportSnapshots.remove(p.getUniqueId());
                saveCourtInv();
            } else if (!isCaseActive(ret.caseId())) {
                teleportSnapshots.remove(p.getUniqueId());
                saveCourtInv();
                if (ret.location() != null && ret.location().getWorld() != null) {
                    p.teleport(ret.location());
                    p.sendMessage("§a庭审已结束，已传送回原位");
                    soundTo(p, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f);
                }
            }
            // 案件仍在进行中：保留快照——结案时在线则回传，离线则下次重进回传
        }
    }

    /** 案件是否仍处于进行中（PENDING/TRIAL/AWAITING_EXECUTION）。 */
    private boolean isCaseActive(int caseId) {
        CourtCase c = cases.get(caseId);
        return c != null && c.isActive();
    }

    /** PlayerQuitEvent：清理定身/名牌/座位/专属快照；庭审中任一方掉线 → 全庭广播，审判不中断。 */
    public void onPlayerQuit(Player p) {
        UUID uuid = p.getUniqueId();
        // v4.18：退出前还原法庭背包（写回玩家数据，防物品丢失）
        restoreCourtInventory(p);
        for (CourtCase c : cases.values()) {
            if (c.state() == CourtCase.State.TRIAL
                    && (uuid.equals(c.defendantUuid()) || uuid.equals(c.plaintiffUuid())
                    || uuid.equals(c.defenseLawyerUuid()) || uuid.equals(c.prosecutionLawyerUuid()))) {
                broadcast("§e[法院] §c" + p.getName() + " §f已离线，庭审继续");
                break;
            }
        }
        if (frozenPlayers.remove(uuid)) {
            try {
                p.setWalkSpeed(0.2f);
            } catch (Exception ignored) {
                // 玩家退出中，忽略
            }
        }
        nameTagSnapshots.remove(uuid);
        // v4.19：位置快照保留（庭审结束后重进服回传原位）；背包快照已在方法开头还原
        verdictInput.remove(uuid);
        for (Map<Integer, UUID> occ : seatOccupancy.values()) {
            occ.remove(uuid);
        }
    }

    // ============================================================== 工具
    private static void broadcast(String msg) {
        Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection().deserialize(msg));
    }

    /** v4.16 全服音效（法院事件提示音）。 */
    private static void globalSound(org.bukkit.Sound s, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), s, 1.0f, pitch);
        }
    }

    /** v4.16 定向音效（发给指定玩家）。 */
    private static void soundTo(Player p, org.bukkit.Sound s, float pitch) {
        if (p != null && p.isOnline()) {
            p.playSound(p.getLocation(), s, 1.0f, pitch);
        }
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private static String stateCn(CourtCase.State s) {
        return switch (s) {
            case PENDING -> "待开庭";
            case TRIAL -> "庭审中";
            case AWAITING_EXECUTION -> "待执行";
            case GUILTY -> "有罪";
            case INNOCENT -> "无罪";
            case DISMISSED -> "已驳回";
        };
    }

    private static String sideCn(Speaking side) {
        return side == Speaking.PLAINTIFF ? "原告" : "被告";
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "judge" -> "法官席";
            case "defendant" -> "被告席";
            case "plaintiff" -> "原告席";
            default -> kind;
        };
    }

    private static String pointText(Point point) {
        return point == null ? "§a未标记" : "§f" + point;
    }

    private static boolean isPartyOf(CourtCase c, UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (uuid.equals(c.defendantUuid())) {
            return true;
        }
        return c.plaintiffUuid() != null && uuid.equals(c.plaintiffUuid());
    }

    private int activeCaseForDefendant(UUID defendantUuid) {
        for (CourtCase c : cases.values()) {
            if (c.isActive() && c.defendantUuid().equals(defendantUuid)) {
                return c.id();
            }
        }
        return -1;
    }
}
