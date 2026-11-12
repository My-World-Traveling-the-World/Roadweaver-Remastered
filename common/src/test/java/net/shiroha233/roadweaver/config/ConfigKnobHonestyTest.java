package net.shiroha233.roadweaver.config;

import net.shiroha233.roadweaver.config.sub.BridgeConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.032：配置项的「诚实性」回归。
 *
 * <p><b>背景（推翻一条错误结论）</b>：0.9.027 把 O-12 尾记成「已关闭 —— 前提不成立，这 6 个键
 * 根本没进 lang、界面也没暴露」。这条是错的：{@code ConfigScreenFactoryImpl} 里 6 个键全都有
 * 界面条目，{@code zh_cn.json} / {@code en_us.json} 里也全都有 label 与 tooltip。其中
 * {@code chunky_road_thread_cap} 与 {@code opencl_accurate_sampling_enabled} 早在 0.8.419
 * 就挂了「⚠️ 不生效」，而另外 4 个（{@code bridge_keep_lamps} / {@code bridge_min_length} /
 * {@code place_waypoints} / {@code ocean_bridge_min_depth}）的 tooltip 仍在**承诺功能**，
 * 全仓却没有任何读取点 —— 玩家照着调是纯浪费。</p>
 *
 * <p>同一版还把 {@code chunkyRoadThreadCap} 从死键名单里划掉，理由是「被
 * {@code ThreadPoolManager:113} 消费」。那条同样是错的：那只是
 * {@code LOGGER.info("… chunkyRoadCap={}", …)} 的实参列表，值只进日志，没有任何分支拿它限流。
 * 所以死键名单是 **6 个**，不是 5 个。</p>
 *
 * <p>本测试钉两件事：①上面 6 个死键的 tooltip 必须带「不生效」标记，防止以后又被写成
 * 「看起来能用」；②R-03 的新旋钮 {@code bridge.shareRadius} 的字段夹取与两份 lang 必须齐全
 * —— 少一处，玩家就只能在 JSON 里手改。</p>
 */
final class ConfigKnobHonestyTest {

    /** 全仓零读取点的 6 个配置键（逐个 grep 过：只有字段/sanitize/snapshot/存取器/界面/本测试）。 */
    private static final List<String> DEAD_KNOBS = List.of(
            "bridge_keep_lamps",
            "bridge_min_length",
            "place_waypoints",
            "ocean_bridge_min_depth",
            "chunky_road_thread_cap",
            "opencl_accurate_sampling_enabled");

    private static final String ZH_MARKER = "不生效";
    private static final String EN_MARKER = "does NOTHING";

    @Test
    void everyInertSwitchAdmitsItIsInert() throws Exception {
        String zh = read("zh_cn.json");
        String en = read("en_us.json");
        for (String knob : DEAD_KNOBS) {
            assertTrue(tooltip(zh, knob).contains(ZH_MARKER),
                    "zh_cn tooltip of " + knob + " must contain '" + ZH_MARKER + "'");
            assertTrue(tooltip(en, knob).contains(EN_MARKER),
                    "en_us tooltip of " + knob + " must contain '" + EN_MARKER + "'");
        }
    }

    @Test
    void bridgeShareRadiusIsWiredEverywhere() throws Exception {
        BridgeConfig cfg = new BridgeConfig();
        assertEquals(24, cfg.shareRadius(), "default share radius");

        // 0 是"关闭共享"的合法值，不能被夹成 1 —— 夹掉了这个开关就等于没有。
        cfg.setShareRadius(0);
        assertEquals(0, cfg.shareRadius(), "0 must survive: it switches sharing off");
        cfg.setShareRadius(-5);
        assertEquals(0, cfg.shareRadius(), "negative clamps to 0, i.e. off rather than a 1-block radius");
        cfg.setShareRadius(200);
        assertEquals(96, cfg.shareRadius(), "upper bound");
        cfg.sanitize();
        assertEquals(96, cfg.shareRadius(), "sanitize keeps a legal value");
        assertEquals(96, cfg.snapshot().shareRadius(), "snapshot carries the value");

        for (String file : List.of("zh_cn.json", "en_us.json")) {
            String text = read(file);
            assertFalse(label(text, "bridge_share_radius").isBlank(),
                    file + " must label config.roadweaver.bridge_share_radius");
            assertFalse(tooltip(text, "bridge_share_radius").isBlank(),
                    file + " must explain config.roadweaver.bridge_share_radius");
        }
    }

    // ------------------------------------------------------------------
    // 极简 lang 取值：不引 JSON 库，避免为一条断言拉依赖。值里没有转义引号。
    // ------------------------------------------------------------------

    private static String read(String langFile) throws Exception {
        String path = "/assets/roadweaver/lang/" + langFile;
        InputStream in = ConfigKnobHonestyTest.class.getResourceAsStream(path);
        assertNotNull(in, "missing classpath resource " + path);
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String label(String lang, String knob) {
        return value(lang, "\"config.roadweaver." + knob + "\"");
    }

    private static String tooltip(String lang, String knob) {
        return value(lang, "\"config.roadweaver." + knob + ".tooltip\"");
    }

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
                out.append(c).append(lang.charAt(++i));
                continue;
            }
            if (c == '"') return out.toString();
            out.append(c);
        }
        return "";
    }
}
