/* 文件职责：以常显详情卡片渲染初始道路生成的阶段、设备与实时吞吐信息。 */
package net.shiroha233.roadweaver.client.loading;

import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.client.tips.LoadingTipsRenderer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressSnapshot;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressSnapshot.RoutePhaseProgress;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessionSnapshot;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;

import java.util.Locale;

/**
 * 世界创建阶段的加载进度卡片。
 *
 * <h2>形态</h2>
 * 与长城/河流卡片同构的常显卡片：主题点 + 标题 + 当前阶段 + 总进度%；一条道路线稿带
 * （车道虚线，行进光点即总进度）；8 节点阶段步进器；一行状态 + 一行 ETA；详情区在下方
 * 无条件全部绘制，保留道路此前的全部字段（两条路线阶段行、采样模式/等级、设备、
 * 瓦片/吞吐、debug 两行、回退原因），按阶段切换逻辑原样呈现。
 *
 * <h2>为什么没有折叠</h2>
 * 卡片曾经是「详情」开关控制的折叠面板，开关已去掉：卡片只在世界创建期间出现，这段时间
 * 玩家没有别的可看，而折叠会把判断是否假死所需的数字藏起来一次点击之后。
 *
 * <h2>信息保真</h2>
 * 本类只重写展示层；{@link InitialGenerationProgressSnapshot}、
 * {@link InitialGenerationStage} 与全部 lang key 一概不动，卡片上的每个字段都来自既有
 * 快照，详情区的每一行都与旧卡片一一对应。
 */
public final class LoadingGenerationOverlayRenderer {
    private static final int CARD_PADDING = 10;
    private static final int LINE = 12;
    private static final int CARD_BG = 0xFF10131A;
    private static final int CARD_BORDER = 0xFF404B5A;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFB7C0CC;
    private static final int TEXT_MUTED = 0xFF8D97A6;
    private static final int TEXT_ACCENT = 0xFF8CE0FF;
    private static final int TEXT_FALLBACK = 0xFFFFB86B;
    private static final int THEME = 0xFFD8C9A0;          // sand-white
    private static final int THEME_DIM = 0xFF6E6450;      // unlit line-art
    private static final int STEP_DONE = 0xFF7FD07F;
    private static final int STEP_PENDING = 0xFF5A6472;
    // Opaque on purpose (same as the wall card): the vanilla loading screen draws its own centred
    // percentage, and a translucent band let that box bleed through the road artwork - it read as
    // a second, contradictory progress number.
    private static final int BAND_BG = 0xFF101622;
    private static final int ROAD_LIT = 0xFFF0E6CC;
    private static final int DOT_LIT = 0xFFFFF3C0;
    private static final int ROADBED = 0xFF3A3830;        // 路基（暗）
    private static final int ROADBED_LIT = 0xFF4A463A;    // 已铺段
    private static final int RUT = 0xFF2E2C26;            // 车辙
    private static final int GRASS_DIM = 0xFF2A3328;      // 路旁草丛（暗）
    private static final int GRASS_LIT = 0xFF5A7A4C;      // 路旁草丛（亮）
    private static final int BUSH_DIM = 0xFF242E22;       // 灌木（暗）
    private static final int BUSH_LIT = 0xFF4E7040;       // 灌木（亮）
    private static final int TREE_DIM = 0xFF242C22;       // 树影（暗）
    private static final int TREE_LIT = 0xFF4C6A40;       // 树影（亮）
    private static final int TREE_HI = 0xFF5E7F50;        // 树冠亮部
    private static final int TRUNK = 0xFF3A2E22;          // 树干
    private static final int POST = 0xFF6A6252;           // 路标
    private static final int CART = 0xFFFFF3C0;           // 行进小车

    private LoadingGenerationOverlayRenderer() {
    }

