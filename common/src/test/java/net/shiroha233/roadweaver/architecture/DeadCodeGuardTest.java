/* 文件职责：死代码守卫 —— 四类分组、剥离注释统计、分类漂移即红。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死代码守卫。
 *
 * <h2>它解决什么问题</h2>
 * <p>重构过程中「切出去但没人调」的类会不断堆积，靠人工 review 抓不住。
 * 本项目实测就误判过三次承重类为死代码，根因都是<b>只扫了一个模块</b>：
 * {@code StructureKeys} 被 common 里 11 处引用、{@code RoadBeardifierAccess}
 * 只在 fabric 的 mixin 里被引用。</p>
 *
 * <h2>★ 2026-11-13 口径大修（独立审计 + 我复现确认的两个真问题）</h2>
 *
 * <h3>问题一：javadoc 被当成真实使用</h3>
 * <p>旧口径在<b>原始文本</b>里统计类型名出现次数。于是
 * {@code /** 见 {@link Foo} *}{@code /} 这样的注释也会让 {@code Foo} 算「有人用」。</p>
 * <p>实测后果：<b>10 个类型靠 javadoc 维持活着</b>，其中 4 个连测试都不引用 ——
 * {@code InitialRoadConstructionService}、{@code Records}、
 * {@code RiverReadinessBroadcast}、{@code TwoStagePathfindingManager}。
 * 它们是真死代码，但旧口径永远看不见。</p>
 * <p>修法：{@link #stripComments(String)} 剥掉块注释与行注释后再统计。</p>
 *
 * <h3>问题二：把公开 API 门面判成「可以删」</h3>
 * <p>旧基线把 {@code CruiseSignalsApi} / {@code PlanningReadinessApi} /
 * {@code StructureReservationApi} 归进「[A] 真死代码，可以删」。但它们都在
 * {@code api/incoming/}，是<b>别的 mod 调我们的入口</b> ——
 * {@code CruiseSignalsApi} 的注释原文：「消费方（区块预生成侧）用这几个方法，<b>不再反射</b>」。</p>
 * <p>零引用是<b>设计如此</b>：我们不调自己，别人调。真删了会让下游 mod
 * 编译失败或运行崩溃。</p>
 * <p>修法：新增 {@link Kind#D}「对外契约」，{@code api} / {@code api-fabric}
 * 模块里的零引用类型一律归入，禁止删。</p>
 *
 * <h3>问题三：[A]/[B] 分组对断言零影响（藏匿后门）</h3>
 * <p>旧实现把四组的成员都塞进同一个 {@code Set<String> baseline}，
 * 断言只看「在不在并集里」。于是把一条真死代码从 {@code [A]} 挪到
 * {@code [B]}（「平台实现，禁止删」）就<b>合法地藏起来了</b>。
 * 历史上确实发生过一次（提交 50f6bbc）。</p>
 * <p>修法：{@link Kind} 成为断言的一部分 —— <b>分类漂移直接红</b>；
 * 并且 {@code [B]} 的每个成员都要通过 {@link #isPlatformImpl} 的自动校验
 * （类名 XxxImpl + 主类 Xxx 里真的声明了 {@code @ExpectPlatform}）。</p>
 *
 * <h2>四类</h2>
 * <ul>
 *   <li>{@link Kind#A} 真死代码 —— 生产与测试都零引用；</li>
 *   <li>{@link Kind#B} 平台实现 —— {@code @ExpectPlatform} 的 Fabric 侧，删了崩；</li>
 *   <li>{@link Kind#C} 测试孤岛 —— 生产零引用、只有测试用；</li>
 *   <li>{@link Kind#D} 对外契约 —— {@code api} 模块，给外部 mod 用。</li>
 * </ul>
 *
 * <p>守卫只做一件事：<b>不许新增、不许换组</b>（ratchet）。已有债务照实记录，慢慢还。</p>
 */
public final class DeadCodeGuardTest {

