/* 文件职责：死代码守卫 —— 全模块扫描零引用类型，只许减少不许新增。 */
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
 * <h2>判定口径（刻意保守）</h2>
 * <p>类型名出现在<b>全部模块（common / fabric / api / api-fabric）的 Java 正文</b>
 * 或<b>全部资源文件</b>（json / properties / toml / txt / cfg / yaml）里就算「活着」。
 * 宁可漏判，不可误杀 —— 资源里可能藏着注册表、mixin 配置、entrypoint、服务加载器声明。</p>
 *
 * <h2>★ 两类必须区分</h2>
 * <ul>
 *   <li><b>[A] 真死代码</b>：删掉不会有任何影响（删完请把基线里那行也删了）；</li>
 *   <li><b>[B] 平台实现</b>：Architectury 的 {@code @ExpectPlatform} 由平台在运行时分发，
 *       Java 侧本来就没有静态引用 —— <b>删了会直接崩</b>。基线里单独分组并标注禁止删。</li>
 * </ul>
 *
 * <p>守卫只做一件事：<b>不许新增</b>零引用类型（ratchet）。已有债务照实记录，慢慢还。</p>
 */
public final class DeadCodeGuardTest {

    /** 顶层类型声明（列 0）—— 不含嵌套类，嵌套类生命周期跟随外层。 */
    private static final Pattern TOP_LEVEL_DECL = Pattern.compile(
            "^(?:public |final |abstract |sealed |non-sealed )*(?:class|interface|enum|record)\\s+(\\w+)",
            Pattern.MULTILINE);

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

    private static List<Path> mainJava() {
        List<Path> out = new ArrayList<>();
        for (String m : MODULES) {
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

    private static boolean isTextResource(String name) {
        return name.endsWith(".json") || name.endsWith(".properties") || name.endsWith(".toml")
                || name.endsWith(".txt") || name.endsWith(".cfg")
                || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String resourceCorpus() {
        StringBuilder sb = new StringBuilder();
        for (String m : MODULES) {
            Path d = repoRoot.resolve(m).resolve("src").resolve("main").resolve("resources");
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    if (!isTextResource(p.getFileName().toString())) continue;
                    sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.toString();
    }

    @Test
    void 死代码只许减少不许新增() throws IOException {
        Assumptions.assumeTrue(repoRoot != null, "找不到仓库根（settings.gradle），跳过");

        List<Path> files = mainJava();
        StringBuilder corpus = new StringBuilder();
        TreeMap<Path, String> texts = new TreeMap<>();
        for (Path p : files) {
            String t = Files.readString(p, StandardCharsets.UTF_8);
            texts.put(p, t);
            corpus.append(t).append('\n');
        }
        String all = corpus + resourceCorpus();

        Set<String> current = new LinkedHashSet<>();
        for (var e : texts.entrySet()) {
            Matcher m = TOP_LEVEL_DECL.matcher(e.getValue());
            while (m.find()) {
                String name = m.group(1);
                Pattern ref = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
                long total = ref.matcher(all).results().count();
                long self = ref.matcher(e.getValue()).results().count();
                if (total - self <= 0) current.add(name);
            }
        }

        Path baselineFile = repoRoot.resolve("common").resolve("src").resolve("test")
                .resolve("resources").resolve("architecture").resolve("dead-code-baseline.txt");
        assertTrue(Files.isRegularFile(baselineFile), "缺死代码基线: " + baselineFile);

        Set<String> baseline = new LinkedHashSet<>();
        Set<String> platformImpls = new LinkedHashSet<>();
        boolean inPlatformSection = false;
        for (String raw : Files.readAllLines(baselineFile, StandardCharsets.UTF_8)) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                if (line.contains("[B]")) inPlatformSection = true;
                if (line.contains("[A]")) inPlatformSection = false;
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            baseline.add(line);
            if (inPlatformSection) platformImpls.add(line);
        }

        List<String> added = new ArrayList<>();
        for (String name : current) {
            if (!baseline.contains(name)) added.add(name);
        }
        assertTrue(added.isEmpty(),
                "新增了零引用类型（死代码 ratchet：只许减少不许新增）：\n  "
                        + String.join("\n  ", added)
                        + "\n如果是「切出来但暂时没人调」，请先在代码里接上调用；"
                        + "确实无用的请直接删掉，不要往基线里加。");

        List<String> stale = new ArrayList<>();
        for (String name : baseline) {
            if (!current.contains(name)) stale.add(name);
        }
        if (!stale.isEmpty()) {
            System.out.println("DeadCodeGuardTest: 基线里有 " + stale.size()
                    + " 条已经不再是零引用（可以删掉基线行）：" + stale);
        }
        System.out.println("DeadCodeGuardTest: 当前零引用类型 " + current.size()
                + " 个，其中平台实现 " + platformImpls.size() + " 个（禁止删）");
    }
}