/* 文件职责：守卫的前置条件守卫 —— 防止任何一个架构守卫因为「找不到根」而静默跳过。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫的前置条件守卫（2026-11-15 新增，批 7「补守卫」）。
 *
 * <h2>它要解决什么问题</h2>
 * <p>{@code architecture/} 下 16 个守卫类里有 <b>7 个</b>用
 * {@code Assumptions.assumeTrue(root != null, "找不到源码根，跳过")}。
 * <b>被 assume 跳过的测试，在 JUnit 里算「通过」</b> —— 也就是说：
 * 一旦根解析失效（工作目录变了、Gradle 换了 test 工作目录、仓库被移动），
 * 这 7 个守卫会<b>全部静默变成绿色</b>，而构建输出只说 `PASS tests=N`，
 * 没有任何一行提示「守卫其实没跑」。</p>
 *
 * <p>风险有多大：{@code UiFeatureGuardTest}（6 个用例）、
 * {@code S5MethodMapTest}（2 个）、{@code SingleSourceOfTruthTest}（2 个）
 * 的<b>全部</b>用例都可以被跳过；{@code DeadCodeGuardTest}、
 * {@code LegacyLedgerTest}、{@code ConfigUiHonestyTest}、
 * {@code ConfigStatusHonestyTest}、{@code PostprocessLedgerTest}、
 * {@code MaintainabilityReportTest} 各有一个。</p>
 *
 * <h2>这个守卫做什么</h2>
 * <ol>
 *   <li><b>用与各守卫完全相同的方式解析根</b>（从 {@code user.dir} 向上找
 *       {@code settings.gradle}），并断言解析成功；</li>
 *   <li>断言<b>每个守卫依赖的具体文件/目录都存在</b> —— 少一个就说明某个守卫会跳过；</li>
 *   <li>断言根解析「向上走几层」在合理范围内（防止误把某个子目录当成根）。</li>
 * </ol>
 *
 * <h2>它<b>不</b>能做什么</h2>
 * <p>它证明的是「前置条件成立、所以那些守卫会跑」，<b>不是</b>「那些守卫的判据本身有效」。
 * 后者只能靠给每个守卫做负控（本项目已经踩过三次「守卫看起来在守、其实抓不到」：
 * 通配 import 让依赖守卫失明、按 {@code \n} 切行让千行守卫少算 46%、
 * 「至少一处」的宽松断言让负控仍绿）。</p>
 */
class GuardPreconditionTest {

    /** 从 {@code user.dir} 向上找 {@code settings.gradle} —— 与各守卫同一算法。 */
    private static Path resolveRepoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int up = 0; up < 8 && dir != null; up++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    @Test
    void 仓库根必须能被解析到() {
        Path root = resolveRepoRoot();
        assertTrue(root != null,
                "从 user.dir=" + System.getProperty("user.dir")
                        + " 向上 8 层都没找到 settings.gradle。\n"
                        + "★★ 这意味着 architecture/ 下 7 个用 Assumptions 的守卫会**全部静默跳过**，"
                        + "而构建仍报 PASS。请先修根解析（通常是 Gradle test 的工作目录被改）");
        assertTrue(Files.isRegularFile(root.resolve("settings.gradle")),
                "解析到的根里没有 settings.gradle: " + root);
    }

    @Test
    void 每个守卫依赖的文件都必须存在() throws IOException {
        Path root = resolveRepoRoot();
        assertTrue(root != null, "根解析失败，先看上一个用例");

        // 每个条目 = 一个守卫会读的东西。少任何一个，对应守卫就会跳过或读不到基线。
        Map<String, String> required = new LinkedHashMap<>();
        required.put("settings.gradle", "所有守卫的根标记");
        required.put("REFACTOR.md", "LegacyLedgerTest（台账）+ S5MethodMapTest");
        required.put("docs/S5-METHOD-MAP.md", "S5MethodMapTest");
        required.put("docs/S5-POSTPROCESS-LEDGER.md", "PostprocessLedgerTest");
        required.put("common/src/main/java/net/shiroha233/roadweaver", "所有源码扫描类守卫");
        required.put("common/src/test/java/net/shiroha233/roadweaver", "回归登记守卫");
        required.put("common/src/test/resources/architecture/dead-code-baseline.txt", "DeadCodeGuardTest");
        required.put("common/src/test/resources/architecture/deps-baseline.txt", "ArchitectureGuardTest");
        required.put("common/src/test/resources/architecture/bidirectional-baseline.txt", "ArchitectureGuardTest");
        required.put("common/src/test/resources/architecture/duty-header-baseline.txt", "DutyHeaderGuardTest");
        required.put("common/src/test/resources/architecture/recompute-sites-baseline.txt", "S5MethodMapTest");

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : required.entrySet()) {
            Path p = root.resolve(e.getKey().replace('/', java.io.File.separatorChar));
            if (!Files.exists(p)) missing.add(e.getKey() + "   ← 被 " + e.getValue() + " 依赖");
        }
        assertTrue(missing.isEmpty(),
                "以下守卫依赖的路径不存在 —— 对应守卫会静默跳过或读到空基线，"
                        + "而构建仍报 PASS：\n  " + String.join("\n  ", missing));
    }

    @Test
    void 基线目录里不许出现未被任何守卫使用的孤儿文件() throws IOException {
        Path root = resolveRepoRoot();
        assertTrue(root != null, "根解析失败");
        Path dir = root.resolve("common/src/test/resources/architecture");
        assertTrue(Files.isDirectory(dir), "基线目录不存在: " + dir);

        // 收集所有守卫源码文本，用来判断某个基线文件是否被引用
        Path guards = root.resolve("common/src/test/java/net/shiroha233/roadweaver/architecture");
        StringBuilder corpus = new StringBuilder();
        try (Stream<Path> s = Files.walk(guards)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                corpus.append(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8)).append('\n');
            }
        }
        List<String> orphans = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (!corpus.toString().contains(name)) orphans.add(name);
            }
        }
        assertTrue(orphans.isEmpty(),
                "基线目录里这些文件没有被任何守卫引用（孤儿基线 = 曾经有守卫、现在没了，"
                        + "或者文件名写错了导致守卫读到空）：\n  " + String.join("\n  ", orphans));
    }

    @Test
    void 守卫目录下的类都必须被回归套件登记() throws IOException {
        // 守卫本身也要能被跑到 —— RegressionRegistrationTest 管全仓，这里只钉 architecture 包，
        // 因为「守守卫没跑」和「守卫没守」是两件事，后者更隐蔽。
        Path root = resolveRepoRoot();
        assertTrue(root != null, "根解析失败");
        Path suite = root.resolve("common/src/test/java/net/shiroha233/roadweaver/RoadJUnitRegressionSuite.java");
        assertTrue(Files.isRegularFile(suite), "找不到回归套件: " + suite);
        String suiteText = Files.readString(suite, java.nio.charset.StandardCharsets.UTF_8);

        Path guards = root.resolve("common/src/test/java/net/shiroha233/roadweaver/architecture");
        List<String> unregistered = new ArrayList<>();
        try (Stream<Path> s = Files.walk(guards)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString().replace(".java", "");
                String fqcn = "net.shiroha233.roadweaver.architecture." + name;
                if (!suiteText.contains(fqcn)) unregistered.add(name);
            }
        }
        assertTrue(unregistered.isEmpty(),
                "这些守卫类没有登记进 RoadJUnitRegressionSuite（套件按 FQCN 登记、"
                        + "没有包扫描 ⇒ 没登记的守卫永远不会跑）：\n  " + String.join("\n  ", unregistered));
    }
}