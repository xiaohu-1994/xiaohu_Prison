package com.xiaohu.prison;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * /court 指令执行器 + Tab 补全（PLAN.md §17.3 / §18.4）。
 *
 * <p>无命令级权限（plugin.yml 未声明），子命令逐条校验：公共项（court.view / court.accuse /
 * court.watch），法官专属项（court.judge || prison.admin || OP）。案件号可省略——进行中案件恰好
 * 1 个时自动指向唯一案件；多个/零个时省略被拒并提示。
 * 全名与拼音短别名均可用（onCommand 与 onTabComplete 都识别）。
 */
public final class CourtCommand implements CommandExecutor, TabCompleter {

    private final CourtManager court;

    public CourtCommand(CourtManager court) {
        this.court = court;
    }

    // ============================================================== 分发
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!court.isEnabled()) {
            sender.sendMessage("§c人民法院模块未启用。");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "accuse", "su" -> onAccuse(sender, args);
            case "prosecute", "gs" -> onProsecute(sender, args);
            case "list", "lb" -> onList(sender);
            case "case", "aj" -> onCase(sender, args);
            case "trial", "kt" -> onTrial(sender, args);
            case "verdict", "pj" -> onVerdict(sender, args);
            case "dismiss", "bh" -> onDismiss(sender, args);
            case "withdraw", "cs" -> onWithdraw(sender, args);
            case "lawyer", "ls" -> onLawyer(sender, args);
            case "volunteer", "bm" -> onVolunteer(sender, args);
            case "watch", "pt" -> onWatch(sender, args);
            case "unwatch", "qx" -> onUnwatch(sender, args);
            case "leave", "tc" -> onLeave(sender);
            case "history", "jl" -> onHistory(sender, args);
            case "pos", "wz" -> onPos(sender, args);
            case "btn", "an" -> onBtn(sender, args);
            case "help" -> sendHelp(sender);
            default -> {
                sender.sendMessage("§c未知子命令 '" + args[0] + "'。");
                sendHelp(sender);
            }
        }
        return true;
    }

    // ============================================================== 子命令
    private void onAccuse(CommandSender sender, String[] args) {
        if (!sender.hasPermission("court.accuse")) {
            sender.sendMessage("§c你没有权限起诉（需要 court.accuse）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /court accuse <玩家> <理由...>");
            return;
        }
        if (!court.isAccusableTarget(player)) {
            sender.sendMessage("§c你正在服刑或为管理员，不能起诉他人。");
            return;
        }
        court.accuse(player, args[1], join(args, 2));
    }

    private void onProsecute(CommandSender sender, String[] args) {
        if (!court.isJudge(sender)) {
            sender.sendMessage("§c你没有权限公诉（需要 court.judge）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /court prosecute <玩家> <理由...>");
            return;
        }
        court.prosecute(player, args[1], join(args, 2));
    }

    private void onList(CommandSender sender) {
        if (!sender.hasPermission("court.view")) {
            sender.sendMessage("§c你没有权限查看案件（需要 court.view）。");
            return;
        }
        court.list(sender);
    }

    private void onCase(CommandSender sender, String[] args) {
        if (!sender.hasPermission("court.view")) {
            sender.sendMessage("§c你没有权限查看案件（需要 court.view）。");
            return;
        }
        int caseId = resolveCaseId(sender, args.length >= 2 ? args[1] : null);
        if (caseId <= 0) {
            return;
        }
        court.showCase(sender, caseId);
    }

    private void onTrial(CommandSender sender, String[] args) {
        if (!court.isJudge(sender)) {
            sender.sendMessage("§c你没有权限开庭（需要 court.judge）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        int caseId = resolveCaseId(sender, args.length >= 2 ? args[1] : null);
        if (caseId <= 0) {
            return;
        }
        court.trial(caseId, player);
    }

    private void onVerdict(CommandSender sender, String[] args) {
        if (!court.isJudge(sender)) {
            sender.sendMessage("§c你没有权限宣判（需要 court.judge）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        int caseId;
        String verdictWord;
        Integer amount = null;
        if (args.length >= 2 && (args[1].equalsIgnoreCase("guilty") || args[1].equalsIgnoreCase("innocent"))) {
            // 省略案件号：/court verdict guilty [数量]
            caseId = resolveCaseId(sender, null);
            if (caseId <= 0) {
                return;
            }
            verdictWord = args[1];
            if (args.length >= 3) {
                amount = parseInt(args[2]);
                if (amount == null) {
                    sender.sendMessage("§c数量必须是整数。");
                    return;
                }
            }
        } else {
            // 完整：/court verdict <编号> <guilty|innocent> [数量]
            if (args.length < 3 || (!args[2].equalsIgnoreCase("guilty") && !args[2].equalsIgnoreCase("innocent"))) {
                sender.sendMessage("§c用法: /court verdict <编号> <guilty|innocent> [数量]");
                return;
            }
            caseId = resolveCaseId(sender, args[1]);
            if (caseId <= 0) {
                return;
            }
            verdictWord = args[2];
            if (args.length >= 4) {
                amount = parseInt(args[3]);
                if (amount == null) {
                    sender.sendMessage("§c数量必须是整数。");
                    return;
                }
            }
        }
        court.verdict(caseId, player, verdictWord, amount);
    }

    private void onDismiss(CommandSender sender, String[] args) {
        if (!court.isJudge(sender)) {
            sender.sendMessage("§c你没有权限驳回案件（需要 court.judge）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /court dismiss <编号> <理由...>");
            return;
        }
        int caseId;
        String reason;
        if (isNumber(args[1])) {
            // 完整：<编号> <理由...>
            caseId = resolveCaseId(sender, args[1]);
            if (caseId <= 0) {
                return;
            }
            if (args.length < 3) {
                sender.sendMessage("§c用法: /court dismiss <编号> <理由...>");
                return;
            }
            reason = join(args, 2);
        } else {
            // 省略案件号：/court dismiss <理由...>
            caseId = resolveCaseId(sender, null);
            if (caseId <= 0) {
                return;
            }
            reason = join(args, 1);
        }
        court.dismiss(caseId, player, reason);
    }

    private void onWithdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        int caseId = resolveCaseId(sender, args.length >= 2 ? args[1] : null);
        if (caseId <= 0) {
            return;
        }
        court.withdraw(caseId, player);
    }

    private void onLawyer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("pick")) {
            // /court lawyer pick <编号> <玩家>（编号可省略，进行中案件仅 1 个时）
            if (args.length < 3) {
                sender.sendMessage("§c用法: /court lawyer pick <编号> <玩家>");
                return;
            }
            int caseId;
            String lawyerName;
            if (args.length == 3 && !isNumber(args[2])) {
                // 省略编号：/court lawyer pick <玩家>
                caseId = resolveCaseId(sender, null);
                lawyerName = args[2];
            } else {
                caseId = resolveCaseId(sender, args[2]);
                if (caseId <= 0) {
                    return;
                }
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /court lawyer pick <编号> <玩家>");
                    return;
                }
                lawyerName = args[3];
            }
            if (caseId <= 0) {
                return;
            }
            court.lawyerPick(caseId, player, lawyerName);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            int caseId = resolveCaseId(sender, args.length >= 3 ? args[2] : null);
            if (caseId <= 0) {
                return;
            }
            court.lawyerCancel(caseId, player);
            return;
        }
        // 招募
        int caseId = resolveCaseId(sender, args.length >= 2 ? args[1] : null);
        if (caseId <= 0) {
            return;
        }
        court.lawyerRecruit(caseId, player);
    }

    private void onVolunteer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("court.view")) {
            sender.sendMessage("§c你没有权限报名律师（需要 court.view）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        int caseId = resolveCaseId(sender, args.length >= 2 ? args[1] : null);
        if (caseId <= 0) {
            return;
        }
        court.volunteer(caseId, player);
    }

    private void onWatch(CommandSender sender, String[] args) {
        if (!sender.hasPermission("court.watch")) {
            sender.sendMessage("§c你没有权限旁听（需要 court.watch）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        int caseId = resolveCaseId(sender, args.length >= 2 ? args[1] : null);
        if (caseId <= 0) {
            return;
        }
        court.watch(caseId, player);
    }

    private void onUnwatch(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        int caseId = resolveCaseId(sender, args.length >= 2 ? args[1] : null);
        if (caseId <= 0) {
            return;
        }
        court.unwatch(caseId, player);
    }

    private void onLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        court.leave(player);
    }

    private void onHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("court.view")) {
            sender.sendMessage("§c你没有权限查看案件记录（需要 court.view）。");
            return;
        }
        court.history(sender, args.length >= 2 ? args[1] : null);
    }

    private void onPos(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /court pos <judge|defendant|plaintiff> | pos spectator <编号> | pos del spectator <编号> | pos region <1|2> | pos del region | pos list");
            return;
        }
        String kind = args[1].toLowerCase();
        if (kind.equals("list")) {
            if (!sender.hasPermission("court.view")) {
                sender.sendMessage("§c你没有权限查看法庭位置（需要 court.view）。");
                return;
            }
            court.posList(sender);
            return;
        }
        if (!court.isJudge(sender)) {
            sender.sendMessage("§c你没有权限标记法庭位置（需要 court.judge）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (kind.equals("spectator")) {
            int n;
            try {
                n = Integer.parseInt(args[2]);
            } catch (Exception ex) {
                sender.sendMessage("§c用法: /court pos spectator <编号>");
                return;
            }
            court.markSpectator(n, player);
            return;
        }
        if (kind.equals("del")) {
            // v4.14：pos del region（清法院区域）
            if (args.length >= 3 && args[2].equalsIgnoreCase("region")) {
                court.delRegion(player);
                return;
            }
            if (args.length < 4 || !args[2].equalsIgnoreCase("spectator")) {
                sender.sendMessage("§c用法: /court pos del spectator <编号> | /court pos del region");
                return;
            }
            int n;
            try {
                n = Integer.parseInt(args[3]);
            } catch (Exception ex) {
                sender.sendMessage("§c编号必须是整数。");
                return;
            }
            court.delSpectator(n, player);
            return;
        }
        // v4.14：pos region <1|2> 标法院区域角点
        if (kind.equals("region")) {
            int which;
            try {
                which = Integer.parseInt(args[2]);
            } catch (Exception ex) {
                sender.sendMessage("§c用法: /court pos region <1|2>");
                return;
            }
            court.markRegionCorner(which, player);
            return;
        }
        court.markPoint(kind, player);
    }

    private void onBtn(CommandSender sender, String[] args) {
        if (!court.isJudge(sender)) {
            sender.sendMessage("§c你没有权限标记按钮（需要 court.judge）。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /court btn <left|middle|right> | btn del <left|middle|right>");
            return;
        }
        String name = args[1].toLowerCase();
        if (name.equals("del")) {
            if (args.length < 3) {
                sender.sendMessage("§c用法: /court btn del <left|middle|right>");
                return;
            }
            court.delButton(args[2].toLowerCase(), player);
            return;
        }
        court.markButton(name, player);
    }

    // ============================================================== 案件号解析
    /** 解析案件号：arg 为空则自动指向唯一进行中案件；非法/不适合时发送提示并返回 <=0。 */
    private int resolveCaseId(CommandSender sender, String arg) {
        if (arg == null || arg.isEmpty()) {
            int single = court.singleActiveCaseId();
            if (single != -1) {
                return single;
            }
            if (court.activeCaseCount() == 0) {
                sender.sendMessage("§c没有进行中案件。");
                return -1;
            }
            sender.sendMessage("§c有多个进行中案件，请指定编号。进行中案件: " + idList(court.activeCaseIds()));
            return -1;
        }
        try {
            int id = Integer.parseInt(arg);
            if (id < 1) {
                sender.sendMessage("§c案件编号必须 >= 1。");
                return -1;
            }
            return id;
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c案件编号必须是整数。");
            return -2;
        }
    }

    private static String idList(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("#").append(ids.get(i));
        }
        return sb.toString();
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static boolean isNumber(String s) {
        if (s == null) {
            return false;
        }
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static Integer parseInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ============================================================== 帮助
    /** /court help：全量可点击帮助（v4.19：每个指令都列出，含按钮/位置标记）。 */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(legacy("§e§l========== 人民法院 /court =========="));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【指控与查询】"));
        sender.sendMessage(clickable("§a/court accuse <玩家> <理由> §7- 起诉玩家（立案，等待法官开庭）", "/court accuse ", true));
        sender.sendMessage(clickable("§a/court list §7- 查看进行中案件", "/court list", false));
        sender.sendMessage(clickable("§a/court case <编号> §7- 查看案件详情（唯一案件时可省略编号）", "/court case ", true));
        sender.sendMessage(clickable("§a/court history [玩家] §7- 查看历史案件", "/court history ", true));
        sender.sendMessage(clickable("§a/court help §7- 本帮助", "/court help", false));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【旁听与律师】"));
        sender.sendMessage(clickable("§a/court watch <编号> §7- 报名旁听（开庭自动/立即传送+分配座位）", "/court watch ", true));
        sender.sendMessage(clickable("§a/court unwatch <编号> §7- 按案件号取消旁听", "/court unwatch ", true));
        sender.sendMessage(clickable("§a/court leave §7- 一键退出所有旁听", "/court leave", false));
        sender.sendMessage(clickable("§a/court lawyer <编号> §7- 广播招募律师（当事人）", "/court lawyer ", true));
        sender.sendMessage(clickable("§a/court lawyer pick <编号> <玩家> §7- 从候选人中指定律师", "/court lawyer pick ", true));
        sender.sendMessage(clickable("§a/court lawyer cancel <编号> §7- 取消招募/解聘", "/court lawyer cancel ", true));
        sender.sendMessage(clickable("§a/court volunteer <编号> §7- 报名担任律师", "/court volunteer ", true));
        sender.sendMessage(legacy(""));
        sender.sendMessage(legacy("§6§l【当事人】"));
        sender.sendMessage(clickable("§a/court withdraw <编号> §7- 撤诉（仅原告本人；公诉案不可撤）", "/court withdraw ", true));
        sender.sendMessage(legacy(""));
        if (court.isJudge(sender)) {
            sender.sendMessage(legacy("§6§l【法官·审判】"));
            sender.sendMessage(clickable("§a/court prosecute <玩家> <理由> §7- 公诉（立案即开庭）", "/court prosecute ", true));
            sender.sendMessage(clickable("§a/court trial <编号> §7- 开庭（被告须在线）", "/court trial ", true));
            sender.sendMessage(clickable("§a/court verdict <编号> <guilty|innocent> [数量] §7- 命令宣判（聊天框输入判决亦可）", "/court verdict ", true));
            sender.sendMessage(clickable("§a/court dismiss <编号> <理由> §7- 驳回案件", "/court dismiss ", true));
            sender.sendMessage(legacy(""));
            sender.sendMessage(legacy("§6§l【法官·位置标记】"));
            sender.sendMessage(clickable("§a/court pos judge §7- 标记法官席（站上去执行）", "/court pos judge", false));
            sender.sendMessage(clickable("§a/court pos defendant §7- 标记被告席", "/court pos defendant", false));
            sender.sendMessage(clickable("§a/court pos plaintiff §7- 标记原告席", "/court pos plaintiff", false));
            sender.sendMessage(clickable("§a/court pos spectator <编号> §7- 标记 N 号观众席（逐个标，站座位上）", "/court pos spectator ", true));
            sender.sendMessage(clickable("§a/court pos del spectator <编号> §7- 删除观众席（占用中拒绝）", "/court pos del spectator ", true));
            sender.sendMessage(clickable("§a/court pos region <1|2> §7- 标记法院区域角点（区域内禁破坏/放置）", "/court pos region ", true));
            sender.sendMessage(clickable("§a/court pos del region §7- 删除法院区域", "/court pos del region", false));
            sender.sendMessage(clickable("§a/court pos list §7- 查看所有标记（席位/区域/按钮）", "/court pos list", false));
            sender.sendMessage(legacy(""));
            sender.sendMessage(legacy("§6§l【法官·按钮标记】（看着石头按钮执行）"));
            sender.sendMessage(clickable("§a/court btn left §7- 标记法官桌左按钮（原告发言）", "/court btn left", false));
            sender.sendMessage(clickable("§a/court btn middle §7- 标记法官桌中按钮（确认开庭→宣判输入）", "/court btn middle", false));
            sender.sendMessage(clickable("§a/court btn right §7- 标记法官桌右按钮（被告发言）", "/court btn right", false));
            sender.sendMessage(clickable("§a/court btn done-plaintiff §7- 标记原告席“发言完毕”按钮（原告自己按）", "/court btn done-plaintiff", false));
            sender.sendMessage(clickable("§a/court btn done-defendant §7- 标记被告席“发言完毕”按钮（被告自己按）", "/court btn done-defendant", false));
            sender.sendMessage(clickable("§a/court btn del <left|middle|right|done-plaintiff|done-defendant> §7- 删除按钮标记（方块被拆会自动失效）", "/court btn del ", true));
            sender.sendMessage(legacy(""));
            sender.sendMessage(legacy("§7按钮操作：右键点击按钮（法官桌 3 个仅法官有效；席位“发言完毕”由当前发言方自按，法官也可代按）"));
            sender.sendMessage(legacy(""));
        }
        sender.sendMessage(legacy("§7短别名: su=起诉 gs=公诉 kt=开庭 pj=宣判 bh=驳回 cs=撤诉 ls=律师 bm=报名 pt=旁听 qx=取消 tc=退出 lb=列表 aj=案件 jl=记录 wz=位置 an=按钮；根命令别名 /fy /ct"));
        sender.sendMessage(legacy("§7提示: 点击指令即可使用；带 <参数> 的指令点击后自动填入聊天框，补全参数后回车执行。"));
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private static Component clickable(String display, String command, boolean suggest) {
        if (suggest) {
            return legacy(display).clickEvent(ClickEvent.suggestCommand(command));
        }
        return legacy(display).clickEvent(ClickEvent.runCommand(command));
    }

    // ============================================================== Tab 补全
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!court.isEnabled()) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return subcommands(sender).stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return completeArg1(sender, sub, prefix);
        }
        if (args.length == 3) {
            String prefix = args[2].toLowerCase();
            return completeArg2(sender, sub, args, prefix);
        }
        if (args.length == 4) {
            String prefix = args[3].toLowerCase();
            // lawyer pick <编号> <玩家>
            if ((sub.equals("lawyer") || sub.equals("ls")) && args[1].equalsIgnoreCase("pick")) {
                Integer caseId = parseIntIfNum(args[2]);
                if (caseId != null) {
                    return court.candidateNames(caseId).stream()
                            .filter(n -> n.toLowerCase().startsWith(prefix))
                            .toList();
                }
                return List.of();
            }
            // pos del spectator <编号>
            if ((sub.equals("pos") || sub.equals("wz")) && args[1].equalsIgnoreCase("del")
                    && args[2].equalsIgnoreCase("spectator")) {
                return court.markedSpectatorSeats().stream()
                        .map(String::valueOf)
                        .filter(s -> s.startsWith(prefix))
                        .toList();
            }
        }
        return List.of();
    }

    private List<String> completeArg1(CommandSender sender, String sub, String prefix) {
        List<String> r = new ArrayList<>();
        switch (sub) {
            case "accuse", "su" -> r.addAll(court.onlinePlayerNames(true));
            case "prosecute", "gs" -> r.addAll(court.onlinePlayerNames(true));
            case "case", "aj" -> addIds(r, court.activeCaseIds());
            case "trial", "kt" -> addIds(r, court.pendingCaseIds());
            case "verdict", "pj" -> addIds(r, court.trialCaseIds());
            case "dismiss", "bh" -> addIds(r, court.activeCaseIds());
            case "withdraw", "cs" -> {
                if (sender instanceof Player p) {
                    addIds(r, court.plaintiffActiveCaseIds(p.getUniqueId()));
                }
            }
            case "lawyer", "ls" -> {
                r.add("pick");
                r.add("cancel");
                addIds(r, court.pendingCaseIds());
            }
            case "volunteer", "bm" -> addIds(r, court.pendingCaseIds());
            case "watch", "pt" -> addIds(r, court.watchableCaseIds());
            case "unwatch", "qx" -> {
                if (sender instanceof Player p) {
                    addIds(r, court.watcherCaseIds(p.getUniqueId()));
                }
            }
            case "history", "jl" -> r.addAll(onlineNames(false));
            case "pos", "wz" -> r.addAll(List.of("judge", "defendant", "plaintiff",
                    "spectator", "region", "list", "del"));
            case "btn", "an" -> r.addAll(List.of("left", "middle", "right", "done-plaintiff", "done-defendant", "del"));
            default -> {
            }
        }
        return r.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
    }

    private List<String> completeArg2(CommandSender sender, String sub, String[] args, String prefix) {
        List<String> r = new ArrayList<>();
        switch (sub) {
            case "verdict", "pj" -> {
                r.add("guilty");
                r.add("innocent");
            }
            case "pos", "wz" -> {
                String kind = args[1].toLowerCase();
                if (kind.equals("spectator")) {
                    // 补座位号 1..max+1（允许标记一个新座位）
                    int max = court.spectatorMarkedCount() + 1;
                    for (int i = 1; i <= max; i++) {
                        r.add(String.valueOf(i));
                    }
                } else if (kind.equals("region")) {
                    // v4.14：补角点 1/2
                    r.add("1");
                    r.add("2");
                } else if (kind.equals("del")) {
                    r.add("spectator");
                    r.add("region"); // v4.14
                }
            }
            case "btn", "an" -> {
                if (args[1].equalsIgnoreCase("del")) {
                    r.add("left");
                    r.add("middle");
                    r.add("right");
                    r.add("done-plaintiff");
                    r.add("done-defendant");
                }
            }
            case "lawyer", "ls" -> {
                if (args[1].equalsIgnoreCase("pick") || args[1].equalsIgnoreCase("cancel")) {
                    addIds(r, court.pendingCaseIds());
                }
            }
            default -> {
            }
        }
        return r.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
    }

    private static void addIds(List<String> list, List<Integer> ids) {
        for (Integer id : ids) {
            list.add(String.valueOf(id));
        }
    }

    private static List<String> onlineNames(boolean accusableOnly) {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static Integer parseIntIfNum(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** arg0 子命令集合（全名+别名），按权限过滤。 */
    private List<String> subcommands(CommandSender sender) {
        List<String> r = new ArrayList<>();
        // 公共项
        if (sender.hasPermission("court.accuse")) {
            r.add("accuse");
            r.add("su");
        }
        if (sender.hasPermission("court.view")) {
            r.add("list");
            r.add("lb");
            r.add("case");
            r.add("aj");
            r.add("lawyer");
            r.add("ls");
            r.add("volunteer");
            r.add("bm");
            r.add("history");
            r.add("jl");
        }
        if (sender.hasPermission("court.watch")) {
            r.add("watch");
            r.add("pt");
        }
        r.add("unwatch");
        r.add("qx");
        r.add("leave");
        r.add("tc");
        r.add("help");
        // 法官专属
        if (court.isJudge(sender)) {
            r.add("prosecute");
            r.add("gs");
            r.add("trial");
            r.add("kt");
            r.add("verdict");
            r.add("pj");
            r.add("dismiss");
            r.add("bh");
            r.add("pos");
            r.add("wz");
            r.add("btn");
            r.add("an");
        }
        return r;
    }
}
