/* 文件职责：守卫「配置界面上暴露的项，若元数据说它不生效，文案必须如实承认」。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：配置界面上暴露的项，如果元数据说它「未接线 / 已废弃」，界面文案必须如实承认。
 *
 * <h2>它补的洞（2026-11-13）</h2>
 *
 * <p>这个仓库有两套互不相通的配置描述：</p>
 * <ul>
 *   <li><b>{@code config/meta}</b>（16 文件 / 2053 行）—— 机器可读元数据，每个键标
 *       {@code ConfigStatus}（ACTIVE / UNWIRED / DEPRECATED / EXPERIMENTAL）。
 *       但 <b>UI 完全不读它</b>（{@code client/} 包对 {@code config.meta} 引用次数 = 0）。</li>
 *   <li><b>{@code lang/*.json}</b> —— 玩家真正看到的 label 与 tooltip。</li>
 * </ul>
 *
 * <p>于是「这个键不生效」这个事实只存在于元数据里，<b>到不了玩家眼前</b>。
 * 此前只有 {@code ConfigKnobHonestyTest} 用手写的 <b>6 个死键名单</b>盯着 lang ——
 * 但元数据里有 9 个 UNWIRED + 3 个 DEPRECATED，手写名单覆盖不全。</p>
 *
 * <h2>它是怎么把两套接起来的</h2>
 *
 * <p>两套系统<b>没有共同键</b>：元数据的 {@code jsonKey} 是 camelCase（{@code keepLamps}），
 * lang 键是「前缀 + 蛇形」（{@code config.roadweaver.bridge_keep_lamps}）。
 * 硬做字符串映射很脆。</p>
 *
 * <p>但 UI 源码里有一样东西同时连得住两边 —— <b>{@code setSaveConsumer} 的方法引用</b>：</p>
 * <pre>
 * category.addEntry(eb
 *         .startBooleanToggle(Component.translatable("config.roadweaver.bridge_keep_lamps"), cfg.keepLamps())
 *         .setTooltip(Component.translatable("config.roadweaver.bridge_keep_lamps.tooltip"))
 *         .setSaveConsumer(cfg::setKeepLamps)      &lt;-- 去掉 set + 首字母小写 = keepLamps
 *         .build());
 * </pre>
 *
 * <p>所以本守卫扫 UI 源码，按 entry 块解析出「lang 的 label 键」与「配置字段名」，
 * 再用字段名去查元数据的 {@code ConfigStatus}。这条链路完全基于既存代码，
 * <b>不需要改任何 UI 文件</b>，也不需要维护映射表。</p>
 *
 * <h2>顺带解决的两件事</h2>
 * <ul>
 *   <li><b>只检查真的暴露在界面上的键</b> —— 元数据里有些 UNWIRED 键（如
 *       {@code landTunnelEnabled}、{@code braidAllowJunctionCross}）压根没进 UI，
 *       玩家看不到，自然不要求 lang 有文案。</li>
 *   <li><b>自动跟随元数据</b> —— 以后把某个键标成 UNWIRED，这里立刻要求补文案，
 *       不用再去改手写名单。</li>
 * </ul>
 */
public final class ConfigUiHonestyTest {

    /** 从 entry 块里抓 label 的 lang 键（第一个不带 .tooltip 的 translatable）。 */
    private static final Pattern TRANSLATABLE = Pattern.compile(
            "Component\\.translatable\\(\"([^\"]+)\"\\)");

    /** 从 entry 块里抓字段名：cfg::setXxx -> xxx。 */
    private static final Pattern SAVE_CONSUMER = Pattern.compile(
            "setSaveConsumer\\(\\s*\\w+::set([A-Z]\\w*)\\s*\\)");

    /** 元数据里的一条 ConfigKey 定义开头。 */
    private static final Pattern KEY_ENTRY = Pattern.compile(
            "ConfigKey\\.(?:integer|decimal|bool|text)\\(\\s*\"(\\w+)\"");

    /** 元数据里的状态标注。 */
    private static final Pattern STATUS = Pattern.compile("ConfigStatus\\.(\\w+)");

    /** 中文 marker：未接线。 */
    private static final String ZH_UNWIRED = "不生效";

