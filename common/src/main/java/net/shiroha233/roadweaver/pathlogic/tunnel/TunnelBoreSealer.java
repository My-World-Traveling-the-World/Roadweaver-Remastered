/* 文件职责：封水施工的最小读写边界，便于离线对拍，保持原来的逐格写入顺序。 */
package net.shiroha233.roadweaver.pathlogic.tunnel;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class TunnelBoreSealer {
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private TunnelBoreSealer() {}

    interface Port {
        BlockState block(BlockPos position);
        int terrain(BlockPos column);
        boolean protectedVolume(BlockPos position, BlockState state, int terrain);
        boolean wallOwned(BlockPos position);
        void write(BlockPos position, BlockState state);

        default boolean water(BlockState state) {
            return state.getFluidState().is(FluidTags.WATER);
        }
    }

    static int[] seal(TunnelPlan plan, int roof, Port port) {
        if (plan == null || plan.interior().isEmpty()) return new int[]{0, 0};
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        boolean sealShell = !plan.portal();
        int dried = 0;
        int sealed = 0;
        for (BlockPos interior : plan.interior()) {
            int terrain = port.terrain(interior);
            for (int dy = 1; dy <= roof; dy++) {
                BlockPos inside = interior.above(dy);
                if (port.water(port.block(inside))) {
                    if (!port.wallOwned(inside)) {
                        port.write(inside, air);
                        dried++;
                    }
                    continue;
                }
                if (!sealShell) continue;
                if (!port.block(inside).isAir()) continue;
                for (int[] side : SIDES) {
                    BlockPos shell = inside.offset(side[0], 0, side[1]);
                    if (!port.water(port.block(shell))) continue;
                    if (port.protectedVolume(shell, port.block(shell), terrain)
                            || port.wallOwned(shell)) continue;
                    port.write(shell, stone);
                    sealed++;
                }
            }
        }
        return new int[]{dried, sealed};
    }
}
