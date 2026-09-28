package com.xiaohu.prison;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 事件监听（PLAN.md §8）：
 * <ul>
 *   <li>PlayerJoinEvent：在狱玩家重进服 → 传回房间（房间缺失回监狱世界出生点）+ 提示任务状态；</li>
 *   <li>PlayerRespawnEvent（HIGHEST，v3.37）：在狱玩家死亡重生 → 重生点强制留在监狱世界
 *       （牢房 → 矿场顶面 → 监狱世界出生点三级兜底，绝不回主世界床点/出生点）；</li>
 *   <li>PlayerCommandPreprocessEvent：在狱且非 OP 且无 prison.admin → 取消指令并提示；</li>
 *   <li>BlockBreakEvent（v3.4）：监狱世界方块保护——非 OP/无 prison.admin 的囚犯只能破坏
 *       配置的可破坏材质（默认黑曜石），破坏其他方块被取消；非囚犯禁止破坏任何方块；</li>
 *   <li>BlockPlaceEvent（v3.1）：监狱世界内非 OP/无 prison.admin 禁止放置任何方块；</li>
 *   <li>PlayerDropItemEvent（v4.2）：监狱世界内非 OP/无 prison.admin 禁止丢弃物品
 *       （参观者与囚犯一视同仁，防走私/装备复制）；</li>
 *   <li>EntityDamageByEntityEvent（v4.2）：visit.protect=true 时参观者对玩家伤害免疫。</li>
 * </ul>
 */
public final class PrisonerListener implements Listener {

    private final PrisonerManager manager;
    private final PrisonCells prisonCells;
    private final PrisonWorld prisonWorld;
    private final PrisonMines prisonMines;
    private final PrisonVisit prisonVisit;
    /** 监狱世界唯一允许囚犯破坏的方块材质（config mine.breakable-material，默认黑曜石）。 */
    private final Material breakableMaterial;
    /** 可破坏材质的中文名（消息提示用）。 */
    private final String breakableDisplayName;

    public PrisonerListener(PrisonerManager manager, PrisonCells prisonCells, PrisonWorld prisonWorld,
                            PrisonMines prisonMines, PrisonVisit prisonVisit,
                            Material breakableMaterial, String breakableDisplayName) {
        this.manager = manager;
        this.prisonCells = prisonCells;
        this.prisonWorld = prisonWorld;
        this.prisonMines = prisonMines;
        this.prisonVisit = prisonVisit;
        this.breakableMaterial = breakableMaterial;
        this.breakableDisplayName = breakableDisplayName;
    }

