/* 文件职责：清出路面上方的净空，同时不漏出一根未受控的落沙柱。
 *
 * 「落沙柱」是这里的核心风险：直接往上挖到空气会切断沙砾的支撑链，
 * 于是整列沙砾在玩家靠近时连锁下落 —— 必须在挖通之前先判定并处理。
 */
package net.shiroha233.roadweaver.decoration.system;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.WorldGenLevel;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Clears road headroom without exposing an unchecked falling-block column. */
public final class AboveColumnClearer {
    /** 0.9.005：保底强行净空时被强清的受保护方块要留日志，但带上限避免刷屏。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final java.util.concurrent.atomic.AtomicInteger FORCED_CLEAR_LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_FORCED_CLEAR_LOGS = 64;
    /** 0.9.005：桥段凸起削平的最大削除高度（格），防止在山体里削成通天井。 */
    private static final int MAX_BUMP_CUT = 12;
    public static final int MAX_FALLING_SCAN = 128;
    static final int MAX_SURFACE_CLUTTER_SCAN = 16;
    private static final Predicate<BlockPos> ALLOW_NONE = ignored -> false;

    private AboveColumnClearer() {}

    public record ClearanceCheck(boolean ready, String reason, BlockPos position) {
        private static ClearanceCheck success() {
            return new ClearanceCheck(true, "ready", null);
        }

        private static ClearanceCheck failure(String reason, BlockPos position) {
            return new ClearanceCheck(false, reason, position);
        }
    }

    public record FallingCapGeometry(boolean complete,
                                     String reason,
                                     BlockPos position,
                                     List<BlockPos> removals) {
        public FallingCapGeometry {
            removals = removals == null || removals.isEmpty() ? List.of() : List.copyOf(removals);
        }

        private static FallingCapGeometry complete(List<BlockPos> removals) {
            return new FallingCapGeometry(true, "ready", null, removals);
        }

        private static FallingCapGeometry incomplete(String reason, BlockPos position,
                                                     List<BlockPos> removals) {
            return new FallingCapGeometry(false, reason, position, removals);
        }
    }

    public static void clearAboveColumn(WorldGenLevel world, BlockPos surfacePos, ModConfig cfg) {
        boolean tunnel = cfg != null && cfg.tunnelEnabled();
        // 0.9.005：地表路面也按「隧道净空」取 max —— 玩家在路面和桥面之间不该一会儿顶头一会儿不顶。
        int defaultClear = cfg != null
                ? Math.max(cfg.roadClearHeight(), cfg.tunnelClearHeight())
                : 3;
        int maxH = tunnel
                ? Math.max(2, Math.min(16, cfg.tunnelClearHeight()))
                : Math.max(1, Math.min(16, defaultClear));
        clearAboveColumn(world, surfacePos, cfg, null, maxH);
        // 0.9.005-A：地表段「凸起削平」—— 台地/小突起比净空高时，净空清不透（10 格台地只能清 5 格），
        // 会在两侧留下断头路。这里把高于路面的那一段直接削到与路面齐平（留 0 格），上限 MAX_BUMP_CUT。
        // 隧道不走这里：RoadTunnelBuilder 只用 checkAboveColumnExact / stabilizeFallingCap，
        // 从不调用本重载，所以不会把隧道打穿成天窗。
        flattenBumpsAboveSurface(world, surfacePos, cfg, "road-bump");
    }

