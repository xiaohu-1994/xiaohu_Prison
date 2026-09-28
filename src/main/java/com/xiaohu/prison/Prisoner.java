package com.xiaohu.prison;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * 在狱玩家模型（record，不可变），字段与 prisoners.yml schema 一一对应（PLAN.md §10）。
 *
 * @param uuid        玩家 UUID（prisoners.yml 键）
 * @param name        入狱时的玩家名（冗余存储，便于调试/列表显示）
 * @param worldName   入狱前所在世界名
 * @param x           入狱前位置 X
 * @param y           入狱前位置 Y
 * @param z           入狱前位置 Z
 * @param yaw         入狱前朝向 yaw
 * @param pitch       入狱前朝向 pitch
 * @param taskKey     任务材质枚举名（null = 无任务，需手动释放）
 * @param taskTarget  任务目标数量（挖够自动释放）
 * @param taskProgress 当前已完成数量
 * @param cellIndex   分配的房间编号（PrisonCells 注册表中的房间号）
 * @param gameMode    入狱前的游戏模式（释放时恢复）
 * @param inventorySnapshot 入狱前完整背包快照（槽位 → ItemStack，含装备与副手；v3.25）
 * @param effectsSnapshot   入狱前药水效果快照（v3.25）
 * @param confiscated 背包/效果是否已暂扣（v4.11：在线入狱=true；离线入狱=false，重进服时延后暂扣）
 */
public record Prisoner(
        UUID uuid,
        String name,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String taskKey,
        int taskTarget,
        int taskProgress,
        int cellIndex,
        GameMode gameMode,
        Map<Integer, ItemStack> inventorySnapshot,
        List<PotionEffect> effectsSnapshot,
        boolean confiscated
) {

    /** 是否分配了任务（taskKey 非 null）。 */
    public boolean hasTask() {
        return taskKey != null;
    }

    /** 任务是否已完成（有任务且进度 >= 目标）。 */
    public boolean isTaskComplete() {
        return hasTask() && taskProgress >= taskTarget;
    }

    /** 复制记录并替换进度（进度变更的公用构造，保留其余字段含快照）。 */
    public Prisoner withProgress(int newProgress) {
        return new Prisoner(uuid, name, worldName, x, y, z, yaw, pitch,
                taskKey, taskTarget, newProgress, cellIndex, gameMode, inventorySnapshot, effectsSnapshot,
                confiscated);
    }

    /** 由入狱时的 Location 快照构造记录（任务进度初始为 0，记录模式/背包/效果快照；在线入狱已暂扣）。 */
    public static Prisoner fromLocation(UUID uuid, String name, Location location,
                                        String taskKey, int taskTarget, int cellIndex, GameMode gameMode,
                                        Map<Integer, ItemStack> inventorySnapshot,
                                        List<PotionEffect> effectsSnapshot) {
        World world = location.getWorld();
        return new Prisoner(uuid, name,
                world == null ? "" : world.getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                taskKey, taskTarget, 0, cellIndex, gameMode, inventorySnapshot, effectsSnapshot,
                true);
    }

    /**
     * 离线入狱记录（v4.11）：位置/背包/效果快照为空、未暂扣（confiscated=false）——
     * 玩家重进服时由 {@code PrisonerManager.confiscateOnJoin} 延后快照暂扣；
     * 释放时若仍未暂扣则跳过背包处理（从未收走就无需归还）。
     */
    public static Prisoner fromOffline(UUID uuid, String name, String taskKey, int taskTarget, int cellIndex) {
        return new Prisoner(uuid, name, "", 0.0, 0.0, 0.0, 0.0f, 0.0f,
                taskKey, taskTarget, 0, cellIndex,
                GameMode.SURVIVAL, new java.util.LinkedHashMap<>(), new java.util.ArrayList<>(),
                false);
    }

    /**
     * 还原入狱前位置。
     *
     * @return 原位置；原世界缺失（被删除/未加载）时返回 null，由调用方回退默认世界出生点
     */
    public Location originalLocation() {
        World world = Bukkit.getWorld(worldName());
        if (world == null) {
            return null;
        }
        return new Location(world, x(), y(), z(), yaw(), pitch());
    }
}
