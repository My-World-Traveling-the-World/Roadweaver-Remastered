package net.shiroha233.roadweaver.client.fabric.diorama;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** 验发行成品能连到原版 Diorama，且不会向它的屏幕、图层或存档登记道路。 */
class DioramaTerrainReleaseTest {
    private static final String CLIENT = "net/shiroha233/roadweaver/client/fabric/";
    private static final String TERRAIN = CLIENT + "diorama/DioramaTerrainBackground";

    @Test void newRoadPackageDoesNotRegisterOrContainTheOldDioramaOverlay() throws Exception {
        try (var road = new ZipFile(System.getProperty("roadJar"))) {
            var metadata = JsonParser.parseString(new String(bytes(road, "fabric.mod.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(metadata.getAsJsonObject("depends").has("diorama"));
            assertFalse(metadata.getAsJsonArray("mixins").toString().contains("diorama"));
            assertFalse(road.stream().anyMatch(e -> e.getName().startsWith("dev/diorama/")
                    || e.getName().contains("DioramaMapScreenMixin") || e.getName().endsWith("DioramaRoadOverlay.class")));
            assertNotNull(road.getEntry(TERRAIN + ".class"));
        }
    }

    @Test void remappedRoadPackageLinksOnlyToDioramasReadSide() throws Exception {
        try (var road = new ZipFile(System.getProperty("roadJar"));
             var diorama = new ZipFile(System.getProperty("dioramaReleaseJar"))) {
            var calls = new HashSet<String>();
            for (var method : node(road, TERRAIN).methods) for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.startsWith("dev/diorama/")) {
                    assertTrue(node(diorama, call.owner).methods.stream()
                            .anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc)), call.owner + "." + call.name);
                    calls.add(call.owner + "." + call.name);
                } else if (insn instanceof FieldInsnNode field && field.owner.startsWith("dev/diorama/")) {
                    assertTrue(node(diorama, field.owner).fields.stream()
                            .anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)), field.name);
                    assertEquals("dev/diorama/data/ChunkSnapshot.minSectionY", field.owner + "." + field.name);
                }
            }
            assertEquals(Set.of("dev/diorama/MapSession.get", "dev/diorama/MapSession.map",
                    "dev/diorama/ServerRules.minimapAllowed", "dev/diorama/ServerRules.mapOff",
                    "dev/diorama/render/MapLevel.roofless", "dev/diorama/render/MapLevel.worldChunk",
                    "dev/diorama/render/MapLevel.fogOfWar", "dev/diorama/render/MapLevel.isSeen",
                    "dev/diorama/data/ChunkSnapshot.surface", "dev/diorama/data/ChunkSnapshot.sectionCount",
                    "dev/diorama/data/ChunkSnapshot.section"), calls);
        }
    }

    @Test void fabricHotkeyPassesTheOptionalBackgroundIntoTheRoadMap() throws Exception {
        try (var road = new ZipFile(System.getProperty("roadJar"))) {
            int gates = 0, opens = 0;
            for (var method : node(road, CLIENT + "ClientInit").methods) for (var insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (call.owner.equals(CLIENT + "diorama/DioramaBackgroundCompat") && call.name.equals("create")) gates++;
                if (call.owner.equals("net/shiroha233/roadweaver/client/map/RoadMapScreen")
                        && call.name.equals("<init>") && call.desc.contains("MapTerrainBackground")) opens++;
            }
            assertEquals(1, gates);
            assertEquals(1, opens);
        }
    }

    private static ClassNode node(ZipFile zip, String name) throws Exception {
        var node = new ClassNode();
        new ClassReader(bytes(zip, name + ".class")).accept(node, 0);
        return node;
    }

    private static byte[] bytes(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name);
        assertNotNull(entry, name);
        try (var stream = zip.getInputStream(entry)) { return stream.readAllBytes(); }
    }
}
