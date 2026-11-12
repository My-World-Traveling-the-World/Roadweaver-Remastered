/* 文件职责：模组名 / 结构名 / 短语级中文对照表（312 条）。 */
package net.shiroha233.roadweaver.client.config.localization;

import java.util.Map;

/**
 * 模组名、结构名与短语级中文对照表。
 *
 * <h2>三张表的分工</h2>
 * <ul>
 *   <li>{@code mods} —— 模组 id → 中文名（{@code roadweaver} → 阡陌交通）</li>
 *   <li>{@code structures} —— 完整结构 id → 中文名（{@code minecraft:ancient_city} → 古城）</li>
 *   <li>{@code phrases} —— 多词短语 → 中文（优先于逐词查表）</li>
 * </ul>
 *
 * <h2>为什么单独成文件</h2>
 * <p>数据与逻辑分离：本类<b>只有数据</b>，查表与拼接逻辑在
 * {@link ChineseRoadWeaverNames}；单词级的 558 条在 {@link ChineseRoadWeaverWords}。
 * 拆开是为了守住「一个文件不得超过千行」的硬约束，同时让查词条不必翻过几百行代码。</p>
 *
 * @see ChineseRoadWeaverNames
 * @see ChineseRoadWeaverWords
 */
final class ChineseRoadWeaverDictionary {

    private ChineseRoadWeaverDictionary() {}

