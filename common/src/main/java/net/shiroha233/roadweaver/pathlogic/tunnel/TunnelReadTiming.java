/* 文件职责：可选的隧道阶段计时，只统计本包直接发出的世界读请求。 */
package net.shiroha233.roadweaver.pathlogic.tunnel;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;

final class TunnelReadTiming {
    private static final boolean ENABLED =
            "on".equalsIgnoreCase(System.getProperty("roadweaver.tunnelTimings", "off"));
    private static final ThreadLocal<Sample> ACTIVE = new ThreadLocal<>();

    private TunnelReadTiming() {
    }

    static Sample begin(String phase, BlockPos anchor) {
        if (!ENABLED) return null;
        Sample sample = new Sample(phase, anchor, ACTIVE.get());
        ACTIVE.set(sample);
        return sample;
    }

    static BlockState block(WorldGenLevel world, BlockPos position) {
        Sample sample = ENABLED ? ACTIVE.get() : null;
        if (sample != null) sample.blockReads++;
        return world.getBlockState(position);
    }

    static int terrain(WorldGenLevel world, int x, int z) {
        Sample sample = ENABLED ? ACTIVE.get() : null;
        if (sample != null) {
            sample.terrainCalls++;
            sample.terrainColumns.add(BlockPos.asLong(x, 0, z));
        }
        return RoadConstructionSafety.terrainHeight(world, x, z);
    }

    static int rock(WorldGenLevel world, int x, int z) {
        Sample sample = ENABLED ? ACTIVE.get() : null;
        if (sample != null) {
            sample.rockCalls++;
            sample.rockColumns.add(BlockPos.asLong(x, 0, z));
        }
        return RoadConstructionSafety.rockSurfaceHeight(world, x, z);
    }

    static final class Sample implements AutoCloseable {
        private final long started = System.nanoTime();
        private final String phase;
        private final BlockPos anchor;
        private final Sample previous;
        // 相同列的查高与查基岩是不同读操作；分别量重复量，不能合并计算。
        private final LongOpenHashSet terrainColumns = new LongOpenHashSet();
        private final LongOpenHashSet rockColumns = new LongOpenHashSet();
        private int terrainCalls, rockCalls, blockReads;
        boolean completed;

        private Sample(String phase, BlockPos anchor, Sample previous) {
            this.phase = phase;
            this.anchor = anchor;
            this.previous = previous;
        }

        @Override
        public void close() {
            long totalUs = (System.nanoTime() - started) / 1000;
            // 施工可在不同线程进行，作用域结束时恢复线程上下文，避免异常后串到下一站。
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            RoadTunnelBuilder.LOGGER.info(
                    "[TUNNEL-READ-TIMING] phase={} pos={} completed={} terrainCalls={} terrainColumns={} rockCalls={} rockColumns={} directBlockReads={} totalUs={}",
                    phase, anchor, completed, terrainCalls, terrainColumns.size(), rockCalls,
                    rockColumns.size(), blockReads, totalUs);
        }
    }
}
