/* 文件职责：组合一个区块的道路切片与密度 stamp。 */
package net.shiroha233.roadweaver.worldgen.road;

import java.util.List;
import net.minecraft.world.level.ChunkPos;

/**
 * 世界生成阶段共享的不可变区块计划。
 */
public final class RoadChunkPlan {
    /** 仅是内存计算结果，不持久化；临时不可用不能冒充已确认无施工。 */
    public enum Outcome { READY, EMPTY_CONFIRMED, DISABLED, RETRYABLE_FAILURE }
    private final ChunkPos chunkPos;
    private final List<RoadChunkSlice> slices;
    private final RoadDensityStamp densityStamp;
    private final long revision;
    private final Outcome outcome;

    private RoadChunkPlan(ChunkPos chunkPos,
                          List<RoadChunkSlice> slices,
                          RoadDensityStamp densityStamp,
                          long revision, Outcome outcome) {
        this.chunkPos = chunkPos;
        this.slices = List.copyOf(slices);
        this.densityStamp = densityStamp;
        this.revision = revision;
        this.outcome = outcome;
    }

    public static RoadChunkPlan empty(ChunkPos chunkPos, int clearHeight, long revision) {
        return emptyResult(chunkPos, clearHeight, revision, Outcome.EMPTY_CONFIRMED);
    }

    public static RoadChunkPlan unavailable(ChunkPos chunkPos, int clearHeight, long revision, Outcome outcome) {
        if (outcome != Outcome.DISABLED && outcome != Outcome.RETRYABLE_FAILURE)
            throw new IllegalArgumentException("非暂缓结果不能使用空的占位计划");
        return emptyResult(chunkPos, clearHeight, revision, outcome);
    }

    private static RoadChunkPlan emptyResult(ChunkPos pos, int height, long revision, Outcome outcome) {
        return new RoadChunkPlan(pos, List.of(), RoadDensityStamp.empty(pos, height), revision, outcome);
    }

    public static RoadChunkPlan of(ChunkPos chunkPos,
                                   List<RoadChunkSlice> slices,
                                   RoadDensityStamp densityStamp,
                                   long revision) {
        if (chunkPos == null || densityStamp == null) {
            throw new IllegalArgumentException("chunk plan requires a position and density stamp");
        }
        if (!chunkPos.equals(densityStamp.chunkPos())) {
            throw new IllegalArgumentException("chunk plan and density stamp positions must match");
        }
        var safe = slices == null ? List.<RoadChunkSlice>of() : slices;
        return new RoadChunkPlan(chunkPos, safe, densityStamp, revision,
                safe.isEmpty() ? Outcome.EMPTY_CONFIRMED : Outcome.READY);
    }

    public ChunkPos chunkPos() {
        return chunkPos;
    }

    public List<RoadChunkSlice> slices() {
        return slices;
    }

    public RoadDensityStamp densityStamp() {
        return densityStamp;
    }

    public long revision() {
        return revision;
    }

    public Outcome outcome() { return outcome; }

    public boolean isAvailable() { return outcome == Outcome.READY || outcome == Outcome.EMPTY_CONFIRMED; }

    /** 修复后第二次取计划也可能暂时失败，必须在准备/结案前传播到施工边界。 */
    public void requireAvailable() {
        if (!isAvailable()) throw new Deferred();
    }

    public static final class Deferred extends RuntimeException {
        // 已有编译失败诊断；正常重试不重复构造异常栈或把临时状态报成施工失败。
        public Deferred() { super("道路区块计划暂不可用", null, false, false); }
    }

    public boolean isEmpty() {
        return slices.isEmpty() && densityStamp.isEmpty();
    }
}