    public static void render(GuiGraphics graphics) {
        LoadingTipsRenderer.render(graphics);

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        ModConfig config = ConfigService.get();
        if (config != null && !config.loadingProgressEnabled()) return;
        if (!InitialGenManager.isActive()) return;

        InitialGenerationProgressSnapshot snapshot = InitialGenManager.getProgressSnapshot();
        if (!snapshot.active()) return;

        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        // 卡片占据屏幕宽度 9/10；线稿带固定 120px（调好的显示比例），详情区一直显示，不再折叠。
        int cardWidth = Math.max(360, (sw * 9) / 10);
        int innerWidth = cardWidth - CARD_PADDING * 2;
        int bandHeight = 120;

        int detailLines = detailLineCount(snapshot);
        int cardHeight = CARD_PADDING
                + LINE                  // title row
                + bandHeight            // road band
                + 6
                + LINE                  // stepper
                + 4
                + LINE                  // status
                + LINE                  // eta
                + detailLines * LINE + 4
                + CARD_PADDING;

        int left = (sw - cardWidth) / 2;
        // 道路卡锚定在屏幕底部居中，但抬离底边约屏幕高的 12%，不贴角落。
        int top = Math.max(20, sh - cardHeight - Math.max(40, (sh * 12) / 100));
        int right = left + cardWidth;
        int bottom = top + cardHeight;
        int x = left + CARD_PADDING;
        int y = top + CARD_PADDING;

        graphics.fill(left, top, right, bottom, CARD_BG);
        graphics.fill(left, top, right, top + 1, CARD_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
        graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
        graphics.fill(right - 1, top, right, bottom, CARD_BORDER);

        // 标题行：主题点 + 标题 + 当前阶段 + 总进度%。
        drawDot(graphics, x + 3, y + 4, 3, THEME);
        Component title = Component.translatable("gui.roadweaver.initgen.title");
        graphics.drawString(font, title, x + 10, y, TEXT_PRIMARY, false);
        String pct = snapshot.overallPercent() + "%";
        graphics.drawString(font, pct, right - CARD_PADDING - font.width(pct), y, THEME, false);
        Component stage = Component.translatable(snapshot.stageLabel());
        graphics.drawString(font, stage,
                right - CARD_PADDING - font.width(pct) - 6 - font.width(stage), y, TEXT_ACCENT, false);

        y += LINE;
        drawBand(graphics, x, y, innerWidth, bandHeight, snapshot);

        y += bandHeight + 6;
        drawStepper(graphics, x, y, innerWidth, snapshot);

        y += LINE + 4;
        String sub = snapshot.subProgressLabel();
        Component status = sub.isBlank()
                ? Component.translatable(snapshot.stageLabel())
                : Component.translatable(snapshot.stageLabel()).append("  " + sub);
        drawFitted(graphics, font, status, x, y, innerWidth, TEXT_SECONDARY);

        y += LINE;
        drawFitted(graphics, font, etaLine(snapshot), x, y, innerWidth, TEXT_MUTED);

        y += LINE + 4;
        drawDetail(graphics, mc, font, config, snapshot, x, y, innerWidth);
    }

    // -----------------------------------------------------------------------------------------
    // 道路线稿带：一条横贯乡野的路，路基+车辙+车道虚线，两侧灌木与分层树影、路标，行进小车
    // 沿路面从左走到右，位置即总进度。
    // -----------------------------------------------------------------------------------------

    private static void drawBand(GuiGraphics graphics, int x, int y, int width, int height,
                                 InitialGenerationProgressSnapshot snapshot) {
        graphics.fill(x, y, x + width, y + height, BAND_BG);
        int roadY = y + height / 2;
        int litWidth = Math.round(width * snapshot.overallPercent() / 100.0F);

        // 路旁灌木、草丛与树影：未点亮处暗、点亮处显形，让路面读得出"穿过乡野"。
        drawVerge(graphics, x, y, width, height, roadY, 0, width, false);
        drawVerge(graphics, x, y, width, height, roadY, 0, litWidth, true);

        // 路基：整条暗色路面，已铺段稍亮。
        graphics.fill(x, roadY - 8, x + width, roadY + 8, ROADBED);
        graphics.fill(x, roadY - 8, x + litWidth, roadY + 8, ROADBED_LIT);
        // 车辙：已铺段上两条平行的浅痕。
        graphics.fill(x, roadY - 5, x + litWidth, roadY - 4, RUT);
        graphics.fill(x, roadY + 4, x + litWidth, roadY + 5, RUT);
        // 车道虚线：亮色的中央分隔虚线，只在已点亮的左段内绘制。
        for (int i = 0; i < litWidth; i += 12) {
            graphics.fill(x + i, roadY - 1, Math.min(x + width, x + i + 6), roadY + 1, ROAD_LIT);
        }
        // 路缘线：上下两条细线，让路面读得出"路"。
        graphics.fill(x, roadY - 9, x + width, roadY - 8, THEME_DIM);
        graphics.fill(x, roadY + 8, x + width, roadY + 9, THEME_DIM);
        // 行进小车：总进度的位置，一辆带轮的小车剪影在已铺段尽头前行。
        drawCart(graphics, x + Math.min(litWidth, width - 9), roadY);
    }

    /** 路两侧的灌木、草丛、树影与路标；{@code lit} 决定用亮色还是暗色，对应已铺/未铺段。 */
    private static void drawVerge(GuiGraphics graphics, int x, int y, int width, int height,
                                  int roadY, int from, int to, boolean lit) {
        if (to <= from) return;
        int grass = lit ? GRASS_LIT : GRASS_DIM;
        int bush = lit ? BUSH_LIT : BUSH_DIM;
        int tree = lit ? TREE_LIT : TREE_DIM;
        int treeHi = lit ? TREE_HI : TREE_DIM;
        for (int i = Math.max(0, from); i < Math.min(width, to); i++) {
            // 草丛：贴着路缘上下各一簇，高低错落的锯齿。
            int g = (i * 7) % 6;
            graphics.fill(x + i, roadY - 11 - g, x + i + 1, roadY - 10, grass);
            graphics.fill(x + i, roadY + 10, x + i + 1, roadY + 11 + ((i * 5) % 5), grass);
            // 灌木：比草丛大的一团，错落分布。
            if ((i % 23) == 4) {
                drawBush(graphics, x + i, roadY - 12, bush, true);
            }
            if ((i % 29) == 12) {
                drawBush(graphics, x + i, roadY + 12, bush, false);
            }
            // 稀疏的分层树：每隔一段一棵，上侧一棵下侧错开一棵。
            if ((i % 41) == 0) {
                drawTree(graphics, x + i, roadY - 14, tree, treeHi, true);
            }
            if ((i % 59) == 24) {
                drawTree(graphics, x + i, roadY + 14, tree, treeHi, false);
            }
            // 路标：偶尔一根，立在路缘上方。
            if ((i % 97) == 30) {
                drawPost(graphics, x + i, roadY - 10);
            }
        }
    }

    /** 一团灌木：底部宽、顶部窄的圆头。{@code up} 为 true 长在路上方，false 倒挂下方。 */
    private static void drawBush(GuiGraphics graphics, int cx, int edge, int color, boolean up) {
        int dir = up ? -1 : 1;
        graphics.fill(cx - 4, edge, cx + 5, edge + dir * 2, color);
        graphics.fill(cx - 3, edge + dir * 2, cx + 4, edge + dir * 3, color);
        graphics.fill(cx - 1, edge + dir * 3, cx + 2, edge + dir * 4, color);
    }

    /** 一棵分层树：一根树干加三层三角树冠，顶层带一点亮部。{@code up} 为 true 长在路上方。 */
    private static void drawTree(GuiGraphics graphics, int cx, int edge, int color, int hi, boolean up) {
        int dir = up ? -1 : 1;
        // 树干
        graphics.fill(cx, edge, cx + 2, edge + dir * 7, TRUNK);
        // 树冠：三层三角，顶层一点亮部
        int ty = edge + dir * 7;
        graphics.fill(cx - 4, ty, cx + 6, ty + dir * 2, color);
        graphics.fill(cx - 3, ty + dir * 2, cx + 5, ty + dir * 3, color);
        graphics.fill(cx - 1, ty + dir * 3, cx + 3, ty + dir * 5, hi);
    }

    /** 一根路标：一根立柱加一个小横牌。 */
    private static void drawPost(GuiGraphics graphics, int cx, int edge) {
        graphics.fill(cx, edge - 8, cx + 2, edge, POST);
        graphics.fill(cx, edge - 8, cx + 5, edge - 6, POST);
    }

    /** 行进小车：一个车厢两个轮加一根车辕，在已铺段尽头标记"铺到这里了"。 */
    private static void drawCart(GuiGraphics graphics, int cx, int roadY) {
        long t = System.currentTimeMillis() / 200L;
        int body = (t % 2L) == 0L ? CART : ROAD_LIT;
        int top = roadY - 9;
        // 车厢
        graphics.fill(cx, top, cx + 9, top + 4, body);
        // 车辕（向右伸出）
        graphics.fill(cx + 9, top + 2, cx + 11, top + 3, body);
        // 两个轮
        graphics.fill(cx + 1, top + 4, cx + 3, top + 6, body);
        graphics.fill(cx + 6, top + 4, cx + 8, top + 6, body);
    }

    // -----------------------------------------------------------------------------------------
    // 步进器：8 个阶段节点，已完成绿勾 / 当前金色脉冲 / 未到灰色空心。
    // -----------------------------------------------------------------------------------------

    private static void drawStepper(GuiGraphics graphics, int x, int y, int width,
                                    InitialGenerationProgressSnapshot snapshot) {
        InitialGenerationStage[] stages = InitialGenerationStage.values();
        int count = stages.length;
        int activeOrdinal = snapshot.stage().ordinal();
        int step = count > 1 ? width / (count - 1) : width;
        int lineY = y + 5;
        graphics.fill(x, lineY, x + width, lineY + 1, STEP_PENDING);
        int litX = x + Math.round(width * snapshot.overallPercent() / 100.0F);
        graphics.fill(x, lineY, Math.min(x + width, litX), lineY + 1, THEME);
        for (int i = 0; i < count; i++) {
            int cx = x + i * step;
            if (i < activeOrdinal) {
                drawCheck(graphics, cx, lineY, STEP_DONE);
            } else if (i == activeOrdinal) {
                drawPulse(graphics, cx, lineY, THEME);
            } else {
                drawHollow(graphics, cx, lineY, STEP_PENDING);
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 详情区：保留道路此前的全部字段，按阶段切换逻辑原样呈现。
    // -----------------------------------------------------------------------------------------

    /** 详情区占几行 —— 计算在 {@link LoadingOverlayText}，这里只做取字段。 */
    private static int detailLineCount(InitialGenerationProgressSnapshot snapshot) {
        return LoadingOverlayText.detailLineCount(snapshot.stage(), snapshot.fallbackReason());
    }

    private static int drawDetail(GuiGraphics graphics, Minecraft mc, Font font, ModConfig config,
                                  InitialGenerationProgressSnapshot snapshot, int x, int y, int width) {
        TerrainSamplingSessionSnapshot samplingSession = samplingSession(mc, config);
        String fallbackReason = samplingSession.hasFallbackReason()
                ? samplingSession.fallbackReason()
                : snapshot.fallbackReason();
        boolean showFallback = fallbackReason != null && !fallbackReason.isBlank();
        boolean showDebug = snapshot.stage() == InitialGenerationStage.ROAD_GENERATION
                || snapshot.stage() == InitialGenerationStage.POST_PROCESSING;

        Component summary = switch (snapshot.stage()) {
            case PLANNING -> Component.translatable(
                    "gui.roadweaver.initgen.planning_summary",
                    snapshot.planningTilesDone(), snapshot.planningTilesTotal(),
                    snapshot.planningStartsDone(), snapshot.planningStartsTotal(),
                    snapshot.planningEmptyStarts());
            case EXACT_SAMPLING -> Component.translatable(
                    "gui.roadweaver.initgen.exact_sampling_summary",
                    snapshot.samplesDone(), snapshot.samplesTotal());
            case EXACT_PATHING -> Component.translatable(
                    "gui.roadweaver.initgen.exact_paths",
                    snapshot.exactPathsDone(), snapshot.exactPathsTotal());
            case COARSE_SAMPLING, COARSE_PATHING -> Component.translatable(
                    "gui.roadweaver.initgen.coarse_sampling_summary",
                    snapshot.tilesLoaded(), snapshot.tilesTotal());
            case POST_PROCESSING -> Component.translatable(
                    "gui.roadweaver.initgen.construction_chunks",
                    snapshot.constructionChunksDone(), snapshot.constructionChunksTotal());
            case ROAD_GENERATION, FINISHED -> Component.translatable(
                    "gui.roadweaver.initgen.route_stage_operation",
                    Component.literal(operationLabel(snapshot.currentOperation())));
        };
        drawFitted(graphics, font, summary, x, y, width, TEXT_SECONDARY);

        y += LINE;
        drawFitted(graphics, font, routePhaseLine(
                "gui.roadweaver.initgen.route_original", snapshot.originalRoutes()), x, y, width, TEXT_SECONDARY);
        y += LINE;
        drawFitted(graphics, font, routePhaseLine(
                "gui.roadweaver.initgen.route_local", snapshot.localRepairRoutes()), x, y, width, TEXT_SECONDARY);

        y += LINE;
        drawFitted(graphics, font, Component.translatable(
                "gui.roadweaver.initgen.sampling_modes",
                samplingModeLabel(samplingSession.configuredMode()),
                samplingModeLabel(samplingSession.effectiveMode())), x, y, width, TEXT_MUTED);

        y += LINE;
        drawFitted(graphics, font, Component.translatable(
                "gui.roadweaver.initgen.sampling_step",
                snapshot.currentSamplingStep() > 0 ? snapshot.currentSamplingStep() : "-"), x, y, width, TEXT_MUTED);

        y += LINE;
        drawFitted(graphics, font, Component.translatable(
                "gui.roadweaver.initgen.device",
                printable(snapshot.backend()), printableDevice(snapshot),
                printable(snapshot.devicePreference())), x, y, width, TEXT_MUTED);

        y += LINE;
        Component tiles = switch (snapshot.stage()) {
            case PLANNING -> Component.translatable(
                    "gui.roadweaver.initgen.planning_detail",
                    snapshot.planningStartsDone(), snapshot.planningStartsTotal(), snapshot.activeWorkers());
            case EXACT_SAMPLING -> Component.translatable(
                    "gui.roadweaver.initgen.exact_batch",
                    snapshot.lastBatchSamples(), snapshot.lastBatchMillis(), exactBatchRate(snapshot));
            case EXACT_PATHING -> Component.translatable(
                    "gui.roadweaver.initgen.exact_paths",
                    snapshot.exactPathsDone(), snapshot.exactPathsTotal());
            case POST_PROCESSING -> Component.translatable(
                    "gui.roadweaver.initgen.construction_chunks",
                    snapshot.constructionChunksDone(), snapshot.constructionChunksTotal());
            default -> Component.translatable(
                    "gui.roadweaver.initgen.tiles",
                    snapshot.tilesLoaded(), snapshot.tilesTotal(), snapshot.tilesFromMemory(),
                    snapshot.tilesFromDisk(), snapshot.tilesSampled());
        };
        drawFitted(graphics, font, tiles, x, y, width, TEXT_MUTED);

        y += LINE;
        Component throughput = switch (snapshot.stage()) {
            case PLANNING -> Component.translatable(
                    "gui.roadweaver.initgen.planning_operation", printable(snapshot.currentOperation()));
            case EXACT_SAMPLING -> Component.translatable(
                    "gui.roadweaver.initgen.exact_throughput",
                    snapshot.samplesDone(), snapshot.samplesTotal(),
                    String.format(Locale.ROOT, "%.0f", snapshot.samplesPerSecond()), exactSamplingEta(snapshot));
            case EXACT_PATHING -> Component.translatable(
                    "gui.roadweaver.initgen.exact_paths_remaining",
                    Math.max(0, snapshot.exactPathsTotal() - snapshot.exactPathsDone()), snapshot.stagePercent());
            case POST_PROCESSING -> Component.translatable(
                    "gui.roadweaver.initgen.construction_remaining",
                    Math.max(0, snapshot.constructionChunksTotal() - snapshot.constructionChunksDone()),
                    snapshot.stagePercent());
            default -> Component.translatable(
                    "gui.roadweaver.initgen.throughput",
                    snapshot.lastBatchSamples(),
                    String.format(Locale.ROOT, "%.0f", snapshot.samplesPerSecond()),
                    snapshot.initialThreads(), snapshot.activeWorkers());
        };
        drawFitted(graphics, font, throughput, x, y, width, TEXT_MUTED);

        if (showDebug) {
            y += LINE;
            drawFitted(graphics, font, Component.translatable(
                    "gui.roadweaver.initgen.debug",
                    snapshot.terrainCacheHitPercent(),
                    String.format(Locale.ROOT, "%.0f", snapshot.terrainNoiseSamplesPerSec()),
                    snapshot.terrainNoiseSamplesTotal()), x, y, width, TEXT_MUTED);
            y += LINE;
            drawFitted(graphics, font, Component.translatable(
                    "gui.roadweaver.initgen.debug2",
                    snapshot.accurateCacheHitPercent(),
                    String.format(Locale.ROOT, "%.0f", snapshot.accurateBaseHeightPerSec()),
                    snapshot.accurateBaseHeightTotal()), x, y, width, TEXT_MUTED);
        }

        if (showFallback) {
            y += LINE;
            drawFitted(graphics, font,
                    Component.translatable("gui.roadweaver.initgen.fallback", fallbackReason),
                    x, y, width, TEXT_FALLBACK);
            y += LINE;
        }
        return y;
    }

    private static Component etaLine(InitialGenerationProgressSnapshot snapshot) {
        return LoadingOverlayText.etaLine(snapshot.elapsedMillis());
    }

    // -----------------------------------------------------------------------------------------
    // 小绘图助手与既有文本助手（原样保留）。
    // -----------------------------------------------------------------------------------------

    private static void drawCheck(GuiGraphics graphics, int cx, int cy, int color) {
        drawDot(graphics, cx, cy, 4, color);
        graphics.fill(cx - 1, cy, cx, cy + 2, 0xFF0E2A14);
        graphics.fill(cx, cy + 1, cx + 2, cy - 1, 0xFF0E2A14);
    }

    private static void drawPulse(GuiGraphics graphics, int cx, int cy, int color) {
        long t = System.currentTimeMillis() / 240L;
        int ring = (t % 2L) == 0L ? color : DOT_LIT;
        drawRing(graphics, cx, cy, 4, ring);
        drawDot(graphics, cx, cy, 2, color);
    }

    private static void drawHollow(GuiGraphics graphics, int cx, int cy, int color) {
        drawRing(graphics, cx, cy, 3, color);
    }

    private static void drawDot(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        graphics.fill(cx - radius, cy - radius + 1, cx + radius, cy + radius - 1, color);
        graphics.fill(cx - radius + 1, cy - radius, cx + radius - 1, cy + radius, color);
    }

    private static void drawRing(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        graphics.fill(cx - radius, cy - radius + 1, cx - radius + 1, cy + radius - 1, color);
        graphics.fill(cx + radius - 1, cy - radius + 1, cx + radius, cy + radius - 1, color);
        graphics.fill(cx - radius + 1, cy - radius, cx + radius - 1, cy - radius + 1, color);
        graphics.fill(cx - radius + 1, cy + radius - 1, cx + radius - 1, cy + radius, color);
    }

    private static TerrainSamplingSessionSnapshot samplingSession(Minecraft minecraft, ModConfig config) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) return TerrainSamplingSessions.forLevel(level).snapshot();
        }
        var configured = config == null ? null : config.planning().terrainSamplingMode();
        return new TerrainSamplingSessionSnapshot(configured, configured, "", "", "");
    }

    private static Component samplingModeLabel(TerrainSamplingMode mode) {
        return LoadingOverlayText.samplingModeLabel(mode);
    }

    private static void drawFitted(GuiGraphics graphics, Font font, Component text,
                                   int x, int y, int maxWidth, int color) {
        String raw = text.getString();
        if (font.width(raw) <= maxWidth) {
            graphics.drawString(font, raw, x, y, color, false);
            return;
        }
        int suffixWidth = font.width("...");
        String clipped = font.plainSubstrByWidth(raw, Math.max(0, maxWidth - suffixWidth));
        graphics.drawString(font, clipped + "...", x, y, color, false);
    }

    private static Component routePhaseLine(String key, RoutePhaseProgress progress) {
        return LoadingOverlayText.routePhaseLine(key, progress);
    }

    private static String operationLabel(String operation) {
        return LoadingOverlayText.operationLabel(operation);
    }

    private static String printable(String value) {
        return LoadingOverlayText.printable(value);
    }

    private static String printableDevice(InitialGenerationProgressSnapshot snapshot) {
        return LoadingOverlayText.printableDevice(snapshot.deviceName(), snapshot.backend());
    }

    private static String exactSamplingEta(InitialGenerationProgressSnapshot snapshot) {
        return LoadingOverlayText.exactSamplingEta(
                snapshot.samplesPerSecond(), snapshot.samplesTotal(), snapshot.samplesDone());
    }

    private static String exactBatchRate(InitialGenerationProgressSnapshot snapshot) {
        return LoadingOverlayText.exactBatchRate(
                snapshot.lastBatchSamples(), snapshot.lastBatchMillis());
    }

}
