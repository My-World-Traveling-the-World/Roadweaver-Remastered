package net.shiroha233.roadweaver.mixin.diorama;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DioramaMixinPluginTest {
    @Test
    void onlyMatchingDioramaWithoutTheStandaloneBridgeAddsTheClientHook() {
        String supported = "0.11.0+1.21-1.21.1";
        assertEquals(List.of("DioramaMapScreenMixin"), DioramaMixinPlugin.selectMixins(supported, false));
        // 缺地图、签名未确认的版本和重复安装都不应注册这条注入。
        assertTrue(DioramaMixinPlugin.selectMixins(null, false).isEmpty());
        assertTrue(DioramaMixinPlugin.selectMixins("0.10.2+1.21-1.21.1", false).isEmpty());
        assertTrue(DioramaMixinPlugin.selectMixins(supported, true).isEmpty());
    }
}
