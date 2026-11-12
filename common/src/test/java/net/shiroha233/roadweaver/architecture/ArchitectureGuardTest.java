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
 *   <li>超过 800 行的类必须属于基线集合（不许新增）</li>
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
            new String[]{"pipeline", "metric,topology,band,foundation-core,api,util"}
    );

    /**
     * 基线：超过 <b>1000 行</b>的文件（2026-11-12 用户硬约束：一个文件不得超过千行）。
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
    private static final java.util.Set<String> BASELINE_OVER_1000 = java.util.Set.of(
            "pathlogic.structures.StructureAvoidanceService",
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
            "client.map.RoadMapScreen",
            "pathlogic.junction.ExistingRoadJunctionService",
            "pathfinding.sampling.AccurateHeightSampler",
            "postprocess.PathPostProcessor"
    );

    /** 基线：超过 800 行的类（2026-11-12）。只许从这里删，不许往这里加。 */
    private static final Set<String> BASELINE_LONG_CLASSES = Set.of(
            "pathlogic.structures.StructureAvoidanceService",
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
            "client.map.RoadMapScreen",
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

    /** 基线：有「文件职责」头注的文件数（2026-11-12，共 461 个 java 文件里的 172 个）。 */
    private static final int BASELINE_DUTY_HEADERS = 172;

    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(net\\.shiroha233\\.roadweaver\\.[\\w.]+)\\s*;",
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

    // ── 约束 3 / 4：类规模 ratchet ───────────────────────────────

    @Test
    void 一个文件不得超过一千行() throws IOException {
        List<String> overLimit = new ArrayList<>();
        for (Path f : allJavaFiles()) {
            int lines = Files.readAllLines(f, StandardCharsets.UTF_8).size();
            if (lines <= 1000) continue;
            String name = className(f);
            if (!BASELINE_OVER_1000.contains(name)) {
                overLimit.add(name + " (" + lines + " 行)");
            }
        }
        assertTrue(overLimit.isEmpty(),
                "【硬约束】一个文件不得超过 1000 行。新增超限文件必须拆分。\n"
                        + "新超限文件:\n  " + String.join("\n  ", overLimit)
                        + "\n（当前基线 " + BASELINE_OVER_1000.size()
                        + " 个文件超限，拆完一个就从 BASELINE_OVER_1000 删掉一个）");
    }

    @Test
    void 超过八百行的类不许新增() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> notInBaseline = new ArrayList<>();
        for (Path f : allJavaFiles()) {
            int lines = Files.readAllLines(f, StandardCharsets.UTF_8).size();
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
}
