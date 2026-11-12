package net.shiroha233.roadweaver.decoration.compat;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;

/**
 * 道路生成兼容层
 */
public final class RoadFeatureCompat {
    private RoadFeatureCompat() {}

    private static final Set<Block> DONT_PLACE = new HashSet<>();
    static {
        DONT_PLACE.add(Blocks.TALL_SEAGRASS);
        DONT_PLACE.add(Blocks.MANGROVE_ROOTS);
    }

    public static boolean dontPlaceHere(Block b) {
        return DONT_PLACE.contains(b);
    }
}
