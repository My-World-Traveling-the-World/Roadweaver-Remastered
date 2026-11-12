package net.shiroha233.roadweaver.client.config.localization;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import net.shiroha233.roadweaver.config.structure.StructureEntry;

public final class ChineseRoadWeaverNames {
   private static final Map<String, String> MOD_NAMES = new HashMap<>();
   private static final Map<String, String> EXACT_STRUCTURES = new HashMap<>();
   private static final Map<String, String> RESOURCE_STRUCTURES = new HashMap<>();
   private static final Map<String, String> RESOURCE_TAGS = new HashMap<>();
   private static final Map<String, String> PHRASES = new HashMap<>();
   private static final Map<String, String> WORDS = new HashMap<>();

   private ChineseRoadWeaverNames() {
   }

   public static String modName(String var0, String var1) {
      try {
         String var2 = normalize(var0);
         String var3 = MOD_NAMES.get(var2);
         return var3 == null ? var1 : var3;
      } catch (Throwable var4) {
         return var1;
      }
   }

   public static String structureName(StructureEntry var0, String var1) {
      try {
         return var0 != null && var0.id() != null ? structureIdName(var0.id().toString(), var1) : keep(var1);
      } catch (Throwable var3) {
         return var1;
      }
   }

   public static String structureIdName(String var0, String var1) {
      try {
         String var2 = RESOURCE_STRUCTURES.get(normalize(var0));
         if (var2 != null) {
            return var2;
         } else {
            String var3 = EXACT_STRUCTURES.get(normalize(var0));
            return var3 != null ? var3 : keep(localizeStructurePath(namespace(var0), path(var0), var1), var1);
         }
      } catch (Throwable var4) {
         return var1;
      }
   }

   public static String leafName(StructureEntry var0, String var1) {
      try {
         if (var0 != null && var0.id() != null) {
            String var2 = var0.id().toString();
            String var3 = EXACT_STRUCTURES.get(normalize(var2));
            return var3 != null ? var3 : keep(localizeSegment(var1), var1);
         } else {
            return keep(localizeSegment(var1), var1);
         }
      } catch (Throwable var4) {
         return var1;
      }
   }

   public static String pathNodeName(String var0, String var1) {
      try {
         String var2 = localizeSegment(var1);
         return keep(var2, var1);
      } catch (Throwable var3) {
         return var1;
      }
   }

   public static String tagName(String var0, String var1) {
      try {
         String var2 = RESOURCE_TAGS.get(normalize(var0));
         if (var2 != null) {
            return var2.startsWith("#") ? var2 : "#" + var2;
         } else {
            String var3 = path(var0);
            String var4 = localizePath(var3, stripHash(var1));
            return "#" + keep(var4, stripHash(var1));
         }
      } catch (Throwable var5) {
         return var1;
      }
   }

   private static String localizeStructurePath(String var0, String var1, String var2) {
      String var3 = normalize(var1);
      if (var3.isEmpty()) {
         return var2;
      } else {
         String var4 = localizeSpecialStructure(var0, var3);
         return !var4.isBlank() ? var4 : localizePath(var3, var2);
      }
   }

   private static String localizeSpecialStructure(String var0, String var1) {
      String var2 = PHRASES.get(var1);
      if (var2 != null) {
         return var2;
      } else {
         if ("ctov".equals(var0)) {
            String[] var3 = var1.split("/");
            if (var3.length == 2 && sizeSuffix(var3[0]) != null && var3[1].startsWith("village_")) {
               return localizeSegment(var3[1]) + "（" + sizeSuffix(var3[0]) + "）";
            }
         }

         if ("towns_and_towers".equals(var0) && var1.startsWith("exclusives/")) {
            String var6 = var1.substring("exclusives/".length());
            String var7 = localizeVillageOrOutpost(var6);
            return var7.isBlank() ? "" : var7 + "（专属）";
         } else {
            String var5 = localizeVillageOrOutpost(var1);
            if (!var5.isBlank()) {
               return var5;
            } else if (var1.startsWith("mimic_")) {
               return "仿原版" + localizeSegment(var1.substring("mimic_".length()));
            } else if (var1.startsWith("supply_cache/")) {
               String var4 = var1.substring("supply_cache/".length());
               return ("dark".equals(var4) ? "黑森林" : localizeSegment(var4)) + "补给贮藏点";
            } else if (var1.startsWith("watchtower/")) {
               return localizeSegment(var1.substring("watchtower/".length())) + "瞭望塔";
            } else if (var1.startsWith("underwater/")) {
               return localizeSegment(var1.substring("underwater/".length()));
            } else {
               return var1.startsWith("underground/") ? "地下" + localizeSegment(var1.substring("underground/".length())) : "";
            }
         }
      }
   }

   private static String localizeVillageOrOutpost(String var0) {
      if (var0.startsWith("pillager_outpost_")) {
         return localizeSegment(var0.substring("pillager_outpost_".length())) + "掠夺者前哨站";
      } else if (var0.startsWith("village_")) {
         String var1 = var0.substring("village_".length());
         return "wandering_trader_camp".equals(var1) ? "流浪商人营地" : localizeSegment(var1) + "村庄";
      } else {
         return "";
      }
   }

   private static String sizeSuffix(String var0) {
      return switch (var0) {
         case "large" -> "大";
         case "medium" -> "中";
         case "small" -> "小";
         default -> null;
      };
   }

   private static String localizePath(String var0, String var1) {
      String var2 = normalize(var0);
      if (var2.isEmpty()) {
         return var1;
      } else {
         String var3 = PHRASES.get(var2);
         if (var3 != null) {
            return var3;
         } else {
            String[] var4 = var2.split("/");
            StringBuilder var5 = new StringBuilder();
            boolean var6 = false;

            for (String var10 : var4) {
               if (!var10.isEmpty()) {
                  String var11 = localizeSegment(var10);
                  if (!var11.equals(var10)) {
                     var6 = true;
                  }

                  if (var5.length() > 0) {
                     var5.append(" · ");
                  }

                  var5.append(var11);
               }
            }

            if (!var6 && var5.length() == 0) {
               return var1;
            } else {
               return var5.length() == 0 ? var1 : var5.toString();
            }
         }
      }
   }

