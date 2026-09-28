package com.xiaohu.prison;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * 监狱参观（v4.2，PLAN.md §19）：管理员用 /prison visit pos 标记参观传送点（visit.yml），
 * 普通玩家 /prison visit 传送到参观点参观，/prison visit leave 传回参观前位置。
 *
 * <p>参观者限制由 PrisonerListener 承担：破坏/放置已有逻辑拦截，丢弃物品（PlayerDropItemEvent）
 * 与玩家伤害免疫（visit.protect）在本模块配合。参观者原位置快照仅存内存（服务器重启丢失后，
 * leave 回主世界出生点兜底）。所有方法仅允许主线程调用。
 */
public final class PrisonVisit {

    /** 参观传送点：仅存原始字段；world 用于与监狱世界名一致性校验。 */
    public record VisitLocation(String worldName, double x, double y, double z, float yaw, float pitch) {

        @Override
        public String toString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }
    }

    private final PrisonPlugin plugin;
    private final String prisonWorldName;
    private final File dataFile;
    /** 参观传送点（null = 未标记）。 */
    private VisitLocation location;
    /** 参观者 → 参观前位置快照（内存态；重启丢失 → leave 回主世界出生点兜底）。 */
    private final Map<UUID, Location> visitors = new LinkedHashMap<>();
    /** 参观者伤害免疫（config visit.protect，默认 true）。 */
    private final boolean protect;

    public PrisonVisit(PrisonPlugin plugin, String prisonWorldName) {
        this.plugin = plugin;
        this.prisonWorldName = prisonWorldName;
        this.dataFile = new File(plugin.getDataFolder(), "visit.yml");
        this.protect = plugin.getConfig().getBoolean("visit.protect", true);
    }

    /** 监狱世界名（监听器判断参观者所在世界用）。 */
    public String getPrisonWorldName() {
        return prisonWorldName;
    }

    /** 是否已标记参观传送点。 */
    public boolean isMarked() {
        return location != null;
    }

    /**
     * 从 visit.yml 加载参观传送点。字段缺失视为未标记（不 warn——首次运行本就为空）。
     */
    public void load() {
        location = null;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        if (cfg.contains("visit.world") && cfg.contains("visit.x")
                && cfg.contains("visit.y") && cfg.contains("visit.z")) {
            location = new VisitLocation(
                    cfg.getString("visit.world"),
                    cfg.getDouble("visit.x"),
                    cfg.getDouble("visit.y"),
                    cfg.getDouble("visit.z"),
                    (float) cfg.getDouble("visit.yaw", 0.0),
                    (float) cfg.getDouble("visit.pitch", 0.0));
        }
        if (location != null) {
            plugin.getLogger().info("已加载监狱参观传送点: " + location);
        }
    }

    /** 全量落盘 visit.yml（标记即保存，onDisable 兜底再存一次）。 */
    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        if (location != null) {
            cfg.set("visit.world", location.worldName());
            cfg.set("visit.x", location.x());
            cfg.set("visit.y", location.y());
            cfg.set("visit.z", location.z());
            cfg.set("visit.yaw", location.yaw());
            cfg.set("visit.pitch", location.pitch());
        }
        try {
            cfg.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 visit.yml 失败: " + ex.getMessage());
        }
    }

    /**
     * 参观传送点。
     *
     * @return 位置（世界解析为当前加载的监狱世界）；未标记、存储世界与监狱世界名不一致、
     *         或监狱世界未加载时返回 null
     */
    public Location visitLocation() {
        if (location == null) {
            return null;
        }
        if (!prisonWorldName.equals(location.worldName())) {
            return null;
        }
        World world = Bukkit.getWorld(prisonWorldName);
        if (world == null) {
            return null;
        }
        return new Location(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
    }

    /** 标记参观传送点（调用方需保证管理员在监狱世界内）。标记即落盘。 */
    public void mark(Location loc) {
        World world = loc.getWorld();
        location = new VisitLocation(
                world == null ? prisonWorldName : world.getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
        save();
    }

    /** 是否正在参观中。 */
    public boolean isVisitor(UUID uuid) {
        return visitors.containsKey(uuid);
    }

    /**
     * 进入参观：快照参观前位置 → 传送 → 发送参观须知。
     *
     * @return 是否传送成功（传送点不可用返回 false；已在参观中由调用方先查 isVisitor）
     */
    public boolean visit(Player player) {
        Location target = visitLocation();
        if (target == null) {
            return false;
        }
        visitors.put(player.getUniqueId(), player.getLocation());
        player.teleport(target);
        player.sendMessage("§a你已进入监狱参观！");
        player.sendMessage("§c注意：参观期间不能破坏/放置方块，不能丢弃物品。");
        player.sendMessage("§e离开请使用: §a/prison visit leave");
        return true;
    }

    /**
     * 离开参观：传回参观前位置（快照丢失——如服务器重启——回默认世界出生点兜底）。
     *
     * @return 是否有快照（true = 传回原位置；false = 兜底回默认世界出生点）
     */
    public boolean leave(Player player) {
        Location original = visitors.remove(player.getUniqueId());
        Location target = original;
        if (target == null) {
            World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            target = defaultWorld == null ? null : defaultWorld.getSpawnLocation();
        }
        if (target != null) {
            player.teleport(target);
        }
        player.sendMessage("§a你已离开监狱参观，欢迎下次再来！");
        return original != null;
    }

    /** 玩家退出时清理参观状态（防内存泄漏；重进服速度/位置本就重置）。 */
    public void onQuit(UUID uuid) {
        visitors.remove(uuid);
    }

    /** 参观者伤害免疫开关（config visit.protect）。 */
    public boolean isProtectEnabled() {
        return protect;
    }
}