    /** 四种归属 —— 是断言的一部分，不只是标签。 */
    enum Kind {
        /** 真死代码：生产与测试都零引用，删掉即可。 */
        A,
        /** 平台实现：{@code @ExpectPlatform} 的 Fabric 侧，删了直接崩。 */
        B,
        /** 测试孤岛：生产零引用、只有测试在用。 */
        C,
        /** 对外契约：{@code api} 模块里的公共类型，给外部 mod 调用。 */
        D,
        /**
         * 测试夹具：生产零引用、但<b>被测试用来验证内核行为</b>，长期保留。
         *
         * <h3>为什么需要这一组，以及它凭什么不是新的藏匿后门</h3>
         * <p>[C] 的定义是「要么接进生产，要么连测试一起删」—— 那是<b>待办</b>。
         * 但有些类型永远不会有生产消费者，因为它存在的意义就是给测试当夹具：
         * 例如 {@code AvoidanceObstacleField} 是 {@code ObstacleField} 的参考实现，
         * 让内核测试能验证「硬挡真的挡得住」「区域外不可通行」这类行为 ——
         * 生产侧用的是另一个内联实现，两者本来就不该合并。</p>
         *
         * <p>把这类东西塞进 [C] 会让 [C] 永远清不干净（假待办）；
         * 塞进 [A] 又会把测试一起删掉（真损失）。所以单列一组。</p>
         *
         * <p><b>它不是后门</b>：断言 ④ 要求 [E] 的每个成员<b>必须真的有测试引用</b>
         * （即实际分类必须是 [C]，不能是 [A]）。也就是说 ——
         * 一个连测试都不用的类型既不能进 [E]，也不能靠 [E] 躲过 [A]。</p>
         */
        E
    }

    /** 顶层类型声明（列 0）—— 不含嵌套类，嵌套类生命周期跟随外层。 */
    private static final Pattern TOP_LEVEL_DECL = Pattern.compile(
            "^(?:public |final |abstract |sealed |non-sealed )*(?:class|interface|enum|record)\\s+(\\w+)",
            Pattern.MULTILINE);

    /** 标识符（统计引用用）。 */
    private static final Pattern WORD = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private static final String[] MODULES = {"common", "fabric", "api", "api-fabric"};

    private static Path repoRoot;