    /**
     * 0.9.005-A：把某一列「高于给定高度」的地形整段削平（只向上削，绝不向下挖）。
     * {@code groundTop <= surfaceY} 时循环不执行 ⇒ 平地/水面/谷底完全不受影响。
     *
     * @return 实际削掉的方块数
     */
    private static int flattenBumpsAboveSurface(WorldGenLevel world, BlockPos surfacePos,
                                                ModConfig cfg, String tag) {
        if (world == null || surfacePos == null) return 0;
        // 0.9.005-A 安全闸门（用户明确要求：宁可留着突起不削，也不能削出山谷）
        if (!bumpCutEnabled(cfg)) return 0;
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CLEAR,
                surfacePos.getX(), surfacePos.getY(), surfacePos.getZ())) return 0;
        int maxCut = bumpCutMaxBlocks(cfg);
        if (maxCut <= 0) return 0;
        boolean allowProtected = cfg != null && cfg.roadAppearance().forcedClearProtected();
        // 0.9.005-C（方案 C）：只用非 _WG 的 MOTION_BLOCKING_NO_LEAVES。
        // ① 正确性：_WG 是 worldgen 专用"粗"高度图，它在 TOP_LAYER_MODIFICATION 阶段是否已随
        //    setBlock 刷新，无法在本仓内取证；MOTION_BLOCKING_NO_LEAVES 从 FEATURES 起就随每次
        //    setBlock 更新，是"当前时刻"的高度图 ⇒ 不依赖未验证的假设。
        // ② 语义更好：它排除树叶 ⇒ 树冠不会被当成"地形凸起"去削。
        // ③ 性能：每列 heightmap 查询 10 次 → 5 次（自身 1 + 4 邻域各 1）；getHeight 是 heightmap
        //    数组 O(1) 读，不是逐列扫方块。
        int groundTop = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                surfacePos.getX(), surfacePos.getZ());
        int rise = groundTop - surfacePos.getY();
        // 闸门①：超过单列上限 ⇒ 不削。这不是"小突起"，是山坡——交给 span 判定/隧道逻辑。
        if (rise <= 0 || rise > maxCut) return 0;
        // 闸门②：局部高列占比超过 bumpCutMaxColumnRatio ⇒ 这一段整体在山里，整段不削。
        // 用"当前列的 4 邻域"当作"段"的廉价代理：孤立突起只有 0~1 个邻居比路面高，
        // 顺着山坡走则多半 ≥2 个。这样不需要段级上下文，也就不会把一段山路削成壕沟。
        int tall = 0;
        int neighbours = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = surfacePos.getX() + d[0];
            int nz = surfacePos.getZ() + d[1];
            int nTop = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
            neighbours++;
            if (nTop - surfacePos.getY() > 0) tall++;
        }
        if (neighbours > 0 && tall / (double) neighbours > bumpCutMaxColumnRatio(cfg)) return 0;
        int cleared = 0;
        for (int y = surfacePos.getY(); y <= groundTop; y++) {
            BlockPos bump = new BlockPos(surfacePos.getX(), y, surfacePos.getZ());
            BlockState state = world.getBlockState(bump);
            if (state.isAir()) continue;
            if (isProtected(world, bump, null)) {
                if (!allowProtected) continue;
                if (FORCED_CLEAR_LOGGED.getAndIncrement() < MAX_FORCED_CLEAR_LOGS) {
                    LOGGER.warn("【为修平路面凸起，按既有规则清理受保护方块】 " + "[ROAD-FORCED-CLEAR] flattening protected block {} at {} for surface bump tag={}",
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                            bump, tag);
                }
            }
            world.setBlock(bump, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            cleared++;
        }
        if (cleared > 0) stabilizeFallingCap(world, surfacePos, null, rise + 1);
        return cleared;
    }

    /** 0.9.005-A：凸起削平三个旋钮（读配置失败时退回安全默认：开、12 格、0.5）。 */
    private static boolean bumpCutEnabled(ModConfig cfg) {
        try {
            return cfg == null || cfg.roadAppearance().bumpCutEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int bumpCutMaxBlocks(ModConfig cfg) {
        try {
            return cfg == null ? MAX_BUMP_CUT : Math.max(0, Math.min(24, cfg.roadAppearance().bumpCutMaxBlocks()));
        } catch (Throwable ignored) {
            return MAX_BUMP_CUT;
        }
    }

    private static double bumpCutMaxColumnRatio(ModConfig cfg) {
        try {
            return cfg == null ? 0.5D : Math.max(0.0D, Math.min(1.0D, cfg.roadAppearance().bumpCutMaxColumnRatio()));
        } catch (Throwable ignored) {
            return 0.5D;
        }
    }

    public static void clearAboveColumn(WorldGenLevel world, BlockPos surfacePos, ModConfig cfg,
            RoadData road, int requestedHeight) {
        clearAboveColumn(world, surfacePos, cfg, road, requestedHeight, false);
    }

    public static void clearAboveColumn(WorldGenLevel world, BlockPos surfacePos, ModConfig cfg,
            RoadData road, int requestedHeight, boolean stabilizeFallingCap) {
        clearAboveColumn(world, surfacePos, cfg, road, requestedHeight, stabilizeFallingCap, false);
    }

    /**
     * 0.9.005-C：与 6 参版本相同，但可显式要求「凸起削平」。
     *
     * <p>为什么要参数化：这个重载同时被 <b>地表铺路</b>（{@code RoadBlockPlacer}，需要削平台地）
     * 和 <b>桥面</b>（{@code BridgeBuilder}，桥段自己已经做过凸起削平）调用。桥那段再削一次既
     * 重复查 heightmap、又用不同参考高度重复 setBlock，所以由调用方显式声明，不靠默认值猜。</p>
     */
    public static void clearAboveColumn(WorldGenLevel world, BlockPos surfacePos, ModConfig cfg,
            RoadData road, int requestedHeight, boolean stabilizeFallingCap, boolean flattenBumps) {
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CLEAR, surfacePos.getX(), surfacePos.getY(), surfacePos.getZ())) return;
        int clearHeight = Math.max(1, Math.min(16, requestedHeight));
        ClearancePlan plan = planClearance(world, surfacePos, road, clearHeight, stabilizeFallingCap, ALLOW_NONE);
        if (!plan.check().ready()) return;

        removeTopDown(world, plan.capRemovals());
        clearNetCells(world, surfacePos, clearExtent(false, clearHeight));
        if (!stabilizeFallingCap) removeLegacyCapClutter(world, surfacePos.above(clearHeight), road);
        if (flattenBumps) flattenBumpsAboveSurface(world, surfacePos, cfg, "road-bump");
    }

    /**
     * 0.9.065：一列净空的「可清范围」契约。
     *
     * @param topDy           允许清到 {@code surfacePos.above(topDy)}（含）；0..topDy 才是净空本身
     * @param clearsAboveBore 是否允许<b>越过净空顶部</b>继续向上清（凸起削平 / 落方块封顶 /
     *                        顶层杂物）。隧道洞身段必须为 {@code false} —— 那里上方每一格岩体
     *                        都是隧道自己的顶板。
     */
    public record ClearExtent(int topDy, boolean clearsAboveBore) {
        public ClearExtent {
            topDy = Math.max(-1, Math.min(15, topDy));
        }
    }

    /**
     * 0.9.065：净空可清范围的唯一判定点。{@code tunnelBody=true} ⇒ 只清洞身净空、禁止任何
     * 越过洞顶的动作；否则与 0.9.064 完全一致（普通路段 / 洞口明挖段）。
     *
     * @param requestedHeight 请求净空（格），夹到 [1,16]
     */
    public static ClearExtent clearExtent(boolean tunnelBody, int requestedHeight) {
        int height = Math.max(1, Math.min(16, requestedHeight));
        return new ClearExtent(height - 1, !tunnelBody);
    }

    /**
     * 0.9.065：隧道<b>洞身段</b>专用 —— 只清洞身净空 {@code [surfacePos, surfacePos + clearHeight)}，
     * 一格都不向上多清。
     *
     * <p>为什么必须和地表路径分开：洞身站的岩体本身就是顶。7 参
     * {@link #clearAboveColumn(WorldGenLevel, BlockPos, ModConfig, RoadData, int, boolean, boolean)}
     * 除了清净空，还会调 {@link #flattenBumpsAboveSurface} 把「高于路面」的地形整段削到与路面齐平；
     * 洞身站照走这条路，就会把洞顶岩体一路削到地表，随后
     * {@link net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder} 量到
     * 「覆土 &lt; 1」而放弃封顶 —— 这正是「隧道没顶」。</p>
     *
     * @param requestedHeight 洞身净空（格），与 {@code RoadTunnelBuilder.clearHeight()} 同源
     * @return 实际清成空气的格数
     */
    public static int clearTunnelBoreHeadroom(WorldGenLevel world, BlockPos surfacePos,
                                              ModConfig cfg, RoadData road, int requestedHeight) {
        if (world == null || surfacePos == null) return 0;
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CLEAR,
                surfacePos.getX(), surfacePos.getY(), surfacePos.getZ())) return 0;
        ClearExtent extent = clearExtent(true, requestedHeight);
        // 契约守卫：洞身段永远不许向上清空。这里显式断言，改坏了立刻炸在本地而不是在山体里。
        if (extent.clearsAboveBore()) {
            throw new IllegalStateException("tunnel bore clear must never clear above the bore");
        }
        return clearNetCells(world, surfacePos, extent);
    }

    /** 把 {@link ClearExtent} 描述的那一串格子清成空气；洞身段与地表路径共用这一个循环。 */
    private static int clearNetCells(WorldGenLevel world, BlockPos surfacePos, ClearExtent extent) {
        int cleared = 0;
        for (int i = 0; i <= extent.topDy(); i++) {
            BlockPos up = surfacePos.above(i);
            if (world.getBlockState(up).isAir()) continue;
            world.setBlock(up, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            cleared++;
        }
        return cleared;
    }

    /**
     * 0.9.005：桥面净空高度（格）。{@code bridge.clearHeight} 为 0 时跟随
     * {@code roadAppearance.tunnelClearHeight}（默认 5），夹到 [1,16]。
     */
    public static int bridgeHeadroom(ModConfig cfg) {
        int configured = cfg == null ? 0 : cfg.bridge().clearHeight();
        int fallback = cfg == null
                ? net.shiroha233.roadweaver.core.constants.RoadConstants.DEFAULT_TUNNEL_CLEAR_HEIGHT
                : cfg.tunnelClearHeight();
        return Math.max(1, Math.min(16, configured > 0 ? configured : fallback));
    }

    /**
     * 0.9.005：保底强行净空。**只清 [surfacePos, surfacePos + height) 这一竖向带**，
     * 一格都不动 surfacePos 以下 —— 所以不会把地形挖成峡谷，也不会掏空桥基/河床。
     *
     * <p>与 {@link #clearAboveColumn} 的区别是它**永不因「受保护方块」而放弃**：
     * 自然地形/树照旧直接清；受保护方块（基岩、方块实体、FEATURES_CANNOT_REPLACE、
     * 巨大树预留区、结构盒）在 {@code allowProtected=true} 时也一并清掉，但每格打一条
     * WARN（带方块 id 与坐标），便于事后排查是谁的建筑被削了。</p>
     *
     * <p>清完会再跑一次 {@link #stabilizeFallingCap}，避免净空上方悬着的沙/砾石塌回来。</p>
     *
     * @param allowProtected 是否连同受保护方块一起强清（= {@code roadAppearance.forcedClearProtected}）
     * @param tag            调用来源标记，进 WARN 便于定位（bridge-deck / bridge-side / template-bridge）
     * @return 实际清掉的方块数
     */
    public static int clearForcedHeadroom(WorldGenLevel world, BlockPos surfacePos, RoadData road,
                                          int height, boolean allowProtected, String tag) {
        if (world == null || surfacePos == null) return 0;
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CLEAR,
                surfacePos.getX(), surfacePos.getY(), surfacePos.getZ())) return 0;
        int clearHeight = Math.max(1, Math.min(16, height));
        int cleared = 0;
        for (int i = 0; i < clearHeight; i++) {
            BlockPos up = surfacePos.above(i);              // ← 只向上：surfacePos 及以下永不触碰
            BlockState state = world.getBlockState(up);
            if (state.isAir()) continue;
            if (isProtected(world, up, road)) {
                if (!allowProtected) continue;              // 回退档：不动，交回调用方的旧「拒建」逻辑
                if (FORCED_CLEAR_LOGGED.getAndIncrement() < MAX_FORCED_CLEAR_LOGS) {
                    LOGGER.warn("【为保留道路净空，按既有规则清理受保护方块】 " + "[ROAD-FORCED-CLEAR] clearing protected block {} at {} for road headroom tag={}",
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                            up, tag);
                }
            }
            world.setBlock(up, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            cleared++;
        }
        if (cleared > 0) stabilizeFallingCap(world, surfacePos, road, clearHeight);

        // 0.9.005 ①：桥段「凸起削平」。净空带只清 clearHeight 格，削不掉 10 格高的台地 —— 这里再
        // 把【高于桥面】的地形整段削到与桥面齐平（surfacePos 就是桥面+1 / 侧向即桥面本身）。
        // 判据天然区分：跨海/跨谷的桥 groundTop <= surfacePos.y ⇒ 循环不执行，海床完全不受影响。
        // 上限 MAX_BUMP_CUT 防止在山体里把整座山削成通天井（那种情况该走隧道，不在本轮范围）。
        // 0.9.005-C（方案 C）：只用非 _WG 的 MOTION_BLOCKING_NO_LEAVES。
        // ① 正确性：_WG 是 worldgen 专用"粗"高度图，它在 TOP_LAYER_MODIFICATION 阶段是否已随
        //    setBlock 刷新，无法在本仓内取证；MOTION_BLOCKING_NO_LEAVES 从 FEATURES 起就随每次
        //    setBlock 更新，是"当前时刻"的高度图 ⇒ 不依赖未验证的假设。
        // ② 语义更好：它排除树叶 ⇒ 树冠不会被当成"地形凸起"去削。
        // ③ 性能：每列 heightmap 查询 10 次 → 5 次（自身 1 + 4 邻域各 1）；getHeight 是 heightmap
        //    数组 O(1) 读，不是逐列扫方块。
        int groundTop = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                surfacePos.getX(), surfacePos.getZ());
        int bridgeRise = groundTop - surfacePos.getY();
        // 闸门①（同地表段）：超过上限就不削，避免把山坡削成崖壁+平台。
        int capTop = bridgeRise <= 0 || bridgeRise > MAX_BUMP_CUT
                ? surfacePos.getY() - 1
                : Math.min(groundTop, surfacePos.getY() + MAX_BUMP_CUT);
        for (int y = surfacePos.getY(); y <= capTop; y++) {
            BlockPos bump = new BlockPos(surfacePos.getX(), y, surfacePos.getZ());
            if (world.getBlockState(bump).isAir()) continue;
            if (isProtected(world, bump, road) && !allowProtected) continue;
            if (isProtected(world, bump, road)
                    && FORCED_CLEAR_LOGGED.getAndIncrement() < MAX_FORCED_CLEAR_LOGS) {
                LOGGER.warn("【为修平桥面凸起，按既有规则清理受保护方块】 " + "[ROAD-FORCED-CLEAR] flattening protected block {} at {} for bridge bump tag={}",
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                                world.getBlockState(bump).getBlock()), bump, tag);
            }
            world.setBlock(bump, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            cleared++;
        }
        if (capTop > surfacePos.getY()) stabilizeFallingCap(world, surfacePos, road, capTop - surfacePos.getY() + 1);
        return cleared;
    }

    /**
     * 0.9.005：模板桥的桥面列净空。只清 [deckY+1, deckY+1+净空) —— 向下不动，所以不会挖成峡谷。
     * 只清桥面列、**不做侧向**：模板的桥面/栏杆是按段贴上去的，侧向强清会削掉相邻段已经贴好的
     * 模板 voxel（procedural 桥那边才做侧向 ±bridge.sideClear）。
     */
    public static void clearTemplateBridgeHeadroom(WorldGenLevel world,
                                                   net.shiroha233.roadweaver.core.model.RoadSegmentPlacement segment,
                                                   int deckY, RoadData road, ModConfig cfg) {
        if (world == null || segment == null || segment.positions() == null) return;
        int headroom = bridgeHeadroom(cfg);
        for (BlockPos widthPos : segment.positions()) {
            if (widthPos == null) continue;
            clearForcedHeadroom(world, new BlockPos(widthPos.getX(), deckY + 1, widthPos.getZ()),
                    road, headroom, true, "template-bridge-deck");
        }
    }

    public static ClearanceCheck checkAboveColumn(WorldGenLevel world, BlockPos surfacePos,
            RoadData road, int requestedHeight, boolean stabilizeFallingCap) {
        int clearHeight = Math.max(1, Math.min(16, requestedHeight));
        return planClearance(world, surfacePos, road, clearHeight, stabilizeFallingCap, ALLOW_NONE).check();
    }

    public static ClearanceCheck checkAboveColumnExact(WorldGenLevel world, BlockPos surfacePos,
            RoadData road, int clearHeight, boolean stabilizeFallingCap) {
        return checkAboveColumnExact(world, surfacePos, road, clearHeight, stabilizeFallingCap, ALLOW_NONE);
    }

    public static ClearanceCheck checkAboveColumnExact(WorldGenLevel world, BlockPos surfacePos,
            RoadData road, int clearHeight, boolean stabilizeFallingCap,
            Predicate<BlockPos> allowedExistingBlock) {
        return planClearance(world, surfacePos, road, Math.max(1, clearHeight), stabilizeFallingCap,
                allowedExistingBlock == null ? ALLOW_NONE : allowedExistingBlock).check();
    }

    /** May be called before the matching headroom is cleared. */
    public static boolean stabilizeFallingCap(WorldGenLevel world, BlockPos surfacePos,
            RoadData road, int clearedHeight) {
        FallingCapPlan plan = planFallingCap(world, surfacePos, road, Math.max(1, clearedHeight), ALLOW_NONE);
        if (!plan.safe()) return false;
        removeTopDown(world, plan.removable());
        return true;
    }

    private static ClearancePlan planClearance(WorldGenLevel world, BlockPos surfacePos,
            RoadData road, int clearHeight, boolean stabilizeFallingCap,
            Predicate<BlockPos> allowedExistingBlock) {
        if (world == null || surfacePos == null) {
            return new ClearancePlan(ClearanceCheck.failure("invalid-clearance-column", surfacePos), List.of());
        }
        for (int i = 0; i < clearHeight; i++) {
            BlockPos position = surfacePos.above(i);
            if (!world.getBlockState(position).isAir() && isProtected(world, position, road)
                    && !allowedExistingBlock.test(position)) {
                // 0.9.027：把「挡住净空的那个方块」带进 reason。
                // 起因是 N-10：整局只有 2 站报 protected-headroom，但日志只给坐标不给方块 id，
                // 于是「这洞是石头堵的还是别的 mod 的结构挡的」只能靠猜。
                // 这里用 `protected-headroom:<blockId>`，让隧道的 PROTECTED_GAP 行直接多出一段可 grep 的方块名。
                return new ClearancePlan(ClearanceCheck.failure(
                        "protected-headroom:" + blockId(world, position), position), List.of());
            }
        }
        if (!stabilizeFallingCap) {
            return new ClearancePlan(ClearanceCheck.success(), List.of());
        }
        FallingCapPlan falling = planFallingCap(world, surfacePos, road, clearHeight, allowedExistingBlock);
        if (!falling.safe()) {
            return new ClearancePlan(ClearanceCheck.failure(falling.reason(), falling.position()), List.of());
        }
        return new ClearancePlan(ClearanceCheck.success(), falling.removable());
    }

    /** 方块注册名（{@code minecraft:stone} / {@code bettershafts:...}），用于把净空否决点写进日志。 */
    private static String blockId(WorldGenLevel world, BlockPos position) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(world.getBlockState(position).getBlock()).toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static FallingCapPlan planFallingCap(WorldGenLevel world, BlockPos surfacePos,
            RoadData road, int clearedHeight, Predicate<BlockPos> allowedExistingBlock) {
        int capY = fallingCapStartY(surfacePos.getY(), clearedHeight);
        if (capY >= world.getMaxBuildHeight()) return FallingCapPlan.safe(List.of());
        int columnTop = Math.max(
                world.getHeight(Heightmap.Types.WORLD_SURFACE_WG, surfacePos.getX(), surfacePos.getZ()),
                world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        surfacePos.getX(), surfacePos.getZ()));
        FallingCapGeometry geometry = inspectFallingCap(
                surfacePos, clearedHeight, world.getMaxBuildHeight(), columnTop,
                y -> world.getBlockState(new BlockPos(surfacePos.getX(), y, surfacePos.getZ())));
        if (!geometry.complete()) {
            return FallingCapPlan.failure(geometry.reason(), geometry.position());
        }
        for (BlockPos position : geometry.removals()) {
            if (isProtected(world, position, road) && !allowedExistingBlock.test(position)) {
                String reason = isFallingBlock(world.getBlockState(position))
                        ? "protected-falling-block" : "protected-falling-cap";
                return FallingCapPlan.failure(reason, position);
            }
        }
        return FallingCapPlan.safe(geometry.removals());
    }

    static FallingCapGeometry inspectFallingCap(BlockPos surfacePos,
                                                int clearedHeight,
                                                int maxBuildHeight,
                                                int columnTop,
                                                IntFunction<BlockState> stateAtY) {
        if (surfacePos == null || stateAtY == null) {
            return FallingCapGeometry.incomplete("invalid-falling-cap-column", surfacePos, List.of());
        }
        int capY = fallingCapStartY(surfacePos.getY(), clearedHeight);
        if (capY >= maxBuildHeight || columnTop <= capY) return FallingCapGeometry.complete(List.of());

        int limitY = Math.min(maxBuildHeight - 1, fallingCapEndY(surfacePos.getY(), clearedHeight));
        List<BlockPos> removable = new ArrayList<>();
        boolean fallingChain = false;
        for (int y = capY; y <= limitY; y++) {
            BlockState state = stateAtY.apply(y);
            if (fallingChain) {
                if (isFallingBlock(state)) {
                    removable.add(new BlockPos(surfacePos.getX(), y, surfacePos.getZ()));
                    continue;
                }
                return FallingCapGeometry.complete(removable);
            }
            if (state == null || state.isAir()) continue;
            if (isCapClutter(state)) {
                removable.add(new BlockPos(surfacePos.getX(), y, surfacePos.getZ()));
                continue;
            }
            if (isFallingBlock(state)) {
                removable.add(new BlockPos(surfacePos.getX(), y, surfacePos.getZ()));
                fallingChain = true;
                continue;
            }
            return FallingCapGeometry.complete(removable);
        }
        if (columnTop > limitY + 1) {
            BlockPos limit = new BlockPos(surfacePos.getX(), limitY + 1, surfacePos.getZ());
            return FallingCapGeometry.incomplete(
                    fallingChain ? "falling-column-scan-limit" : "falling-cap-scan-limit",
                    limit, removable);
        }
        return FallingCapGeometry.complete(removable);
    }

    public static int fallingCapStartY(int surfaceY, int clearedHeight) {
        long start = (long) surfaceY + Math.max(1, clearedHeight);
        return start > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) start;
    }

    public static int fallingCapEndY(int surfaceY, int clearedHeight) {
        long end = (long) fallingCapStartY(surfaceY, clearedHeight) + MAX_FALLING_SCAN;
        return end > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) end;
    }

    private static boolean isProtected(WorldGenLevel world, BlockPos position, RoadData road) {
        return RoadConstructionSafety.isProtectedPreparedBlock(world, position, road);
    }

    private static void removeTopDown(WorldGenLevel world, List<BlockPos> positions) {
        for (int i = positions.size() - 1; i >= 0; i--) {
            BlockPos position = positions.get(i);
            // 统一出口：这一路过去只查 isProtected（方块实体/FEATURES_CANNOT_REPLACE/基岩），
            // 石砖墙不在保护范围。长城列一律不删。
            if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                    net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CUT,
                    position.getX(), position.getY(), position.getZ())) continue;
            world.setBlock(position, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        }
    }

    private static void removeLegacyCapClutter(WorldGenLevel world, BlockPos cap, RoadData road) {
        BlockState state = world.getBlockState(cap);
        if ((state.getBlock() instanceof BushBlock || state.getBlock() instanceof SnowLayerBlock)
                && !isProtected(world, cap, road)
                && !net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                        net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CUT,
                        cap.getX(), cap.getY(), cap.getZ())) {
            world.removeBlock(cap, false);
        }
    }

    /** Removes a plant or snow chain left unsupported after a terrain cut. */
    public static int clearUnsupportedSurfaceClutter(WorldGenLevel world, BlockPos originalSurface,
                                                     RoadData road) {
        if (world == null || originalSurface == null) return 0;
        List<BlockPos> removals = inspectUnsupportedSurfaceClutter(
                originalSurface,
                world.getMaxBuildHeight(),
                y -> world.getBlockState(new BlockPos(originalSurface.getX(), y, originalSurface.getZ())));
        for (BlockPos removal : removals) {
            if (isProtected(world, removal, road)) return 0;
        }
        removeTopDown(world, removals);
        return removals.size();
    }

    static List<BlockPos> inspectUnsupportedSurfaceClutter(BlockPos originalSurface,
                                                            int maxBuildHeight,
                                                            IntFunction<BlockState> stateAtY) {
        if (originalSurface == null || stateAtY == null || originalSurface.getY() >= maxBuildHeight) {
            return List.of();
        }
        int limitY = Math.min(maxBuildHeight - 1,
                originalSurface.getY() + MAX_SURFACE_CLUTTER_SCAN - 1);
        ArrayList<BlockPos> removals = new ArrayList<>();
        boolean foundClutter = false;
        for (int y = originalSurface.getY(); y <= limitY; y++) {
            BlockState state = stateAtY.apply(y);
            if (state == null || state.isAir()) {
                if (foundClutter) break;
                continue;
            }
            if (!isCapClutter(state)) break;
            foundClutter = true;
            removals.add(new BlockPos(originalSurface.getX(), y, originalSurface.getZ()));
        }
        return List.copyOf(removals);
    }

    public static boolean isCapClutter(BlockState state) {
        return state != null
                && (state.getBlock() instanceof BushBlock || state.getBlock() instanceof SnowLayerBlock);
    }

    public static boolean isFallingBlock(BlockState state) {
        return state != null && state.getBlock() instanceof FallingBlock;
    }

    private record ClearancePlan(ClearanceCheck check, List<BlockPos> capRemovals) {
        private ClearancePlan {
            capRemovals = List.copyOf(capRemovals);
        }
    }

    private record FallingCapPlan(boolean safe, String reason, BlockPos position, List<BlockPos> removable) {
        private FallingCapPlan {
            removable = List.copyOf(removable);
        }

        private static FallingCapPlan safe(List<BlockPos> removable) {
            return new FallingCapPlan(true, "ready", null, removable);
        }

        private static FallingCapPlan failure(String reason, BlockPos position) {
            return new FallingCapPlan(false, reason, position, List.of());
        }
    }
}
