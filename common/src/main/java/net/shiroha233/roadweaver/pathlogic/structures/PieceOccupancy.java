/* 文件职责：单个结构碎片的方块占位判定 —— 逐块分类成实心 / 杂物 / 会掉的块。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import net.shiroha233.roadweaver.compat.SinglePoolElementTemplateAccess;
import net.shiroha233.roadweaver.compat.StructureTemplatePaletteAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 单个结构碎片的占位信息。
 *
 * <h2>它回答的唯一问题</h2>
 * <p>「这条路在这一列会不会撞到这座结构的实体方块？」—— 路面铺在 y 附近时，
 * 结构里飘在高空的装饰不该挡路，而地板、墙、会下落的方块该挡路。</p>
 *
 * <h2>为什么分三档而不是布尔</h2>
 * <ul>
 *   <li>{@code SOLID} —— 实心，冲突</li>
 *   <li>{@code CLUTTER} —— 杂物（告示牌、火把一类），路可以压过去</li>
 *   <li>{@code FALLING} —— 会掉落的块（沙、砂砾），单独处理以免路修好后塌方</li>
 * </ul>
 *
 * <h2>解析是惰性的，而且有缓存上限</h2>
 * <p>模板解析（把 StructureTemplate 展开成方块集合）很贵，所以只在第一次询问时做，
 * 结果按 {@link #MAX_CACHED_BLOCKS} 封顶缓存 —— 超出就不再缓存，宁可重算也不吃满内存。</p>
 *
 * <p>本类是 2026-11-12 从 4766 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>
 * 出来的（含它用到的 {@link FallingCapOccupancy} 枚举），一行逻辑都没改 ——
 * 搬迁目的是让那个文件能继续拆下去。</p>
 */
enum FallingCapOccupancy {
    AIR,
    SOLID,
    REMOVABLE,
    UNKNOWN
}

final class PieceOccupancy {
    /** 与原外部类同名同 logger —— 搬迁不改变任何日志输出。 */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("roadweaver");
    private static final int MAX_CACHED_BLOCKS = 200_000;
    private static final int MAX_LOGGED_UNSUPPORTED_CLASSES = 64;
    private static final byte SOLID = 1;
    private static final byte CLUTTER = 1 << 1;
    private static final byte FALLING = 1 << 2;

    // ---- 取证（零行为）：模板解析到底花了多少时间、同一个模板被重复解析了多少次 ----
    //
    // 结构解析是开局第二大耗时项（基线 251 s），但一直没有模板级计时，所以"能不能靠记忆化
    // 省下来"只能靠猜。这里的计数器不改变任何判定：一个实例 = 一次（模板 × 原点 × 镜像 ×
    // 旋转 × 支点）的落位，所以 count/templates 就是**复用倍数** —— 接近 1 说明每个模板只用
    // 一次（记忆化没用），远大于 1 才值得做。
    private static final Object RESOLVE_LOCK = new Object();
    private static final java.util.Set<StructureTemplate> RESOLVED_TEMPLATES =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static long resolveCount;
    private static long resolveNanos;
    private static long resolveBlocks;
    private static long resolveOverflow;
    private static long resolveNotPalette;
    private static long resolveNullTemplate;
    private static long resolveFailed;

    static void clearResolveDiagnostics() {
        synchronized (RESOLVE_LOCK) {
            RESOLVED_TEMPLATES.clear();
            resolveCount = 0L;
            resolveNanos = 0L;
            resolveBlocks = 0L;
            resolveOverflow = 0L;
            resolveNotPalette = 0L;
            resolveNullTemplate = 0L;
            resolveFailed = 0L;
        }
    }

    private final Supplier<StructureTemplate> templateResolver;
    private final BlockPos origin;
    private final Mirror mirror;
    private final net.minecraft.world.level.block.Rotation rotation;
    private final BlockPos rotationPivot;
    private volatile Map<Long, Byte> occupied;
    private volatile List<AABB> collisionBoxes;
    private volatile boolean resolved;
    private volatile boolean available;