    /** 在狱玩家重进服：传回房间；提示任务状态（v3 无倒计时）。管理员免疫（v3.5）不传送。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // v3.38（P3 修复）：先归还"离线释放时暂存"的背包（该玩家已不在狱，与下方囚犯逻辑互斥）
        manager.applyPendingRestore(player);
        // 管理员免疫：OP / prison.admin 不受监狱机制影响
        if (player.isOp() || player.hasPermission("prison.admin")) {
            return;
        }
        Prisoner prisoner = manager.getPrisoner(player.getUniqueId());
        if (prisoner == null) {
            return;
        }
        // v4.11：离线入狱玩家重进服 → 此刻快照暂扣背包（并记录此刻位置为入狱前位置）
        manager.confiscateOnJoin(player);
        Location cell = prisonCells.cellLocation(prisoner.cellIndex());
        if (cell != null) {
            player.teleport(cell);
        } else {
            // v3.30：房间缺失时禁止传送到世界出生点（出生点只属于管理员）→ 优先矿场顶面兜底，都没有则原地不动
            Location fallback = prisonMines.firstMineTopLocation();
            if (fallback != null) {
                player.teleport(fallback);
            } else {
                player.getServer().getLogger().warning("[Prison] 囚犯 " + prisoner.name() + " 的房间 #"
                        + prisoner.cellIndex() + " 缺失且无矿场，未传送（请用 /prison pos 重新标记房间）。");
            }
        }
        player.sendMessage("§c你仍在服刑中，已传送回牢房！");
        player.setGameMode(GameMode.SURVIVAL); // v3.24：重进服强制生存，防冒险模式无法挖黑曜石
        manager.givePrisonGear(player); // v3.6：补发狱中装备（防装备丢失后空手）
        sendTaskHint(player, prisoner);
    }

    /**
     * 在狱玩家死亡重生：重生点强制留在监狱世界（v3.37 硬化）——
     * 只要仍在服刑，无论玩家在别处（主世界床点等）设置了什么重生点，死后一律回监狱，
     * 绝不回到主世界。优先级 HIGHEST：覆盖其他插件在 NORMAL/HIGH 优先级设置的重生点。
     * 兜底顺序：牢房 → 矿场顶面 → 监狱世界出生点（自带平台防坠落）；
     * 监狱世界不可用时先尝试重新加载/创建。管理员免疫（v3.5）不干预。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // 管理员免疫：OP / prison.admin 不受监狱机制影响
        if (player.isOp() || player.hasPermission("prison.admin")) {
            return;
        }
        Prisoner prisoner = manager.getPrisoner(player.getUniqueId());
        if (prisoner == null) {
            return;
        }
        // 监狱世界理论上必已加载（囚犯死亡前就在其中）；万一不可用则尝试重新加载/创建
        if (prisonWorld.getWorld() == null) {
            prisonWorld.ensureWorld();
        }
        Location cell = prisonCells.cellLocation(prisoner.cellIndex());
        if (cell != null) {
            event.setRespawnLocation(cell);
            player.sendMessage("§c你仍在服刑中，已传送回牢房！");
        } else {
            // v3.30+：牢房缺失时禁止回世界出生点以外的位置；优先矿场顶面（安全站位）
            Location fallback = prisonMines.firstMineTopLocation();
            if (fallback == null) {
                // 无矿场时回监狱世界出生点（出生点带 5x5 平台，不会坠落）
                fallback = prisonWorld.worldSpawnLocation();
            }
            if (fallback != null) {
                event.setRespawnLocation(fallback);
                player.sendMessage("§c你仍在服刑中（牢房缺失，已传送到安全位置）！");
            } else {
                player.getServer().getLogger().severe("[Prison] 囚犯 " + prisoner.name()
                        + " 重生时监狱世界不可用，重生点未干预——请检查监狱世界配置！");
            }
        }
        player.setGameMode(GameMode.SURVIVAL); // v3.24：重生强制生存
        manager.givePrisonGear(player); // v3.6：死亡重生补发狱中装备（keepInventory=false 时原装备已掉落）
        sendTaskHint(player, prisoner);
    }

    /** 在狱玩家（非 OP / 无 prison.admin）禁止使用任何指令；普通玩家对狱中玩家的任何指令全部无效。 */
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (manager.isImprisoned(player.getUniqueId())) {
            // 在狱玩家（非管理员）封锁一切指令
            if (player.isOp() || player.hasPermission("prison.admin")) {
                return;
            }
            // setMessage("") 缓解 Paper chat-signature 问题（PLAN.md §15 风险 1）
            event.setCancelled(true);
            event.setMessage("");
            player.sendMessage("§e[监狱] §c你由于在服刑期间无法使用指令！");
            return;
        }
        // v3.13 一刀切：普通玩家的任何指令，只要参数指向狱中玩家 → 全部无效（只有管理员可以）
        if (player.isOp() || player.hasPermission("prison.admin")) {
            return;
        }
        String message = event.getMessage();
        if (message.length() < 2 || message.charAt(0) != '/') {
            return;
        }
        String[] parts = message.substring(1).trim().split("\\s+");
        if (parts.length < 2) {
            return; // 无参数指令不涉及目标玩家
        }
        for (Prisoner p : manager.getPrisoners().values()) {
            Player online = Bukkit.getPlayer(p.uuid());
            if (online == null) {
                continue; // 只拦在场（在线）的囚犯目标
            }
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].equalsIgnoreCase(online.getName())) {
                    event.setCancelled(true);
                    event.setMessage("");
                    player.sendMessage("§c不能对狱中的玩家 " + online.getName() + " 使用指令！只有管理员可以。");
                    return;
                }
            }
        }
    }

    /**
     * 监狱世界方块保护（v3.1 + v3.4）：非 OP / 无 prison.admin 的玩家——
     * 只有囚犯能破坏方块，且只能破坏配置的固定可破坏材质（默认黑曜石）；
     * 破坏其他任何方块被取消。破坏可破坏方块时，若正是任务材质则计数进度。
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        // 只在监狱世界内限制
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            return;
        }
        // OP / prison.admin 放行（建筑、管理不受限）
        if (player.isOp() || player.hasPermission("prison.admin")) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Prisoner prisoner = manager.getPrisoner(uuid);
        // 非囚犯（误入监狱世界的普通玩家）：禁止破坏任何方块
        if (prisoner == null) {
            event.setCancelled(true);
            player.sendMessage("§c你没有权限在此破坏方块。");
            return;
        }
        // 只允许破坏配置的可破坏材质（默认黑曜石），其余一律取消
        if (event.getBlock().getType() != breakableMaterial) {
            event.setCancelled(true);
            player.sendMessage("§c你正在服刑，只能破坏" + breakableDisplayName + "！");
            return;
        }
        // 允许破坏；若该方块正是任务材质则计数进度（任务材质与可破坏材质不一致的任务将无法完成）
        if (prisoner.hasTask()) {
            Material taskMaterial = manager.taskMaterial(prisoner.taskKey());
            if (taskMaterial != null && event.getBlock().getType() == taskMaterial) {
                manager.addProgress(uuid, 1);
            }
        }
    }

    /**
     * 监狱世界放置保护（v3.1）：非 OP / 无 prison.admin 的玩家在监狱世界内禁止放置任何方块
     * （防垫脚越狱、放岩浆/火把等搞破坏）。
     */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            return;
        }
        if (player.isOp() || player.hasPermission("prison.admin")) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage("§c你正在服刑，狱中禁止放置方块！");
    }

    /**
     * 监狱世界丢物保护（v4.2，PLAN.md §19.2）：非 OP / 非 prison.admin 的玩家
     * （参观者与囚犯）在监狱世界内不能丢弃任何物品——防参观者走私物品给囚犯、
     * 防囚犯丢弃监狱装备复制。
     */
    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            return;
        }
        if (player.isOp() || player.hasPermission("prison.admin")) {
            return;
        }
        event.setCancelled(true);
        if (manager.isImprisoned(player.getUniqueId())) {
            player.sendMessage("§c服刑期间不能丢弃物品！");
        } else {
            player.sendMessage("§c参观期间不能丢弃物品！");
        }
    }

    /**
     * 参观者伤害保护（v4.2，PLAN.md §19.2）：config visit.protect=true（默认）时，
     * 监狱世界内的参观者不受任何玩家攻击（防囚犯攻击参观者）；环境伤害不干预。
     */
    @EventHandler
    public void onDamageByPlayer(EntityDamageByEntityEvent event) {
        if (!prisonVisit.isProtectEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!victim.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            return;
        }
        if (!prisonVisit.isVisitor(victim.getUniqueId())) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        event.setCancelled(true);
        attacker.sendMessage("§c该玩家正在参观监狱，受到保护，不能攻击！");
        victim.sendMessage("§c你正在参观监狱，受到保护，不会被玩家攻击！");
    }

    /**
     * 监狱世界 PvP 击杀转移进度（v3.11）：囚犯互殴，败者任务进度扣半（保留一半），
     * 这一半叠加到胜者进度上（胜者可能因此完成出狱，走 addProgress 庆祝路径）。
     * 仅监狱世界内、双方均在狱且有任务时生效（管理员击杀囚犯不转移）。
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!victim.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            return;
        }
        Player killer = victim.getKiller();
        if (killer == null || killer == victim) {
            return;
        }
        Prisoner victimP = manager.getPrisoner(victim.getUniqueId());
        Prisoner killerP = manager.getPrisoner(killer.getUniqueId());
        if (victimP == null || killerP == null || !victimP.hasTask() || !killerP.hasTask()) {
            return;
        }
        int half = victimP.taskProgress() / 2;
        if (half <= 0) {
            return;
        }
        // 败者进度减半（保留一半），胜者获得这一半
        manager.setProgress(victim.getUniqueId(), victimP.taskProgress() - half);
        manager.addProgress(killer.getUniqueId(), half);
        victim.sendMessage("§c你在监狱被 " + killer.getName() + " 击杀，任务进度被夺走一半（-" + half + "）！");
        killer.sendMessage("§a你击杀了 " + victimP.name() + "，获得其一半任务进度（+" + half + "）！");
    }

    /**
     * 监狱长巡视通报（v3.15）+ 监狱世界模式修正（v3.26）：
     * 管理员（OP / prison.admin）进入监狱世界时给在狱玩家发送「[监狱]xxx监狱长来了！」；
     * 任何玩家进入监狱世界若被切成冒险模式 → 强制切回生存（创造不受影响）。
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            return;
        }
        // v3.26：监狱世界禁止冒险模式（防止其他插件把进世界的人切成冒险导致无法挖矿/建筑）
        if (player.getGameMode() == GameMode.ADVENTURE) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        // 只有管理员进入监狱世界才通报（普通玩家进入不通报）
        if (!player.isOp() && !player.hasPermission("prison.admin")) {
            return;
        }
        String message = "§e[监狱] §6" + player.getName() + " §f监狱长来了！";
        for (Prisoner p : manager.getPrisoners().values()) {
            Player online = Bukkit.getPlayer(p.uuid());
            if (online != null) {
                online.sendMessage(message);
            }
        }
    }

    /** 提示当前任务状态（v3 无倒计时）：有任务显示进度，无任务提示需手动释放。 */
    private void sendTaskHint(Player player, Prisoner prisoner) {
        String text = manager.taskProgressText(prisoner);
        if (text != null) {
            player.sendMessage("§e" + text);
        } else {
            player.sendMessage("§e当前无任务（需管理员手动释放）。");
        }
    }
}
