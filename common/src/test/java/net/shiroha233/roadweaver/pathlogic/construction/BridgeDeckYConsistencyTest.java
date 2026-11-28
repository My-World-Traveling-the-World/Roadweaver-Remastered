/* 文件职责：守卫桥梁实体甲板和普通路面的脚底高度一致。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.EmptyBlockGetter;
import net.shiroha233.roadweaver.structures.registry.BridgeTemplateStructureRegistry.BridgeTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 校验脚底坐标的布线和正式模板资产；桥口写入由独立施工夹具验证。 */
class BridgeDeckYConsistencyTest {

    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();
    private static final String PKG = "common/src/main/java/net/shiroha233/roadweaver/";

    private static String read(String rel) throws IOException {
        Path p = REPO_ROOT.resolve(rel);
        assertTrue(Files.isRegularFile(p), "找不到源文件（仓库根定位错了？）: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String stripComments(String t) {
        return t.replaceAll("/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    @Test
    void 程序化路径传入脚底高度() throws IOException {
        String code = stripComments(read(PKG + "pathlogic/bridge/BridgeSegmentPlanner.java"));
        assertTrue(compact(code).contains("intsegDeckY=baseYForThis;"), "避免程序桥整体抬高一格");
    }

    @Test
    void 建造路径的减一必须存在() throws IOException {
        String code = stripComments(read(PKG + "pathlogic/bridge/BridgeBuilder.java"));
        assertTrue(compact(code).contains("intdeckBlockY=deckSurfaceY-1;"),
                "桥面实体在脚底下一层，正好和普通整砖面齐平");
    }

    @Test
    void 体积估算必须直接从profile起算() throws IOException {
        String code = stripComments(read(PKG + "pathlogic/construction/RoadConstructionVolumePlanner.java"));
        String c = compact(code);
        assertTrue(c.contains("intdeckBlockY=profile[index]-1;"), "体积从实体甲板起算");
    }

    @Test
    void 模板路径的映射偏移必须与传参配套() throws IOException {
        String registry = stripComments(read(PKG + "structures/registry/BridgeTemplateStructureRegistry.java"));
        assertTrue(compact(registry).contains("structure.heightOffset+2"), "模板映射须与实际资产的偏移配套");

        String feature = stripComments(read(PKG + "pathlogic/construction/ConstructionStationWork.java"));
        assertTrue(compact(feature).contains("intactualDeckY=baseYForThis;"),
                "两种桥共享同一个脚底高度");
    }

    @Test
    void bundledBridgeBodiesHaveWalkableFloorOneBlockBelowProfile() throws IOException {
        Path root = REPO_ROOT.resolve("common/src/main/resources/data/roadweaver");
        int checked = 0;
        try (var files = Files.list(root.resolve("worldgen/structure"))) {
            for (Path definition : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseString(Files.readString(definition)).getAsJsonObject();
                if (!"roadweaver:bridge".equals(json.get("type").getAsString())) continue;
                String template = json.get("template").getAsString().split(":", 2)[1];
                int middleX = (json.get("bridge_deck_start").getAsInt() + json.get("bridge_deck_end").getAsInt()) / 2;
                int entityFloor = json.get("height_offset").getAsInt() + 1;
                try (var input = Files.newInputStream(root.resolve("structure").resolve(template + ".nbt"))) {
                    var nbt = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                    int middleZ = nbt.getList("size", 3).getInt(2) / 2;
                    var palette = nbt.getList("palette", 10);
                    var blocks = nbt.getList("blocks", 10);
                    boolean sturdyFloor = false;
                    for (int i = 0; i < blocks.size(); i++) {
                        var block = blocks.getCompound(i);
                        var pos = block.getList("pos", 3);
                        if (pos.getInt(0) != middleX || pos.getInt(1) != entityFloor || pos.getInt(2) != middleZ) continue;
                        var state = BridgeTemplate.readBlockState(palette.getCompound(block.getInt("state")));
                        sturdyFloor = state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
                    }
                    assertTrue(sturdyFloor, template + " 的桥身中心必须有与普通路面齐平的可站立面");
                    checked++;
                }
            }
        }
        assertEquals(9, checked, "必须实际读取九个正式模板");
    }

    /** 去掉所有空白，让断言不受缩进/换行影响。 */
    private static String compact(String s) {
        return s.replaceAll("\\s+", "");
    }
}
