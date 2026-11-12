/* 文件职责：钉死架构约束，防止重构几年后烂回旧仓那个样子。 */
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构守卫。
 *
 * <h2>为什么必须有这个测试</h2>
 * <p>旧仓不是一开始就烂的 —— 它也是从干净结构开始的，三年后变成
 * {@code StructureAvoidanceService} 单类 4,766 行 / 173 个方法、
 * 22 个包里 6 个在说「路径/规划」谁也分不清。**单次重构一定会腐烂，
 * 因为没有任何机制阻止它重新腐烂。**</p>
 *
 * <p>所以这里用 <b>ratchet（棘轮）</b> 机制：把当前的违规集合记成基线，
 * 之后<b>只允许减少、不允许增加</b>。每清理一个，基线就往下调一格。</p>
 *
 * <h2>六条约束</h2>
 * <ol>
 *   <li>新架构包必须存在，且带中文 {@code package-info}</li>
 *   <li>层依赖必须单向（反向依赖直接红）</li>
 *   <li>有效代码行超过 800 的类必须属于基线集合（不许新增；注释不计）</li>
 *   <li>超过 60 个方法的类必须属于基线集合（不许新增）</li>
 *   <li>有「文件职责」头注的文件数不得少于基线</li>
 *   <li>{@code ARCHITECTURE.md} 必须列出每个新架构包的职责</li>
 * </ol>
 *
 * @see <a href="file:../../../../../../../ARCHITECTURE.md">ARCHITECTURE.md</a>
 */
class ArchitectureGuardTest {

    /** JavaExec 的工作目录是 common 模块，源码根在它下面。 */
    private static final Path SRC = Paths.get("src", "main", "java");
    private static final String ROOT_PKG = "net/shiroha233/roadweaver/";
    private static final Path ROOT = SRC.resolve(ROOT_PKG);

    /** 新架构的求解层包（本轮新建，未来所有新代码进这里）。 */
    private static final List<String> SOLVER_PACKAGES =
            List.of("metric", "topology", "band", "pipeline");

    /**
     * 层依赖白名单：包 -> 允许它 import 的内部包前缀。
     *
     * <p>不在这张表里的角色一律按「反向依赖」判红。</p>
     */
    private static final List<String[]> LAYER_RULES = List.of(
            // 包名（相对 roadweaver），允许依赖的内部包
            new String[]{"metric",   "foundation-core,api,util"},
            new String[]{"topology", "metric,foundation-core,api,util"},
            new String[]{"band",     "topology,foundation-core,api,util"},
            new String[]{"pipeline", "metric,topology,band,foundation-core,api,util"},
            // bridge 是适配器层：唯一同时认识 foundation 与求解层的地方。
            // 反向（求解层 import bridge）会被上面四条规则拦住。
            new String[]{"bridge", "metric,topology,band,pipeline,core,util,api,pathfinding,pathlogic,config"}
    );

    /**
     * 基线：有效代码行超过 <b>1000</b> 的文件（注释不计）（2026-11-12 用户硬约束：一个文件不得超过千行）。
     *
     * <p>这 17 个文件合计 32,257 行，是重构的<b>主要债务</b>。规则是 ratchet：
     * 只能从这里删，不许往这里加。拆完一个就从这里删掉一个，
     * 最终这个集合必须为空。</p>
     *
     * <p>拆分方向（按功能，不按行数均分）：</p>
     * <ul>
     *   <li>内部 record / enum / interface → 各自独立成文件（机械拆分，零风险）</li>
     *   <li>按职责分组的方法 → 拆成协作类（如「快照构建」/「避让查询」/「端点筛选」）</li>
     * </ul>
     */
    // 2026-11-12：client.map.RoadMapScreen（1244 行，原唯一超限界面类）已拆到 999 行，
    // 从此表移除。留这条注释是为了让「为什么界面类突然不在名单里」有据可查。
    private static final java.util.Set<String> BASELINE_OVER_1000 = java.util.Set.of(
    );

