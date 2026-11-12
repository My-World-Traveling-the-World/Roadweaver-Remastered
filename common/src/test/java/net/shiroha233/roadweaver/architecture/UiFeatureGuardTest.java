/* 文件职责：UI 功能保全守卫 —— 把「重构后功能一个不丢」变成可机检的清单。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI 功能保全守卫。
 *
 * <h2>为什么需要它</h2>
 * <p>用户对这次重制最硬的一条要求是「<b>原有的功能不能丢</b>」。这句话没法靠人记：
 * 重构 UI 时删掉一个 dock 按钮、改掉一个键位名、漏掉一个语言键，
 * 游戏里表现都很轻微（少个按钮、按钮无提示），但确实是功能丢失。</p>
 *
 * <p>所以把「功能清单」写成断言。清单来自旧版实测，不是从当前代码反推 ——
 * 反推的话，删了功能清单也跟着缩，守卫就永远绿。</p>
 *
 * <h2>为什么全部用源码扫描，而不是反射/实例化</h2>
 * <p>这些类（{@code KeyMapping}、{@code GuiGraphics}）一加载就要 Minecraft 的注册表，
 * 纯 JUnit 环境里直接炸。源码扫描虽然土，但<b>恒真、零依赖、能定位到文件</b>，
 * 对这个场景是正确工具。</p>
 */
public final class UiFeatureGuardTest {

    /** 旧版实测的 9 个 dock 动作。删任何一个都是功能丢失。 */
    private static final List<String> EXPECTED_DOCK_ACTIONS = List.of(
            "SEARCH", "FILTER", "REFRESH", "MANUAL_CONNECT", "DELETE_CONNECTION",
            "ROUTE_EDIT", "TOGGLE_MARKERS", "CONFIG", "CLOSE");

    /** 旧版实测的 15 个手绘路线键位。 */
    private static final List<String> EXPECTED_ROUTE_KEYS = List.of(
            "key.roadweaver.route.move_forward",
            "key.roadweaver.route.move_back",
            "key.roadweaver.route.move_left",
            "key.roadweaver.route.move_right",
            "key.roadweaver.route.cycle_kind",
            "key.roadweaver.route.delete_point",
            "key.roadweaver.route.insert_point",
            "key.roadweaver.route.undo",
            "key.roadweaver.route.open_height",
            "key.roadweaver.route.confirm_build",
            "key.roadweaver.route.toggle_preview",
            "key.roadweaver.route.toggle_hud",
            "key.roadweaver.route.abort_preload",
            "key.roadweaver.route.speed_boost",
            "key.roadweaver.route.edit_modifier");

    /** 四个 UI 区的文件数下限（ratchet：只许涨）。 */
    private static final String[][] UI_AREAS = {
            {"client/map", "地图界面", "56"},
            {"route/editor", "手绘路线编辑器", "12"},
            {"client/config", "配置界面", "12"},
            {"client/loading", "预热/加载界面", "3"},
    };

    private static final Pattern ENUM_CONSTANT = Pattern.compile("^\\s{4}([A-Z][A-Z0-9_]*)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern LANG_KEY = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:");

    private static Path repoRoot;
    private static Path srcRoot;