    /** 英文 marker：未接线（大小写不敏感地找 no effect / does nothing）。 */
    private static final Pattern EN_UNWIRED = Pattern.compile(
            "does nothing|no effect|not wired|unwired|does not work",
            Pattern.CASE_INSENSITIVE);

    /**
     * 中文 marker：已废弃。
     *
     * <p>接受「不生效」或「废弃」两种说法 —— 对玩家而言 DEPRECATED 与 UNWIRED
     * 的体验是一样的（改了都没用），文案不必纠结术语。实测
     * {@code bridge_keep_lamps} 的现有文案写的就是「此开关不生效」，
     * 那比「已废弃」更好懂，不该因为用词不同被判失败。</p>
     */
    private static final String[] ZH_DEPRECATED = {"不生效", "废弃"};

    /** 英文 marker：已废弃。 */
    private static final Pattern EN_DEPRECATED = Pattern.compile(
            "deprecated|obsolete|removed|does nothing|no effect", Pattern.CASE_INSENSITIVE);

    private static Path repoRoot;

    @BeforeAll
    static void locateRepo() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (dir == null) {
                return;
            }
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                repoRoot = dir;
                return;
            }
            dir = dir.getParent();
        }
    }

    private static List<Path> listJava(Path root) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        collect(root, out);
        return out;
    }

    private static void collect(Path dir, List<Path> out) {
        Path[] children;
        try {
            children = Files.list(dir).toArray(Path[]::new);
        } catch (IOException e) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            if (Files.isDirectory(children[i])) {
                collect(children[i], out);
            } else if (children[i].toString().endsWith(".java")) {
                out.add(children[i]);
            }
        }
    }

    /** 元数据的「字段名 -> 状态」。 */
    private static Map<String, String> declaredStatuses() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<Path> all = listJava(repoRoot.resolve("common").resolve("src").resolve("main").resolve("java"));
        for (int f = 0; f < all.size(); f++) {
            Path file = all.get(f);
            if (!file.toString().replace('\\', '/').contains("/config/meta/")) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = KEY_ENTRY.matcher(text);
            List<Integer> starts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (m.find()) {
                starts.add(m.start());
                names.add(m.group(1));
            }
            for (int i = 0; i < starts.size(); i++) {
                int from = starts.get(i);
                int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
                Matcher s = STATUS.matcher(text.substring(from, to));
                if (s.find()) {
                    result.put(names.get(i), s.group(1));
                }
            }
        }
        return result;
    }

    /** 界面条目：lang 的 label 键 + 配置字段名。 */
    private record UiEntry(String labelKey, String field, String section) {
    }

    /** 扫 fabric 的 12 个 Section，解析出每个 entry。 */
    private static List<UiEntry> uiEntries() throws IOException {
        List<UiEntry> out = new ArrayList<>();
        List<Path> all = listJava(repoRoot.resolve("fabric").resolve("src").resolve("main").resolve("java"));
        for (int f = 0; f < all.size(); f++) {
            Path file = all.get(f);
            if (!file.getFileName().toString().endsWith("Section.java")) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String section = file.getFileName().toString().replace(".java", "");
            int cursor = 0;
            while (true) {
                int start = text.indexOf("category.addEntry(", cursor);
                if (start < 0) {
                    break;
                }
                int end = text.indexOf(".build());", start);
                if (end < 0) {
                    break;
                }
                String block = text.substring(start, end + ".build());".length());
                cursor = end + 1;

                Matcher save = SAVE_CONSUMER.matcher(block);
                if (!save.find()) {
                    continue;                       // 自定义控件，跳过
                }
                String field = Character.toLowerCase(save.group(1).charAt(0))
                        + save.group(1).substring(1);

                Matcher tr = TRANSLATABLE.matcher(block);
                String labelKey = null;
                while (tr.find()) {
                    if (!tr.group(1).endsWith(".tooltip")) {
                        labelKey = tr.group(1);
                        break;
                    }
                }
                if (labelKey == null) {
                    continue;
                }
                out.add(new UiEntry(labelKey, field, section));
            }
        }
        return out;
    }

    private static String langText(String fileName) throws IOException {
        Path p = repoRoot.resolve("common").resolve("src").resolve("main").resolve("resources")
                .resolve("assets").resolve("roadweaver").resolve("lang").resolve(fileName);
        if (!Files.isRegularFile(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 取某个 key 的值（极简 JSON 取值：值里不含转义引号时足够）。 */
    private static String value(String lang, String quotedKey) {
        int key = lang.indexOf(quotedKey);
        if (key < 0) return "";
        int colon = lang.indexOf(':', key + quotedKey.length());
        if (colon < 0) return "";
        int open = lang.indexOf('"', colon + 1);
        if (open < 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = open + 1; i < lang.length(); i++) {
            char c = lang.charAt(i);
            if (c == '\\') {
                out.append(c);
                if (i + 1 < lang.length()) out.append(lang.charAt(++i));
                continue;
            }
            if (c == '"') return out.toString();
            out.append(c);
        }
        return "";
    }

    @Test
    void 界面上暴露的不生效项必须自我承认() throws IOException {
        Assumptions.assumeTrue(repoRoot != null, "找不到仓库根（settings.gradle），跳过");

        Map<String, String> statuses = declaredStatuses();
        List<UiEntry> entries = uiEntries();
        assertTrue(entries.size() > 150,
                "只解析出 " + entries.size() + " 个界面条目 —— Section 的写法可能变了");

        String zh = langText("zh_cn.json");
        String en = langText("en_us.json");
        assertTrue(!zh.isEmpty() && !en.isEmpty(), "读不到 lang 文件");

        List<String> problems = new ArrayList<>();
        int inert = 0;
        for (int i = 0; i < entries.size(); i++) {
            UiEntry e = entries.get(i);
            String status = statuses.get(e.field());
            if (status == null) {
                continue;
            }
            boolean isUnwired = "UNWIRED".equals(status);
            boolean isDeprecated = "DEPRECATED".equals(status);
            if (!isUnwired && !isDeprecated) {
                continue;
            }
            inert++;

            String zhLabel = value(zh, "\"" + e.labelKey() + "\"");
            String enLabel = value(en, "\"" + e.labelKey() + "\"");
            String zhTip = value(zh, "\"" + e.labelKey() + ".tooltip\"");
            String enTip = value(en, "\"" + e.labelKey() + ".tooltip\"");

            String[] zhNeeds = isUnwired ? new String[]{ZH_UNWIRED} : ZH_DEPRECATED;
            Pattern enNeed = isUnwired ? EN_UNWIRED : EN_DEPRECATED;

            boolean zhOk = false;
            String zhNeed = zhNeeds[0];
            for (int n = 0; n < zhNeeds.length; n++) {
                if (zhLabel.contains(zhNeeds[n]) || zhTip.contains(zhNeeds[n])) {
                    zhOk = true;
                    zhNeed = zhNeeds[n];
                    break;
                }
            }
            boolean enOk = enNeed.matcher(enLabel + " " + enTip).find();

            if (!zhOk || !enOk) {
                problems.add(e.section() + " → " + e.labelKey() + "（" + e.field() + "，"
                        + status + "）\n      中文缺「" + zhNeed + "」=" + !zhOk
                        + "，英文缺标记=" + !enOk
                        + "\n      现有 zh label=" + quote(zhLabel) + " tip=" + quote(zhTip));
            }
        }

        assertTrue(problems.isEmpty(),
                "以下配置在界面上暴露给玩家，元数据也标明它「不生效」，但文案没承认。\n"
                        + "玩家照着调是纯浪费 —— 这是「配置项不诚实」最直接的一种。\n\n  "
                        + String.join("\n  ", problems)
                        + "\n\n修法：在该键的 lang label 或 tooltip 里加上标记"
                        + "（中文「不生效」/「已废弃」，英文 does nothing / deprecated）。"
                        + "\n这条守卫自动跟随 config/meta 的标注，不用维护手写名单。");

        System.out.println("ConfigUiHonestyTest: 界面共 " + entries.size()
                + " 项，其中元数据标为不生效的 " + inert + " 项，全部已如实标注");
    }

    private static String quote(String s) {
        if (s == null || s.isEmpty()) return "(缺)";
        return s.length() > 40 ? "\"" + s.substring(0, 40) + "…\"" : "\"" + s + "\"";
    }
}