    /** 基线：超过 800 行的类（2026-11-12）。只许从这里删，不许往这里加。 */
    private static final Set<String> BASELINE_LONG_CLASSES = Set.of(
            "pathlogic.structures.StructureAvoidanceService",
            // 999 行：2026-11-12 已从 1244 拆到千行以内，但仍超 800 行的门槛
            "client.map.RoadMapScreen",
            "postprocess.RoadSnapService",
            "planning.terrain.AccurateGradeRepairPlanner",
            "planning.RoadPlanningService",
            "generation.InitialGenManager",
            "pathfinding.PathSpanExtractor",
            "pathlogic.pathcalc.RoadPathCalculator",
            "pathlogic.core.Road",
            "pathlogic.tunnel.RoadTunnelBuilder",
            "generation.RoadGenerationService",
            "pathlogic.grade.RoadGradePlanner",
            "pathlogic.junction.ExistingRoadJunctionService",
            "pathfinding.sampling.AccurateHeightSampler",
            "postprocess.PathPostProcessor",
            "route.editor.RouteEditorSession",
            "runtime.RuntimeLoadPolicy",
            "persistence.files.StructureFileStorage",
            "pathlogic.grade.RoadConstructionSafety",
            "route.editor.RouteMapRenderer",
            "pathfinding.sampling.opencl.OpenCLAccurateProgram",
            "route.RouteChunkPreloader",
            "runtime.ThreadPoolManager",
            "persistence.chunk.RoadChunkStore",
            "planning.RoadPlanningReadinessIndex",
            "generation.progress.InitialGenerationProgressTracker",
            "pathfinding.sampling.opencl.DensityGraphCompiler"
    );

    /** 基线：超过 60 个方法的类（2026-11-12）。只许减不许增。 */
    private static final Set<String> BASELINE_WIDE_CLASSES = Set.of(
            "pathlogic.structures.StructureAvoidanceService",
            "postprocess.RoadSnapService",
            "config.sub.PathfindingCostConfig",
            "config.sub.PlanningConfig",
            "planning.terrain.AccurateGradeRepairPlanner",
            "planning.RoadPlanningService",
            "runtime.ThreadPoolManager",
            "config.sub.PerformanceConfig",
            "pathlogic.tunnel.RoadTunnelBuilder",
            "persistence.files.StructureFileStorage",
            "generation.progress.InitialGenerationProgressTracker"
    );

    /**
     * 基线：有「文件职责」头注的文件数。
     *
     * <p>2026-11-12 设 172（当时共 461 个 java 文件）；到 2026-11-13 实际已达 <b>298 / 586</b>，
     * 基线却还停在 172 ⇒ ratchet 早已失效：文件数掉到 173 也能过检。
     * 现按实测抬到 298，之后只许升不许降。2026-11-13 又补 12 个最大的文件 ⇒ 320。</p>
     */
    private static final int BASELINE_DUTY_HEADERS = 320;

