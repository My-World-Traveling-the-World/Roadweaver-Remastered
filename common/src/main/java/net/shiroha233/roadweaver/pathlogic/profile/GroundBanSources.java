/* 文件职责：收集「地面不可用」的物理约束 —— 桥隧 DP 的禁地面掩码不再取自旧判定。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceCache;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;

/**
 * 禁地面来源收集器（2026-11-15 新增，用于解除批 2 的循环论证）。
 *
 * <h2>它解决的问题</h2>
 * <p>桥隧联合 DP 需要一个 {@code groundBanned} 掩码，告诉它「这些站不能铺地面」。
 * 第一版直接复用了 {@code isCrossing}，而 {@code isCrossing} 又来自
 * <b>旧判定产出的 spans</b> ⇒ DP 被告知的「不能铺地面」恰好就是「旧判定认为该架桥」，
 * 于是「DP 判出的桥 vs 旧判定判出的桥」这种比较只能得到高一致率，不是独立验证。</p>
 *
 * <p>本类改用<b>与旧判定无关的物理事实</b>来构造这个掩码：</p>
 * <ul>
 *   <li><b>水面</b>：水深达到架桥阈值（来自地形场的高度图 / 生物群系 / 海平面）；</li>
 *   <li><b>保护区 / 结构占地</b>：已验证结构体的占地与道路施工带相交。</li>
 * </ul>
 *
 * <h2>为什么用「探针」而不是直接接地形场</h2>
 * <p>{@link PathTerrainField} 的实现要覆盖 {@code Holder<Biome> biome(...)} 等
 * MC 类型，单测里无法轻量构造。所以核心逻辑只依赖两个极小接口
 * （{@link WaterProbe} / {@link StructureProbe}），生产入口
 * {@link #collect(PathTerrainField, List, int, int)} 负责把它们适配出来。
 * 这与 {@code ElasticBandSolver.BlockedCheck}、{@code BandPathfinder.EdgeProbe}
 * 是同一套「端口 + 适配」写法 —— 纯逻辑可测，适配点只有一处。</p>
 *
 * <h2>刻意没接的来源（如实标注，不是遗漏）</h2>
 * <ul>
 *   <li><b>已有公路</b>：现有 {@code RoadPositionQuery.isOnRoad} 的实际语义是
 *       「防止树木长在路上」，而且被 {@code roadAppearance.preventTreesOnRoad}
 *       配置门控 —— 用它当「地面不可用」会在玩家关掉树木抑制时静默失效，
 *       语义也不匹配。正确接入需要走路网复用/合并那一层，属批 4 的范围。</li>
 * </ul>
 *
 * <h2>保护区快照可能不可用</h2>
 * <p>{@code shouldAvoidPlanning} 读的是 ThreadLocal 快照，缺失时静默返回 false ——
 * 与「确实没有结构」同形。所以结果里单独用
 * {@link Result#structureSnapshotAvailable()} 把这件事暴露出去，
 * 日志据此判断「保护区来源本次是否真的生效」，而不是把「查不到」当成「没有」。</p>
 */
public final class GroundBanSources {

    private GroundBanSources() {
    }

    /**
     * 水面探针：只回答「这里的水深是否达到架桥阈值」。
     *
     * <p>刻意不暴露水深数值 —— 调用方要的是「能不能铺地面」这个布尔结论，
     * 阈值细节留在适配层，避免核心逻辑跟着地形实现变化。</p>
     */
    @FunctionalInterface
    public interface WaterProbe {
        boolean isBridgeWater(int x, int z);
    }

    /** 结构占地探针：该位置是否被已验证结构体占用。 */
    @FunctionalInterface
    public interface StructureProbe {
        boolean blocks(BlockPos pos);
    }

    /**
     * 收集结果。
     *
     * @param banned                     每站「地面不可用」掩码
     * @param waterStations              由「水面」判据置位的站数
     * @param structureStations          由「保护区」判据置位的站数（与水面可能重叠）
     * @param structureSnapshotAvailable 保护区快照是否可用；false 表示本来源本次未生效
     */
    public record Result(boolean[] banned,
                         int waterStations,
                         int structureStations,
                         boolean structureSnapshotAvailable) {

        /** 被置位的总站数（重叠只算一次）。 */
        public int bannedStations() {
            if (banned == null) return 0;
            int c = 0;
            for (boolean b : banned) {
                if (b) c++;
            }
            return c;
        }
    }

    /**
     * 纯逻辑核心：按两个探针构造禁地面掩码。**不依赖任何 Minecraft 运行时**。
     *
     * @param water        水面探针；{@code null} 表示本来源不可用
     * @param structure    结构探针；{@code null} 表示本来源不可用
     * @param snapshotOk   结构快照是否可用（仅用于结果标注，不影响掩码计算）
     * @param centers      每站中心坐标
     * @param n            站数
     * @return 永不返回 {@code null}；来源不可用时对应掩码位保持 false 并如实标注
     */
    public static Result collect(WaterProbe water,
                                 StructureProbe structure,
                                 boolean snapshotOk,
                                 List<BlockPos> centers,
                                 int n) {
        if (n <= 0) {
            return new Result(new boolean[0], 0, 0, false);
        }
        boolean[] banned = new boolean[n];
        if (centers == null || centers.size() < n) {
            // 坐标不全就无法判定任何来源；返回空掩码并由调用方在日志里体现。
            return new Result(banned, 0, 0, false);
        }

        int waterCount = 0;
        int structureCount = 0;

        for (int i = 0; i < n; i++) {
            BlockPos center = centers.get(i);
            if (center == null) continue;

            // ① 水面：完全独立于旧判定，只问「这里的水深够不够架桥」。
            if (water != null && water.isBridgeWater(center.getX(), center.getZ())) {
                banned[i] = true;
                waterCount++;
            }

            // ② 保护区：独立于旧判定，只问「这个位置是否压在已验证结构体的占地里」。
            //    这里问的是「站点本身能不能铺地面」，水平安全边距由调用方另行处理。
            if (structure != null && structure.blocks(center)) {
                banned[i] = true;
                structureCount++;
            }
        }

        return new Result(banned, waterCount, structureCount,
                structure != null && snapshotOk);
    }

    /**
     * 生产入口：直接接地形场与结构避让服务。
     *
     * <p>适配只发生在这里 —— 核心逻辑见上面的重载。两个来源任一不可用都不影响另一个。</p>
     *
     * @param terrain             地形场；{@code null} 表示无法判水面
     * @param centers             每站中心坐标
     * @param n                   站数
     * @param bridgeMinWaterDepth 水深达到多少格才算「必须架桥」
     */
    public static Result collect(PathTerrainField terrain,
                                 List<BlockPos> centers,
                                 int n,
                                 int bridgeMinWaterDepth) {
        if (n <= 0) {
            return new Result(new boolean[0], 0, 0, false);
        }
        WaterProbe water = terrain == null
                ? null
                : (x, z) -> terrain.isBridgeWater(x, z, Math.max(1, bridgeMinWaterDepth));
        // 半径 0：只问站点本身，施工带的水平余量不在这里重复计算。
        StructureProbe structure = pos -> StructureAvoidanceService.shouldAvoidPlanning(pos, 0);
        boolean snapshotOk = safeSnapshotPresent();
        return collect(water, structure, snapshotOk, centers, n);
    }

    /** 探测快照是否存在；探测本身不允许影响生产，异常一律当不可用。 */
    private static boolean safeSnapshotPresent() {
        try {
            return StructureAvoidanceCache.planningSnapshotPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }
}