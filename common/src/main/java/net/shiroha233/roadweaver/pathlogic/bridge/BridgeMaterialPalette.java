/* 文件职责：内置木桥模板的材料投影。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.shiroha233.roadweaver.core.model.RoadData;

/** 从内置木桥模板提取材料，避免程序化支撑与模板主体颜色脱节。 */
public record BridgeMaterialPalette(
        BlockState deck,
        BlockState accentDeck,
        BlockState edgeDeck,
        BlockState support,
        BlockState rail,
        BlockState accentRail
) {
    public static BridgeMaterialPalette forRange(
            RoadData road,
            int rangeStart,
            int rangeEnd
    ) {
        // Worldgen features must not query a bridge anchor in another chunk:
        // C2ME may still be generating that chunk, which makes the lookup fail
        // and poisons the whole FEATURES future. The bundled bodies need no world IO at all, so the
        // choice is made from the road's own surface material - which is what the bridge stands in for.
        // It used to be a constant, so every bridge in every biome was the same birch-and-cherry body:
        // a sandstone desert road arrived at a stream and turned into a birch bridge.
        return fromTemplatePath(pathFor(road));
    }

    /** 根据所属路面选择内置桥体；没有石桥模板时取深色木体，不伪造不存在的模板。
 *
     * {@return the bundled body that matches the road surface this bridge belongs to}
     *
     * <p>There is no stone body among the bundled templates, so stone and gravel roads take the darkest
     * wood instead - the point is that a bridge reads as belonging to its road, not that the species is
     * exact.
     */
    static String pathFor(RoadData road) {
        if (road == null || road.materials().isEmpty()) return "bridge_bridge_1";
        var first = road.materials().get(0).getBlock();
        if (first == Blocks.SANDSTONE || first == Blocks.CUT_SANDSTONE
                || first == Blocks.RED_SANDSTONE || first == Blocks.CUT_RED_SANDSTONE) {
            return "wood_bridge_jinhehuan";
        }
        if (first == Blocks.STONE || first == Blocks.ANDESITE || first == Blocks.COBBLESTONE
                || first == Blocks.MOSSY_COBBLESTONE || first == Blocks.GRAVEL) {
            return "wood_bridge_shensexiangmu";
        }
        if (first == Blocks.DIRT_PATH || first == Blocks.COARSE_DIRT || first == Blocks.PACKED_MUD
                || first == Blocks.ROOTED_DIRT) {
            return "wood_bridge_hongshu";
        }
        if (first == Blocks.BAMBOO_PLANKS || first == Blocks.BAMBOO_MOSAIC) {
            return "wood_bridge_zhuzi";
        }
        return "bridge_bridge_1";
    }

    static BridgeMaterialPalette fromTemplatePath(String path) {
        return switch (path) {
            case "bridge_bridge_0" -> palette(
                    Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.BIRCH_SLAB,
                    Blocks.STRIPPED_OAK_LOG, Blocks.OAK_FENCE, Blocks.BIRCH_FENCE);
            case "wood_bridge_hongshu" -> palette(
                    Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_SLAB,
                    Blocks.MANGROVE_LOG, Blocks.MANGROVE_FENCE, Blocks.MANGROVE_FENCE);
            case "wood_bridge_huamu" -> palette(
                    Blocks.BIRCH_PLANKS, Blocks.BIRCH_PLANKS, Blocks.BIRCH_SLAB,
                    Blocks.STRIPPED_BIRCH_LOG, Blocks.BIRCH_FENCE, Blocks.BIRCH_FENCE);
            case "wood_bridge_jinhehuan" -> palette(
                    Blocks.ACACIA_PLANKS, Blocks.ACACIA_PLANKS, Blocks.ACACIA_SLAB,
                    Blocks.STRIPPED_ACACIA_LOG, Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE);
            case "wood_bridge_shensexiangmu" -> palette(
                    Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_SLAB,
                    Blocks.STRIPPED_DARK_OAK_LOG, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_FENCE);
            case "wood_bridge_zhuzi" -> palette(
                    Blocks.BAMBOO_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.BAMBOO_MOSAIC_SLAB,
                    Blocks.BAMBOO_BLOCK, Blocks.BAMBOO_FENCE, Blocks.BAMBOO_FENCE);
            case "bridge_bridge_1", "wood_bridge_yinghuamu" -> palette(
                    Blocks.BIRCH_PLANKS, Blocks.CHERRY_PLANKS, Blocks.CHERRY_SLAB,
                    Blocks.OAK_LOG, Blocks.CHERRY_FENCE, Blocks.BIRCH_FENCE);
            default -> palette(
                    Blocks.BIRCH_PLANKS, Blocks.CHERRY_PLANKS, Blocks.CHERRY_SLAB,
                    Blocks.OAK_LOG, Blocks.CHERRY_FENCE, Blocks.BIRCH_FENCE);
        };
    }

    private static BridgeMaterialPalette palette(
            Block deck,
            Block accentDeck,
            Block edgeDeck,
            Block support,
            Block rail,
            Block accentRail
    ) {
        BlockState edgeState = edgeDeck.defaultBlockState();
        if (edgeState.hasProperty(SlabBlock.TYPE)) {
            edgeState = edgeState.setValue(SlabBlock.TYPE, SlabType.TOP);
        }
        return new BridgeMaterialPalette(
                deck.defaultBlockState(),
                accentDeck.defaultBlockState(),
                edgeState,
                support.defaultBlockState(),
                rail.defaultBlockState(),
                accentRail.defaultBlockState());
    }

}
