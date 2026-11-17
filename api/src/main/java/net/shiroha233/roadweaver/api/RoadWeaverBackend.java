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

    /** 预留结构的后端契约与公开入口保持一致，避免绕过占地校验。 */
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

    /** 注册外部结构端点；自动连路意图由公开接口定义。 */
    void registerStructureEndpoint(ServerLevel level, BlockPos pos, String structureId, boolean autoConnect);

    /** 提交两端点连接意图，立即生成标志沿用公开接口契约。 */
    void ensureConnection(ServerLevel level, BlockPos from, BlockPos to, boolean generateImmediately);

    /** 按方块坐标矩形请求规划，边界口径以公开入口为准。 */
    void planRegion(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ);
}
