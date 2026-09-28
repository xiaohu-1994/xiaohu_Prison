package com.xiaohu.prison;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.xiaohu.prison.PrisonCells.CellLocation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * /prison 指令执行器 + Tab 补全（PLAN.md §6）。
 *
 * <p>子命令 send/release/list/pos/help 均需 prison.admin 权限（plugin.yml 已声明 default: op）；
 * 控制台可用（按玩家名解析；pos 标记仅玩家可用）。注意：在狱玩家被 PrisonerListener 封锁，
 * 无法执行这些指令。
 * <p>v3：/prison send <玩家> <任务id> —— 任务 id 必填，由管理员从 config.yml tasks 段指定；
 * 无默认/随机任务。
 */
public final class PrisonCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("send", "release", "list", "pos", "de",
            "obsidian", "tp", "spawn", "back", "visit", "help");

    private final PrisonerManager manager;
    private final PrisonCells prisonCells;
    private final PrisonWorld prisonWorld;
    private final PrisonMines prisonMines;
    private final PrisonVisit prisonVisit;
    /** 可破坏材质枚举名（/prison send 的任务材质，v3.14 起固定用它）。 */
    private final String breakableMaterialName;
    /** 可破坏材质中文名（消息提示用）。 */
    private final String breakableDisplayName;
    private final String mainWorldName;

    public PrisonCommand(PrisonerManager manager, PrisonCells prisonCells, PrisonWorld prisonWorld,
                         PrisonMines prisonMines, PrisonVisit prisonVisit, String breakableMaterialName,
                         String breakableDisplayName, String mainWorldName) {
        this.manager = manager;
        this.prisonCells = prisonCells;
        this.prisonWorld = prisonWorld;
        this.prisonMines = prisonMines;
        this.prisonVisit = prisonVisit;
        this.breakableMaterialName = breakableMaterialName;
        this.breakableDisplayName = breakableDisplayName;
        this.mainWorldName = mainWorldName;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // 无参数：管理员看完整帮助；普通玩家提示参观用法
            if (sender.hasPermission("prison.admin")) {
                sendHelp(sender);
            } else {
                sender.sendMessage("§e监狱参观: §a/prison visit §7- 进入监狱参观");
                sender.sendMessage("§e离开参观: §a/prison visit leave");
            }
            return true;
        }
        // v4.2（PLAN.md §19.3）：visit 子命令对普通玩家开放（prison.visit，默认 true）；
        // 命令级权限已从 plugin.yml 移除，其余子命令仍在此逐条校验 prison.admin
        String sub = args[0].toLowerCase();
        if (sub.equals("visit")) {
            if (!sender.hasPermission("prison.visit")) {
                sender.sendMessage("§c你没有权限使用参观指令。");
                return true;
            }
            onVisit(sender, args);
            return true;
        }
        if (!sender.hasPermission("prison.admin")) {
            sender.sendMessage("§c你没有权限使用监狱管理指令。");
            return true;
        }
        switch (sub) {
            case "send" -> onSend(sender, args);
            case "release" -> onRelease(sender, args);
            case "list" -> onList(sender);
            case "pos" -> onPos(sender, args);
            case "de" -> onDe(sender, args);
            case "obsidian" -> onObsidian(sender, args);
            case "tp" -> onTp(sender);
            case "spawn" -> onSpawn(sender, args);
            case "back" -> onBack(sender);
            case "help" -> sendHelp(sender);
            default -> {
                sender.sendMessage("§c未知子命令 '" + args[0] + "'。");
                sendHelp(sender);
            }
        }
        return true;
    }

    /**
     * /prison visit [leave|pos]（v4.2，PLAN.md §19.4）：
     * 无参数 → 普通玩家传送参观；leave → 离开参观传回原位置；pos → 管理员标记参观传送点。
     */
    private void onVisit(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("leave")) {
            onVisitLeave(sender);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("pos")) {
            onVisitPos(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (prisonVisit.isVisitor(player.getUniqueId())) {
            sender.sendMessage("§c你已在参观中，离开请使用 /prison visit leave。");
            return;
        }
        if (!prisonVisit.isMarked()) {
            sender.sendMessage("§c参观传送点尚未标记，请联系管理员执行 /prison visit pos 标记。");
            return;
        }
        if (prisonVisit.visit(player)) {
            // 传送成功，参观须知已在 visit() 内发送
        } else {
            sender.sendMessage("§c监狱世界不可用，暂时无法参观。");
        }
    }

    /** /prison visit leave：离开参观，传回参观前位置（快照丢失回主世界出生点）。 */
    private void onVisitLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        prisonVisit.leave(player);
    }

    /** /prison visit pos：管理员站在监狱世界内标记参观传送点（仅玩家）。 */
    private void onVisitPos(CommandSender sender) {
        // visit 分支对 prison.visit 开放，pos 子命令需单独校验 prison.admin
        if (!sender.hasPermission("prison.admin")) {
            sender.sendMessage("§c你没有权限标记参观传送点。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c控制台无法标记参观传送点");
            return;
        }
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            sender.sendMessage("§c请在监狱世界内标记参观传送点");
            return;
        }
        prisonVisit.mark(player.getLocation());
        sender.sendMessage("§a已标记监狱参观传送点");
    }

    /** /prison send <玩家> <数量>：在线玩家直接收监；离线玩家记录入狱（v4.11，重进服自动收监）。 */
    private void onSend(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /prison send <玩家> <数量>（数量 = 需要挖的" + breakableDisplayName + "个数）");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            // v4.11：离线入狱——须服务器玩家库中已知玩家
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
            if (!offline.hasPlayedBefore() && offline.getName() == null) {
                sender.sendMessage("§c玩家 " + args[1] + " 不存在（离线入狱仅支持已知玩家）。");
                return;
            }
            onSendOffline(sender, offline, args);
            return;
        }
        // 管理员免疫（v3.5）：OP / prison.admin 玩家不能被送入监狱（防管理员互恶搞）
        if (target.isOp() || target.hasPermission("prison.admin")) {
            sender.sendMessage("§c不能将管理员送入监狱（" + target.getName() + " 拥有 prison.admin 权限，监狱机制对其免疫）。");
            return;
        }
        if (manager.isImprisoned(target.getUniqueId())) {
            sender.sendMessage("§c玩家 " + target.getName() + " 已经在监狱中。");
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c数量必须是整数。");
            return;
        }
        if (amount < 1) {
            sender.sendMessage("§c数量必须大于 0。");
            return;
        }

        Prisoner prisoner = manager.imprison(target, breakableMaterialName, amount);
        if (prisoner == null) {
            // 已标记房间全部被占用（PrisonerManager.imprison 返回 null）
            sender.sendMessage("§c监狱房间已满，请先使用 /prison pos <编号> 分配新房间");
            prisonCells.getLogger().warning("监狱房间已满，无法将玩家 " + target.getName() + " 送入监狱"
                    + "（已标记房间数: " + prisonCells.getCells().size() + "）。");
            return;
        }
        sender.sendMessage("§a已将玩家 " + target.getName() + " 送入监狱（房间#" + prisoner.cellIndex()
                + "，任务: 挖" + breakableDisplayName + " " + amount + " 个）。");
        target.sendMessage("§c草泥马给我好好的改造！");
        target.sendMessage("§e任务：挖掘" + amount + "个" + breakableDisplayName + "，任务完成自动释放！");
        // v3.7 全服通报（入狱）：Adventure 组件广播（broadcastMessage(String) 已弃用）
        Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection().deserialize(
                "§e[监狱] §c" + target.getName() + " §f玩家违规了被带入监狱劳改！§a请大家不要学他哦~"));
    }

    /**
     * 离线入狱（v4.11，PLAN.md §20）：校验 + manager.imprisonOffline。
     * 管理员免疫仅能查 isOp（离线无法可靠查权限组）；数量校验与在线路径一致。
     */
    private void onSendOffline(CommandSender sender, OfflinePlayer offline, String[] args) {
        String name = offline.getName() == null ? args[1] : offline.getName();
        // 管理员免疫（v3.5）：离线只查 OP（权限组无法离线解析，属已知限制）
        if (offline.isOp()) {
            sender.sendMessage("§c不能将管理员（OP）送入监狱。");
            return;
        }
        UUID uuid = offline.getUniqueId();
        if (uuid == null) {
            sender.sendMessage("§c玩家 " + name + " 不存在（离线入狱仅支持已知玩家）。");
            return;
        }
        if (manager.isImprisoned(uuid)) {
            sender.sendMessage("§c玩家 " + name + " 已经在监狱中。");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c数量必须是整数。");
            return;
        }
        if (amount < 1) {
            sender.sendMessage("§c数量必须大于 0。");
            return;
        }
        Prisoner prisoner = manager.imprisonOffline(uuid, name, breakableMaterialName, amount);
        if (prisoner == null) {
            sender.sendMessage("§c监狱房间已满，请先使用 /prison pos <编号> 分配新房间");
            prisonCells.getLogger().warning("监狱房间已满，无法将离线玩家 " + name + " 送入监狱"
                    + "（已标记房间数: " + prisonCells.getCells().size() + "）。");
            return;
        }
        sender.sendMessage("§a已把离线玩家 " + name + " 送入监狱（房间#" + prisoner.cellIndex()
                + "，任务: 挖" + breakableDisplayName + " " + amount + " 个）——重进服时自动收监。");
        // v3.7 全服通报（入狱）
        Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection().deserialize(
                "§e[监狱] §c" + name + " §f玩家违规了被带入监狱劳改！§a请大家不要学他哦~（离线收监，重进服生效）"));
    }

    /** /prison release <玩家>：按存储名（或 UUID 解析的当前名）匹配在狱记录后释放。 */
    private void onRelease(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /prison release <玩家>");
            return;
        }
        Prisoner prisoner = manager.findPrisonerByName(args[1]);
        if (prisoner == null) {
            sender.sendMessage("§c玩家 " + args[1] + " 不在监狱中。");
            return;
        }
        if (manager.release(prisoner.uuid(), "§a你已被管理员释放，欢迎回来！")) {
            sender.sendMessage("§a已释放玩家 " + prisoner.name() + "。");
            // v3.8 全服通报（管理员强制释放）
            Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection().deserialize(
                    "§e[监狱] §a" + prisoner.name() + " §f已被管理员释放！"));
        } else {
            sender.sendMessage("§c玩家 " + args[1] + " 不在监狱中。");
        }
    }

    /** /prison list：列出在狱玩家与任务进度（无任务 → 需手动释放）。 */
    private void onList(CommandSender sender) {
        Map<UUID, Prisoner> prisoners = manager.getPrisoners();
        if (prisoners.isEmpty()) {
            sender.sendMessage("§e当前没有在狱玩家。");
            return;
        }
        sender.sendMessage("§e===== 在狱玩家 (" + prisoners.size() + " 人) =====");
        for (Prisoner p : prisoners.values()) {
            String taskInfo;
            if (p.hasTask()) {
                Material mat = manager.taskMaterial(p.taskKey());
                String matName = mat == null ? p.taskKey() : PrisonTasks.displayName(mat);
                taskInfo = "任务: 挖" + matName + " " + p.taskProgress() + "/" + p.taskTarget();
            } else {
                taskInfo = "任务: 无（需手动释放）";
            }
            sender.sendMessage("§a" + p.name() + " §7(房间#" + p.cellIndex() + ") §f" + taskInfo);
        }
    }

    /**
     * /prison pos <编号>：管理员站在监狱世界的房间内，把当前位置标记为 N 号房间（覆盖已存在）。
     * /prison pos list：列出所有已标记房间（空闲/占用 + 坐标）与空余数。
     */
    private void onPos(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /prison pos <编号> | /prison pos list");
            return;
        }
        if (args[1].equalsIgnoreCase("list")) {
            onPosList(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c控制台无法标记房间位置");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c用法: /prison pos <编号>（编号为大于等于 1 的整数）");
            return;
        }
        if (n < 1) {
            sender.sendMessage("§c用法: /prison pos <编号>（编号为大于等于 1 的整数）");
            return;
        }
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            sender.sendMessage("§c请在监狱世界内标记房间位置");
            return;
        }
        prisonCells.mark(n, player.getLocation());
        sender.sendMessage("§a已标记房间 #" + n);
    }

    /** /prison pos list：房间状态总览。 */
    private void onPosList(CommandSender sender) {
        Map<Integer, CellLocation> cells = prisonCells.getCells();
        if (cells.isEmpty()) {
            sender.sendMessage("§e尚未分配房间，请使用 /prison pos <编号> 标记房间位置");
            return;
        }
        // 占用者按房间编号匹配（玩家可能改名，显示存储名即可）
        Map<Integer, String> occupants = new HashMap<>();
        for (Prisoner p : manager.getPrisoners().values()) {
            occupants.putIfAbsent(p.cellIndex(), p.name());
        }
        sender.sendMessage("§e===== 房间状态 (" + cells.size() + " 间) =====");
        int freeCount = 0;
        for (Map.Entry<Integer, CellLocation> entry : cells.entrySet()) {
            String occupant = occupants.get(entry.getKey());
            if (occupant == null) {
                freeCount++;
                sender.sendMessage("§a#" + entry.getKey() + " §7- 空闲 §f" + entry.getValue());
            } else {
                sender.sendMessage("§a#" + entry.getKey() + " §7- 占用: " + occupant + " §f" + entry.getValue());
            }
        }
        sender.sendMessage("§e空余: " + freeCount + "/" + cells.size());
    }

    /** /prison de pos <编号>：删除房间；正被玩家占用的房间拒绝删除。 */
    private void onDe(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("pos")) {
            sender.sendMessage("§c用法: /prison de pos <编号>");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c用法: /prison de pos <编号>（编号为大于等于 1 的整数）");
            return;
        }
        if (n < 1) {
            sender.sendMessage("§c用法: /prison de pos <编号>（编号为大于等于 1 的整数）");
            return;
        }
        if (!prisonCells.getCells().containsKey(n)) {
            sender.sendMessage("§c房间 #" + n + " 不存在");
            return;
        }
        // 占用检查：该房间有在狱玩家则拒绝删除
        for (Prisoner p : manager.getPrisoners().values()) {
            if (p.cellIndex() == n) {
                sender.sendMessage("§c房间 #" + n + " 正被玩家 " + p.name() + " 占用，请先释放该玩家");
                return;
            }
        }
        if (prisonCells.remove(n)) {
            sender.sendMessage("§a已删除房间 #" + n);
        } else {
            sender.sendMessage("§c房间 #" + n + " 不存在");
        }
    }

    /**
     * /prison obsidian：管理员站在监狱世界内，在脚下生成 size×size 黑曜石矿场（每 refresh-seconds
     * 秒自动补满被挖掉的黑曜石）。
     * /prison obsidian list：列出所有矿场。
     * /prison obsidian del <编号>：删除矿场（区域内黑曜石清空为空气，停止刷新）。
     */
    private void onObsidian(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            List<PrisonMines.Mine> mines = prisonMines.list();
            if (mines.isEmpty()) {
                sender.sendMessage("§e尚未生成矿场，站在监狱世界内使用 /prison obsidian 生成。");
                return;
            }
            sender.sendMessage("§e===== 黑曜石矿场 (" + mines.size() + " 个) =====");
            for (PrisonMines.Mine m : mines) {
                sender.sendMessage("§a" + m);
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("del")) {
            if (args.length < 3) {
                sender.sendMessage("§c用法: /prison obsidian del <编号>");
                return;
            }
            int id;
            try {
                id = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c用法: /prison obsidian del <编号>（编号为大于等于 1 的整数）");
                return;
            }
            if (id < 1 || !prisonMines.contains(id)) {
                sender.sendMessage("§c矿场 #" + id + " 不存在");
                return;
            }
            if (prisonMines.remove(id)) {
                sender.sendMessage("§a已删除矿场 #" + id + "（区域内黑曜石已清空，不再刷新）");
            } else {
                sender.sendMessage("§c矿场 #" + id + " 不存在");
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c控制台无法生成矿场（需要站立位置）");
            return;
        }
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            sender.sendMessage("§c请在监狱世界内生成黑曜石矿场");
            return;
        }
        PrisonMines.Mine mine = prisonMines.add(player.getLocation());
        sender.sendMessage("§a已在脚下生成黑曜石矿场 #" + mine.id() + "（" + mine.size() + "x" + mine.size()
                + "，自动补满黑曜石）。");
    }

    /** /prison tp：管理员传送至监狱世界出生点（便于进入监狱世界搞建筑）。 */
    private void onTp(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        Location loc = prisonWorld.worldSpawnLocation();
        if (loc == null) {
            sender.sendMessage("§c监狱世界不可用");
            return;
        }
        player.teleport(loc);
        sender.sendMessage("§a已传送至监狱世界 '" + prisonWorld.getWorld().getName() + "' 出生点。");
    }

    /**
     * /prison spawn yes：把脚下坐标保存为监狱世界出生点（需站在监狱世界内；v3.29）。
     * /prison spawn：同上（兼容旧语法）。
     */
    private void onSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (prisonWorld.getWorld() == null) {
            sender.sendMessage("§c监狱世界不可用");
            return;
        }
        if (args.length > 2 || (args.length == 2 && !args[1].equalsIgnoreCase("yes"))) {
            sender.sendMessage("§c用法: /prison spawn yes");
            return;
        }
        if (!player.getWorld().getName().equals(prisonCells.getPrisonWorldName())) {
            sender.sendMessage("§c请在监狱世界内执行 /prison spawn yes");
            return;
        }
        Location target = player.getLocation();
        if (!prisonWorld.setWorldSpawn(target)) {
            sender.sendMessage("§c设置监狱世界出生点失败");
            return;
        }
        sender.sendMessage("§a已将监狱世界出生点设为脚下坐标 " + String.format("%.1f, %.1f, %.1f",
                target.getX(), target.getY(), target.getZ()));
    }

    /** /prison back：传送回主世界出生点（主世界名来自 config main-world，缺失时回退服务器第一个世界）。 */
    private void onBack(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        World main = Bukkit.getWorld(mainWorldName);
        if (main == null) {
            main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }
        if (main == null) {
            sender.sendMessage("§c主世界不可用");
            return;
        }
        player.teleport(main.getSpawnLocation());
        sender.sendMessage("§a已传送回主世界 '" + main.getName() + "' 出生点。");
    }

    /** /prison help：完整中文帮助，指令可点击（无参数点击直接执行；带参数点击填入聊天框补全）。 */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(legacy("§e§l========== 监狱管理帮助 /prison =========="));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【入狱与释放】"));
        sender.sendMessage(clickable("§a/prison send <玩家> <数量> §7- 把在线玩家关入监狱（数量 = 挖黑曜石个数）", "/prison send <玩家> <数量>", true));
        sender.sendMessage(clickable("§a/prison release <玩家> §7- 管理员强制释放玩家，传送回入狱前位置（全服通报）", "/prison release <玩家>", true));
        sender.sendMessage(clickable("§a/prison list §7- 查看在狱玩家、房间号与任务进度", "/prison list", false));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【房间管理】"));
        sender.sendMessage(clickable("§a/prison pos <编号> §7- 站在监狱世界的房间内，把当前位置标记为 N 号房间", "/prison pos <编号>", true));
        sender.sendMessage(clickable("§a/prison pos list §7- 查看所有房间：空闲/占用/坐标/空余数", "/prison pos list", false));
        sender.sendMessage(clickable("§a/prison de pos <编号> §7- 删除房间（房间有玩家时拒绝）", "/prison de pos <编号>", true));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【黑曜石矿场】"));
        sender.sendMessage(clickable("§a/prison obsidian §7- 站在监狱世界内，脚下生成 15x15x15 黑曜石立方体矿场（自动补满）", "/prison obsidian", false));
        sender.sendMessage(clickable("§a/prison obsidian list §7- 查看矿场列表", "/prison obsidian list", false));
        sender.sendMessage(clickable("§a/prison obsidian del <编号> §7- 删除矿场并清空黑曜石", "/prison obsidian del <编号>", true));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【传送】"));
        sender.sendMessage(clickable("§a/prison tp §7- 传送至监狱世界出生点（进监狱搞建筑）", "/prison tp", false));
        sender.sendMessage(clickable("§a/prison spawn yes §7- 把脚下坐标保存为监狱世界出生点", "/prison spawn yes", false));
        sender.sendMessage(clickable("§a/prison back §7- 传送回主世界出生点", "/prison back", false));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【监狱参观（普通玩家可用）】"));
        sender.sendMessage(clickable("§a/prison visit §7- 进入监狱参观（不能破坏/放置/丢弃物品）", "/prison visit", false));
        sender.sendMessage(clickable("§a/prison visit leave §7- 离开参观，传回参观前位置", "/prison visit leave", false));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§7提示: 点击指令即可使用；带 <参数> 的指令点击后自动填入聊天框，补全参数后回车执行。"));
    }

    /** 普通彩色文本组件（§ 颜色码 → Adventure 组件）。 */
    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    /**
     * 可点击指令行：无参数用 RUN_COMMAND（点击直接执行）；带参数用 SUGGEST_COMMAND
     * （点击填入聊天框，补全后回车）。
     */
    private static Component clickable(String display, String command, boolean suggest) {
        if (suggest) {
            // 带参数：点击填入聊天框，补全后回车执行
            return legacy(display).clickEvent(ClickEvent.suggestCommand(command));
        }
        // 无参数：点击直接执行
        return legacy(display).clickEvent(ClickEvent.runCommand(command));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("prison.admin");
        boolean visitor = sender.hasPermission("prison.visit");
        if (args.length == 1) {
            // v4.2：按权限过滤——普通玩家只见 visit，管理员见全部
            String prefix = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            if (visitor) {
                suggestions.add("visit");
            }
            if (admin) {
                suggestions.addAll(SUBCOMMANDS);
            }
            return suggestions.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("visit")) {
            // visit：补全 leave（所有人）+ pos（管理员）
            String prefix = args[1].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            suggestions.add("leave");
            if (admin) {
                suggestions.add("pos");
            }
            return suggestions.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (!admin) {
            return List.of();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            if (args[0].equalsIgnoreCase("pos")) {
                // pos：补全 "list" + 数字 1..(maxRoom()+1)（+1 便于标记新房间）
                List<String> suggestions = new ArrayList<>();
                suggestions.add("list");
                for (int i = 1; i <= prisonCells.maxRoom() + 1; i++) {
                    suggestions.add(String.valueOf(i));
                }
                return suggestions.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
            }
            if (args[0].equalsIgnoreCase("de")) {
                // de：第二参数固定 "pos"
                return "pos".startsWith(prefix) ? List.of("pos") : List.of();
            }
            if (args[0].equalsIgnoreCase("obsidian")) {
                // obsidian：补全 "list" / "del"
                return List.of("list", "del").stream()
                        .filter(s -> s.startsWith(prefix))
                        .toList();
            }
            if (args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("spawn")
                    || args[0].equalsIgnoreCase("back") || args[0].equalsIgnoreCase("list")
                    || args[0].equalsIgnoreCase("help")) {
                // 无参数子命令
                return List.of();
            }
            // send/release：补全在线玩家名
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("de") && args[1].equalsIgnoreCase("pos")) {
                // de pos <编号>：补全已标记的房间号
                String prefix = args[2].toLowerCase();
                return prisonCells.getCells().keySet().stream()
                        .map(String::valueOf)
                        .filter(s -> s.startsWith(prefix))
                        .toList();
            }
            if (args[0].equalsIgnoreCase("obsidian") && args[1].equalsIgnoreCase("del")) {
                // obsidian del <编号>：补全已注册的矿场编号
                String prefix = args[2].toLowerCase();
                return prisonMines.list().stream()
                        .map(m -> String.valueOf(m.id()))
                        .filter(s -> s.startsWith(prefix))
                        .toList();
            }
        }
        return List.of();
    }
}