    private PieceOccupancy(Supplier<StructureTemplate> templateResolver,
                           BlockPos origin,
                           Mirror mirror,
                           net.minecraft.world.level.block.Rotation rotation,
                           BlockPos rotationPivot) {
        this.templateResolver = templateResolver;
        this.origin = origin;
        this.mirror = mirror;
        this.rotation = rotation;
        this.rotationPivot = rotationPivot;
    }

    private PieceOccupancy(Map<Long, Byte> occupied) {
        this(occupied, null);
    }

    private PieceOccupancy(Map<Long, Byte> occupied, List<AABB> suppliedCollisionBoxes) {
        this.templateResolver = null;
        this.origin = BlockPos.ZERO;
        this.mirror = Mirror.NONE;
        this.rotation = net.minecraft.world.level.block.Rotation.NONE;
        this.rotationPivot = BlockPos.ZERO;
        this.occupied = Map.copyOf(occupied);
        if (suppliedCollisionBoxes == null) {
            List<AABB> boxes = new ArrayList<>(occupied.size());
            for (long key : occupied.keySet()) {
                BlockPos pos = BlockPos.of(key);
                boxes.add(new AABB(pos));
            }
            this.collisionBoxes = List.copyOf(boxes);
        } else {
            this.collisionBoxes = List.copyOf(suppliedCollisionBoxes);
        }
        this.resolved = true;
        this.available = true;
    }

