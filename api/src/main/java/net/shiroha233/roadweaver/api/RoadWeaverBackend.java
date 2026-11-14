/* 文件职责：后端契约：别的模组实现它来接管结构预留/释放（我们只调接口）。 */
package net.shiroha233.roadweaver.api;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.api.incoming.PlanningLifecycleListener;
import net.shiroha233.roadweaver.api.incoming.ReadinessState;

/**
 * API 后端的实现契约。
 *
 * <h2>为什么要有这一层</h2>
 * <p>本接口让 {@code roadweaver-api} 模块对 RoadWeaver 的实现<b>零引用</b>：
 * api 模块只认识这个接口，RoadWeaver 在初始化时把自己的实现注进来
 * （{@link RoadWeaverApi#installBackend}）。</p>
 *
 * <p>好处是 RoadWeaver 不装时 api 模块仍能正常加载、每个方法都能安全降级，
 * 不会出现「调一下静态方法就 {@code NoClassDefFoundError}」的情况。</p>
 *
 * <p><b>这个接口只由 RoadWeaver 实现</b>，外部 mod 不要实现它。</p>
 */
public interface RoadWeaverBackend {

    /** RoadWeaver 是否真的在运行（不是只有 jar 在）。 */
    boolean isAvailable();

    /** 本实现支持的能力集合，元素用 {@code RoadWeaverApi.CAP_*} 常量。 */
    Set<String> capabilities();

    // ── 结构预留（incoming）───────────────────────────────────────────────

    /** @see net.shiroha233.roadweaver.api.incoming.StructureReservationApi#reserve */
    boolean reserveStructure(ServerLevel level, String structureId, BlockPos center,
                             int[] rotationMirrored, int fallenDirection);

    boolean isReserved(ServerLevel level, BlockPos pos);

    void releaseChunk(ServerLevel level, ChunkPos chunk);

    void clearReservationsForLevel(ServerLevel level);

    void clearAllReservations();

    // ── 规划生命周期（incoming）───────────────────────────────────────────

    void addLifecycleListener(PlanningLifecycleListener listener);

    void removeLifecycleListener(PlanningLifecycleListener listener);

    // ── 规划就绪（incoming）───────────────────────────────────────────────

    ReadinessState requestRegion(ServerLevel level, int regionX, int regionZ);

    ReadinessState regionState(ServerLevel level, int regionX, int regionZ);

    String progressSummary(ServerLevel level);

    void setChunkyPreflightActive(boolean active);

    void setChunkyExclusiveActive(boolean active);

    void markRegionDispatched(ServerLevel level, int regionX, int regionZ);

    // ── 路网操作（incoming）───────────────────────────────────────────────

    /** @see net.shiroha233.roadweaver.api.RoadNetworkApi#registerStructureEndpoint */
    void registerStructureEndpoint(ServerLevel level, BlockPos pos, String structureId, boolean autoConnect);

    /** @see net.shiroha233.roadweaver.api.RoadNetworkApi#ensureConnection */
    void ensureConnection(ServerLevel level, BlockPos from, BlockPos to, boolean generateImmediately);

    /** @see net.shiroha233.roadweaver.api.RoadNetworkApi#planRegion */
    void planRegion(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ);
}
