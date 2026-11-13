/* 文件职责：把结构/模组的英文名本地化成中文（逻辑部分；词表在 Dictionary 与 Words）。 */
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
import net.shiroha233.roadweaver.config.structure.StructureTagEntry;

public final class ChineseRoadWeaverNames {
   private static final Map<String, String> MOD_NAMES = new HashMap<>();
   private static final Map<String, String> EXACT_STRUCTURES = new HashMap<>();
   private static final Map<String, String> RESOURCE_STRUCTURES = new HashMap<>();
   private static final Map<String, String> RESOURCE_TAGS = new HashMap<>();
   private static final Map<String, String> PHRASES = new HashMap<>();
   private static final Map<String, String> WORDS = new HashMap<>();

   private ChineseRoadWeaverNames() {
   }

   /**
    * 取结构标签的中文显示名 —— <b>UI 侧专用</b>。
    *
    * <p>数据层（{@code StructureTagEntry.displayName()}）2026-11-13 起只返回原始名，
    * 本地化职责搬到了这里：{@code client} 依赖 {@code config} 是正常方向，
    * 反过来就是层次错乱（还会把中文固化进结构缓存）。</p>
    */
   public static String tagDisplayName(StructureTagEntry tag) {
      if (tag == null) return "";
      return tagName(tag.tagId() == null ? "" : tag.tagId().toString(), tag.displayName());
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

   static {
      ChineseRoadWeaverDictionary.registerInto(MOD_NAMES, EXACT_STRUCTURES, PHRASES);
      ChineseRoadWeaverWords.registerInto(WORDS);
      loadResourceTranslations();
      loadResourceTranslations();
   }
}
