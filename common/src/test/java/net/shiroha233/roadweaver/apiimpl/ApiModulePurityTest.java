package net.shiroha233.roadweaver.apiimpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「api 模块与实现物理分离」这条硬约束。
 *
 * <h2>为什么必须有这个测试</h2>
 * <p>{@code roadweaver-api} 是<b>独立部署</b>的 mod，会被其它 mod 在编译期依赖。
 * 一旦它偷偷 import 了 RoadWeaver 的内部类，就会出现两种后果：</p>
 * <ol>
 *   <li>RoadWeaver 不装时 api 模块加载即失败（{@code NoClassDefFoundError}）；</li>
 *   <li>更隐蔽：RoadWeaver 重构时把那个内部类移走，api 模块编译就断 ——
 *       而外部 mod 会先一步断，且它们不知道该怪谁。</li>
 * </ol>
 *
 * <p>历史上 {@code net.shiroha233.roadweaver.api.interop} 就是契约与实现放在一起，
 * 0.8.406 删实现时把契约一起删了，五个外部 mod 静默失效。这条测试是那次事故的钉子。</p>
 *
 * <p><b>唯一允许的例外</b>：{@code net.shiroha233.roadweaver.api.*} 自身
 * （即契约之间互相引用）。</p>
 */
class ApiModulePurityTest {

    /** api 模块源码根。JavaExec 的工作目录是 common 模块，所以往上一级找。 */
    private static final Path API_SRC = Paths.get("..", "api", "src", "main", "java");

    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(net\\.shiroha233\\.roadweaver\\.[\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final String ALLOWED_PREFIX = "net.shiroha233.roadweaver.api.";

    @Test
    void apiModuleMustNotReferenceInternalPackages() throws IOException {
        assertTrue(Files.isDirectory(API_SRC),
                "api 模块源码目录不存在: " + API_SRC.toAbsolutePath()
                        + "（工作目录应为 common 模块）");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(API_SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String fqn : scanNonApiImports(text)) {
                    violations.add(API_SRC.relativize(f) + " -> " + fqn);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "roadweaver-api 模块禁止 import 道路内部包（只允许 net.shiroha233.roadweaver.api.*）。\n"
                        + "违反项:\n  " + String.join("\n  ", violations));
    }

    @Test
    void apiModuleSourceExists() {
        assertTrue(Files.isDirectory(API_SRC), "api 模块源码目录不存在");
        Path main = API_SRC.resolve("net/shiroha233/roadweaver/api/RoadWeaverApi.java");
        assertTrue(Files.isRegularFile(main), "RoadWeaverApi.java 必须存在（它是冻死的契约入口）");
    }

    /**
     * 自证：扫描器确实能抓到违规。
     *
     * <p>为什么需要这条：{@code :api:compileJava} 会因为 api 模块没有 common 依赖而先一步失败，
     * 所以上面那条测试在真实违规下<b>根本没机会执行</b> —— 一个从不失败的测试是陷阱，
     * 正是 {@code api.interop} 那次事故的形态。这里用合成源码证明扫描器本身有效。</p>
     */
    @Test
    void scannerDetectsViolationInSyntheticSource() {
        String bad = "package net.shiroha233.roadweaver.api.incoming;\n"
                + "import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation;\n";
        assertEquals(List.of("net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation"),
                scanNonApiImports(bad),
                "扫描器必须能识别出内部包 import");

        String good = "package net.shiroha233.roadweaver.api;\n"
                + "import net.shiroha233.roadweaver.api.incoming.ReadinessState;\n"
                + "import java.util.List;\n";
        assertTrue(scanNonApiImports(good).isEmpty(),
                "api 内部互引与 JDK import 不应被误报");

        // 静态 import 也要能抓到
        String staticBad = "import static net.shiroha233.roadweaver.core.Constants.X;\n";
        assertEquals(1, scanNonApiImports(staticBad).size(), "静态 import 也必须被抓到");
    }

    /** 提取一段源码里所有指向「道路内部包」的 import 全限定名。 */
    private static List<String> scanNonApiImports(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            String fqn = m.group(1);
            if (!fqn.startsWith(ALLOWED_PREFIX)) {
                out.add(fqn);
            }
        }
        return out;
    }
}