    @BeforeAll
    static void locateRepo() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                repoRoot = dir;
                return;
            }
            dir = dir.getParent();
        }
    }

    /**
     * 剥掉注释。
     *
     * <p>这是本守卫最关键的一行逻辑 —— 不剥注释，{@code {@link Foo}} 就会让
     * {@code Foo} 永远「有人用」（2026-11-13 实测 10 个类型被这样掩护）。</p>
     */
    static String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private static List<Path> javaFiles(String module, String phase) {
        List<Path> out = new ArrayList<>();
        Path d = repoRoot.resolve(module).resolve("src").resolve(phase).resolve("java");
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.walk(d)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static boolean isTextResource(String name) {
        return name.endsWith(".json") || name.endsWith(".properties") || name.endsWith(".toml")
                || name.endsWith(".txt") || name.endsWith(".cfg")
                || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /** 资源文本：mixin 配置、entrypoint、服务加载器声明都可能让一个类「活着」。 */
    private static String resourceCorpus() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String m : MODULES) {
            Path d = repoRoot.resolve(m).resolve("src").resolve("main").resolve("resources");
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    if (isTextResource(p.getFileName().toString())) {
                        sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String moduleOf(Path file) {
        return repoRoot.relativize(file).getName(0).toString();
    }

    /** 统计一段文本里每个标识符出现次数。 */
    private static Map<String, Integer> countWords(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) out.merge(m.group(), 1, Integer::sum);
        return out;
    }

    /**
     * 这个类型是不是 {@code @ExpectPlatform} 的平台实现。
     *
     * <p>判据（两条都要满足）：</p>
     * <ol>
     *   <li>类名是 {@code XxxImpl}；</li>
     *   <li>生产源码里存在类 {@code Xxx}，且它真的声明了 {@code @ExpectPlatform}。</li>
     * </ol>
     *
     * <p>这条校验是 2026-11-13 加的：此前 {@code [B]} 只是一个文本分组，
     * 「位于 fabric 模块」或「类名以 Impl 结尾」就能进去 ——
     * 于是 {@code RoadWeaverDataGenerator}（实现了 {@code DataGeneratorEntrypoint}
     * 但 {@code fabric.mod.json} 里<b>没有注册</b>，永不被加载）靠这个混进了
     * 「禁止删」组。现在它会因为找不到 {@code RoadWeaver}？不 —— 因为它没有
     * {@code DataGenerator} 主类，直接判为非平台实现，落回 {@code [A]}。</p>
     */
    private static boolean isPlatformImpl(String typeName, Set<String> expectPlatformClasses) {
        if (!typeName.endsWith("Impl")) return false;
        String primary = typeName.substring(0, typeName.length() - "Impl".length());
        return expectPlatformClasses.contains(primary);
    }

    /** 扫出所有声明了 {@code @ExpectPlatform} 的类型名。 */
    private static Set<String> expectPlatformClasses(Map<Path, String> prodTexts) {
        Set<String> out = new LinkedHashSet<>();
        for (String text : prodTexts.values()) {
            if (!text.contains("@ExpectPlatform")) continue;
            Matcher m = TOP_LEVEL_DECL.matcher(text);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /**
     * 算出每个零引用类型的<b>实际</b>归属。
     *
     * <p>「零引用」= 生产代码（剥注释）+ 资源里，去掉它自己文件内的出现后，
     * 别处出现次数为 0。测试引用单独统计，用来区分 {@link Kind#A} 与 {@link Kind#C}。</p>
     */
    private static Map<String, Kind> classify() throws IOException {
        Map<Path, String> raw = new TreeMap<>();
        Map<Path, String> code = new TreeMap<>();
        for (String m : MODULES) {
            for (Path p : javaFiles(m, "main")) {
                String t = Files.readString(p, StandardCharsets.UTF_8);
                raw.put(p, t);
                code.put(p, stripComments(t));
            }
        }
        String prodCorpus = String.join("\n", code.values()) + "\n" + resourceCorpus();
        Map<String, Integer> prodCounts = countWords(prodCorpus);

        StringBuilder testCode = new StringBuilder();
        for (String m : MODULES) {
            for (Path p : javaFiles(m, "test")) {
                testCode.append(stripComments(Files.readString(p, StandardCharsets.UTF_8))).append('\n');
            }
        }
        Map<String, Integer> testCounts = countWords(testCode.toString());

        Set<String> platforms = expectPlatformClasses(raw);

        Map<String, Kind> out = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : raw.entrySet()) {
            Path file = e.getKey();
            String selfCode = code.get(file);
            Map<String, Integer> selfCounts = countWords(selfCode);
            Matcher m = TOP_LEVEL_DECL.matcher(e.getValue());
            while (m.find()) {
                String name = m.group(1);
                int elsewhere = prodCounts.getOrDefault(name, 0) - selfCounts.getOrDefault(name, 0);
                if (elsewhere > 0) continue;                      // 生产代码真的在用

                if (isPlatformImpl(name, platforms)) {
                    out.put(name, Kind.B);
                    continue;
                }
                String module = moduleOf(file);
                if ("api".equals(module) || "api-fabric".equals(module)) {
                    out.put(name, Kind.D);                        // 对外契约，零引用正常
                    continue;
                }
                out.put(name, testCounts.getOrDefault(name, 0) > 0 ? Kind.C : Kind.A);
            }
        }
        return out;
    }

    /**
     * 分节标题：形如 {@code # ── [C] 测试孤岛(...) ──────}。
     *
     * <h3>为什么必须是「标题形状」而不是「注释里出现 [X]」（2026-11-13 修）</h3>
     * <p>原实现是 {@code comment.contains("[C]")} —— 于是<b>任何</b>提到组名的注释行
     * 都会切换当前分组。第一次踩到就是写 [E] 的说明文字时顺口引用了
     * 「连测试都不用的，那是 [A] 真死代码」，结果下一行的成员被归进了 [A]，
     * 守卫报「基线写的是 [A]，实际是 [C]」—— 报错信息与真实原因完全对不上，
     * 排查花了半轮。</p>
     *
     * <p>现在只认「{@code #} + 若干装饰横线 + {@code [X]}」这种标题形状。
     * 说明文字里随便提组名都不再有副作用。</p>
     */
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^\\s*#\\s*[\\u2500\\u2501\\-—=]{2,}\\s*\\[([ABCDE])\\]");

    /** 解析基线：按 {@code [A]/[B]/[C]/[D]/[E]} 分节标题分组。 */
    private static Map<String, Kind> parseBaseline(Path file) throws IOException {
        Map<String, Kind> out = new LinkedHashMap<>();
        Kind current = null;
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher header = SECTION_HEADER.matcher(rawLine);
            if (header.find()) {
                current = Kind.valueOf(header.group(1));
                continue;                                  // 标题行本身不是成员
            }
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);             // 注释只用来写说明，不参与分组
            }
            line = line.trim();
            if (line.isEmpty() || current == null) continue;
            out.put(line, current);
        }
        return out;
    }

    @Test
    void 死代码五组都只许减少不许新增且不许换组() throws IOException {
        Assumptions.assumeTrue(repoRoot != null, "找不到仓库根（settings.gradle），跳过");

        Path baselineFile = repoRoot.resolve("common").resolve("src").resolve("test")
                .resolve("resources").resolve("architecture").resolve("dead-code-baseline.txt");
        assertTrue(Files.isRegularFile(baselineFile), "缺死代码基线: " + baselineFile);

        Map<String, Kind> baseline = parseBaseline(baselineFile);
        Map<String, Kind> current = classify();
        assertTrue(baseline.size() > 10,
                "基线只解析出 " + baseline.size() + " 条 —— 分节标记 [A]/[B]/[C]/[D]/[E] 可能被删了");

        List<String> problems = new ArrayList<>();

        // ① 新增：实际零引用但基线里没有
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Kind> e : current.entrySet()) {
            if (!baseline.containsKey(e.getKey())) added.add(e.getKey() + "（" + e.getValue() + " 类）");
        }
        if (!added.isEmpty()) {
            problems.add("新增了零引用类型（ratchet：只许减少不许新增）：\n    "
                    + String.join("\n    ", added));
        }

        // ② 换组：两组都有但归属不同 —— 这是「把死代码挪进禁止删的组」的后门
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, Kind> e : current.entrySet()) {
            Kind declared = baseline.get(e.getKey());
            // [E] 的 actual 分类恒为 C（生产零引用 + 测试有引用），这是设计如此，
            // 不算「换组藏债」。真正的把关在断言 ④。
            if (declared == Kind.E && e.getValue() == Kind.C) continue;
            if (declared != null && declared != e.getValue()) {
                moved.add(e.getKey() + "  基线写的是 [" + declared + "]，实际是 [" + e.getValue() + "]");
            }
        }
        if (!moved.isEmpty()) {
            problems.add("零引用类型的归属和基线不一致（换组会把债务藏起来）：\n    "
                    + String.join("\n    ", moved));
        }

        // ③ [B] 的每个成员都必须真的是平台实现
        List<String> fakePlatforms = new ArrayList<>();
        for (Map.Entry<String, Kind> e : baseline.entrySet()) {
            if (e.getValue() != Kind.B) continue;
            Kind actual = current.get(e.getKey());
            if (actual == Kind.B) continue;                        // 已在 ② 里处理过
            if (actual == null) continue;                          // 不再是零引用，跳过
            fakePlatforms.add(e.getKey() + "（实际是 " + actual + " 类）");
        }
        if (!fakePlatforms.isEmpty()) {
            problems.add("这些类型被放在 [B]「平台实现，禁止删」里，但它们不是 "
                    + "@ExpectPlatform 的实现（类名不是 XxxImpl，或主类没声明 @ExpectPlatform）：\n    "
                    + String.join("\n    ", fakePlatforms)
                    + "\n    [B] 是「删了会崩」的保护区，不是藏死代码的地方。");
        }

        // ④ [E] 的每个成员都必须真的有测试引用 —— 否则它是 [A] 真死代码伪装的
        List<String> fakeFixtures = new ArrayList<>();
        for (Map.Entry<String, Kind> e : baseline.entrySet()) {
            if (e.getValue() != Kind.E) continue;
            Kind actual = current.get(e.getKey());
            if (actual == null) continue;                          // 不再是零引用，跳过
            if (actual != Kind.C) {
                fakeFixtures.add(e.getKey() + "（实际是 " + actual + " 类）");
            }
        }
        if (!fakeFixtures.isEmpty()) {
            problems.add("这些类型被放在 [E]「测试夹具，长期保留」里，但它们连测试都不引用：\n    "
                    + String.join("\n    ", fakeFixtures)
                    + "\n    [E] 要求「生产零引用 + 测试有引用」。连测试都不用的就是 [A] 真死代码，"
                    + "\n    应当删除而不是换个组藏起来。");
        }

        assertTrue(problems.isEmpty(),
                "死代码守卫失败：\n\n" + String.join("\n\n", problems)
                        + "\n\n基线文件：common/src/test/resources/architecture/dead-code-baseline.txt");

        // ④ 基线里已经不再是零引用的：提示可以删掉（不影响通过）
        List<String> stale = new ArrayList<>();
        for (String name : baseline.keySet()) {
            if (!current.containsKey(name)) stale.add(name);
        }
        if (!stale.isEmpty()) {
            System.out.println("DeadCodeGuardTest: 基线里有 " + stale.size()
                    + " 条已经不是零引用（可以删掉基线行）：" + stale);
        }

        // 按**基线归属**统计 —— 这才是「有几笔债」的真实分布。
        // 早先这里是按实际分类统计，于是 [C] 会把 [E] 测试夹具也算进去，
        // 输出「[C]测试孤岛=3」而基线里只有 2 条 —— 每次看日志都要重新推一遍。
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Kind k : baseline.values()) counts.merge(k, 1, Integer::sum);
        System.out.println("DeadCodeGuardTest: 基线 " + baseline.size()
                + " 条 / 实际零引用 " + current.size()
                + " 个 —— [A]真死=" + counts.getOrDefault(Kind.A, 0)
                + " [B]平台实现=" + counts.getOrDefault(Kind.B, 0)
                + " [C]测试孤岛(待办)=" + counts.getOrDefault(Kind.C, 0)
                + " [D]对外契约=" + counts.getOrDefault(Kind.D, 0)
                + " [E]测试夹具=" + counts.getOrDefault(Kind.E, 0));
    }
}