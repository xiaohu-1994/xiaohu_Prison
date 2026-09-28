package com.xiaohu.prison;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 人民法院事件监听（v4.x）：
 * <ul>
 *   <li>PlayerJoinEvent：执行 AWAITING_EXECUTION 判决（被告重进服自动收监）；</li>
 *   <li>PlayerQuitEvent：清理定身/名牌/座位/专属快照（防内存泄漏、无残留状态）；</li>
 *   <li>PlayerMoveEvent：定身玩家位移（>0.01 格）→ 取消（转身 yaw/pitch 不受影响）；</li>
 *   <li>PlayerVehicleEnterEvent（org.bukkit.event.vehicle.VehicleEnterEvent）：定身玩家上车 → 驱逐 + 取消；</li>
 *   <li>PlayerInteractEvent：右键点中法官桌石头按钮 → 法官按钮系统；</li>
 *   <li>AsyncChatEvent：宣判输入模式截获判决文字 + 轮次禁言（对方发言时禁本方）；</li>
 *   <li>PlayerCommandPreprocessEvent：庭审指令限制（当事人/律师只能使用法院指令）。</li>
 * </ul>
 */
public final class CourtListener implements Listener {

    private final PrisonPlugin plugin;
    private final CourtManager court;

    public CourtListener(PrisonPlugin plugin, CourtManager court) {
        this.plugin = plugin;
        this.court = court;
    }

    /** 重进服执行 AWAITING_EXECUTION 判决（自动收监）。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        court.onPlayerJoin(event.getPlayer());
    }

    /** 玩家退出：清理定身/名牌/座位快照。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        court.onPlayerQuit(event.getPlayer());
    }

    /** 定身玩家不能移动（位置变化 > 0.01 格 → 取消）；转头不受影响。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (!court.isFrozen(p)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    /** 定身玩家尝试上车 → 驱逐 + 取消（防骑马/船移动）。 */
    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player p)) {
            return;
        }
        if (!court.isFrozen(p)) {
            return;
        }
        event.setCancelled(true);
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
    }

    /** 法官桌按钮点击（右键石头按钮）。 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        court.onButtonInteract(event.getPlayer(), block);
    }

    /** 宣判输入 + 轮次禁言。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        // 宣判输入模式：截获法官下一条聊天为判决文字
        Integer caseId = court.verdictInputCase(p.getUniqueId());
        if (caseId != null) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            UUID uuid = p.getUniqueId();
            // 异步线程只捕获文本与玩家 UUID；标题/广播回主线程
            Bukkit.getScheduler().runTask(plugin, () -> court.onVerdictText(uuid, text));
            return;
        }
        // 轮次禁言：轮到一方发言时，对方（含其律师）聊天被取消（法官/观众不受限）
        if (court.shouldMute(p)) {
            event.setCancelled(true);
            p.sendMessage("§c现在不是你的发言时间，请等待法官点名");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f); // v4.16 禁言提示音
        }
    }

    /** 庭审指令限制：当事人/律师庭审期间只能使用法院指令（法官/观众不受限）。 */
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        String msg = event.getMessage();
        if (msg.length() < 2 || msg.charAt(0) != '/') {
            return;
        }
        // v4.15 修订：法官在宣判输入模式中输入指令（如 /ban、/prison send）→
        // 指令放行由法官正常执行（不代执行），同时全庭标题 + 判决书广播；
        // v4.20 防护：法院自身指令（/court、/fy、/ct）不当作判决捕获——防止误输入即误判，模式保持
        if (court.verdictInputCase(p.getUniqueId()) != null) {
            String label = msg.substring(1).trim().split("\\s+")[0].toLowerCase();
            if (!label.equals("court") && !label.equals("fy") && !label.equals("ct")) {
                UUID uuid = p.getUniqueId();
                court.onVerdictText(uuid, msg);
                return; // 不取消事件：指令继续正常执行
            }
            return; // 法院指令照常执行，宣判输入模式保持
        }
        String label = msg.substring(1).trim().split("\\s+")[0].toLowerCase();
        if (court.shouldRestrictCommand(p, label)) {
            event.setCancelled(true);
            event.setMessage("");
            p.sendMessage("§c庭审期间只能使用法院指令");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f); // v4.16 指令限制提示音
        }
    }

    /** v4.14 法院区域保护：区域内所有人（含管理员）禁止破坏方块。 */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (court.isProtectRegionEnabled() && court.isInCourtRegion(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c庭审区域禁止破坏方块！");
        }
    }

    /** v4.14 法院区域保护：区域内所有人（含管理员）禁止放置方块。 */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (court.isProtectRegionEnabled() && court.isInCourtRegion(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c庭审区域禁止放置方块！");
        }
    }
}