    @BeforeAll
    static void locate() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path cand = dir.resolve("common").resolve("src").resolve("main").resolve("java")
                    .resolve("net").resolve("shiroha233").resolve("roadweaver");
            if (Files.isDirectory(cand)) {
                repoRoot = dir;
                srcRoot = cand;
                return;
            }
            dir = dir.getParent();
        }
    }

    private static String read(String rel) throws IOException {
        return Files.readString(srcRoot.resolve(rel), StandardCharsets.UTF_8);
    }

    // ── 一、dock 动作清单 ─────────────────────────────────────

    @Test
    void dock动作一个都不能少() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "找不到源码根，跳过");
        String src = read("client/map/render/MapDockAction.java");
        Set<String> actual = new LinkedHashSet<>();
        Matcher m = ENUM_CONSTANT.matcher(src);
        while (m.find()) actual.add(m.group(1));

        List<String> missing = new ArrayList<>();
        for (String want : EXPECTED_DOCK_ACTIONS) {
            if (!actual.contains(want)) missing.add(want);
        }
        assertTrue(missing.isEmpty(),
                "地图底部 Dock 少了功能按钮（旧版有 9 个）：" + missing
                        + "\n当前：" + actual);

        List<String> extra = new ArrayList<>();
        for (String got : actual) {
            if (!EXPECTED_DOCK_ACTIONS.contains(got)) extra.add(got);
        }
        assertTrue(extra.isEmpty(),
                "Dock 出现了未登记的新按钮（如有意新增，请同步本测试的清单与语言键）：" + extra);
    }

    @Test
    void 每个dock动作都必须有分派分支() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "跳过");
        String src = read("client/map/RoadMapScreen.java");

        int start = src.indexOf("switch (action) {");
        assertTrue(start >= 0, "RoadMapScreen 里找不到 `switch (action) {` —— 分派结构被改了");

        // 找到与 switch 配对的右花括号
        int depth = 0;
        int end = -1;
        for (int i = src.indexOf('{', start); i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        assertTrue(end > start, "switch 花括号不配对");
        String body = src.substring(start, end);

        List<String> missing = new ArrayList<>();
        for (String action : EXPECTED_DOCK_ACTIONS) {
            // 允许 `case SEARCH ->`、`case SEARCH, FILTER ->`、`case SEARCH:`
            boolean has = Pattern.compile("case[^:>-]*\\b" + Pattern.quote(action) + "\\b").matcher(body).find();
            if (!has) missing.add(action);
        }
        assertTrue(missing.isEmpty(),
                "以下 dock 按钮在分派里没有分支（点了没反应）：" + missing);
    }

    // ── 二、手绘路线键位清单 ──────────────────────────────────

    @Test
    void 手绘路线键位一个都不能少() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "跳过");
        String src = read("route/editor/RouteKeys.java");
        Set<String> actual = new LinkedHashSet<>();
        Matcher m = Pattern.compile("new KeyMapping\\(\"([^\"]+)\"").matcher(src);
        while (m.find()) actual.add(m.group(1));

        List<String> missing = new ArrayList<>();
        for (String want : EXPECTED_ROUTE_KEYS) {
            if (!actual.contains(want)) missing.add(want);
        }
        assertTrue(missing.isEmpty(),
                "手绘路线少了键位（旧版有 15 个）：" + missing + "\n当前：" + actual);
        assertEquals(EXPECTED_ROUTE_KEYS.size(), actual.size(),
                "键位数量变了（旧版 15 个）：" + actual);
    }

    // ── 三、语言键两种语言都要有 ──────────────────────────────

    @Test
    void 所有UI文案在中英文里都要有() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "跳过");

        Set<String> en = langKeys("assets/roadweaver/lang/en_us.json");
        Set<String> zh = langKeys("assets/roadweaver/lang/zh_cn.json");

        List<String> problems = new ArrayList<>();
        for (String action : EXPECTED_DOCK_ACTIONS) {
            String key = "gui.roadweaver.map.dock." + switch (action) {
                case "MANUAL_CONNECT" -> "manual_connect";
                case "DELETE_CONNECTION" -> "delete_connection";
                case "ROUTE_EDIT" -> "route_edit";
                case "TOGGLE_MARKERS" -> "toggle_markers";
                default -> action.toLowerCase();
            };
            if (!en.contains(key)) problems.add("en_us 缺 " + key);
            if (!zh.contains(key)) problems.add("zh_cn 缺 " + key);
        }
        for (String key : EXPECTED_ROUTE_KEYS) {
            // 只有可绑定的主键位才要求出现在 lang 里（edit_modifier 这类修饰键也要求）
            if (!en.contains(key)) problems.add("en_us 缺 " + key);
            if (!zh.contains(key)) problems.add("zh_cn 缺 " + key);
        }
        assertTrue(problems.isEmpty(),
                "UI 文案缺翻译（会显示成原始键名）：\n  " + String.join("\n  ", problems));
    }

    private static Set<String> langKeys(String rel) throws IOException {
        Path p = repoRoot.resolve("common").resolve("src").resolve("main").resolve("resources").resolve(rel);
        assertTrue(Files.isRegularFile(p), "缺语言文件：" + p);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LANG_KEY.matcher(Files.readString(p, StandardCharsets.UTF_8));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ── 四、四个 UI 区都还在，且没缩水 ────────────────────────

    @Test
    void 四个UI区都还在且没缩水() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "跳过");
        List<String> problems = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (String[] area : UI_AREAS) {
            Path dir = srcRoot.resolve(area[0]);
            if (!Files.isDirectory(dir)) {
                problems.add(area[1] + "（" + area[0] + "）整个不见了");
                continue;
            }
            int count;
            try (Stream<Path> s = Files.walk(dir)) {
                count = (int) s.filter(x -> x.toString().endsWith(".java")).count();
            }
            int floor = Integer.parseInt(area[2]);
            report.append("  ").append(area[1]).append("（").append(area[0]).append("）")
                    .append(count).append(" 个文件 / 下限 ").append(floor).append('\n');
            if (count < floor) {
                problems.add(area[1] + " 从 " + floor + " 个文件缩到 " + count + " —— 有功能被删了");
            }
        }
        System.out.print("UiFeatureGuardTest 四个 UI 区：\n" + report);
        assertTrue(problems.isEmpty(), "UI 区缩水：\n  " + String.join("\n  ", problems));
    }

    @Test
    void 配置界面必须仍然可达() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "跳过");
        // CONFIG 按钮 → ConfigScreenFactory（common 侧接口）→ fabric 侧实现
        Path iface = srcRoot.resolve("client/ConfigScreenFactory.java");
        assertTrue(Files.isRegularFile(iface), "common 侧缺 ConfigScreenFactory 接口");

        Path impl = repoRoot.resolve("fabric").resolve("src").resolve("main").resolve("java")
                .resolve("net").resolve("shiroha233").resolve("roadweaver").resolve("client")
                .resolve("fabric").resolve("ConfigScreenFactoryImpl.java");
        assertTrue(Files.isRegularFile(impl), "fabric 侧缺 ConfigScreenFactoryImpl 实现：" + impl);
        int lines = Files.readAllLines(impl, StandardCharsets.UTF_8).size();
        assertTrue(lines >= 1500,
                "配置界面实现从 1791 行缩到 " + lines + " 行 —— 疑似丢功能");
    }
}