package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;

import java.util.List;

import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;

/**
 * Regression coverage for the hard upper bound used when nearby building
 * boxes form a daisy-chain.
 */
public final class StructureClusterWindowTest {
    public static void main(String[] args) {
        List<BlockPos> path = List.of(new BlockPos(0, 64, 0), new BlockPos(50, 64, 0),
                new BlockPos(192, 64, 0), new BlockPos(193, 64, 0));
        require(structureClusterWindowAllows(path, 0, 2),
                "the bounded local window must retain nearby conflicting buildings");
        require(!structureClusterWindowAllows(path, 0, 3),
                "a distant chained building must be left for a later local repair");
        System.out.println("StructureClusterWindowTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
