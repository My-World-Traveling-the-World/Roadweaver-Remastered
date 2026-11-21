/* 文件职责：守卫配置元数据标注的 UNWIRED/DEPRECATED 状态与代码真实读取点一致。 */
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

import net.shiroha233.roadweaver.config.meta.ConfigKey;
import net.shiroha233.roadweaver.config.meta.ConfigKeyTable;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：{@code config/meta} 里标成 UNWIRED / DEPRECATED 的键，代码里必须真的没有读取点。
 *
 * <p><b>为什么需要这条（2026-11-13 实测）</b></p>
 *
 * <p>{@code config/meta} 那套元数据（16 文件 / 2053 行）会给每个配置键标一个
 * {@code ConfigStatus}，其中 {@code UNWIRED} 表示「有字段但代码不读，改了无效」，
 * {@code DEPRECATED} 表示「代码里不再读取」。这两个标注的价值全在于准确 ——
 * 玩家或维护者看到「未接线」就不该去调它。而它们没有任何机制保证和代码同步，
 * 实测已经漂了：</p>
 *
 * <ul>
 *   <li>{@code minWaterDepth} —— 元数据标 UNWIRED，注释写「真实水深在 7 处硬编码为 1」；
 *       实际有 <b>4 处真的读配置</b>（参考 {@code CrossingNegotiation}、
 *       {@code RawPathSearch} 两处、{@code PathPostProcessor}）。
 *       那条注释来自 O-11 的早期分析，代码后来改了、注释没跟上。</li>
 *   <li>{@code landTunnelEnabled} —— 元数据标 UNWIRED，注释写「假开关，赋值是 oldBuried」；
 *       实际<b>真的参与判定</b>（{@code boolean buried = landTunnelSwitch && newBuried;}）。
 *       旧注释描述的是 0.9.005-B 时期的状态。</li>
 * </ul>
 *
 * <p>这类漂移最危险的方向是「把能用的功能说成不能用」—— 玩家因此永远不去调一个
 * 其实有效的键。反过来（把不能用的说成能用）由 {@code ConfigKnobHonestyTest}
 * 在 lang tooltip 层面盯着。</p>
 *
 * <p><b>它怎么判定「有读取点」</b>：在 common / fabric 的 {@code src/main/java} 里搜
 * {@code .字段名(}。这个口径刻意粗 —— 只要有人调用这个存取器就算「接线了」。
 * 好处是简单可靠、不会漏；代价是把「读了但没用」也算接线（例如只进日志的那个键）。
 * 所以本守卫只把「有读取点」当作<b>要求复核</b>的信号，失败信息里会让人自己确认
 * 该改标注还是该删读取点。那份手写的精确死键名单在 {@code ConfigKnobHonestyTest}，
 * 两者互补。</p>
 */
public final class ConfigStatusHonestyTest {

    /** 从 meta 里抓一条 ConfigKey 定义的开头，group(1) 是键名。 */
    private static final Pattern KEY_ENTRY = Pattern.compile(
            "ConfigKey\\.(?:integer|decimal|bool|text)\\(\\s*\"(\\w+)\"");

    /** 抓状态标注。 */
    private static final Pattern STATUS = Pattern.compile("ConfigStatus\\.(\\w+)");

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

    /** 列出某模块某源码目录下的全部 .java。 */
    private static List<Path> javaFiles(String module, String phase) {
        List<Path> out = new ArrayList<>();
        Path root = repoRoot.resolve(module).resolve("src").resolve(phase).resolve("java");
        if (!Files.isDirectory(root)) {
            return out;
        }
        collect(root, out);
        return out;
    }

    /** 递归收集 .java（不用 Stream，写法朴素便于定位问题）。 */
    private static void collect(Path dir, List<Path> out) {
        Path[] children;
        try {
            children = Files.list(dir).toArray(Path[]::new);
        } catch (IOException e) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            Path child = children[i];
            if (Files.isDirectory(child)) {
                collect(child, out);
            } else if (child.toString().endsWith(".java")) {
                out.add(child);
            }
        }
    }

    /**
     * 从 config/meta 解析出「键名 -> 状态」。
     *
     * <p>那套元数据是一长串列表字面量，每条都是完整的 ConfigKey.xxx(...) 组，
     * 所以按「两条定义之间的文本里找 ConfigStatus」来粗切分，足够稳。</p>
     */
    /**
     * 「字段名 -> 状态」。
     *
     * <p><b>用 {@link ConfigKeyTable} 的真值，不再正则扫描 meta 源码。</b>
     * 第一版是拿正则去解析那 16 个文件的列表字面量 —— 那是重复建设：
     * {@code ConfigKeyTable.all()} 已经把同样的信息准备好了，而且是编译期绑定的，
     * 比正则稳。改成用 API 之后 {@code ConfigKeyTable} 也终于有了真实消费者。</p>
     */
    private static Map<String, String> declaredStatuses() {
        Map<String, String> result = new LinkedHashMap<>();
        List<ConfigKey> keys = ConfigKeyTable.all();
        for (int i = 0; i < keys.size(); i++) {
            ConfigKey key = keys.get(i);
            result.put(key.jsonKey(), key.status().name());
        }
        return result;
    }

    /**
     * 生产代码里出现 {@code .字段名(} 的<b>真实消费点</b>。
     *
     * <p><b>为什么要排除这几类（2026-11-13 实测踩到）</b>：第一版口径太粗，把
     * 「读取存取值」一律算接线，于是 10 个正确的 UNWIRED 标注全被误报。逐个看下来，
     * 那些读取点分四类，只有最后一类才算真的「接线」：</p>
     *
     * <ul>
     *   <li><b>UI 显示</b> —— {@code client/} 下把当前值画到配置界面上。
     *       读它只是为了</b>显示</b>，改了当然无效。排除。</li>
     *   <li><b>配置转发</b> —— {@code config/ConfigService} 把 {@code ModConfig} 的快照
     *       拷进 {@code RoadGenerationConfig} 这类使用层对象。它只是搬运，不消费。排除。</li>
     *   <li><b>指纹计算</b> —— {@code PlannedPathFingerprintService} 把配置值混进哈希，
     *       用来判断「配置变了要重算」。它读值但不改变任何生成行为。排除。</li>
     *   <li><b>字段定义/存取器</b> —— {@code ModConfig}、{@code config/sub/}、
     *       {@code config/meta}。那是它自己。排除。</li>
     * </ul>
     *
     * <p>排除后剩下的才是「读了并据此改变行为」的地方 —— 那才叫接线。
     * 实测这套规则下，{@code biomeCost*}(5)、{@code openclAccurateSamplingEnabled}、
     * {@code placeWaypoints}、{@code oceanBridgeMinDepth}、{@code keepLamps}、
     * {@code minLength} <b>全部归零</b>，与原始结论一致；
     * 而 {@code minWaterDepth}（4 处真实消费）和 {@code landTunnelEnabled}（1 处真实判定）
     * 会被抓出来 —— 那两个正是本次修掉的标注错误。</p>
     */
    private static List<String> readSites(String field) throws IOException {
        Pattern call = Pattern.compile("\\." + Pattern.quote(field) + "\\s*\\(");
        List<String> hits = new ArrayList<>();
        String[] modules = {"common", "fabric"};
        for (int mi = 0; mi < modules.length; mi++) {
            List<Path> all = javaFiles(modules[mi], "main");
            for (int f = 0; f < all.size(); f++) {
                Path file = all.get(f);
                String slashPath = file.toString().replace('\\', '/');
                if (isNotConsumer(slashPath)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int comment = line.indexOf("//");
                    String code = (comment >= 0) ? line.substring(0, comment) : line;
                    if (call.matcher(code).find()) {
                        hits.add(slashPath.substring(slashPath.indexOf("src/")) + ":" + (i + 1));
                    }
                }
            }
        }
        return hits;
    }

    /** 这个文件里的「读取」不算真实消费（理由见 {@link #readSites}）。 */
    private static boolean isNotConsumer(String slashPath) {
        return slashPath.contains("/client/")                       // UI 显示当前值
                || slashPath.endsWith("/config/ConfigService.java")  // 配置转发
                || slashPath.endsWith("/PlannedPathFingerprintService.java") // 只算指纹
                || slashPath.endsWith("/ModConfig.java")             // 字段定义
                || slashPath.contains("/config/sub/")                // 存取器
                || slashPath.contains("/config/meta/");              // 元数据本身
    }

    @Test
    void 标了未接线或已废弃的键必须真的没有读取点() throws IOException {
        Assumptions.assumeTrue(repoRoot != null, "找不到仓库根（settings.gradle），跳过");

        Map<String, String> declared = declaredStatuses();
        assertTrue(declared.size() > 100,
                "只解析出 " + declared.size() + " 条配置键 —— config/meta 的结构可能变了");

        List<String> unwired = new ArrayList<>();
        List<String> deprecated = new ArrayList<>();
        for (Map.Entry<String, String> e : declared.entrySet()) {
            String status = e.getValue();
            if ("UNWIRED".equals(status)) {
                unwired.add(e.getKey());
            } else if ("DEPRECATED".equals(status)) {
                deprecated.add(e.getKey());
            }
        }
        // 新版不再保留无效旋钮；零项是删除后的契约，用明确断言守住，不能继续要求存在死键。
        assertTrue(unwired.isEmpty() && deprecated.isEmpty(), "无效配置应从字段、元数据与菜单一起删除");

        List<String> problems = new ArrayList<>();
        for (int i = 0; i < unwired.size(); i++) {
            String field = unwired.get(i);
            List<String> sites = readSites(field);
            if (sites.isEmpty()) {
                continue;
            }
            problems.add("[UNWIRED] " + field + " —— 元数据说「未接线」，但代码里有 "
                    + sites.size() + " 处读取：" + sites);
        }
        for (int i = 0; i < deprecated.size(); i++) {
            String field = deprecated.get(i);
            List<String> sites = readSites(field);
            if (sites.isEmpty()) {
                continue;
            }
            problems.add("[DEPRECATED] " + field + " —— 元数据说「已废弃」，但代码里有 "
                    + sites.size() + " 处读取：" + sites);
        }

        assertTrue(problems.isEmpty(),
                "配置元数据的状态标注与代码不一致。这类漂移最危险的方向是「把能用的功能说成不能用」，"
                        + "玩家因此永远不去调它。\n  "
                        + String.join("\n  ", problems)
                        + "\n修法二选一："
                        + "\n  a) 那个键其实接线了 ⇒ 把 ConfigStatus 改成 ACTIVE，并删掉注释里过期的说法；"
                        + "\n  b) 那个读取点确实多余（例如只把值打进日志）⇒ 删掉读取点。"
                        + "\n改完请同步 lang 的 tooltip（中英文各一份）。");

        System.out.println("ConfigStatusHonestyTest: UNWIRED=" + unwired.size()
                + " DEPRECATED=" + deprecated.size() + "，全部与代码一致");
    }
}