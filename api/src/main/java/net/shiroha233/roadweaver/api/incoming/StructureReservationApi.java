/* 文件职责：我们暴露给别的 mod 的结构预留：预留/查询/按区块释放。 */
package net.shiroha233.roadweaver.api.incoming;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.api.RoadWeaverApi;
import net.shiroha233.roadweaver.api.RoadWeaverBackend;

/**
 * 结构占地预留：别的 mod 在放置结构前把占地登记进来，道路规划会绕开。
 *
 * <p>原先 tanshugetrees 用纯反射 + 硬编码全限定名调
 * {@code features.path.pathlogic.core.HugeTreeReservation}，0.9.087 结构重构
 * 把这个类移走后，那条链路<b>静默失效</b>（对方是 {@code catch (Throwable)} 降级），
 * 表现为「道路突然开始穿巨树」。</p>
 *
 * <p>本门面把 {@link #reserve} 的返回值从 {@code void} 改成 {@code boolean}：
 * 调用方现在能知道<b>到底有没有生效</b>，而不是只能静默吞掉。</p>
 *
 * <p>RoadWeaver 未安装时所有方法安全降级（返回 {@code false} / 空操作），
 * 不抛异常。</p>
 */
public final class StructureReservationApi {

    private StructureReservationApi() {
    }

    /**
     * 登记一块结构占地。
     *
     * @param level            服务端世界
     * @param structureId      当前巨树实现所需的模板路径，透传给形状读取方；不能用任意结构ID代替模板
     * @param center           中心方块坐标
     * @param rotationMirrored 旋转与镜像，长度 2；允许 {@code null}
     * @param fallenDirection  倒伏方向，未倒伏传 {@code -1}
     * @return {@code true} 已发布非空占地；{@code false} 不可用、参数无效、模板无需预留或形状读取失败
     */
    public static boolean reserve(ServerLevel level, String structureId, BlockPos center,
                                  int[] rotationMirrored, int fallenDirection) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return false;
        }
        try {
            return b.reserveStructure(level, structureId, center, rotationMirrored, fallenDirection);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 某坐标是否已被登记为预留。 */
    public static boolean isReserved(ServerLevel level, BlockPos pos) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return false;
        }
        try {
            return b.isReserved(level, pos);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 区块卸载时释放该区块内的预留。 */
    public static void releaseChunk(ServerLevel level, ChunkPos chunk) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.releaseChunk(level, chunk);
        } catch (RuntimeException | LinkageError ignored) {
            // 释放失败不影响调用方主流程
        }
    }

    /** 清空某个世界的全部预留。 */
    public static void clearForLevel(ServerLevel level) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.clearReservationsForLevel(level);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 清空全部世界的预留（世界卸载时用）。 */
    public static void clearAll() {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.clearAllReservations();
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}
