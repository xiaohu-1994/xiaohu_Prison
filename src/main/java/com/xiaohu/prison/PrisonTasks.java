package com.xiaohu.prison;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Material;

/**
 * 材质中文名工具（v3.14 起为纯静态工具；config tasks 段与任务 id 机制已移除——
 * /prison send <玩家> <数量> 直接指定挖矿数量，材质固定为 mine.breakable-material）。
 */
public final class PrisonTasks {

    private PrisonTasks() {
    }

    /** 常见材质中文名映射；未收录的材质回退为材质枚举名。 */
    public static String displayName(Material material) {
        String name = CHINESE_NAMES.get(material);
        return name == null ? material.name() : name;
    }

    private static final Map<Material, String> CHINESE_NAMES = buildChineseNames();

    private static Map<Material, String> buildChineseNames() {
        Map<Material, String> map = new LinkedHashMap<>();
        map.put(Material.STONE, "石头");
        map.put(Material.COBBLESTONE, "圆石");
        map.put(Material.DEEPSLATE, "深板岩");
        map.put(Material.DIAMOND_ORE, "钻石矿");
        map.put(Material.IRON_ORE, "铁矿石");
        map.put(Material.COAL_ORE, "煤矿石");
        map.put(Material.GOLD_ORE, "金矿石");
        map.put(Material.EMERALD_ORE, "绿宝石矿石");
        map.put(Material.COPPER_ORE, "铜矿石");
        map.put(Material.OAK_LOG, "橡木原木");
        map.put(Material.SPRUCE_LOG, "云杉原木");
        map.put(Material.BIRCH_LOG, "白桦原木");
        map.put(Material.JUNGLE_LOG, "丛林原木");
        map.put(Material.ACACIA_LOG, "金合欢原木");
        map.put(Material.DARK_OAK_LOG, "深色橡木原木");
        map.put(Material.MANGROVE_LOG, "红树木原木");
        map.put(Material.CHERRY_LOG, "樱花原木");
        map.put(Material.NETHERRACK, "地狱岩");
        map.put(Material.NETHER_QUARTZ_ORE, "下界石英矿石");
        map.put(Material.ANCIENT_DEBRIS, "远古残骸");
        map.put(Material.GRASS_BLOCK, "草方块");
        map.put(Material.DIRT, "泥土");
        map.put(Material.SANDSTONE, "砂岩");
        map.put(Material.SAND, "沙子");
        map.put(Material.GRAVEL, "沙砾");
        map.put(Material.STONE_BRICKS, "石砖");
        map.put(Material.OBSIDIAN, "黑曜石");
        return Collections.unmodifiableMap(map);
    }
}