   private static String localizeSegment(String var0) {
      String var1 = normalize(var0);
      if (var1.isEmpty()) {
         return var0;
      } else {
         String var2 = PHRASES.get(var1);
         if (var2 != null) {
            return var2;
         } else {
            String[] var3 = var1.split("_");
            StringBuilder var4 = new StringBuilder();
            boolean var5 = false;

            for (String var9 : var3) {
               if (!var9.isEmpty()) {
                  String var10;
                  if (isNumber(var9)) {
                     var10 = var9;
                     var5 = true;
                  } else {
                     var10 = WORDS.get(var9);
                     if (var10 == null) {
                        var10 = titleCase(var9);
                     } else {
                        var5 = true;
                     }
                  }

                  if (var4.length() > 0 && isAsciiWord(var10)) {
                     var4.append(' ');
                  }

                  var4.append(var10);
               }
            }

            return var5 && var4.length() > 0 ? var4.toString() : var0;
         }
      }
   }

   private static String namespace(String var0) {
      String var1 = normalize(var0);
      int var2 = var1.indexOf(58);
      return var2 < 0 ? "" : var1.substring(0, var2);
   }

   private static String path(String var0) {
      String var1 = normalize(var0);
      int var2 = var1.indexOf(58);
      return var2 < 0 ? var1 : var1.substring(var2 + 1);
   }

   private static String stripHash(String var0) {
      if (var0 == null) {
         return "";
      } else {
         return var0.startsWith("#") ? var0.substring(1) : var0;
      }
   }

   private static String normalize(String var0) {
      return var0 == null ? "" : var0.trim().toLowerCase(Locale.ROOT);
   }

   private static String keep(String var0) {
      return var0 != null && !var0.isBlank() ? var0 : "";
   }

   private static String keep(String var0, String var1) {
      return var0 != null && !var0.isBlank() ? var0 : var1;
   }

   private static String titleCase(String var0) {
      if (var0.isEmpty()) {
         return var0;
      } else {
         return var0.length() == 1 ? var0.toUpperCase(Locale.ROOT) : var0.substring(0, 1).toUpperCase(Locale.ROOT) + var0.substring(1);
      }
   }

   private static boolean isNumber(String var0) {
      for (int var1 = 0; var1 < var0.length(); var1++) {
         if (!Character.isDigit(var0.charAt(var1))) {
            return false;
         }
      }

      return !var0.isEmpty();
   }

   private static boolean isAsciiWord(String var0) {
      for (int var1 = 0; var1 < var0.length(); var1++) {
         if (var0.charAt(var1) > 127) {
            return false;
         }
      }

      return true;
   }

   private static void loadResourceTranslations() {
      loadMapResource("assets/roadweaver/localization/roadweaver_structures_zh_cn.json", "structures", RESOURCE_STRUCTURES);
      loadMapResource("assets/roadweaver/localization/roadweaver_structures_zh_cn.json", "tags", RESOURCE_TAGS);
   }

   private static void loadMapResource(String var0, String var1, Map<String, String> var2) {
      try {
         try (InputStream var3 = ChineseRoadWeaverNames.class.getClassLoader().getResourceAsStream(var0)) {
            if (var3 == null) {
               return;
            }

            JsonElement var4 = JsonParser.parseReader(new InputStreamReader(var3, StandardCharsets.UTF_8));
            if (!var4.isJsonObject()) {
               return;
            }

            JsonObject var5 = var4.getAsJsonObject();
            JsonElement var6 = var5.get(var1);
            if (var6 != null && var6.isJsonObject()) {
               for (Entry var8 : var6.getAsJsonObject().entrySet()) {
                  JsonElement var9 = (JsonElement)var8.getValue();
                  if (var9 != null && var9.isJsonPrimitive()) {
                     var2.put(normalize((String)var8.getKey()), var9.getAsString());
                  }
               }

               return;
            }
         }
      } catch (Throwable var12) {
         var2.clear();
      }
   }

   private static void putMod(String var0, String var1) {
      MOD_NAMES.put(var0, var1);
   }

   private static void putStructure(String var0, String var1) {
      EXACT_STRUCTURES.put(var0, var1);
   }

   private static void putPhrase(String var0, String var1) {
      PHRASES.put(var0, var1);
   }

   private static void putWord(String var0, String var1) {
      WORDS.put(var0, var1);
   }