    /*
     * 匹配本仓内部 import —— 必须同时覆盖四种写法，少一种就等于给守卫开天窗：
     *   ① import net.x.Y;              （普通导入）
     *   ② import static net.x.Y.member;（静态导入单成员）
     *   ③ import static net.x.Y.*;     （静态通配）
     *   ④ import net.x.*;              （普通通配）
     *
     * 2026-11-13 修正：原正则用 [\w.]+ 直接接 ;，**匹配不到 ③④ 两种通配导入**，
     * 而全仓实测有 420 处通配 import（116 个文件、20%），来源前六全是重构时抽出的
     * pathlogic.structures.* —— 抽取时用 import static X.* 做同包协作，
     * 结果系统性地把守卫弄瞎了。教训：守卫自己的正则也要有负控。
     */
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(net\\.shiroha233\\.roadweaver\\.[\\w.]+?)(?:\\.\\*)?\\s*;",
            Pattern.MULTILINE);

    private static final Pattern METHOD_DECL = Pattern.compile(
            "^\\s+(?:public|private|protected)\\s+(?:static\\s+)?[\\w<>\\[\\],.?\\s]+\\s+\\w+\\s*\\(",
            Pattern.MULTILINE);

    // ── 约束 1：新架构包存在且带中文职责 ──────────────────────────

    @Test
    void 新架构包必须存在且带中文职责说明() throws IOException {
        assertTrue(Files.isDirectory(ROOT),
                "源码根不存在: " + ROOT.toAbsolutePath() + "（工作目录应为 common 模块）");
        List<String> problems = new ArrayList<>();
        for (String pkg : SOLVER_PACKAGES) {
            Path info = ROOT.resolve(pkg).resolve("package-info.java");
            if (!Files.isRegularFile(info)) {
                problems.add(pkg + "：缺 package-info.java");
                continue;
            }
            String text = Files.readString(info, StandardCharsets.UTF_8);
            if (!text.contains("文件职责")) {
                problems.add(pkg + "：package-info 缺「文件职责」头注");
            }
            if (!text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF)) {
                problems.add(pkg + "：package-info 没有中文说明（本仓要求完整中文注释）");
            }
        }
        assertTrue(problems.isEmpty(),
                "新架构包不合规：\n  " + String.join("\n  ", problems));
    }

    // ── 约束 2：层依赖单向 ────────────────────────────────────────

    @Test
    void 层依赖必须单向() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String[] rule : LAYER_RULES) {
            String pkg = rule[0];
            Set<String> allowed = new LinkedHashSet<>(List.of(rule[1].split(",")));
            Path dir = ROOT.resolve(pkg);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    Matcher m = IMPORT.matcher(text);
                    while (m.find()) {
                        String fqn = m.group(1);
                        String rest = fqn.substring("net.shiroha233.roadweaver.".length());
                        String target = rest.contains(".")
                                ? rest.substring(0, rest.indexOf('.'))
                                : rest;
                        if (target.equals(pkg)) continue;
                        if (!allowed.contains(target)) {
                            violations.add(pkg + " -> " + fqn
                                    + "   (" + SRC.relativize(f) + ")");
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "层依赖必须单向（ARCHITECTURE.md 的依赖表）。反向依赖违反项:\n  "
                        + String.join("\n  ", violations));
    }


    // ── 约束 2b：28 个包的跨包依赖 ratchet（2026-11-13 补强）──────────

    /**
     * 跨包依赖基线文件。
     *
     * <p>2026-11-13 之前，守卫的分层规则**只覆盖 metric/topology/band/pipeline/bridge
     * 五个包**，其余 23 个包零约束 —— 实测长出 39 对双向依赖而无人报警。
     * 现在把全部 28 个包都纳入 ratchet：<b>现有的 157 条依赖边是基线，只许缩小不许新增</b>。</p>
     *
     * <p>为什么不是一次性收紧成硬规则：现有边里就藏着真违规（例如
     * {@code network -> client} 9 处、{@code config -> client} 1 处），
     * 一次性硬卡会直接把构建打红、逼人绕过守卫。ratchet 是唯一可持续的做法 ——
     * 它承认债务存在，但保证债务不会变大。</p>
     *
     * <p>要消除某条边：改代码，然后把该行从基线文件里删掉。</p>
     */
    private static final Path DEPS_BASELINE =
            Paths.get("src", "test", "resources", "architecture", "deps-baseline.txt");

    /** 已知「服务端包 import 客户端」的债务：只许减少，不许新增。 */
    private static final java.util.Map<String, Integer> CLIENT_LEAK_BASELINE = java.util.Map.of(
            "config", 1,
            "map", 1,
            "network", 9,
            "route", 2);

    /** 扫出全部「源包 -> 目标包」的依赖边及出现次数。 */
    private static java.util.Map<String, Integer> crossPackageEdges() throws IOException {
        java.util.Map<String, Integer> edges = new java.util.TreeMap<>();
        for (Path f : allJavaFiles()) {
            // 必须相对 ROOT（src/main/java/net/shiroha233/roadweaver）而不是 SRC ——
            // 否则首段会算成仓库根包名 net，得到 net -> core 这种荒谬的边。
            String rel = ROOT.relativize(f).toString();
            // src/main/java 下还有不属于本模组命名空间的目录（如 architectury 的 dev/），
            // 它们会 relativize 成 ..\dev\… —— 不是我们的包，跳过。
            if (rel.startsWith("..")) continue;
            String srcPkg = rel.contains(java.io.File.separator)
                    ? rel.substring(0, rel.indexOf(java.io.File.separator))
                    : "(root)";
            Matcher m = IMPORT.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                String rest = m.group(1).substring("net.shiroha233.roadweaver.".length());
                String dstPkg = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
                if (dstPkg.isEmpty() || dstPkg.equals(srcPkg)) continue;
                edges.merge(srcPkg + " -> " + dstPkg, 1, Integer::sum);
            }
        }
        return edges;
    }

    @Test
    void 跨包依赖不许新增() throws IOException {
        assertTrue(Files.isRegularFile(DEPS_BASELINE),
                "缺跨包依赖基线文件: " + DEPS_BASELINE.toAbsolutePath());
        java.util.Set<String> baseline = new java.util.LinkedHashSet<>();
        for (String line : Files.readAllLines(DEPS_BASELINE, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            baseline.add(s.replaceAll("\\s+\\d+$", ""));
        }
        java.util.Map<String, Integer> current = crossPackageEdges();
        List<String> added = new ArrayList<>();
        for (String edge : current.keySet()) {
            if (!baseline.contains(edge)) added.add(edge + "  (" + current.get(edge) + " 处)");
        }
        assertTrue(added.isEmpty(),
                "新增了不允许的跨包依赖（28 个包的依赖图是 ratchet，只许缩小）：\n  "
                        + String.join("\n  ", added)
                        + "\n如确属必要，请先把该边加入 "
                        + "common/src/test/resources/architecture/deps-baseline.txt 并说明理由。");
    }

    @Test
    void 服务端包不得依赖客户端() throws IOException {
        List<String> problems = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : crossPackageEdges().entrySet()) {
            String[] parts = e.getKey().split(" -> ");
            if (!"client".equals(parts[1]) || "client".equals(parts[0])) continue;
            Integer allowed = CLIENT_LEAK_BASELINE.get(parts[0]);
            if (allowed == null) {
                problems.add(parts[0] + " -> client（新增，客户端类不该出现在服务端包里，"
                        + e.getValue() + " 处）");
            } else if (e.getValue() > allowed) {
                problems.add(parts[0] + " -> client（" + e.getValue() + " 处 > 基线 " + allowed + "）");
            }
        }
        assertTrue(problems.isEmpty(),
                "服务端包不得依赖客户端类（专用服务器上这些类不存在）。\n  "
                        + String.join("\n  ", problems)
                        + "\n若只是 DTO，请把它搬到中立包（如 core.model）。");

        // 反向也要守：债务只许减少 —— 减少了就顺手把基线数字改小。
        for (java.util.Map.Entry<String, Integer> e : CLIENT_LEAK_BASELINE.entrySet()) {
            int now = crossPackageEdges().getOrDefault(e.getKey() + " -> client", 0);
            assertTrue(now <= e.getValue(),
                    "基线数字小于实际：" + e.getKey() + " 现在 " + now + " 处 > 基线 " + e.getValue());
        }
    }

    // ── 约束 3 / 4：类规模 ratchet ───────────────────────────────

    @Test
    void 一个文件不得超过一千行() throws IOException {
        List<String> overLimit = new ArrayList<>();
        for (Path f : allJavaFiles()) {
            int lines = effectiveCodeLines(f);
            if (lines <= 1000) continue;
            String name = className(f);
            if (!BASELINE_OVER_1000.contains(name)) {
                overLimit.add(name + " (" + lines + " 行)");
            }
        }
        assertTrue(overLimit.isEmpty(),
                "【硬约束】一个文件的有效代码行不得超过 1000（注释不计）。新增超限文件必须拆分。\n"
                        + "新超限文件:\n  " + String.join("\n  ", overLimit)
                        + "\n（当前基线 " + BASELINE_OVER_1000.size()
                        + " 个文件超限，拆完一个就从 BASELINE_OVER_1000 删掉一个）");
    }

    @Test
    void 超过八百行的类不许新增() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> notInBaseline = new ArrayList<>();
        for (Path f : allJavaFiles()) {
            int lines = effectiveCodeLines(f);
            if (lines <= 800) continue;
            String name = className(f);
            found.add(name + " (" + lines + " 行)");
            if (!BASELINE_LONG_CLASSES.contains(name)) notInBaseline.add(name + " (" + lines + " 行)");
        }
        assertTrue(notInBaseline.isEmpty(),
                "新增了超过 800 行的类。请拆分（单类职责单一，建议 ≤400 行）。\n"
                        + "新超限类:\n  " + String.join("\n  ", notInBaseline)
                        + "\n当前超限 " + found.size() + " 个（基线 "
                        + BASELINE_LONG_CLASSES.size() + " 个）。"
                        + "清理掉一个后，把基线里对应名字删掉。");
    }

    @Test
    void 超过六十个方法的类不许新增() throws IOException {
        List<String> notInBaseline = new ArrayList<>();
        for (Path f : allJavaFiles()) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            int count = 0;
            Matcher m = METHOD_DECL.matcher(text);
            while (m.find()) count++;
            if (count <= 60) continue;
            String name = className(f);
            if (!BASELINE_WIDE_CLASSES.contains(name)) {
                notInBaseline.add(name + " (" + count + " 方法)");
            }
        }
        assertTrue(notInBaseline.isEmpty(),
                "新增了超过 60 个方法的类 —— 典型上帝类，必须拆。\n  "
                        + String.join("\n  ", notInBaseline));
    }

    // ── 约束 5：中文「文件职责」头注 ratchet ─────────────────────

    @Test
    void 文件职责头注数量不得下降() throws IOException {
        int total = 0;
        int withHeader = 0;
        for (Path f : allJavaFiles()) {
            total++;
            List<String> head = Files.readAllLines(f, StandardCharsets.UTF_8)
                    .subList(0, Math.min(3,
                            Files.readAllLines(f, StandardCharsets.UTF_8).size()));
            if (String.join(" ", head).contains("文件职责")) withHeader++;
        }
        assertTrue(withHeader >= BASELINE_DUTY_HEADERS,
                "带「文件职责」头注的文件从 " + BASELINE_DUTY_HEADERS
                        + " 降到 " + withHeader + " / " + total
                        + "。本仓要求每个 .java 首行写：\n"
                        + "  /* 文件职责：<做什么、不做什么>—— 一句话。 */");
    }

    // ── 约束 6：文档与代码同源 ───────────────────────────────────

    @Test
    void 架构文档必须说明每个新架构包() throws IOException {
        Path doc = Paths.get("..", "ARCHITECTURE.md");
        assertTrue(Files.isRegularFile(doc),
                "ARCHITECTURE.md 不存在（工作目录应为 common 模块，往上一级找）: "
                        + doc.toAbsolutePath());
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String pkg : SOLVER_PACKAGES) {
            if (!text.contains(pkg)) missing.add(pkg);
        }
        assertTrue(missing.isEmpty(),
                "ARCHITECTURE.md 缺这些新架构包的职责说明（文档与代码必须同源）: "
                        + String.join(", ", missing));
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private static List<Path> allJavaFiles() throws IOException {
        assertTrue(Files.isDirectory(SRC),
                "源码根不存在: " + SRC.toAbsolutePath() + "（工作目录应为 common 模块）");
        try (Stream<Path> files = Files.walk(SRC)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** 把 apps 的绝对路径转成 {@code pathlogic.structures.Xxx} 这种包名+类名。 */
    private static String className(Path f) {
        String rel = SRC.relativize(f).toString().replace('\\', '/');
        String pkg = rel.substring(0, rel.lastIndexOf('/')).replace('/', '.');
        String cls = rel.substring(rel.lastIndexOf('/') + 1);
        cls = cls.substring(0, cls.length() - ".java".length());
        String prefix = "net.shiroha233.roadweaver.";
        return pkg.startsWith(prefix) ? pkg.substring(prefix.length()) + "." + cls : pkg + "." + cls;
    }
    /**
     * 统计一个 Java 文件的<b>有效代码行</b>：剥掉注释、空行之后再数。
     *
     * <h2>为什么不用物理行数</h2>
     * <p>用户 2026-11-13 明确要求「注释不算代码行数」。注释写多少都不该顶着千行上限 ——
     * 否则「把代码讲清楚」和「守住文件体积」会互相打架，最后一定是注释先被牺牲。</p>
     *
     * <h2>★ 必须真正解析注释，不能只看行首</h2>
     * <p>一行里既有代码又有行注释（{@code int a = 1; // 说明}）非常常见，
     * 按「行首是不是 //」来判会把它整行算成注释，等于漏算代码。
     * 同理 {@code String url = "http://x";} 里的 {@code //} 是字符串内容不是注释。</p>
     *
     * <p>所以这里做一次逐字符扫描：跟踪块注释/行注释/字符串/字符字面量四种状态，
     * 只有落在注释里的字符不算，最后按「该行是否出现过真实代码字符」计数。</p>
     */
    private static int effectiveCodeLines(java.nio.file.Path file) throws java.io.IOException {
        String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        int count = 0;
        boolean inBlock = false;
        boolean inLine = false;
        boolean sawCode = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (inLine) {
                if (ch == '\n') {
                    inLine = false;
                    if (sawCode) { count++; sawCode = false; }
                }
                continue;
            }
            if (inBlock) {
                if (ch == '*' && next == '/') { inBlock = false; i++; }
                else if (ch == '\n' && sawCode) { count++; sawCode = false; }
                continue;
            }
            if (ch == '/' && next == '/') { inLine = true; i++; continue; }
            if (ch == '/' && next == '*') { inBlock = true; i++; continue; }
            if (ch == '"' || ch == '\'') {
                sawCode = true;
                char quote = ch;
                i++;
                while (i < text.length()) {
                    char d = text.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    if (d == quote || d == '\n') { break; }
                    i++;
                }
                continue;
            }
            if (ch == '\n') {
                if (sawCode) { count++; sawCode = false; }
            } else if (!Character.isWhitespace(ch)) {
                sawCode = true;
            }
        }
        if (sawCode) count++;
        return count;
    }
}
