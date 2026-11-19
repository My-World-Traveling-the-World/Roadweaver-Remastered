package net.shiroha233.roadweaver.pathlogic.construction;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实施工循环记录全部写块；只有外部墙查询和世界读写替换为可控边界。 */
class WallPavementProtectionTest {
    private static final BlockPos WALL = new BlockPos(-1, 64, 0);
    private static final BlockPos OPEN = new BlockPos(4, 64, 0);
    private static final Holder<Biome> BIOME = Holder.direct(new Biome.BiomeBuilder()
            .hasPrecipitation(true).temperature(0.5F).downfall(0.5F)
            .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0)
                    .waterFogColor(0).skyColor(0).build())
            .generationSettings(BiomeGenerationSettings.EMPTY).mobSpawnSettings(MobSpawnSettings.EMPTY).build());

    @Test void wallVetoStopsSurfaceOnlyWritesButNeighborStillPaves() {
        Site site = new Site(true);
        assertTrue(pave(site, List.of(WALL, OPEN), true));
        assertEquals(Blocks.STONE_BRICKS.defaultBlockState(), site.writes.get(OPEN),
                "夹具必须真正执行只补路面的直接写入分支，不能全段空转");
        assertFalse(site.writes.keySet().stream().anyMatch(p -> sameColumn(p, WALL)),
                "墙列不能写路面、支撑、半砖或净空");
        assertTrue(site.queries.containsAll(List.of(WALL, OPEN)));
        assertEquals(Blocks.CHEST.defaultBlockState(), site.state(OPEN.below()));
    }

    @Test void withoutWallProtectedRoadbedStillGetsSurfaceAndKeepsContainer() {
        Site site = new Site(true);
        assertTrue(pave(site, List.of(WALL), false));
        assertEquals(Blocks.STONE_BRICKS.defaultBlockState(), site.writes.get(WALL));
        assertFalse(site.writes.containsKey(WALL.below()), "墙保护不能把原先补洞能力改成覆盖容器");
    }

    @Test void wallVetoAlsoStopsOrdinaryRoadbedAndSlabWrites() {
        Site site = new Site(false);
        assertTrue(pave(site, List.of(WALL, OPEN), true));
        assertEquals(Blocks.STONE_BRICKS.defaultBlockState(), site.writes.get(OPEN.below()),
                "普通分支必须确实铺出路基");
        assertFalse(site.writes.keySet().stream().anyMatch(p -> sameColumn(p, WALL)));
    }

    @Test void publicEntryUsesRealWallQueryRatherThanAnUnprotectedAdapter() throws Exception {
        // 行为用例替换了外部查询，还需证明公开入口真的把长城API接进同一生产循环。
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var files = manager.getJavaFileObjects(java.nio.file.Path.of(
                    "src/main/java/net/shiroha233/roadweaver/pathlogic/construction/SegmentPaver.java"));
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, manager, null,
                    List.of("-proc:none"), null, files);
            var methods = new ArrayList<com.sun.source.tree.MethodTree>();
            for (var unit : task.parse()) new com.sun.source.util.TreeScanner<Void, Void>() {
                @Override public Void visitMethod(com.sun.source.tree.MethodTree method, Void ignored) {
                    methods.add(method);
                    return super.visitMethod(method, ignored);
                }
            }.scan(unit, null);
            var entry = methods.stream().filter(m -> m.getName().contentEquals("paveSegment")
                    && m.getParameters().size() == 14).findFirst().orElseThrow();
            var returned = (com.sun.source.tree.ReturnTree) entry.getBody().getStatements().getFirst();
            var call = (com.sun.source.tree.MethodInvocationTree) returned.getExpression();
            assertEquals("paveSegment", call.getMethodSelect().toString());
            assertEquals(15, call.getArguments().size());
            assertEquals("SegmentPaver::isWallOwned", call.getArguments().getLast().toString());
            var adapter = methods.stream().filter(m -> m.getName().contentEquals("isWallOwned")).findFirst().orElseThrow();
            var query = (com.sun.source.tree.MethodInvocationTree)
                    ((com.sun.source.tree.ReturnTree) adapter.getBody().getStatements().getFirst()).getExpression();
            assertEquals("net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor", query.getMethodSelect().toString());
            assertEquals(List.of("net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE",
                    "pos.getX()", "pos.getY()", "pos.getZ()"), query.getArguments().stream().map(Object::toString).toList());
        }
    }

    private static boolean pave(Site site, List<BlockPos> columns, boolean protect) {
        int[] heights = columns.stream().mapToInt(BlockPos::getY).toArray();
        // 采用桥头已确定纵断面，跳过地形预算，仍走正式的单列保护和全部铺材分支。
        return SegmentPaver.paveSegment(site.world(), null, new RoadSegmentPlacement(OPEN, columns),
                0, List.of(OPEN), new int[]{64},
                new SegmentPaver.SegmentGrade(heights, heights.clone(), false, true, !site.containers),
                0, List.of(Blocks.STONE_BRICKS.defaultBlockState()),
                List.of(Blocks.STONE_BRICK_SLAB.defaultBlockState()), RandomSource.create(8),
                new ModConfig(), new HashMap<>(), false, pos -> {
                    site.queries.add(pos);
                    return protect && sameColumn(pos, WALL);
                });
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private static final class Site {
        final boolean containers;
        final Map<BlockPos, BlockState> writes = new HashMap<>();
        final List<BlockPos> queries = new ArrayList<>();
        Site(boolean containers) { this.containers = containers; }
        BlockState state(BlockPos pos) {
            if (writes.containsKey(pos)) return writes.get(pos);
            if (pos.getY() == 63) return (containers ? Blocks.CHEST : Blocks.STONE).defaultBlockState();
            return (pos.getY() < 63 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        WorldGenLevel world() {
            return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getLevel" -> null;
                        case "getBiome" -> BIOME;
                        case "getMinBuildHeight" -> -64;
                        case "getMaxBuildHeight" -> 320;
                        case "getSeaLevel" -> 63;
                        case "getHeight" -> 64;
                        case "getBlockState" -> state((BlockPos) args[0]);
                        case "setBlock" -> {
                            writes.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
                            yield true;
                        }
                        default -> throw new AssertionError("未声明的世界访问：" + method.getName());
                    });
        }
    }
}
