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

/** 只保留有意禁用的GPU精确采样说明；已删除的死键不得重新向玩家承诺能力。 */
final class ConfigKnobHonestyTest {

    // 精确采样固定CPU后端是已验证的设计，不随本轮旧配置清理更改。
    private static final List<String> DEAD_KNOBS = List.of("opencl_accurate_sampling_enabled");

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
