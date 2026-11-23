/* 文件职责：S5 方法级删除边界守卫 —— 冻住「哪些方法会随旧链路消失」。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 方法级删除边界守卫。
 *
 * <h2>它守的是什么</h2>
 * <p>S7 要「逐块删旧实现」。但删旧实现最容易踩的坑不是删错文件，而是
 * <b>以为某个方法没人用了、结果半个月后别的地方开始调它</b> ——
 * 那时它就从「可删」悄悄变回「不可删」，而没人会注意到。</p>
 *
 * <p>所以把「可删边界」写成断言：分类为 {@code DELETE_WITH_LEGACY} 的方法，
 * 其<b>外部引用数必须恒为 0</b>。谁引用了，谁就会看到这条红。</p>
 *
 * <h2>为什么不是「直接删掉」</h2>
 * <p>这 17 个方法在同包内部还被互相调用（例如 {@code constrainProfileToFootprintSupport}
 * 被 {@code stabilizeSurfaceProfile} 调）。单独删任何一个都会编译失败 ——
 * 它们必须<b>与旧链路整体一起下线</b>。台账的作用就是把这个整体圈出来。</p>
 *
 * <h2>口径说明</h2>
 * <p>按 <b>方法名</b> 统计全模块（common / fabric / api / api-fabric）的引用。
 * 这不是完美口径 —— 通用名会被同名方法污染，所以台账里单列了
 * {@code AMBIGUOUS} 类（{@code cut}/{@code fill}/{@code clamp}/{@code isBridge}/
 * {@code stationSpacing}/{@code overburden}）交人工核。
 * 但 {@code DELETE_WITH_LEGACY} 的分类恰恰不受这个缺陷影响：
 * <b>它们外部引用是 0，通用名不可能把它们误判成 0</b>。</p>
 */
public final class S5MethodMapTest {

    /** 受管文件（相对 {@code common/src/main/java/net/shiroha233/roadweaver}）。 */
    private static final String[] TRACKED_FILES = {
            "pathlogic/grade/RoadGradePlanner.java",
            "pathlogic/grade/GradeOptimizer.java",
            "pathlogic/grade/SlopeContinuityEnforcer.java",
    };

    private static final Set<String> CATEGORIES = Set.of(
            "DELETE_WITH_LEGACY", "LEGACY_ONLY", "KEEP_INPUT", "AMBIGUOUS");

    /** 方法声明（4 空格缩进）。 */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^    (?:public |private |protected )?(?:static )?(?:final )?"
                    + "[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*\\(",
            Pattern.MULTILINE);

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

    private static List<Path> mainJava() {
        List<Path> out = new ArrayList<>();
        for (String m : new String[]{"common", "fabric", "api", "api-fabric"}) {
            Path d = repoRoot.resolve(m).resolve("src").resolve("main").resolve("java");
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    /** 台账行：类名 → (方法名 → 分类)。 */
    private static Map<String, Map<String, String>> readMap(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int from = text.indexOf("<!-- S5M:BEGIN -->");
        int to = text.indexOf("<!-- S5M:END -->");
        assertTrue(from >= 0 && to > from, "S5 方法台账缺 S5M:BEGIN / S5M:END 标记块");

        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String line : text.substring(from, to).split("\n")) {
            String s = line.trim();
            if (!s.startsWith("S5M|")) continue;
            String[] p = s.split("\\|", -1);
            if (p.length < 6) continue;
            if ("类".equals(p[1])) continue;          // 表头
            if (p[1].startsWith("-")) continue;       // 分隔行
            out.computeIfAbsent(p[1], k -> new LinkedHashMap<>()).put(p[2], p[5]);
        }
        return out;
    }

    @Test
    void 台账必须覆盖受管文件的每个方法() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "找不到源码根，跳过");
        Path map = repoRoot.resolve("common/src/test/resources/architecture/postprocess-methods-baseline.txt");
        assertTrue(Files.isRegularFile(map), "缺 S5 方法台账：" + map);
        Map<String, Map<String, String>> listed = readMap(map);

        List<String> problems = new ArrayList<>();
        for (String rel : TRACKED_FILES) {
            Path f = srcRoot.resolve(rel);
            if (!Files.isRegularFile(f)) continue;
            String cls = f.getFileName().toString().replace(".java", "");
            Set<String> declared = new LinkedHashSet<>();
            Matcher m = METHOD_DECL.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                String name = m.group(1);
                if (name.equals("if") || name.equals("for") || name.equals("while")
                        || name.equals("switch") || name.equals("return")
                        || name.equals("new") || name.equals("super") || name.equals("this")) {
                    continue;
                }
                declared.add(name);
            }
            Map<String, String> row = listed.getOrDefault(cls, Map.of());
            for (String name : declared) {
                if (!row.containsKey(name)) {
                    problems.add(cls + "." + name + " 未登记进 S5 方法台账");
                } else if (!CATEGORIES.contains(row.get(name))) {
                    problems.add(cls + "." + name + " 分类非法：" + row.get(name));
                }
            }
            for (String name : row.keySet()) {
                if (!declared.contains(name)) {
                    problems.add("台账登记了不存在的方法：" + cls + "." + name + "（删方法要删台账行）");
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "S5 方法台账与代码不同步：\n  " + String.join("\n  ", problems));
    }

    @Test
    void 可删方法的外部引用必须恒为零() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "跳过");
        Path map = repoRoot.resolve("common/src/test/resources/architecture/postprocess-methods-baseline.txt");
        assertTrue(Files.isRegularFile(map), "缺 S5 方法台账");
        Map<String, Map<String, String>> listed = readMap(map);

        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (var e : listed.entrySet()) {
            String cls = e.getKey();
            for (var m : e.getValue().entrySet()) {
                if (!"DELETE_WITH_LEGACY".equals(m.getValue())) continue;
                checked++;
                Path owner = srcRoot.resolve(TRACKED_FILES[0]).getParent().resolve(cls + ".java");
                Pattern call = Pattern.compile("\\b" + Pattern.quote(m.getKey()) + "\\s*\\(");
                for (Path f : mainJava()) {
                    if (Files.isSameFile(f, owner)) continue;
                    Matcher mm = call.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    if (mm.find()) {
                        violations.add(cls + "." + m.getKey() + " 被 "
                                + repoRoot.relativize(f) + " 引用了 —— 它不再「可随旧链路一起删」");
                    }
                }
            }
        }
        assertTrue(checked >= 15, "可删方法只登记了 " + checked + " 个，台账可能被清空了");
        assertTrue(violations.isEmpty(),
                "可删边界被打破（这些方法原本外部零引用）：\n  "
                        + String.join("\n  ", violations)
                        + "\n如果新引用是必要的，请把台账里该方法改成 KEEP_INPUT 并说明理由。");
    }
}