    static PieceOccupancy forTest(Map<BlockPos, BlockState> blocks) {
        Map<Long, Byte> classified = new HashMap<>();
        List<AABB> shapes = new ArrayList<>();
        if (blocks != null) {
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                BlockPos position = entry.getKey();
                BlockState state = entry.getValue();
                if (position == null || ignoredState(state)) continue;
                classified.merge(position.asLong(), classify(state),
                        (left, right) -> (byte) (left | right));
                var collision = state.getCollisionShape(
                        EmptyBlockGetter.INSTANCE, position, CollisionContext.empty());
                if (!collision.isEmpty()) {
                    for (AABB local : collision.toAabbs()) shapes.add(local.move(position));
                }
            }
        }
        return new PieceOccupancy(classified, shapes);
    }

    static PieceOccupancy forPiece(ServerLevel level, StructurePiece piece) {
        if (level == null || piece == null) return null;
        if (piece instanceof PoolElementStructurePiece pool
                && pool.getElement() instanceof SinglePoolElement single) {
            return new PieceOccupancy(
                    () -> single instanceof SinglePoolElementTemplateAccess templateAccess
                            ? templateAccess.roadweaver$getTemplate(level.getStructureManager())
                            : null,
                    pool.getPosition(), Mirror.NONE, pool.getRotation(), BlockPos.ZERO);
        }
        if (piece instanceof TemplateStructurePiece templatePiece) {
            StructurePlaceSettings settings = templatePiece.placeSettings();
            if (settings != null) {
                return new PieceOccupancy(
                        templatePiece::template,
                        templatePiece.templatePosition(),
                        settings.getMirror(),
                        settings.getRotation(),
                        settings.getRotationPivot());
            }
        }
        String className = piece.getClass().getName();
        // 0.9.023（O-09 / N-01）：同一种 piece 类型只打一次，其余降到 DEBUG。
        // 真机实测这一行会按 piece 类型反复刷（一局几十上百行：vanilla class_3353$*、
        // class_3421$*、bettermineshafts 9 种、biomeswevegone GourPlateauPiece 等），
        // 功能完全正常、纯噪音。
        //
        // 0.9.033：外层 StructureAvoidanceCache.UNSUPPORTED_PIECE_CLASSES 已经是「每种类型只进一次 + 总数上限
        // MAX_LOGGED_UNSUPPORTED_CLASSES」去重，所以 0.9.023 又加的内层
        // OCCUPANCY_FALLBACK_TYPES 永远只会 add 成功、它的 else-DEBUG 分支是死代码；
        // 而且内层是个**无上限**的静态集合（外层有上限），长跑会缓慢膨胀。删掉内层，
        // 只留外层。
        if (StructureAvoidanceCache.UNSUPPORTED_PIECE_CLASSES.size() < MAX_LOGGED_UNSUPPORTED_CLASSES
                && StructureAvoidanceCache.UNSUPPORTED_PIECE_CLASSES.add(className)) {
            LOGGER.info("RoadWeaver structure occupancy fallback for unsupported piece type: {} "
                    + "(logged once per piece type)", className);
        }
        return null;
    }

    Boolean intersects(int x, int z, int minY, int maxY, int horizontalRadius) {
        ensureResolved();
        Map<Long, Byte> blocks = occupied;
        if (!available || blocks == null) return null;
        List<AABB> shapes = collisionBoxes;
        // A resolved template with no collision boxes is valid (for
        // example foliage or decorative blocks) and must not fall back
        // to whole-block occupancy.
        if (shapes != null) {
            AABB roadBox = new AABB(
                    x - horizontalRadius, minY, z - horizontalRadius,
                    x + horizontalRadius + 1.0D, maxY + 1.0D, z + horizontalRadius + 1.0D);
            for (AABB shape : shapes) {
                if (shape != null && shape.intersects(roadBox)) return true;
            }
            return false;
        }
        for (int px = x - horizontalRadius; px <= x + horizontalRadius; px++) {
            for (int pz = z - horizontalRadius; pz <= z + horizontalRadius; pz++) {
                for (int py = minY; py <= maxY; py++) {
                    if (blocks.containsKey(BlockPos.asLong(px, py, pz))) return true;
                }
            }
        }
        return false;
    }

    Boolean intersectsSolid(int x, int z, int minY, int maxY) {
        ensureResolved();
        Map<Long, Byte> blocks = occupied;
        if (!available || blocks == null) return null;
        for (int py = minY; py <= maxY; py++) {
            Byte kind = blocks.get(BlockPos.asLong(x, py, z));
            if (kind != null && (kind & SOLID) != 0) return true;
        }
        return false;
    }

    FallingCapOccupancy fallingCapOccupancyAt(int x, int y, int z) {
        ensureResolved();
        Map<Long, Byte> blocks = occupied;
        if (!available || blocks == null) return FallingCapOccupancy.UNKNOWN;
        Byte kind = blocks.get(BlockPos.asLong(x, y, z));
        if (kind == null) return FallingCapOccupancy.AIR;
        return (kind & (CLUTTER | FALLING)) != 0
                ? FallingCapOccupancy.REMOVABLE : FallingCapOccupancy.SOLID;
    }

    String kind() {
        ensureResolved();
        return available && occupied != null ? "template-blocks" : "fallback-box";
    }

    BoundingBox collisionBounds() {
        ensureResolved();
        List<AABB> shapes = collisionBoxes;
        if (!available || shapes == null || shapes.isEmpty()) return null;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (AABB shape : shapes) {
            if (shape == null) continue;
            minX = Math.min(minX, shape.minX);
            minY = Math.min(minY, shape.minY);
            minZ = Math.min(minZ, shape.minZ);
            maxX = Math.max(maxX, shape.maxX);
            maxY = Math.max(maxY, shape.maxY);
            maxZ = Math.max(maxZ, shape.maxZ);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)) return null;
        return new BoundingBox(
                (int) Math.floor(minX), (int) Math.floor(minY), (int) Math.floor(minZ),
                (int) Math.ceil(maxX) - 1, (int) Math.ceil(maxY) - 1, (int) Math.ceil(maxZ) - 1);
    }

    private void ensureResolved() {
        if (resolved) return;
        synchronized (this) {
            if (resolved) return;
            long startedNanos = System.nanoTime();   // 取证用，不参与判定
            StructureTemplate source = null;
            boolean nullTemplate = false;
            boolean notPalette = false;
            boolean overflow = false;
            boolean failed = false;
            int blocksRead = 0;
            try {
                StructureTemplate template = templateResolver.get();
                if (template == null) { nullTemplate = true; return; }
                source = template;
                if (!(template instanceof StructureTemplatePaletteAccess paletteAccess)) { notPalette = true; return; }
                List<StructureTemplate.Palette> palettes = paletteAccess.roadweaver$getPalettes();
                Map<Long, Byte> blocks = new HashMap<>();
                List<AABB> shapes = new ArrayList<>();
                for (StructureTemplate.Palette palette : palettes) {
                    for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
                        if (ignoredState(info.state())) continue;
                        BlockPos transformed = StructureTemplate.transform(
                                info.pos(), mirror, rotation, rotationPivot).offset(origin);
                        blocks.merge(transformed.asLong(), classify(info.state()),
                                (left, right) -> (byte) (left | right));
                        BlockState transformedState = info.state().mirror(mirror).rotate(rotation);
                        var collision = transformedState.getCollisionShape(
                                EmptyBlockGetter.INSTANCE, transformed, CollisionContext.empty());
                        if (!collision.isEmpty()) {
                            for (AABB local : collision.toAabbs()) {
                                shapes.add(local.move(transformed));
                            }
                        }
                        blocksRead++;
                        if (blocks.size() > MAX_CACHED_BLOCKS) { overflow = true; return; }
                    }
                }
                occupied = Map.copyOf(blocks);
                collisionBoxes = List.copyOf(shapes);
                available = true;
            } catch (RuntimeException ignored) {
                failed = true;
                available = false;
            } finally {
                resolved = true;
                recordResolution(startedNanos, blocksRead, source,
                        nullTemplate, notPalette, overflow, failed);
            }
        }
    }

    /**
     * 记账：模板解析的耗时与复用倍数。只读计数器，不改变任何结果。
     *
     * <p>日志 `STRUCTURE_RESOLVE` 每 256 次解析一行；`reuse = count / templates` 就是
     * "同一个模板被解析了几遍"，这是决定要不要做模板级记忆化的唯一判据。
     */
    private static void recordResolution(long startedNanos, int blocksRead, StructureTemplate source,
                                         boolean nullTemplate, boolean notPalette,
                                         boolean overflow, boolean failed) {
        long elapsed = System.nanoTime() - startedNanos;
        long count;
        long templates;
        long totalNanos;
        long totalBlocks;
        synchronized (RESOLVE_LOCK) {
            resolveNanos += elapsed;
            resolveBlocks += blocksRead;
            if (nullTemplate) resolveNullTemplate++;
            if (notPalette) resolveNotPalette++;
            if (overflow) resolveOverflow++;
            if (failed) resolveFailed++;
            if (source != null) RESOLVED_TEMPLATES.add(source);
            count = ++resolveCount;
            templates = RESOLVED_TEMPLATES.size();
            totalNanos = resolveNanos;
            totalBlocks = resolveBlocks;
        }
        if ((count & 255L) == 0L) {
            LOGGER.info("STRUCTURE_RESOLVE count={} templates={} reuse={} avgMs={} totalMs={} blocks={} "
                            + "overflow={} notPalette={} nullTemplate={} failed={}",
                    count, templates,
                    String.format(java.util.Locale.ROOT, "%.2f", templates == 0 ? 0.0 : (double) count / templates),
                    String.format(java.util.Locale.ROOT, "%.3f", count == 0 ? 0.0 : totalNanos / 1.0e6 / count),
                    String.format(java.util.Locale.ROOT, "%.1f", totalNanos / 1.0e6),
                    totalBlocks, resolveOverflow, resolveNotPalette, resolveNullTemplate, resolveFailed);
        }
    }

    private static boolean ignoredState(BlockState state) {
        return state == null || state.isAir() || state.is(Blocks.STRUCTURE_VOID)
                || state.is(Blocks.JIGSAW);
    }

    private static byte classify(BlockState state) {
        if (AboveColumnClearer.isFallingBlock(state)) return FALLING;
        if (AboveColumnClearer.isCapClutter(state)) return CLUTTER;
        return SOLID;
    }
}
