package net.shiroha233.roadweaver.client.fabric.diorama;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 在发行命名空间实际应用 Sponge，避免 named 编译成功却在游戏中找不到注入目标。 */
class DioramaMixinTest {
    @Test
    void releaseMapScreenCallsTheNestedRoadOverlayOnce() throws Exception {
        String roadJar = System.getProperty("roadJar");
        var configName = "roadweaver.diorama.mixins.json";
        try (var jar = new ZipFile(roadJar)) {
            var metadata = JsonParser.parseString(read(jar, "fabric.mod.json")).getAsJsonObject();
            assertFalse(metadata.getAsJsonObject("depends").has("diorama"));
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("dev/diorama/")));
            long configEntries = metadata.getAsJsonArray("mixins").asList().stream()
                    .filter(e -> e.isJsonObject() && configName.equals(e.getAsJsonObject().get("config").getAsString()))
                    .filter(e -> "client".equals(e.getAsJsonObject().get("environment").getAsString()))
                    .count();
            assertEquals(1, configEntries);

            // 独立变换器不启动 Fabric Loader，显式选择与已装受支持地图相同的注入项。
            // 选择规则另有测试，真实成品的 Mixin 类和发行地图 class 均从 JAR 读取。
            var config = JsonParser.parseString(read(jar, configName)).getAsJsonObject();
            config.remove("plugin");
            var client = new com.google.gson.JsonArray();
            client.add("DioramaMapScreenMixin");
            config.add("client", client);
            var harness = new DioramaMixinHarness(System.getProperty("dioramaReleaseJar"), config.toString());
            harness.addJar(System.getProperty("intermediaryClientJar"));
            harness.addJar(roadJar);

            var node = new ClassNode();
            new ClassReader(harness.apply("dev.diorama.MapScreen")).accept(node, 0);
            int drawCalls = 0;
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/shiroha233/roadweaver/client/fabric/diorama/DioramaRoadOverlay")
                            && call.name.equals("render")) {
                        drawCalls++;
                    }
                }
            }
            assertEquals(1, drawCalls);
        }
    }

    private static String read(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name);
        assertNotNull(entry, name);
        try (var input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