   static {
      putMod("minecraft", "原版");
      putMod("roadweaver", "阡陌交通");
      putMod("abridged", "桥梁 Abridged");
      putMod("amendments", "Amendments 修正");
      putMod("ctov", "CTOV 村庄大修");
      putMod("ecologics", "丰富的生态");
      putMod("end_villager_outpost", "末地村民哨站");
      putMod("farmers_structures", "农夫结构");
      putMod("bettermineshafts", "YUNG 更好的矿井");
      putMod("betteroceanmonuments", "YUNG 更好的海底神殿");
      putMod("betterstrongholds", "YUNG 更好的要塞");
      putMod("betterwitchhuts", "YUNG 更好的女巫小屋");
      putMod("biomeswevegone", "我们逝去的生物群系");
      putMod("campfire", "野营篝火");
      putMod("explorify", "Explorify 探索结构");
      putMod("formationsnether", "下界地貌结构");
      putMod("formationsoverworld", "主世界地貌结构");
      putMod("friendsandfoes", "友与敌");
      putMod("galosphere", "Galosphere");
      putMod("giant_natural_additions", "大型自然扩展");
      putMod("goodending", "Good Ending");
      putMod("heaven", "天堂结构");
      putMod("hopo", "Hopo 结构");
      putMod("immersive_gateways", "沉浸式传送门");
      putMod("joshie", "Joshie 结构");
      putMod("lios_outlandish_villages", "Lio 的异域村庄");
      putMod("mbs", "Moog 更好结构");
      putMod("mes", "Moog 末地结构");
      putMod("mmr", "Moog 矿井遗迹");
      putMod("mmv", "Moog 更多村庄");
      putMod("mns", "Moog 下界结构");
      putMod("mostructures", "Mo' Structures");
      putMod("mss", "Moog 船舰结构");
      putMod("mtr", "Moog 旅行遗迹");
      putMod("mvs", "Moog 村庄结构");
      putMod("natures_spirit", "自然之灵");
      putMod("ocean_lily_pad_village", "海上睡莲村庄");
      putMod("qrafty", "Qrafty 结构");
      putMod("snowyspirit", "雪意节庆");
      putMod("structory", "Structory 结构");
      putMod("structory_towers", "Structory 高塔");
      putMod("supplementaries", "锦致装饰");
      putMod("terralith", "Terralith");
      putMod("totw_modded", "旷野之塔：模组版");
      putMod("towns_and_towers", "城镇与高塔");
      putMod("trek", "Trek 结构");
      putMod("villagehorizons", "村庄地平线");
      putMod("weatheredwells", "风化水井");
      putStructure("minecraft:ancient_city", "古城");
      putStructure("minecraft:bastion_remnant", "堡垒遗迹");
      putStructure("minecraft:buried_treasure", "埋藏的宝藏");
      putStructure("minecraft:desert_pyramid", "沙漠神殿");
      putStructure("minecraft:end_city", "末地城");
      putStructure("minecraft:fortress", "下界要塞");
      putStructure("minecraft:igloo", "雪屋");
      putStructure("minecraft:jungle_pyramid", "丛林神庙");
      putStructure("minecraft:mansion", "林地府邸");
      putStructure("minecraft:mineshaft", "矿井");
      putStructure("minecraft:mineshaft_mesa", "恶地矿井");
      putStructure("minecraft:monument", "海底神殿");
      putStructure("minecraft:nether_fossil", "下界化石");
      putStructure("minecraft:ocean_ruin_cold", "冷水海底废墟");
      putStructure("minecraft:ocean_ruin_warm", "暖水海底废墟");
      putStructure("minecraft:pillager_outpost", "掠夺者前哨站");
      putStructure("minecraft:ruined_portal", "废弃传送门");
      putStructure("minecraft:ruined_portal_desert", "沙漠废弃传送门");
      putStructure("minecraft:ruined_portal_jungle", "丛林废弃传送门");
      putStructure("minecraft:ruined_portal_mountain", "山地废弃传送门");
      putStructure("minecraft:ruined_portal_nether", "下界废弃传送门");
      putStructure("minecraft:ruined_portal_ocean", "海洋废弃传送门");
      putStructure("minecraft:ruined_portal_swamp", "沼泽废弃传送门");
      putStructure("minecraft:shipwreck", "沉船");
      putStructure("minecraft:shipwreck_beached", "搁浅沉船");
      putStructure("minecraft:stronghold", "要塞");
      putStructure("minecraft:stronghold_big", "大型要塞");
      putStructure("minecraft:swamp_hut", "沼泽小屋");
      putStructure("minecraft:trail_ruins", "古迹废墟");
      putStructure("minecraft:trial_chambers", "试炼密室");
      putStructure("minecraft:village_desert", "沙漠村庄");
      putStructure("minecraft:village_plains", "平原村庄");
      putStructure("minecraft:village_savanna", "热带草原村庄");
      putStructure("minecraft:village_snowy", "雪原村庄");
      putStructure("minecraft:village_taiga", "针叶林村庄");
      putStructure("roadweaver:bridge_bridge_0", "桥梁 0");
      putStructure("roadweaver:bridge_bridge_1", "桥梁 1");
      putStructure("roadweaver:roadside_bench", "路边长椅");
      putStructure("roadweaver:roadside_campfire", "路边篝火");
      putStructure("roadweaver:roadside_campsite_1", "路边营地 1");
      putStructure("roadweaver:roadside_campsite_2", "路边营地 2");
      putStructure("roadweaver:roadside_campsite_3", "路边营地 3");
      putStructure("roadweaver:roadside_grave_easter_egg", "路边彩蛋墓碑");
      putStructure("roadweaver:roadside_grave_fogg05", "路边 Fogg05 墓碑");
      putStructure("roadweaver:roadside_grave_magic_muggle", "路边魔法麻瓜墓碑");
      putStructure("roadweaver:roadside_grave_notch", "路边 Notch 墓碑");
      putStructure("roadweaver:roadside_grave_technoblade", "路边 Technoblade 墓碑");
      putStructure("roadweaver:roadside_maid_house", "路边女仆小屋");
      putStructure("roadweaver:roadside_sakura_coffee_house", "路边樱花咖啡屋");
      putStructure("roadweaver:spawn_cabin", "出生木屋");
      putStructure("roadweaver:stone_bridge_shiqiao", "石桥");
      putStructure("roadweaver:wood_bridge_hongshu", "红树林木桥");
      putStructure("roadweaver:wood_bridge_huamu", "桦木桥");
      putStructure("roadweaver:wood_bridge_jinhehuan", "金合欢木桥");
      putStructure("roadweaver:wood_bridge_shensexiangmu", "深色橡木桥");
      putStructure("roadweaver:wood_bridge_yinghuamu", "樱花木桥");
      putStructure("roadweaver:wood_bridge_zhuzi", "竹桥");
      putPhrase("ancient_city", "古城");
      putPhrase("bastion_remnant", "堡垒遗迹");
      putPhrase("buried_treasure", "埋藏的宝藏");
      putPhrase("desert_pyramid", "沙漠神殿");
      putPhrase("end_city", "末地城");
      putPhrase("jungle_pyramid", "丛林神庙");
      putPhrase("pillager_outpost", "掠夺者前哨站");
      putPhrase("ruined_portal", "废弃传送门");
      putPhrase("swamp_hut", "沼泽小屋");
      putPhrase("trail_ruins", "古迹废墟");
      putPhrase("trial_chambers", "试炼密室");
      putPhrase("village_beach", "海滩村庄");
      putPhrase("village_christmas", "圣诞村庄");
      putPhrase("village_dark_forest", "黑森林村庄");
      putPhrase("village_desert", "沙漠村庄");
      putPhrase("village_desert_oasis", "沙漠绿洲村庄");
      putPhrase("village_jungle", "丛林村庄");
      putPhrase("village_jungle_tree", "丛林树屋村庄");
      putPhrase("village_mesa", "恶地村庄");
      putPhrase("village_mesa_fortified", "加固恶地村庄");
      putPhrase("village_mountain", "山地村庄");
      putPhrase("village_mountain_alpine", "高山村庄");
      putPhrase("village_mushroom", "蘑菇岛村庄");
      putPhrase("village_plains", "平原村庄");
      putPhrase("village_plains_fortified", "加固平原村庄");
      putPhrase("village_savanna", "热带草原村庄");
      putPhrase("village_savanna_na", "热带草原美洲原住民村庄");
      putPhrase("village_snowy", "雪原村庄");
      putPhrase("village_snowy_igloo", "雪屋村庄");
      putPhrase("village_swamp", "沼泽村庄");
      putPhrase("village_swamp_fortified", "加固沼泽村庄");
      putPhrase("village_taiga", "针叶林村庄");
      putPhrase("village_taiga_fortified", "加固针叶林村庄");
      putPhrase("village_underground", "地下村庄");
      putPhrase("dark_forest", "黑森林");
      putPhrase("flower_forest", "繁花森林");
      putPhrase("bamboo_jungle", "竹林");
      putPhrase("cherry_grove", "樱花树林");
      putPhrase("mangrove_swamp", "红树林沼泽");
      putPhrase("wooded_badlands", "疏林恶地");
      putPhrase("crimson_forest", "绯红森林");
      putPhrase("warped_forest", "诡异森林");
      putPhrase("large_village", "大型村庄");
      putPhrase("medium_village", "中型村庄");
      putPhrase("small_village", "小型村庄");
      putPhrase("better_mineshafts", "更好的矿井");
      putPhrase("better_ocean_monuments", "更好的海底神殿");
      putPhrase("better_strongholds", "更好的要塞");
      putPhrase("witch_hut", "女巫小屋");
      putPhrase("witch_circle", "女巫法阵");
      putPhrase("ocean_monument", "海底神殿");
      putPhrase("underwaterstructure", "水下结构");
      putPhrase("mineshaft_acacia", "金合欢矿井");
      putPhrase("mineshaft_desert", "沙漠矿井");
      putPhrase("mineshaft_dripstone", "滴水石矿井");
      putPhrase("mineshaft_ice", "冰原矿井");
      putPhrase("mineshaft_jungle", "丛林矿井");
      putPhrase("mineshaft_lush", "繁茂矿井");
      putPhrase("mineshaft_mesa", "恶地矿井");
      putPhrase("mineshaft_mushroom", "蘑菇矿井");
      putPhrase("mineshaft_oak", "橡木矿井");
      putPhrase("mineshaft_overgrown", "藤蔓矿井");
      putPhrase("mineshaft_red_desert", "红沙漠矿井");
      putPhrase("mineshaft_spruce", "云杉矿井");
      putPhrase("mineshaft_spruce_snowy", "雪原云杉矿井");
      putPhrase("pink_salt_shrine", "粉盐神龛");
      putPhrase("giant_brown_mushroom", "巨型棕色蘑菇");
      putPhrase("giant_cactus", "巨型仙人掌");
      putPhrase("giant_cherry_blossom_bonsai_tree", "巨型樱花盆景树");
      putPhrase("giant_tree_stump", "巨型树桩");
      putPhrase("giant_yellow_tree", "巨型黄叶树");
      putPhrase("deep_dark", "深暗之域");
      putPhrase("overworld_leak", "主世界裂隙");
      putPhrase("cooking_pork", "烤猪肉营火");
      putPhrase("elven_pillars", "精灵石柱");
      putPhrase("smoldering_altar", "闷燃祭坛");
      putPhrase("termite_hill", "白蚁丘");
      putPhrase("two_woods", "双木遗迹");
      putPhrase("beehive_house", "蜂巢小屋");
      putPhrase("buried_village", "埋藏村庄");
      putPhrase("claw_pillager", "爪形掠夺者营地");
      putPhrase("claw_spike", "爪形尖刺");
      putPhrase("cold_hideout", "寒地藏身处");
      putPhrase("desert_ranch", "沙漠牧场");
      putPhrase("destroyed_house", "破败房屋");
      putPhrase("offshore_platform_pillager", "近海掠夺者平台");
      putPhrase("pillager_weapons", "掠夺者武器库");
      putPhrase("street_vendor_pillager", "街边摊贩掠夺者营地");
      putPhrase("street_vendor_stall", "街边摊位");
      putPhrase("underwater_sword", "水下巨剑");
      putPhrase("water_reservoir_pillager", "水库掠夺者营地");
      putPhrase("bamboo_jungle_campfire", "竹林篝火");
      putPhrase("dark_forest_campfire", "黑森林篝火");
      putPhrase("guide_post_cold", "寒地向导岗亭");
      putPhrase("guide_post_warm", "暖地向导岗亭");
      putPhrase("dark_forest_settlement", "黑森林聚落");
      putPhrase("snowy_igloo", "雪屋");
      putPhrase("wandering_trader_camp", "流浪商人营地");
      putPhrase("native_american", "美洲原住民");
      putPhrase("savanna_na", "热带草原美洲原住民");
      putPhrase("underwater_city", "水下城市");
      putPhrase("underwater_fossils", "水下化石群");
      putPhrase("underwater_small_ruins", "水下小型废墟");
      putPhrase("underwater_warm_small_ruins", "暖水小型海底废墟");
      putPhrase("ruin_grassy", "草地废墟");
      putPhrase("taiga_ruin_underground", "地下针叶林废墟");
      putPhrase("taiga_ruin_surface", "地表针叶林废墟");
      putPhrase("abandoned_castle_pillager", "废弃掠夺者城堡");
      putPhrase("pyramide_of_anubis", "阿努比斯金字塔");
      putPhrase("sunken_city", "沉没城市");
      putPhrase("floating_farm_large", "大型浮空农场");
      putPhrase("big_pale_house", "大型苍白房屋");
      putPhrase("building_remains", "建筑遗骸");
      putPhrase("cobweb_stilt_house", "蛛网高脚屋");
      putPhrase("cozy_camp", "温馨营地");
      putPhrase("dead_gold_pile_iron_golem", "枯木金堆铁傀儡");
      putPhrase("dead_wood_ghost_train", "枯木幽灵列车");
      putPhrase("dead_wood_train_tracks", "枯木铁轨");
      putPhrase("gateway_to_somewhere", "通往某处的门");
      putPhrase("leafy_fallen_branch", "倒落枝叶");
      putPhrase("mini_watch_tower", "小型瞭望塔");
      putPhrase("pirate_stash", "海盗藏宝点");
      putPhrase("sacrafice_grounds", "祭祀场");
      putPhrase("wheat_feild_tall", "高小麦田");
      putPhrase("astral_hideaway", "星界隐居所");
      putPhrase("astral_meteorite", "星界陨石");
      putPhrase("enderbloom_grove", "末地花林");
      putPhrase("enderkeep_courtyard", "末地要塞庭院");
      putPhrase("enderpin_spikes", "末影尖刺群");
      putPhrase("enderwatch_tower", "末影瞭望塔");
      putPhrase("manuscript_shrine", "手稿神龛");
      putPhrase("mega_ship_basic", "基础巨型船");
      putPhrase("mystical_archway", "神秘拱道");
      putPhrase("mythic_garden", "神话花园");
      putPhrase("phantom_citadel", "幻影城塞");
      putPhrase("placid_prairie", "宁静草原");
      putPhrase("starlight_voyager", "星光航行者");
      putPhrase("air_balloon", "热气球");
      putPhrase("barn_house", "谷仓房屋");
      putPhrase("killer_bunny_castle", "杀手兔城堡");
      putPhrase("pillager_outpost_sunflower_plains", "向日葵平原掠夺者前哨站");
      putPhrase("village_sunflower_plains", "向日葵平原村庄");
      putPhrase("wreckage_ocean", "海洋残骸");
      putPhrase("farmland_field", "耕地田野");
      putPhrase("log_shelter", "原木避难所");
      putPhrase("mesoamerican_temple", "中美洲神庙");
      putPhrase("tiny_house", "微型房屋");
      putPhrase("illusioner_training_grounds", "幻术师训练场");
      putPhrase("revamped_swamp_hut", "翻新沼泽小屋");
      putPhrase("parkour_challenge", "跑酷挑战");
      putPhrase("respawn_anchor_altar", "重生锚祭坛");
      putPhrase("dead_house_5_bed", "枯木五床屋");
      putPhrase("dead_house_5_path", "枯木五径屋");
      putPhrase("desert_pine_cart2", "沙漠松木矿车 2");
      putPhrase("lodestone_circle", "磁石环");
      putPhrase("the_castle_in_the_sky", "天空之城");
      putPhrase("floating_islands", "浮空群岛");
      putPhrase("horse_pen", "马圈");
      putPhrase("snowy_dog_hut", "雪原犬屋");
      putPhrase("wheat_grain_bin", "小麦粮仓");
      putPhrase("village_coconut", "椰林村庄");
      putPhrase("village_wisteria", "紫藤村庄");
      putPhrase("buried_villager", "埋藏村民");
      putPhrase("jungle_treevillage", "丛林树屋村庄");
      putPhrase("magenta_shroom_clearing", "品红蘑菇空地");
      putPhrase("roadside_grave_fogg05", "路边 Fogg05 墓碑");
      putPhrase("roadside_grave_notch", "路边 Notch 墓碑");
      putPhrase("roadside_grave_technoblade", "路边 Technoblade 墓碑");
      putPhrase("gingerbread_house", "姜饼屋");
      putPhrase("northern_ruin", "北方废墟");
      putPhrase("outcast_villager_desert", "沙漠流亡村民屋");
      putPhrase("outcast_villager_grassy", "草地流亡村民屋");
      putPhrase("taiga_ruin_surface", "针叶林地表废墟");
      putPhrase("great_toadstool", "巨型毒蘑菇");
      putPhrase("valley_lodge", "山谷木屋");
      putPhrase("tw_canopy", "暮色森林林冠塔");
      putPhrase("tw_mangrove", "暮色森林红树林塔");
      putPhrase("ruine_tour", "废墟塔楼");
      putPhrase("three_finger_ice", "三指冰刺");
      putPhrase("swamp_vanilla", "原版沼泽");
      putPhrase("big_pale_house_1", "大型苍白房屋 1");
      putPhrase("lonely_tower", "孤塔");
      putPhrase("lonely_well", "孤井");
      putPhrase("sunzi_gate", "孙子门");
      putPhrase("vh_randomplay", "村庄地平线随机结构");
      putPhrase("vh_village_plains", "村庄地平线平原村庄");
      putPhrase("clear_well", "清澈水井");
      putPhrase("dry_well", "干涸水井");
      putPhrase("add_potion_cauldron", "添加药水炼药锅");
      putPhrase("aspen_manors", "白杨庄园群");
      putPhrase("bog_trials", "沼泽试炼");
      putPhrase("on_mystery_maps", "位于神秘地图");
      putPhrase("cats_spawn_as_black", "猫生成为黑猫");
      putPhrase("cats_spawn_in", "猫生成于");
      putPhrase("dolphin_located", "海豚定位");
      putPhrase("eye_of_ender_located", "末影之眼定位");
      putPhrase("no_delta", "无三角洲");
      putPhrase("no_lakes", "无湖泊");
      putPhrase("all_structory_structures", "全部 Structory 结构");
      putPhrase("way_sign_destinations", "路标目的地");
      putPhrase("blocked_by_deeper_oceans", "受深海阻挡");
      putPhrase("fortified_villages", "加固村庄群");
      putPhrase("mage_towers", "法师高塔群");
      putPhrase("remove_basalt_columns_feature_in", "移除玄武岩柱特征区域");
      putPhrase("remove_delta_feature_in", "移除三角洲特征区域");
      putPhrase("remove_magma_feature_in", "移除岩浆特征区域");
      putPhrase("remove_vines_feature_in", "移除藤蔓特征区域");
      putWord("abandoned", "废弃");
      putWord("acacia", "金合欢");
      putWord("adventure", "冒险");
      putWord("all", "全部");
      putWord("alpine", "高山");
      putWord("ancient", "远古");
      putWord("any", "任意");
      putWord("arch", "拱门");
      putWord("aspen", "白杨");
      putWord("badlands", "恶地");
      putWord("bamboo", "竹林");
      putWord("beach", "海滩");
      putWord("better", "更好");
      putWord("big", "大型");
      putWord("birch", "白桦");
      putWord("bog", "沼泽");
      putWord("bridge", "桥梁");
      putWord("cabin", "木屋");
      putWord("campfire", "篝火");
      putWord("campsite", "营地");
      putWord("castle", "城堡");
      putWord("cave", "洞穴");
      putWord("chambers", "密室");
      putWord("cherry", "樱花");
      putWord("christmas", "圣诞");
      putWord("city", "城市");
      putWord("cold", "寒冷");
      putWord("crimson", "绯红");
      putWord("dark", "深色");
      putWord("desert", "沙漠");
      putWord("dripstone", "滴水石");
      putWord("dungeon", "地牢");
      putWord("end", "末地");
      putWord("explorer", "探险家");
      putWord("farm", "农场");
      putWord("fisher", "渔民");
      putWord("flower", "繁花");
      putWord("forest", "森林");
      putWord("forgotten", "遗忘");
      putWord("fortified", "加固");
      putWord("fortress", "要塞");
      putWord("fossil", "化石");
      putWord("grave", "墓碑");
      putWord("grove", "树林");
      putWord("house", "房屋");
      putWord("hut", "小屋");
      putWord("ice", "冰原");
      putWord("igloo", "雪屋");
      putWord("jungle", "丛林");
      putWord("lake", "湖泊");
      putWord("large", "大型");
      putWord("lush", "繁茂");
      putWord("mage", "法师");
      putWord("mangrove", "红树林");
      putWord("manor", "庄园");
      putWord("map", "地图");
      putWord("mesa", "恶地");
      putWord("medium", "中型");
      putWord("mineshaft", "矿井");
      putWord("monument", "神殿");
      putWord("mountain", "山地");
      putWord("mushroom", "蘑菇");
      putWord("nether", "下界");
      putWord("oak", "橡木");
      putWord("oasis", "绿洲");
      putWord("ocean", "海洋");
      putWord("outpost", "前哨站");
      putWord("overgrown", "藤蔓覆盖");
      putWord("pillager", "掠夺者");
      putWord("plains", "平原");
      putWord("portal", "传送门");
      putWord("prairie", "草原");
      putWord("pyramid", "神殿");
      putWord("red", "红色");
      putWord("remnant", "遗迹");
      putWord("roadside", "路边");
      putWord("rock", "岩石");
      putWord("ruin", "废墟");
      putWord("ruined", "废弃");
      putWord("savanna", "热带草原");
      putWord("shipwreck", "沉船");
      putWord("small", "小型");
      putWord("snowy", "雪原");
      putWord("spruce", "云杉");
      putWord("stronghold", "要塞");
      putWord("structure", "结构");
      putWord("swamp", "沼泽");
      putWord("taiga", "针叶林");
      putWord("tower", "高塔");
      putWord("town", "城镇");
      putWord("trail", "古迹");
      putWord("treasure", "宝藏");
      putWord("tree", "树");
      putWord("trial", "试炼");
      putWord("underground", "地下");
      putWord("underwater", "水下");
      putWord("village", "村庄");
      putWord("warm", "温暖");
      putWord("warped", "诡异");
      putWord("well", "水井");
      putWord("witch", "女巫");
      putWord("woodland", "林地");
      putWord("altar", "祭坛");
      putWord("archeology", "考古");
      putWord("archway", "拱道");
      putWord("arena", "竞技场");
      putWord("base", "基地");
      putWord("basalt", "玄武岩");
      putWord("bench", "长椅");
      putWord("black", "黑色");
      putWord("blackstone", "黑石");
      putWord("blossom", "花");
      putWord("bone", "骨");
      putWord("brick", "砖");
      putWord("camp", "营地");
      putWord("cage", "笼");
      putWord("cache", "贮藏点");
      putWord("cart", "矿车");
      putWord("cart1", "矿车 1");
      putWord("church", "教堂");
      putWord("citadel", "城塞");
      putWord("classic", "经典");
      putWord("common", "常见");
      putWord("copper", "铜");
      putWord("cottage", "小屋");
      putWord("crashed", "坠毁");
      putWord("dead", "枯死");
      putWord("deepslate", "深板岩");
      putWord("default", "默认");
      putWord("derelict", "荒废");
      putWord("destinations", "目的地");
      putWord("dirt", "泥土");
      putWord("dome", "穹顶");
      putWord("dragon", "龙");
      putWord("ender", "末影");
      putWord("exclusives", "专属");
      putWord("fallen", "倒塌");
      putWord("farmer", "农夫");
      putWord("field", "田地");
      putWord("fields", "田野");
      putWord("fir", "冷杉");
      putWord("fire", "火焰");
      putWord("floating", "浮空");
      putWord("forge", "锻造铺");
      putWord("fort", "堡垒");
      putWord("fountain", "喷泉");
      putWord("frozen", "冰封");
      putWord("fungus", "真菌");
      putWord("garden", "花园");
      putWord("giant", "巨型");
      putWord("gold", "黄金");
      putWord("graveyard", "墓园");
      putWord("grassy", "草地");
      putWord("grounds", "场地");
      putWord("growth", "生长");
      putWord("guide", "向导");
      putWord("hill", "山丘");
      putWord("hobbit", "霍比特");
      putWord("hole", "洞");
      putWord("horse", "马厩");
      putWord("in", "位于");
      putWord("island", "岛");
      putWord("jacaranda", "蓝花楹");
      putWord("lamp", "灯");
      putWord("lantern", "灯笼");
      putWord("lava", "熔岩");
      putWord("leafy", "枝叶");
      putWord("leaves", "树叶");
      putWord("lighthouse", "灯塔");
      putWord("located", "定位");
      putWord("log", "原木");
      putWord("lower", "下层");
      putWord("magic", "魔法");
      putWord("mahogany", "桃花心木");
      putWord("maple", "枫木");
      putWord("maps", "地图");
      putWord("market", "市场");
      putWord("mansion", "府邸");
      putWord("meadow", "草甸");
      putWord("mediterranean", "地中海式");
      putWord("mega", "巨型");
      putWord("mine", "矿山");
      putWord("monster", "怪物");
      putWord("na", "变体");
      putWord("no", "无");
      putWord("of", "的");
      putWord("old", "古旧");
      putWord("on", "位于");
      putWord("orange", "橙色");
      putWord("orbital", "环形");
      putWord("ore", "矿石");
      putWord("overworld", "主世界");
      putWord("palm", "棕榈");
      putWord("patch", "地块");
      putWord("pedestal", "基座");
      putWord("pillar", "柱");
      putWord("pillars", "柱群");
      putWord("pine", "松木");
      putWord("pirate", "海盗");
      putWord("pile", "堆");
      putWord("pink", "粉色");
      putWord("plainsandgrass", "平原草地");
      putWord("plateau", "高原");
      putWord("pond", "池塘");
      putWord("pool", "水池");
      putWord("post", "岗亭");
      putWord("prismarine", "海晶石");
      putWord("pyramide", "神殿");
      putWord("rare", "稀有");
      putWord("redwood", "红木");
      putWord("remains", "遗骸");
      putWord("river", "河流");
      putWord("road", "道路");
      putWord("room", "房间");
      putWord("rubble", "碎石");
      putWord("ruine", "废墟");
      putWord("ruins", "废墟");
      putWord("rustic", "乡野");
      putWord("scraps", "残骸");
      putWord("sea", "海");
      putWord("settlement", "聚落");
      putWord("ship", "船");
      putWord("shrine", "神龛");
      putWord("sign", "告示牌");
      putWord("skull", "头骨");
      putWord("slopes", "斜坡");
      putWord("snow", "雪");
      putWord("sparse", "稀疏");
      putWord("spike", "尖刺");
      putWord("spikes", "尖刺群");
      putWord("spire", "尖塔");
      putWord("spires", "尖塔群");
      putWord("spiral", "螺旋");
      putWord("stable", "马厩");
      putWord("stall", "摊位");
      putWord("statue", "雕像");
      putWord("stone", "石头");
      putWord("strange", "奇异");
      putWord("street", "街道");
      putWord("sunken", "沉没");
      putWord("supply", "补给");
      putWord("swedish", "瑞典式");
      putWord("sword", "剑");
      putWord("table", "桌");
      putWord("tall", "高大");
      putWord("tavern", "酒馆");
      putWord("temple", "神庙");
      putWord("the", "的");
      putWord("trade", "贸易");
      putWord("trader", "商人");
      putWord("train", "火车");
      putWord("treehouse", "树屋");
      putWord("tudor", "都铎式");
      putWord("upper", "上层");
      putWord("vendor", "摊贩");
      putWord("very", "非常");
      putWord("villager", "村民");
      putWord("wandering", "流浪");
      putWord("wart", "疣");
      putWord("watch", "瞭望");
      putWord("watchtower", "瞭望塔");
      putWord("water", "水");
      putWord("wheat", "小麦");
      putWord("windmill", "风车");
      putWord("with", "带");
      putWord("wood", "木制");
      putWord("wooded", "疏林");
      putWord("wooden", "木制");
      putWord("yard", "院落");
      putWord("adobe", "土坯");
      putWord("aether", "天境");
      putWord("air", "空中");
      putWord("akropolis", "卫城");
      putWord("angels", "天使");
      putWord("astral", "星界");
      putWord("autumn", "秋季");
      putWord("azalea", "杜鹃");
      putWord("azelea", "杜鹃");
      putWord("bacon", "培根");
      putWord("balloon", "气球");
      putWord("bar", "酒吧");
      putWord("barn", "谷仓");
      putWord("bateaux", "船只");
      putWord("beacon", "信标");
      putWord("bee", "蜜蜂");
      putWord("beehive", "蜂巢");
      putWord("beet", "甜菜");
      putWord("birk", "桦木");
      putWord("blacksmith", "铁匠铺");
      putWord("blade", "剑刃");
      putWord("blue", "蓝色");
      putWord("boat", "船");
      putWord("bonsai", "盆景");
      putWord("boulder", "巨石");
      putWord("brown", "棕色");
      putWord("building", "建筑");
      putWord("bunker", "地堡");
      putWord("butcherhouse", "屠夫屋");
      putWord("cabane", "木屋");
      putWord("cacao", "可可");
      putWord("cactus", "仙人掌");
      putWord("camel", "骆驼");
      putWord("canopy", "林冠");
      putWord("cartographer", "制图师");
      putWord("cathedral", "大教堂");
      putWord("chamber", "密室");
      putWord("chapel", "礼拜堂");
      putWord("checkerboard", "棋盘");
      putWord("chest", "箱子");
      putWord("cod", "鳕鱼");
      putWord("colossuem", "圆形剧场");
      putWord("column", "柱");
      putWord("complex", "综合体");
      putWord("conduit", "潮涌核心");
      putWord("cook", "厨师");
      putWord("cooking", "烹饪");
      putWord("cobble", "圆石");
      putWord("cobweb", "蛛网");
      putWord("cozy", "温馨");
      putWord("crane", "起重机");
      putWord("crystal", "水晶");
      putWord("cypress", "柏木");
      putWord("dense", "茂密");
      putWord("destroyed", "破败");
      putWord("devil", "恶魔");
      putWord("duck", "鸭子");
      putWord("enchanter", "附魔师");
      putWord("enchanting", "附魔");
      putWord("engineer", "工程师");
      putWord("everbright", "永昼");
      putWord("everdawn", "永晨");
      putWord("exogino", "异域遗迹");
      putWord("farmstead", "农庄");
      putWord("farmerhouse", "农夫屋");
      putWord("farming", "农耕");
      putWord("farmland", "耕地");
      putWord("feild", "田地");
      putWord("firetower", "消防瞭望塔");
      putWord("fish", "鱼");
      putWord("fishermanhouse", "渔夫屋");
      putWord("fletcherhouse", "制箭师屋");
      putWord("flaming", "燃烧");
      putWord("foraging", "采集");
      putWord("fossils", "化石群");
      putWord("fox", "狐狸");
      putWord("frosted", "霜冻");
      putWord("gallows", "绞刑架");
      putWord("glacial", "冰川");
      putWord("glacio", "冰川");
      putWord("golem", "傀儡");
      putWord("grain", "谷物");
      putWord("greatsword", "巨剑");
      putWord("grass", "草地");
      putWord("hay", "干草");
      putWord("haystack", "干草垛");
      putWord("haunted", "闹鬼");
      putWord("hell", "地狱");
      putWord("hideaway", "隐居所");
      putWord("hideout", "藏身处");
      putWord("hive", "蜂巢");
      putWord("hollow", "空心");
      putWord("iberian", "伊比利亚");
      putWord("iceologer", "冰术师");
      putWord("illusioner", "幻术师");
      putWord("iron", "铁");
      putWord("ironwood", "铁木");
      putWord("land", "陆地");
      putWord("leak", "裂隙");
      putWord("lecturn", "讲台");
      putWord("library", "图书馆");
      putWord("libraries", "图书馆");
      putWord("libraray", "图书馆");
      putWord("lodge", "木屋");
      putWord("logs", "原木");
      putWord("lookout", "哨塔");
      putWord("lotussea", "莲海");
      putWord("maison", "宅邸");
      putWord("mars", "火星");
      putWord("mason", "石匠");
      putWord("mausoleum", "陵墓");
      putWord("mercury", "水星");
      putWord("meteor", "陨石");
      putWord("meteorite", "陨石");
      putWord("mining", "采矿");
      putWord("mimic", "仿原版");
      putWord("mirage", "海市蜃楼");
      putWord("monolith", "巨石碑");
      putWord("moss", "苔藓");
      putWord("mossy", "覆苔");
      putWord("moon", "月球");
      putWord("mystical", "神秘");
      putWord("mythic", "神话");
      putWord("nilotic", "尼罗河风格");
      putWord("nomad", "游牧");
      putWord("normal", "普通");
      putWord("obsidian", "黑曜石");
      putWord("offering", "供品");
      putWord("offshore", "近海");
      putWord("oriental", "东方");
      putWord("pale", "苍白");
      putWord("parkour", "跑酷");
      putWord("phantom", "幻影");
      putWord("piglin", "猪灵");
      putWord("platform", "平台");
      putWord("pump", "水泵");
      putWord("pumpkin", "南瓜");
      putWord("quartz", "石英");
      putWord("quarter", "驻地");
      putWord("raft", "木筏");
      putWord("railroad", "铁路");
      putWord("ranch", "牧场");
      putWord("ratatouille", "普罗旺斯杂烩");
      putWord("ratlantis", "鼠特兰蒂斯");
      putWord("refinery", "精炼厂");
      putWord("regular", "常规");
      putWord("relic", "遗物");
      putWord("relict", "遗迹");
      putWord("respawn", "重生");
      putWord("reservoir", "水库");
      putWord("rib", "肋骨");
      putWord("ritual", "仪式");
      putWord("roman", "罗马");
      putWord("rugged", "崎岖");
      putWord("sacred", "神圣");
      putWord("salem", "塞勒姆");
      putWord("salt", "盐");
      putWord("sandy", "沙质");
      putWord("shack", "棚屋");
      putWord("shard", "碎片");
      putWord("sharpened", "尖锐");
      putWord("shelter", "避难所");
      putWord("shepherd", "牧羊人");
      putWord("shroom", "蘑菇");
      putWord("skyris", "斯凯里斯");
      putWord("sleepy", "沉睡");
      putWord("smiths", "铁匠铺");
      putWord("smoldering", "闷燃");
      putWord("smoking", "冒烟");
      putWord("snowman", "雪人");
      putWord("sorcerers", "巫师");
      putWord("soul", "灵魂");
      putWord("sphere", "球形");
      putWord("sphinx", "狮身人面像");
      putWord("spring", "春季");
      putWord("square", "方形");
      putWord("squid", "鱿鱼");
      putWord("star", "星形");
      putWord("starlight", "星光");
      putWord("stash", "藏宝点");
      putWord("stew", "炖菜");
      putWord("stilt", "高脚");
      putWord("stonehedge", "石阵");
      putWord("stump", "树桩");
      putWord("stumps", "树桩");
      putWord("summer", "夏季");
      putWord("sunflower", "向日葵");
      putWord("sushi", "寿司");
      putWord("termite", "白蚁");
      putWord("tiny", "微型");
      putWord("toadstool", "毒蘑菇");
      putWord("toolsmith", "工具匠");
      putWord("tracks", "轨道");
      putWord("training", "训练");
      putWord("trident", "三叉戟");
      putWord("venus", "金星");
      putWord("voyager", "航行者");
      putWord("wagon", "货车");
      putWord("waterfall", "瀑布");
      putWord("weapons", "武器");
      putWord("wild", "荒野");
      putWord("winter", "冬季");
      putWord("wizard", "巫师");
      putWord("wreckage", "残骸");
      putWord("yellow", "黄色");
      putWord("anchor", "锚");
      putWord("bed", "床");
      putWord("bin", "仓");
      putWord("buried", "埋藏");
      putWord("calcite", "方解石");
      putWord("cart2", "矿车 2");
      putWord("challenge", "挑战");
      putWord("circle", "圆环");
      putWord("clearing", "空地");
      putWord("coconut", "椰子");
      putWord("coves", "海湾");
      putWord("diorite", "闪长岩");
      putWord("dog", "犬");
      putWord("elven", "精灵");
      putWord("emphyreal", "天境");
      putWord("empyreal", "天境");
      putWord("empyrial", "天境");
      putWord("enderskog", "末地森林");
      putWord("endscraps", "末地残骸");
      putWord("factory", "工厂");
      putWord("finger", "指");
      putWord("galleon", "大帆船");
      putWord("gingerbread", "姜饼");
      putWord("gour", "葫芦");
      putWord("great", "巨型");
      putWord("hellbark", "地狱皮木");
      putWord("houses", "房屋群");
      putWord("huts", "小屋群");
      putWord("islands", "群岛");
      putWord("leaf", "叶片");
      putWord("maya", "玛雅");
      putWord("mines", "矿洞");
      putWord("moai", "摩艾石像");
      putWord("muddy", "泥泞");
      putWord("northern", "北方");
      putWord("origin", "源生");
      putWord("out", "外侧");
      putWord("outcast", "流亡");
      putWord("paths", "道路");
      putWord("pen", "围栏");
      putWord("railway", "铁路");
      putWord("rocky", "岩石");
      putWord("rocks", "岩石");
      putWord("sand", "沙地");
      putWord("shed", "棚屋");
      putWord("sky", "天空");
      putWord("stelzen", "高脚屋");
      putWord("surface", "地表");
      putWord("sunzi", "孙子");
      putWord("tour", "塔楼");
      putWord("transilvania", "特兰西瓦尼亚");
      putWord("tsumago", "妻笼宿");
      putWord("tw", "暮色森林");
      putWord("umbram", "暗影木");
      putWord("umbran", "暗影木");
      putWord("vanilla", "原版");
      putWord("volcano", "火山");
      putWord("walls", "墙体");
      putWord("white", "白色");
      putWord("willow", "柳木");
      putWord("wisteria", "紫藤");
      putWord("as", "作为");
      putWord("cats", "猫");
      putWord("clear", "清澈");
      putWord("columns", "柱群");
      putWord("delta", "三角洲");
      putWord("dolphin", "海豚");
      putWord("dry", "干涸");
      putWord("eye", "眼");
      putWord("feature", "特征");
      putWord("gate", "门");
      putWord("lakes", "湖泊");
      putWord("magma", "岩浆");
      putWord("manors", "庄园群");
      putWord("mystery", "神秘");
      putWord("remove", "移除");
      putWord("spawn", "生成");
      putWord("structures", "结构");
      putWord("towers", "高塔群");
      putWord("trials", "试炼");
      putWord("vh", "村庄地平线");
      putWord("villages", "村庄群");
      putWord("vines", "藤蔓");
      putWord("way", "路标");
      loadResourceTranslations();
   }
}