    /**
     * 把模组名 / 结构名 / 短语注册进各自的目标表。
     *
     * @param mods       模组 id → 中文名
     * @param structures 结构 id → 中文名
     * @param phrases    多词短语 → 中文
     */
    static void registerInto(Map<String, String> mods,
                             Map<String, String> structures,
                             Map<String, String> phrases) {
        mods.put("minecraft", "原版");
        mods.put("roadweaver", "阡陌交通");
        mods.put("abridged", "桥梁 Abridged");
        mods.put("amendments", "Amendments 修正");
        mods.put("ctov", "CTOV 村庄大修");
        mods.put("ecologics", "丰富的生态");
        mods.put("end_villager_outpost", "末地村民哨站");
        mods.put("farmers_structures", "农夫结构");
        mods.put("bettermineshafts", "YUNG 更好的矿井");
        mods.put("betteroceanmonuments", "YUNG 更好的海底神殿");
        mods.put("betterstrongholds", "YUNG 更好的要塞");
        mods.put("betterwitchhuts", "YUNG 更好的女巫小屋");
        mods.put("biomeswevegone", "我们逝去的生物群系");
        mods.put("campfire", "野营篝火");
        mods.put("explorify", "Explorify 探索结构");
        mods.put("formationsnether", "下界地貌结构");
        mods.put("formationsoverworld", "主世界地貌结构");
        mods.put("friendsandfoes", "友与敌");
        mods.put("galosphere", "Galosphere");
        mods.put("giant_natural_additions", "大型自然扩展");
        mods.put("goodending", "Good Ending");
        mods.put("heaven", "天堂结构");
        mods.put("hopo", "Hopo 结构");
        mods.put("immersive_gateways", "沉浸式传送门");
        mods.put("joshie", "Joshie 结构");
        mods.put("lios_outlandish_villages", "Lio 的异域村庄");
        mods.put("mbs", "Moog 更好结构");
        mods.put("mes", "Moog 末地结构");
        mods.put("mmr", "Moog 矿井遗迹");
        mods.put("mmv", "Moog 更多村庄");
        mods.put("mns", "Moog 下界结构");
        mods.put("mostructures", "Mo' Structures");
        mods.put("mss", "Moog 船舰结构");
        mods.put("mtr", "Moog 旅行遗迹");
        mods.put("mvs", "Moog 村庄结构");
        mods.put("natures_spirit", "自然之灵");
        mods.put("ocean_lily_pad_village", "海上睡莲村庄");
        mods.put("qrafty", "Qrafty 结构");
        mods.put("snowyspirit", "雪意节庆");
        mods.put("structory", "Structory 结构");
        mods.put("structory_towers", "Structory 高塔");
        mods.put("supplementaries", "锦致装饰");
        mods.put("terralith", "Terralith");
        mods.put("totw_modded", "旷野之塔：模组版");
        mods.put("towns_and_towers", "城镇与高塔");
        mods.put("trek", "Trek 结构");
        mods.put("villagehorizons", "村庄地平线");
        mods.put("weatheredwells", "风化水井");
        structures.put("minecraft:ancient_city", "古城");
        structures.put("minecraft:bastion_remnant", "堡垒遗迹");
        structures.put("minecraft:buried_treasure", "埋藏的宝藏");
        structures.put("minecraft:desert_pyramid", "沙漠神殿");
        structures.put("minecraft:end_city", "末地城");
        structures.put("minecraft:fortress", "下界要塞");
        structures.put("minecraft:igloo", "雪屋");
        structures.put("minecraft:jungle_pyramid", "丛林神庙");
        structures.put("minecraft:mansion", "林地府邸");
        structures.put("minecraft:mineshaft", "矿井");
        structures.put("minecraft:mineshaft_mesa", "恶地矿井");
        structures.put("minecraft:monument", "海底神殿");
        structures.put("minecraft:nether_fossil", "下界化石");
        structures.put("minecraft:ocean_ruin_cold", "冷水海底废墟");
        structures.put("minecraft:ocean_ruin_warm", "暖水海底废墟");
        structures.put("minecraft:pillager_outpost", "掠夺者前哨站");
        structures.put("minecraft:ruined_portal", "废弃传送门");
        structures.put("minecraft:ruined_portal_desert", "沙漠废弃传送门");
        structures.put("minecraft:ruined_portal_jungle", "丛林废弃传送门");
        structures.put("minecraft:ruined_portal_mountain", "山地废弃传送门");
        structures.put("minecraft:ruined_portal_nether", "下界废弃传送门");
        structures.put("minecraft:ruined_portal_ocean", "海洋废弃传送门");
        structures.put("minecraft:ruined_portal_swamp", "沼泽废弃传送门");
        structures.put("minecraft:shipwreck", "沉船");
        structures.put("minecraft:shipwreck_beached", "搁浅沉船");
        structures.put("minecraft:stronghold", "要塞");
        structures.put("minecraft:stronghold_big", "大型要塞");
        structures.put("minecraft:swamp_hut", "沼泽小屋");
        structures.put("minecraft:trail_ruins", "古迹废墟");
        structures.put("minecraft:trial_chambers", "试炼密室");
        structures.put("minecraft:village_desert", "沙漠村庄");
        structures.put("minecraft:village_plains", "平原村庄");
        structures.put("minecraft:village_savanna", "热带草原村庄");
        structures.put("minecraft:village_snowy", "雪原村庄");
        structures.put("minecraft:village_taiga", "针叶林村庄");
        structures.put("roadweaver:bridge_bridge_0", "桥梁 0");
        structures.put("roadweaver:bridge_bridge_1", "桥梁 1");
        structures.put("roadweaver:roadside_bench", "路边长椅");
        structures.put("roadweaver:roadside_campfire", "路边篝火");
        structures.put("roadweaver:roadside_campsite_1", "路边营地 1");
        structures.put("roadweaver:roadside_campsite_2", "路边营地 2");
        structures.put("roadweaver:roadside_campsite_3", "路边营地 3");
        structures.put("roadweaver:roadside_grave_easter_egg", "路边彩蛋墓碑");
        structures.put("roadweaver:roadside_grave_fogg05", "路边 Fogg05 墓碑");
        structures.put("roadweaver:roadside_grave_magic_muggle", "路边魔法麻瓜墓碑");
        structures.put("roadweaver:roadside_grave_notch", "路边 Notch 墓碑");
        structures.put("roadweaver:roadside_grave_technoblade", "路边 Technoblade 墓碑");
        structures.put("roadweaver:roadside_maid_house", "路边女仆小屋");
        structures.put("roadweaver:roadside_sakura_coffee_house", "路边樱花咖啡屋");
        structures.put("roadweaver:spawn_cabin", "出生木屋");
        structures.put("roadweaver:stone_bridge_shiqiao", "石桥");
        structures.put("roadweaver:wood_bridge_hongshu", "红树林木桥");
        structures.put("roadweaver:wood_bridge_huamu", "桦木桥");
        structures.put("roadweaver:wood_bridge_jinhehuan", "金合欢木桥");
        structures.put("roadweaver:wood_bridge_shensexiangmu", "深色橡木桥");
        structures.put("roadweaver:wood_bridge_yinghuamu", "樱花木桥");
        structures.put("roadweaver:wood_bridge_zhuzi", "竹桥");
        phrases.put("ancient_city", "古城");
        phrases.put("bastion_remnant", "堡垒遗迹");
        phrases.put("buried_treasure", "埋藏的宝藏");
        phrases.put("desert_pyramid", "沙漠神殿");
        phrases.put("end_city", "末地城");
        phrases.put("jungle_pyramid", "丛林神庙");
        phrases.put("pillager_outpost", "掠夺者前哨站");
        phrases.put("ruined_portal", "废弃传送门");
        phrases.put("swamp_hut", "沼泽小屋");
        phrases.put("trail_ruins", "古迹废墟");
        phrases.put("trial_chambers", "试炼密室");
        phrases.put("village_beach", "海滩村庄");
        phrases.put("village_christmas", "圣诞村庄");
        phrases.put("village_dark_forest", "黑森林村庄");
        phrases.put("village_desert", "沙漠村庄");
        phrases.put("village_desert_oasis", "沙漠绿洲村庄");
        phrases.put("village_jungle", "丛林村庄");
        phrases.put("village_jungle_tree", "丛林树屋村庄");
        phrases.put("village_mesa", "恶地村庄");
        phrases.put("village_mesa_fortified", "加固恶地村庄");
        phrases.put("village_mountain", "山地村庄");
        phrases.put("village_mountain_alpine", "高山村庄");
        phrases.put("village_mushroom", "蘑菇岛村庄");
        phrases.put("village_plains", "平原村庄");
        phrases.put("village_plains_fortified", "加固平原村庄");
        phrases.put("village_savanna", "热带草原村庄");
        phrases.put("village_savanna_na", "热带草原美洲原住民村庄");
        phrases.put("village_snowy", "雪原村庄");
        phrases.put("village_snowy_igloo", "雪屋村庄");
        phrases.put("village_swamp", "沼泽村庄");
        phrases.put("village_swamp_fortified", "加固沼泽村庄");
        phrases.put("village_taiga", "针叶林村庄");
        phrases.put("village_taiga_fortified", "加固针叶林村庄");
        phrases.put("village_underground", "地下村庄");
        phrases.put("dark_forest", "黑森林");
        phrases.put("flower_forest", "繁花森林");
        phrases.put("bamboo_jungle", "竹林");
        phrases.put("cherry_grove", "樱花树林");
        phrases.put("mangrove_swamp", "红树林沼泽");
        phrases.put("wooded_badlands", "疏林恶地");
        phrases.put("crimson_forest", "绯红森林");
        phrases.put("warped_forest", "诡异森林");
        phrases.put("large_village", "大型村庄");
        phrases.put("medium_village", "中型村庄");
        phrases.put("small_village", "小型村庄");
        phrases.put("better_mineshafts", "更好的矿井");
        phrases.put("better_ocean_monuments", "更好的海底神殿");
        phrases.put("better_strongholds", "更好的要塞");
        phrases.put("witch_hut", "女巫小屋");
        phrases.put("witch_circle", "女巫法阵");
        phrases.put("ocean_monument", "海底神殿");
        phrases.put("underwaterstructure", "水下结构");
        phrases.put("mineshaft_acacia", "金合欢矿井");
        phrases.put("mineshaft_desert", "沙漠矿井");
        phrases.put("mineshaft_dripstone", "滴水石矿井");
        phrases.put("mineshaft_ice", "冰原矿井");
        phrases.put("mineshaft_jungle", "丛林矿井");
        phrases.put("mineshaft_lush", "繁茂矿井");
        phrases.put("mineshaft_mesa", "恶地矿井");
        phrases.put("mineshaft_mushroom", "蘑菇矿井");
        phrases.put("mineshaft_oak", "橡木矿井");
        phrases.put("mineshaft_overgrown", "藤蔓矿井");
        phrases.put("mineshaft_red_desert", "红沙漠矿井");
        phrases.put("mineshaft_spruce", "云杉矿井");
        phrases.put("mineshaft_spruce_snowy", "雪原云杉矿井");
        phrases.put("pink_salt_shrine", "粉盐神龛");
        phrases.put("giant_brown_mushroom", "巨型棕色蘑菇");
        phrases.put("giant_cactus", "巨型仙人掌");
        phrases.put("giant_cherry_blossom_bonsai_tree", "巨型樱花盆景树");
        phrases.put("giant_tree_stump", "巨型树桩");
        phrases.put("giant_yellow_tree", "巨型黄叶树");
        phrases.put("deep_dark", "深暗之域");
        phrases.put("overworld_leak", "主世界裂隙");
        phrases.put("cooking_pork", "烤猪肉营火");
        phrases.put("elven_pillars", "精灵石柱");
        phrases.put("smoldering_altar", "闷燃祭坛");
        phrases.put("termite_hill", "白蚁丘");
        phrases.put("two_woods", "双木遗迹");
        phrases.put("beehive_house", "蜂巢小屋");
        phrases.put("buried_village", "埋藏村庄");
        phrases.put("claw_pillager", "爪形掠夺者营地");
        phrases.put("claw_spike", "爪形尖刺");
        phrases.put("cold_hideout", "寒地藏身处");
        phrases.put("desert_ranch", "沙漠牧场");
        phrases.put("destroyed_house", "破败房屋");
        phrases.put("offshore_platform_pillager", "近海掠夺者平台");
        phrases.put("pillager_weapons", "掠夺者武器库");
        phrases.put("street_vendor_pillager", "街边摊贩掠夺者营地");
        phrases.put("street_vendor_stall", "街边摊位");
        phrases.put("underwater_sword", "水下巨剑");
        phrases.put("water_reservoir_pillager", "水库掠夺者营地");
        phrases.put("bamboo_jungle_campfire", "竹林篝火");
        phrases.put("dark_forest_campfire", "黑森林篝火");
        phrases.put("guide_post_cold", "寒地向导岗亭");
        phrases.put("guide_post_warm", "暖地向导岗亭");
        phrases.put("dark_forest_settlement", "黑森林聚落");
        phrases.put("snowy_igloo", "雪屋");
        phrases.put("wandering_trader_camp", "流浪商人营地");
        phrases.put("native_american", "美洲原住民");
        phrases.put("savanna_na", "热带草原美洲原住民");
        phrases.put("underwater_city", "水下城市");
        phrases.put("underwater_fossils", "水下化石群");
        phrases.put("underwater_small_ruins", "水下小型废墟");
        phrases.put("underwater_warm_small_ruins", "暖水小型海底废墟");
        phrases.put("ruin_grassy", "草地废墟");
        phrases.put("taiga_ruin_underground", "地下针叶林废墟");
        phrases.put("taiga_ruin_surface", "地表针叶林废墟");
        phrases.put("abandoned_castle_pillager", "废弃掠夺者城堡");
        phrases.put("pyramide_of_anubis", "阿努比斯金字塔");
        phrases.put("sunken_city", "沉没城市");
        phrases.put("floating_farm_large", "大型浮空农场");
        phrases.put("big_pale_house", "大型苍白房屋");
        phrases.put("building_remains", "建筑遗骸");
        phrases.put("cobweb_stilt_house", "蛛网高脚屋");
        phrases.put("cozy_camp", "温馨营地");
        phrases.put("dead_gold_pile_iron_golem", "枯木金堆铁傀儡");
        phrases.put("dead_wood_ghost_train", "枯木幽灵列车");
        phrases.put("dead_wood_train_tracks", "枯木铁轨");
        phrases.put("gateway_to_somewhere", "通往某处的门");
        phrases.put("leafy_fallen_branch", "倒落枝叶");
        phrases.put("mini_watch_tower", "小型瞭望塔");
        phrases.put("pirate_stash", "海盗藏宝点");
        phrases.put("sacrafice_grounds", "祭祀场");
        phrases.put("wheat_feild_tall", "高小麦田");
        phrases.put("astral_hideaway", "星界隐居所");
        phrases.put("astral_meteorite", "星界陨石");
        phrases.put("enderbloom_grove", "末地花林");
        phrases.put("enderkeep_courtyard", "末地要塞庭院");
        phrases.put("enderpin_spikes", "末影尖刺群");
        phrases.put("enderwatch_tower", "末影瞭望塔");
        phrases.put("manuscript_shrine", "手稿神龛");
        phrases.put("mega_ship_basic", "基础巨型船");
        phrases.put("mystical_archway", "神秘拱道");
        phrases.put("mythic_garden", "神话花园");
        phrases.put("phantom_citadel", "幻影城塞");
        phrases.put("placid_prairie", "宁静草原");
        phrases.put("starlight_voyager", "星光航行者");
        phrases.put("air_balloon", "热气球");
        phrases.put("barn_house", "谷仓房屋");
        phrases.put("killer_bunny_castle", "杀手兔城堡");
        phrases.put("pillager_outpost_sunflower_plains", "向日葵平原掠夺者前哨站");
        phrases.put("village_sunflower_plains", "向日葵平原村庄");
        phrases.put("wreckage_ocean", "海洋残骸");
        phrases.put("farmland_field", "耕地田野");
        phrases.put("log_shelter", "原木避难所");
        phrases.put("mesoamerican_temple", "中美洲神庙");
        phrases.put("tiny_house", "微型房屋");
        phrases.put("illusioner_training_grounds", "幻术师训练场");
        phrases.put("revamped_swamp_hut", "翻新沼泽小屋");
        phrases.put("parkour_challenge", "跑酷挑战");
        phrases.put("respawn_anchor_altar", "重生锚祭坛");
        phrases.put("dead_house_5_bed", "枯木五床屋");
        phrases.put("dead_house_5_path", "枯木五径屋");
        phrases.put("desert_pine_cart2", "沙漠松木矿车 2");
        phrases.put("lodestone_circle", "磁石环");
        phrases.put("the_castle_in_the_sky", "天空之城");
        phrases.put("floating_islands", "浮空群岛");
        phrases.put("horse_pen", "马圈");
        phrases.put("snowy_dog_hut", "雪原犬屋");
        phrases.put("wheat_grain_bin", "小麦粮仓");
        phrases.put("village_coconut", "椰林村庄");
        phrases.put("village_wisteria", "紫藤村庄");
        phrases.put("buried_villager", "埋藏村民");
        phrases.put("jungle_treevillage", "丛林树屋村庄");
        phrases.put("magenta_shroom_clearing", "品红蘑菇空地");
        phrases.put("roadside_grave_fogg05", "路边 Fogg05 墓碑");
        phrases.put("roadside_grave_notch", "路边 Notch 墓碑");
        phrases.put("roadside_grave_technoblade", "路边 Technoblade 墓碑");
        phrases.put("gingerbread_house", "姜饼屋");
        phrases.put("northern_ruin", "北方废墟");
        phrases.put("outcast_villager_desert", "沙漠流亡村民屋");
        phrases.put("outcast_villager_grassy", "草地流亡村民屋");
        phrases.put("taiga_ruin_surface", "针叶林地表废墟");
        phrases.put("great_toadstool", "巨型毒蘑菇");
        phrases.put("valley_lodge", "山谷木屋");
        phrases.put("tw_canopy", "暮色森林林冠塔");
        phrases.put("tw_mangrove", "暮色森林红树林塔");
        phrases.put("ruine_tour", "废墟塔楼");
        phrases.put("three_finger_ice", "三指冰刺");
        phrases.put("swamp_vanilla", "原版沼泽");
        phrases.put("big_pale_house_1", "大型苍白房屋 1");
        phrases.put("lonely_tower", "孤塔");
        phrases.put("lonely_well", "孤井");
        phrases.put("sunzi_gate", "孙子门");
        phrases.put("vh_randomplay", "村庄地平线随机结构");
        phrases.put("vh_village_plains", "村庄地平线平原村庄");
        phrases.put("clear_well", "清澈水井");
        phrases.put("dry_well", "干涸水井");
        phrases.put("add_potion_cauldron", "添加药水炼药锅");
        phrases.put("aspen_manors", "白杨庄园群");
        phrases.put("bog_trials", "沼泽试炼");
        phrases.put("on_mystery_maps", "位于神秘地图");
        phrases.put("cats_spawn_as_black", "猫生成为黑猫");
        phrases.put("cats_spawn_in", "猫生成于");
        phrases.put("dolphin_located", "海豚定位");
        phrases.put("eye_of_ender_located", "末影之眼定位");
        phrases.put("no_delta", "无三角洲");
        phrases.put("no_lakes", "无湖泊");
        phrases.put("all_structory_structures", "全部 Structory 结构");
        phrases.put("way_sign_destinations", "路标目的地");
        phrases.put("blocked_by_deeper_oceans", "受深海阻挡");
        phrases.put("fortified_villages", "加固村庄群");
        phrases.put("mage_towers", "法师高塔群");
        phrases.put("remove_basalt_columns_feature_in", "移除玄武岩柱特征区域");
        phrases.put("remove_delta_feature_in", "移除三角洲特征区域");
        phrases.put("remove_magma_feature_in", "移除岩浆特征区域");
        phrases.put("remove_vines_feature_in", "移除藤蔓特征区域");
    }
}
