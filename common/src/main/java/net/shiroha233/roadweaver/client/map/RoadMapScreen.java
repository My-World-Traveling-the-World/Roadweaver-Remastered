/* 文件职责：呈现道路地图并同步单机世界的有效地形图层。 */
package net.shiroha233.roadweaver.client.map;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.client.map.data.ClientMapNotes;
import net.shiroha233.roadweaver.client.map.data.MapDataCollector;
import net.shiroha233.roadweaver.client.map.data.MapSnapshot;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotCache;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotPatch;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotStore;
import net.shiroha233.roadweaver.client.map.data.MapAutomaticPlanningSamplingCache;
import net.shiroha233.roadweaver.client.map.interaction.MapInteraction;
import net.shiroha233.roadweaver.client.map.data.MapFilterState;
import net.shiroha233.roadweaver.client.map.render.MapDockAction;
import net.shiroha233.roadweaver.client.map.render.MapDockRenderer;
import net.shiroha233.roadweaver.client.map.render.MapAutomaticPlanningSamplingOverlayRenderer;
import net.shiroha233.roadweaver.client.map.render.MapLegendRenderer;
import net.shiroha233.roadweaver.client.map.render.MapOverlayRenderer;
import net.shiroha233.roadweaver.client.map.render.MapRenderers;
import net.shiroha233.roadweaver.client.map.render.MapStatusRenderer;
import net.shiroha233.roadweaver.client.map.ui.ContextMenu;
import net.shiroha233.roadweaver.client.map.ui.MapWorkspacePanel;
import net.shiroha233.roadweaver.client.map.ui.NoteEditScreen;
import net.shiroha233.roadweaver.client.map.ui.Rect;
import net.shiroha233.roadweaver.client.map.ui.SimpleTextInputScreen;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.map.permission.MapAccessService;
import net.shiroha233.roadweaver.network.ClientNetBridge;
import net.shiroha233.roadweaver.map.search.MapSearchResult;
import net.shiroha233.roadweaver.map.search.MapStructureSearchService;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessionSnapshot;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;
import net.shiroha233.roadweaver.util.ComputeService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.lwjgl.glfw.GLFW;

/**
 * 道路地图界面
 */
public class RoadMapScreen extends Screen implements MapInputHandler.Callbacks {
    private static final int WORKSPACE_DOCK_GAP = 8;
    
    // 翻译键
    private static final Component MENU_TELEPORT = Component.translatable("gui.roadweaver.map.menu.teleport");
    private static final Component MENU_SET_ALIAS = Component.translatable("gui.roadweaver.map.menu.set_alias");
    private static final Component MENU_EDIT_NOTE = Component.translatable("gui.roadweaver.map.menu.edit_note");
    private static final Component DIALOG_ALIAS_TITLE = Component.translatable("gui.roadweaver.map.dialog.alias_title");

    // 数据
    private MapSnapshot snapshot = MapSnapshot.empty();
    private ResourceLocation currentDimensionId;
    private MapSnapshotStore snapshotStore = new MapSnapshotStore();
    private MapLoadSession loadSession;
    
    // 组件
    private final MapState state = new MapState();
    private final MapView view = new MapView();
    private final MapInputHandler inputHandler;
    private final ContextMenu contextMenu = new ContextMenu();
    private final MapWorkspacePanel workspacePanel = new MapWorkspacePanel();
    private final MapFilterState filterState = new MapFilterState();
    private final XaeroMapBackgroundRenderer xaeroBackground = new XaeroMapBackgroundRenderer();

    /**
     * 玩家手绘路线编辑器。
     *
     * <p>独立于 {@link MapState}（那套是「连接两个结构」的旧手动模式）。
     * 本编辑器管理「多点折线 + 每段类型」，见 {@code route.editor} 包。</p>
     */
    private final net.shiroha233.roadweaver.route.editor.RouteEditorBridge routeEditor =
            new net.shiroha233.roadweaver.route.editor.RouteEditorBridge(this.view);

    // ── 手绘路线工具条的布局常量（渲染与点击命中必须共用，否则会错位）──
    private static final int TOOLBAR_X = 8;
    private static final int TOOLBAR_BUTTON_W = 46;
    private static final int TOOLBAR_HEIGHT = 20;
    /** 每次渲染后回填的实际 Y（随 Dock 位置而变），供点击命中复用。 */
    private int toolbarY = -1;

    private MapDockRenderer.DockLayout dockLayout;
    private EditBox searchBox;
    private String searchQuery = "";
    private List<MapSearchResult> searchResults = List.of();
    private boolean searchLoading;
    private boolean searchFailed;
    private int searchRequestSeq;
    private long searchDeadlineMs;
    private long searchStartedAtMs;
    private String pendingSearchQuery = "";
    private String submittedSearchQuery = "";
    private MapSnapshot filteredSnapshot;
    private int filteredSnapshotRevision = -1;
    private MapSnapshot filteredSnapshotSource;
    
    // 布局
    private int mapX, mapY, mapW, mapH;

    public RoadMapScreen() {
        super(Component.translatable("gui.roadweaver.map.title"));
        this.inputHandler = new MapInputHandler(state, view, this);
    }

    // ========== 生命周期 ==========
    
    @Override
    protected void init() {
        super.init();
        searchBox = new EditBox(this.font, 0, 0, 1, 18,
                Component.translatable("gui.roadweaver.map.panel.search.input"));
        searchBox.setMaxLength(MapStructureSearchService.MAX_QUERY_LENGTH);
        searchBox.setBordered(false);
        searchBox.setTextColor(MapTheme.PANEL_TEXT);
        searchBox.setTextColorUneditable(MapTheme.PANEL_MUTED);
        searchBox.setHint(Component.translatable("gui.roadweaver.map.panel.search.hint"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(this::onSearchQueryChanged);
        if (!hasMapOpenPermission()) {
            denyMapAccessAndClose();
            return;
        }
        MapSnapshotCache.cancelClear();
        Minecraft mc = this.minecraft;
        if (mc != null && mc.level != null) {
            currentDimensionId = mc.level.dimension().location();
            snapshotStore = MapSnapshotCache.store(currentDimensionId);
            this.snapshot = snapshotStore.snapshot();
        }
        computeMapRect();
        inputHandler.updateLayout(mapX, mapY, mapW, mapH, 0);

        // 0.8.344：地图打开 → 让 GPU 粗采样让路（否则两者抢 GPU，地图明显卡顿）。
        // 由 performance.pauseCoarseGpuWhileMapOpen 控制（默认开启）。
        net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy.setMapScreenOpen(true);
        
        int contentW = mapW;
        int contentH = mapH;
        view.resetFromSnapshot(snapshot);
        
        if (mc != null && mc.player != null) {
            view.calibrateInitialToPlayer(mc, contentW, contentH, MapTheme.GRID_TARGET_PX);
        }
        onRequestView();
    }

    @Override
    public void removed() {
        super.removed();
        // 0.8.344：地图关闭 → 立即恢复 GPU 粗采样。
        // 必须与 init() 成对，否则采样会被永久挂起。
        net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy.setMapScreenOpen(false);
        xaeroBackground.reset();
        loadSession = null;
        MapSnapshotCache.putStoreSnapshot(currentDimensionId, snapshotStore);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ========== 渲染 ==========
    
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int contentW = mapW;
        int contentH = mapH;
        view.lockAspect(contentW, contentH);
        int left = 0;
        int top = 0;
        int right = this.width;
        int bottom = this.height;
        Minecraft mc = this.minecraft;
        TerrainSamplingSessionSnapshot samplingSession = terrainSamplingSession(mc);
        ServerLevel samplingLevel = MapViewportController.resolveSingleplayerLevel(mc);
        List<AutomaticPlanningSamplingBounds> automaticPlanningSamplingBounds =
                activeAutomaticPlanningSamplingBounds(samplingLevel);
        processSearchDebounce();
        MapSnapshot visibleSnapshot = visibleSnapshot();
        dockLayout = MapDockRenderer.layout(this.width, this.height);
        int workspaceBottom = workspaceBottomLimit(dockLayout);
        workspacePanel.setMousePosition(mouseX, mouseY);
        g.fill(0, 0, this.width, this.height, MapTheme.COLOR_BACKGROUND);
        g.enableScissor(left, top, right, bottom);
        xaeroBackground.render(g, this.minecraft, this, view,
                contentW, contentH, mouseX, mouseY, partialTick);
        g.enableScissor(left, top, right, bottom);
        MapRenderers.renderGrid(g, this.font, 0, 0, mapW, mapH, 0,
                view.getMinX(), view.getMaxX(), view.getMinZ(), view.getMaxZ(),
                MapTheme.COLOR_GRID, MapTheme.GRID_TARGET_PX, MapTheme.COLOR_TEXT);
        int thickness = computeThickness();
        MapRenderers.renderRoadPolylines(g, visibleSnapshot.roadPolylines(),
                (x1, z1, x2, z2) -> view.segmentInViewWorld(x1, z1, x2, z2),
                v -> view.toScreenX(v, 0, 0, contentW),
                v -> view.toScreenY(v, 0, 0, contentH),
                Math.max(1, thickness),
                MapTheme.COLOR_COMPLETED,
                left, top, right, bottom,
                Math.max(1, Math.round(16 / Math.max(0.1f, (float) view.pxPerBlockX(contentW)))));
        MapRenderers.renderStructures(g, this.font,
                visibleSnapshot.structures(),
                visibleSnapshot::structureName,
                v -> view.toScreenX(v, 0, 0, contentW),
                v -> view.toScreenY(v, 0, 0, contentH),
                (x, z) -> view.isInViewWorld(x, z),
                Math.max(MapTheme.STRUCTURE_MARKER_SIZE, computePointSize()),
                left, top, right, bottom);
        List<StructureConnection> connForLines = new ArrayList<>(visibleSnapshot.connections());
        boolean hasDetailedRoadPolylines = !visibleSnapshot.roadPolylines().isEmpty();
        // Detailed blue polylines are the authoritative road display. Protected gaps
        // remain in the snapshot for construction/state handling, but their endpoint
        // status line is misleading because it renders as a straight dashed overlay.
        connForLines.removeIf(c -> c.status() == ConnectionStatus.PROTECTED_GAP
                || (hasDetailedRoadPolylines && c.status() == ConnectionStatus.COMPLETED));

        // ── 玩家手绘路线：画在既有道路之上，避免被覆盖 ──
        // 渲染时会回填控制点的屏幕坐标，供下一次命中检测使用。
        if (routeEditor.isActive()) {
            routeEditor.updateLayout(0, 0, 0);
            java.util.List<Integer> screenXY = net.shiroha233.roadweaver.route.editor.RouteMapRenderer.render(
                    g, routeEditor.state(),
                    v -> view.toScreenX(v, 0, 0, contentW),
                    v -> view.toScreenY(v, 0, 0, contentH),
                    (x, z) -> view.isInViewWorld(x, z),
                    left, top, right, bottom,
                    true,
                    mouseX, mouseY);
            routeEditor.updateScreenPoints(screenXY);
        }
        MapRenderers.renderConnections(g, connForLines,
                (x1, z1, x2, z2) -> view.segmentInViewWorld(x1, z1, x2, z2),
                v -> view.toScreenX(v, 0, 0, contentW),
                v -> view.toScreenY(v, 0, 0, contentH),
                Math.max(1, thickness - 1),
                MapTheme.COLOR_PLANNED, MapTheme.COLOR_GENERATING,
                MapTheme.COLOR_COMPLETED, MapTheme.COLOR_FAILED,
                left, top, right, bottom);
        MapAutomaticPlanningSamplingOverlayRenderer.render(
                g,
                this.font,
                view,
                automaticPlanningSamplingBounds,
                contentW,
                contentH,
                left,
                top,
                right,
                bottom);
        MapOverlayRenderer.renderManualModePreview(g, visibleSnapshot, view, inputHandler,
                state.hasSelection() ? state.getSelectedA() : null,
                state.isDeleteConnectionMode(),
                mouseX, mouseY, contentW, contentH,
                left, top, right, bottom,
                computePointSize() * 2 + 4,
                computeThickness());
        if (!contextMenu.isOpen()
                && !dockLayout.contains(mouseX, mouseY)
                && !workspacePanel.contains(mouseX, mouseY, this.width, this.height, workspaceBottom)) {
            MapInteraction.renderHoverHighlight(g, visibleSnapshot, view, 0, 0, mapW, mapH,
                    0, mouseX, mouseY);
        }
        MapOverlayRenderer.renderPlayer(g, this.minecraft, inputHandler, view,
                contentW, contentH, left, top, right, bottom);
        g.disableScissor();

        // ── 手绘路线编辑模式的界面元素（工具条 / 状态 / 高度输入框）──
        if (routeEditor.isActive()) {
            // ★ 工具条必须放在底部 Dock 上方 —— 原先硬编码 height-70，
            //   而 Dock 起点是 height-42-12 = height-54（1920x1080 时 y=1026），
            //   两者所有分辨率下都重叠；点击时 Dock 先处理，导致按钮点不到。
            //   这里按 Dock 实际布局动态算 Y，并留 8px 间隙。
            net.shiroha233.roadweaver.client.map.render.MapDockRenderer.DockLayout dl =
                    net.shiroha233.roadweaver.client.map.render.MapDockRenderer.layout(
                            this.width, this.height);
            int toolbarY = dl.bounds().y() - 8 - TOOLBAR_HEIGHT;
            toolbarY = Math.max(8, toolbarY);
            this.toolbarY = toolbarY;
            net.shiroha233.roadweaver.route.editor.RouteMapRenderer.renderToolbar(
                    g, routeEditor.state(), TOOLBAR_X, toolbarY, TOOLBAR_BUTTON_W, TOOLBAR_HEIGHT);
            net.shiroha233.roadweaver.route.editor.RouteMapRenderer.renderStatus(
                    g, routeEditor.state(), 8, 8);
            net.shiroha233.roadweaver.route.editor.RouteMapRenderer.renderHeightInput(
                    g, routeEditor.state(), this.width / 2, this.height / 2);
        }
        int statusRight = workspacePanel.leftEdge(this.width, this.height, workspaceBottom);
        if (!workspacePanel.isOpen()) {
            MapLegendRenderer.render(g, this.font, this.width, visibleSnapshot);
        }
        MapStatusRenderer.render(g, this.font, this.width, statusRight, loadSession, samplingSession);
        if (!contextMenu.isOpen()
                && !dockLayout.contains(mouseX, mouseY)
                && !workspacePanel.contains(mouseX, mouseY, this.width, this.height, workspaceBottom)) {
            MapInteraction.renderHoverTooltip(g, this.font, visibleSnapshot, view, 0, 0, mapW, mapH,
                    0, mouseX, mouseY);
        }
        if (state.isZoomDebounceReady()) {
            state.clearZoomDebounce();
            onRequestView();
        }

        workspacePanel.render(g, this.font, this.width, this.height, workspaceBottom,
                snapshot, filterState, searchResults, searchLoading, searchFailed);
        renderSearchBox(g, mouseX, mouseY, partialTick);

        EnumSet<MapDockAction> activeActions = EnumSet.noneOf(MapDockAction.class);
        if (workspacePanel.isTab(MapWorkspacePanel.Tab.SEARCH)) activeActions.add(MapDockAction.SEARCH);
        if (workspacePanel.isTab(MapWorkspacePanel.Tab.FILTER)) activeActions.add(MapDockAction.FILTER);
        if (loadSession != null) activeActions.add(MapDockAction.REFRESH);
        if (state.isManualMode()) activeActions.add(MapDockAction.MANUAL_CONNECT);
        if (state.isDeleteConnectionMode()) activeActions.add(MapDockAction.DELETE_CONNECTION);
        if (routeEditor.isActive()) activeActions.add(MapDockAction.ROUTE_EDIT);
        // ★ 任务 A（2026-09-26）：端点标记开关是**持续状态**，开着时图标高亮 ——
        //   与 SEARCH/FILTER 那种"当前标签页"高亮同一语义（一眼看出当前是开还是关）。
        if (net.shiroha233.roadweaver.client.map.data.MapMarkersVisible.enabled()) {
            activeActions.add(MapDockAction.TOGGLE_MARKERS);
        }
        EnumSet<MapDockAction> disabledActions = EnumSet.noneOf(MapDockAction.class);
        MapDockRenderer.render(g, this.font, dockLayout, mouseX, mouseY,
                activeActions, disabledActions, -1);
        contextMenu.render(g, this.font, mouseX, mouseY, this.width, this.height);
    }

    // ========== 输入处理 ==========
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // ── 手绘路线编辑：滚轮调高度（选中点时）──
        // 必须放在最前面，否则会被地图缩放吃掉。
        if (routeEditor.isActive() && routeEditor.onScroll(scrollY)) {
            return true;
        }
        dockLayout = MapDockRenderer.layout(this.width, this.height);
        int workspaceBottom = workspaceBottomLimit(dockLayout);
        if (dockLayout.contains(mouseX, mouseY)) return true;
        if (workspacePanel.contains(mouseX, mouseY, this.width, this.height, workspaceBottom)) {
            workspacePanel.scroll(scrollY);
            return true;
        }
        return inputHandler.mouseScrolled(mouseX, mouseY, scrollY)
               || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (contextMenu.isOpen()) {
            if (contextMenu.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        dockLayout = MapDockRenderer.layout(this.width, this.height);
        int workspaceBottom = workspaceBottomLimit(dockLayout);
        MapDockAction dockAction = dockLayout.hit(mouseX, mouseY);
        if (button == 0 && dockAction != null) {
            handleDockAction(dockAction);
            return true;
        }

        MapSnapshot visibleSnapshot = visibleSnapshot();
        if (workspacePanel.contains(mouseX, mouseY, this.width, this.height, workspaceBottom)) {
            MapWorkspacePanel.Hit hit = workspacePanel.hit(
                    mouseX, mouseY, this.width, this.height, workspaceBottom,
                    snapshot, filterState, searchResults);
            handleWorkspaceHit(hit, mouseX, mouseY, button);
            return true;
        }

        if (workspacePanel.isOpen()) {
            workspacePanel.close();
            if (searchBox != null) searchBox.setFocused(false);
        }

        // ── 手绘路线编辑：工具条色块命中（切类型）+ 「施工」按钮 ──
        // 用渲染时回填的 toolbarY，保证与画出来的位置完全一致。
        if (button == 0 && routeEditor.isActive() && toolbarY >= 0) {
            int tbY = toolbarY;
            var kinds = net.shiroha233.roadweaver.route.RouteKind.values();
            for (int i = 0; i < kinds.length; i++) {
                int bx = TOOLBAR_X + i * (TOOLBAR_BUTTON_W + 4);
                if (mouseX >= bx && mouseX <= bx + TOOLBAR_BUTTON_W
                        && mouseY >= tbY && mouseY <= tbY + TOOLBAR_HEIGHT) {
                    routeEditor.state().setCurrentKind(kinds[i]);
                    return true;
                }
            }
            // 施工按钮
            int[] bb = net.shiroha233.roadweaver.route.editor.RouteMapRenderer
                    .buildButtonBounds(TOOLBAR_X, tbY, TOOLBAR_BUTTON_W, TOOLBAR_HEIGHT);
            if (mouseX >= bb[0] && mouseX <= bb[2] && mouseY >= bb[1] && mouseY <= bb[3]) {
                // ★ UX-7（用户 2026-09-25「弹确认框」）：先弹确认框，
                //   显示点数/长度/分段/将加载区块数/未探索警告，确认后才真提交。
                showBuildConfirm();
                return true;
            }
            // ★ 审计 R3-P2-8：新建 / 删除 / 重新编辑 三个按钮
            int[] nb = net.shiroha233.roadweaver.route.editor.RouteMapRenderer
                    .newButtonBounds(TOOLBAR_X, tbY, TOOLBAR_BUTTON_W, TOOLBAR_HEIGHT);
            if (mouseX >= nb[0] && mouseX <= nb[2] && mouseY >= nb[1] && mouseY <= nb[3]) {
                routeEditor.state().newRoute();
                return true;
            }
            int[] db = net.shiroha233.roadweaver.route.editor.RouteMapRenderer
                    .deleteButtonBounds(TOOLBAR_X, tbY, TOOLBAR_BUTTON_W, TOOLBAR_HEIGHT);
            if (mouseX >= db[0] && mouseX <= db[2] && mouseY >= db[1] && mouseY <= db[3]) {
                routeEditor.state().deleteCurrent();
                return true;
            }
            int[] rb = net.shiroha233.roadweaver.route.editor.RouteMapRenderer
                    .reopenButtonBounds(TOOLBAR_X, tbY, TOOLBAR_BUTTON_W, TOOLBAR_HEIGHT);
            if (mouseX >= rb[0] && mouseX <= rb[2] && mouseY >= rb[1] && mouseY <= rb[3]) {
                routeEditor.state().reopenForEdit();
                return true;
            }
            // ★ 2026-09-25：HUD 开关按钮（与世界内的 TOGGLE_HUD 键共用状态）
            int[] hb = net.shiroha233.roadweaver.route.editor.RouteMapRenderer
                    .hudButtonBounds(TOOLBAR_X, tbY, TOOLBAR_BUTTON_W, TOOLBAR_HEIGHT);
            if (mouseX >= hb[0] && mouseX <= hb[2] && mouseY >= hb[1] && mouseY <= hb[3]) {
                net.shiroha233.roadweaver.route.editor.RouteMapRenderer.toggleHud();
                return true;
            }
            // ★ 任务 A（2026-09-26）：端点标记开关**移到地图底部 Dock 的独立按钮**
            //   （用户：「这里下方不是有一排按钮么加个格外的按钮就行，不用绑定键盘了」）。
            //   原来的 Ctrl+M 键位与手绘工具条按钮都已移除 —— 入口只保留 Dock 一个，
            //   避免同一个开关有三个入口反而难记。
            // ★ UX-5「重试铺设」按钮 —— 仅在存在失败预加载时可见/可点。
            //
            // 命中检测必须与绘制用**同一个条件**（hasFailedPreload），
            // 否则会出现"看不见但能点到"的幽灵按钮。
            if (net.shiroha233.roadweaver.route.editor.RouteMapRenderer
                    .retryButtonVisible()) {
                int[] rtb = net.shiroha233.roadweaver.route.editor.RouteMapRenderer
                        .retryButtonBounds(TOOLBAR_X, tbY, TOOLBAR_BUTTON_W, TOOLBAR_HEIGHT);
                if (mouseX >= rtb[0] && mouseX <= rtb[2]
                        && mouseY >= rtb[1] && mouseY <= rtb[3]) {
                    retryFailedPreloads();
                    return true;
                }
            }
        }

        // ── 手绘路线编辑：左键放点 / 命中点选中并准备拖动 ──
        // 放在结构点击之前，让编辑模式优先拿到事件。
        if (button == 0 && routeEditor.isActive()
                && routeEditor.onLeftClick(mouseX, mouseY, mapW, mapH)) {
            return true;
        }

        if (button == 1 && inputHandler.insideMap(mouseX, mouseY)) {
            BlockPos target = inputHandler.findNearestStructure(visibleSnapshot, mouseX, mouseY);
            if (target != null) {
                openContextMenuFor(target, (int) mouseX, (int) mouseY);
                return true;
            }
        }

        if (button == 0 && !state.isEndpointEditMode() && inputHandler.insideMap(mouseX, mouseY)) {
            BlockPos target = inputHandler.findNearestStructure(visibleSnapshot, mouseX, mouseY);
            if (target != null) {
                workspacePanel.openDetails(target);
                return true;
            }
        }

        return inputHandler.mouseClicked(mouseX, mouseY, button, visibleSnapshot)
               || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // ── 手绘路线编辑：命中点时拖动挪点；空白处放行给地图平移 ──
        if (routeEditor.isActive() && routeEditor.onDrag(mouseX, mouseY, mapW, mapH)) {
            return true;
        }
        return inputHandler.mouseDragged(mouseX, mouseY, button, dragX, dragY)
               || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (routeEditor.isActive() && routeEditor.onRelease(mouseX, mouseY, button)) {
            return true;
        }
        return inputHandler.mouseReleased(mouseX, mouseY, button)
               || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ── 手绘路线编辑：高度输入 / 撤销 / 删点 / 换类型 ──
        // 放在最前，避免被搜索框或其它逻辑抢走。
        // 需要 scanCode —— 编辑动作键走 KeyMapping.matches(keyCode, scanCode)，
        // 这样玩家在设置里改过键之后，地图界面里也生效。
        //
        // ★ 审计 R3-P2-11：**搜索框有焦点时必须让路**。
        //
        // 原先无条件把按键先给编辑器，而 RouteEditorBridge.onKey 不检查焦点，
        // 于是编辑模式下打开搜索面板打字时：输入 z 会撤销、g 会换类型、
        // Delete 会删点、y 会当快捷键 —— 玩家一边搜索一边把路线改坏了。
        // 正常输入框拥有输入焦点时，键盘事件就该归它。
        boolean searchFocused = workspacePanel.isTab(MapWorkspacePanel.Tab.SEARCH)
                && searchBox != null && searchBox.isFocused();
        if (!searchFocused && routeEditor.isActive()
                && routeEditor.onKey(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submitSearchNow();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                workspacePanel.close();
                return true;
            }
            searchBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && workspacePanel.isOpen()) {
            workspacePanel.close();
            return true;
        }
        return inputHandler.keyPressed(keyCode, scanCode, modifiers, this.minecraft)
               || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (workspacePanel.isTab(MapWorkspacePanel.Tab.SEARCH)
                && searchBox != null
                && searchBox.isFocused()
                && searchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void handleDockAction(MapDockAction action) {
        switch (action) {
            case SEARCH -> {
                if (workspacePanel.isTab(MapWorkspacePanel.Tab.SEARCH)) {
                    workspacePanel.close();
                    if (searchBox != null) searchBox.setFocused(false);
                } else {
                    workspacePanel.openSearch();
                    if (searchBox != null) searchBox.setFocused(true);
                }
            }
            case FILTER -> {
                if (workspacePanel.isTab(MapWorkspacePanel.Tab.FILTER)) {
                    workspacePanel.close();
                } else {
                    workspacePanel.openFilter();
                }
                if (searchBox != null) searchBox.setFocused(false);
            }
            case REFRESH -> refreshCurrentView();
            case MANUAL_CONNECT -> {
                state.toggleManualMode();
                workspacePanel.close();
                if (searchBox != null) searchBox.setFocused(false);
            }
            case DELETE_CONNECTION -> {
                state.toggleDeleteConnectionMode();
                workspacePanel.close();
                if (searchBox != null) searchBox.setFocused(false);
            }
            case ROUTE_EDIT -> {
                // 手绘路线编辑模式开关。
                // 与旧的手动连接模式互斥（两者都要抢左键），进来时先关掉旧的。
                if (routeEditor.isActive()) {
                    routeEditor.exit();
                } else {
                    state.setManualMode(false);
                    state.setDeleteConnectionMode(false);
                    routeEditor.enter();
                }
                workspacePanel.close();
                if (searchBox != null) searchBox.setFocused(false);
            }
            // ★ 任务 A（2026-09-26）：开关地图端点标记（用户要求做成 Dock 按钮，不绑键盘）。
            case TOGGLE_MARKERS -> {
                net.shiroha233.roadweaver.client.map.data.MapMarkersVisible.toggle();
                // 切换后让地图快照重算 —— 否则要等下一次连接状态变化才生效。
                try {
                    net.shiroha233.roadweaver.client.map.data.MapSnapshotCache.clearNow();
                } catch (RuntimeException | LinkageError ignored) {
                    // 快照清理失败不影响开关本身，下一帧仍会重建
                }
            }
            case CONFIG -> onOpenConfig();
            case CLOSE -> onCloseScreen();
        }
    }

    private void handleWorkspaceHit(MapWorkspacePanel.Hit hit,
                                    double mouseX,
                                    double mouseY,
                                    int button) {
        if (button != 0 || hit == null) return;
        switch (hit.kind()) {
            case CLOSE -> {
                workspacePanel.close();
                if (searchBox != null) searchBox.setFocused(false);
            }
            case SEARCH_TAB -> {
                workspacePanel.openSearch();
                if (searchBox != null) searchBox.setFocused(true);
            }
            case FILTER_TAB -> {
                workspacePanel.openFilter();
                if (searchBox != null) searchBox.setFocused(false);
            }
            case DETAILS_TAB -> {
                if (workspacePanel.selectedStructure() != null) {
                    workspacePanel.openDetails(workspacePanel.selectedStructure());
                }
                if (searchBox != null) searchBox.setFocused(false);
            }
            case SEARCH_FIELD -> {
                if (searchBox != null) searchBox.mouseClicked(mouseX, mouseY, button);
            }
            case SEARCH_RESULT -> selectSearchResult((MapSearchResult) hit.value());
            case STATUS_FILTER -> {
                filterState.toggleStatus((ConnectionStatus) hit.value());
                invalidateFilteredSnapshot();
            }
            case SOURCE_FILTER -> {
                filterState.toggleSource((net.shiroha233.roadweaver.map.search.MapStructureSource) hit.value());
                invalidateFilteredSnapshot();
            }
            case TYPE_FILTER -> {
                filterState.toggleType((String) hit.value(), filterState.availableTypes(snapshot));
                invalidateFilteredSnapshot();
            }
            case RESET_FILTER -> {
                filterState.reset();
                invalidateFilteredSnapshot();
            }
            case DETAIL_TELEPORT -> {
                if (hit.value() instanceof BlockPos pos) onTeleportTo(pos);
            }
            case DETAIL_ALIAS -> {
                if (hit.value() instanceof BlockPos pos) openAliasDialog(pos);
            }
            case DETAIL_NOTE -> {
                if (hit.value() instanceof BlockPos pos) openNoteEditor(pos);
            }
            case NONE -> {
            }
        }
    }

    private void renderSearchBox(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!workspacePanel.isTab(MapWorkspacePanel.Tab.SEARCH) || searchBox == null) return;
        var rect = workspacePanel.searchField();
        if (rect == null) return;
        MapDockRenderer.fillRounded(graphics, rect.x(), rect.y(), rect.width(), rect.height(), 5, MapTheme.PANEL_CONTROL_BG);
        searchBox.setX(rect.x() + 6);
        searchBox.setY(rect.y() + 1);
        searchBox.setWidth(Math.max(20, rect.width() - 12));
        searchBox.render(graphics, mouseX, mouseY, partialTick);
    }

    private void onSearchQueryChanged(String value) {
        searchQuery = value == null ? "" : value;
        pendingSearchQuery = searchQuery.trim();
        searchFailed = false;
        searchLoading = false;
        searchStartedAtMs = 0L;
        searchResults = ClientMapNotes.searchAliases(pendingSearchQuery, MapStructureSearchService.MAX_RESULTS);
        if (pendingSearchQuery.isEmpty()) {
            searchLoading = false;
            searchDeadlineMs = 0L;
            return;
        }
        searchDeadlineMs = System.currentTimeMillis() + 250L;
    }

    private void processSearchDebounce() {
        long now = System.currentTimeMillis();
        if (searchLoading && searchStartedAtMs > 0L && now - searchStartedAtMs > 8_000L) {
            searchLoading = false;
            searchFailed = true;
            searchStartedAtMs = 0L;
            searchRequestSeq++;
        }
        if (searchDeadlineMs <= 0L || now < searchDeadlineMs) return;
        submitSearchNow();
    }

    private void submitSearchNow() {
        String query = searchQuery.trim();
        searchDeadlineMs = 0L;
        if (query.isEmpty() || currentDimensionId == null) {
            searchLoading = false;
            searchResults = List.of();
            return;
        }
        submittedSearchQuery = query;
        searchLoading = true;
        searchFailed = false;
        searchStartedAtMs = System.currentTimeMillis();
        int requestSeq = ++searchRequestSeq;
        ClientNetBridge.requestSearch(requestSeq, currentDimensionId, query);
    }

    private void selectSearchResult(MapSearchResult result) {
        if (result == null) return;
        BlockPos pos = result.pos();
        view.centerOn(pos.getX(), pos.getZ(), mapW, mapH);
        workspacePanel.openDetails(pos);
        if (searchBox != null) searchBox.setFocused(false);
        onRequestView();
    }

    private void refreshCurrentView() {
        MapViewportController.RequestRect rect = MapViewportController.currentRequestRect(view);
        for (MapLoadPhase phase : MapLoadPhase.values()) {
            snapshotStore.clearRect(phase, rect);
        }
        snapshot = snapshotStore.snapshot();
        invalidateFilteredSnapshot();
        MapSnapshotCache.put(currentDimensionId, snapshot);
        loadSession = null;
        onRequestView();
    }

    private MapSnapshot visibleSnapshot() {
        if (filteredSnapshot == null
                || filteredSnapshotSource != snapshot
                || filteredSnapshotRevision != filterState.revision()) {
            filteredSnapshot = filterState.apply(snapshot);
            filteredSnapshotSource = snapshot;
            filteredSnapshotRevision = filterState.revision();
        }
        return filteredSnapshot;
    }

    private void invalidateFilteredSnapshot() {
        filteredSnapshot = null;
        filteredSnapshotSource = null;
        filteredSnapshotRevision = -1;
    }

    // ========== 回调实现 ==========
    
    @Override
    public void onCloseScreen() {
        this.onClose();
    }

    @Override
    public void onOpenConfig() {
        if (this.minecraft == null) return;
        var p = this.minecraft.player;
        if (p != null && this.minecraft.getSingleplayerServer() == null) {
            // 多人游戏：配置修改应限制为 OP（服务端权限等级 2+）
            if (!p.hasPermissions(2)) {
                p.displayClientMessage(Component.translatable("gui.roadweaver.map.config.denied"), true);
                return;
            }
        }
        try {
            Screen next = net.shiroha233.roadweaver.client.ConfigScreenFactory.createConfigScreen(this);
            if (next != null) {
                this.minecraft.setScreen(next);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestView() {
        if (!hasMapOpenPermission()) {
            denyMapAccessAndClose();
            return;
        }

        Minecraft mc = this.minecraft;
        if (mc == null) return;

        MapViewportController.RequestRect requestRect = MapViewportController.currentRequestRect(view);
        currentDimensionId = MapViewportController.syncDimensionAndRestoreCache(mc, currentDimensionId, cached -> {
            snapshotStore = MapSnapshotCache.store(mc.level.dimension().location());
            this.snapshot = snapshotStore.snapshot();
        });

        ResourceLocation did = (mc.level != null) ? mc.level.dimension().location() : currentDimensionId;
        if (did == null) {
            did = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        }
        if (currentDimensionId == null || !did.equals(currentDimensionId)) {
            snapshotStore = MapSnapshotCache.store(did);
            snapshot = snapshotStore.snapshot();
        }
        currentDimensionId = did;

        List<MapViewportController.RequestRect> structureRects = MapViewportController.prioritizeMissingRects(
                requestRect, snapshotStore.loadedRects(MapLoadPhase.STRUCTURES));
        List<MapViewportController.RequestRect> roadRects = MapViewportController.prioritizeMissingRects(
                requestRect, snapshotStore.loadedRects(MapLoadPhase.ROADS));
        List<MapViewportController.RequestRect> connectionRects = MapViewportController.prioritizeMissingRects(
                requestRect, snapshotStore.loadedRects(MapLoadPhase.CONNECTIONS));
        List<MapLoadSession.ResponseRequest> requests;
        if (!structureRects.isEmpty()) {
            requests = MapLoadSession.ResponseRequest.fromRects(structureRects, List.of(), List.of());
        } else if (!roadRects.isEmpty()) {
            requests = MapLoadSession.ResponseRequest.fromRects(List.of(), roadRects, List.of());
        } else if (!connectionRects.isEmpty()) {
            requests = MapLoadSession.ResponseRequest.fromRects(List.of(), List.of(), connectionRects);
        } else {
            loadSession = null;
            snapshot = snapshotStore.snapshot();
            MapSnapshotCache.put(did, snapshot);
            return;
        }

        int requestSeq = state.incrementAndGetRequestSeq();
        loadSession = new MapLoadSession(requestSeq, did, requests);
        ServerLevel singleplayerLevel = MapViewportController.resolveSingleplayerLevel(mc);
        if (singleplayerLevel != null && did.equals(singleplayerLevel.dimension().location())) {
            scheduleSingleplayerSnapshotLoad(mc, singleplayerLevel, requestSeq, requests);
        } else {
            dispatchRemoteSnapshotLoad(requestSeq, did, requests);
        }
    }

    @Override
    public void onTeleportTo(BlockPos pos) {
        ClientNetBridge.requestTeleport(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void onManualConnect(BlockPos a, BlockPos b) {
        ClientNetBridge.requestManualConnect(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    @Override
    public void onDeleteConnection(BlockPos a, BlockPos b) {
        ClientNetBridge.requestDeleteConnection(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    /**
     * 弹出施工确认框（★ UX-7 / UX-1 / UX-3，用户 2026-09-25 要求「弹确认框」）。
     *
     * <p>统计口径（都从玩家看得懂的角度，不深挖内部结构）：</p>
     * <ul>
     *   <li><b>控制点 / 长度</b>：路线自身</li>
     *   <li><b>分段</b>：按每段的 kind 分类计数（路/桥/隧道）</li>
     *   <li><b>将加载区块</b>：按控制点与相邻点连线覆盖的区块<b>近似</b>统计
     *       （精确值由服务端 {@code RouteChunkPreloader} 在真正预加载时算）</li>
     *   <li><b>未探索</b>：这些区块里当前不在内存中的数量 —— 会触发生成，慢</li>
     * </ul>
     */
    private void showBuildConfirm() {
        var route = routeEditor.state().route();
        if (route == null || route.pointCount() < 2) {
            // 点数不够时不弹框，直接走原逻辑（它会给出「无可施工分段」提示）
            routeEditor.state().submitForBuild();
            return;
        }
        var points = route.points();
        int n = points.size();
        // 分段按"段的起点 kind"分类（与 RouteKindResolver 的规则 A 一致）
        int road = 0, bridge = 0, tunnel = 0;
        double length = 0;
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        // ★ 采样预算：确认框跑在**渲染线程**（mouseClicked）里，不能为超长路线
        //   卡住一帧。全路线最多采这么多次 —— 超过就按比例放大步长
        //   （区块数只是"量级提示"，精确值由服务端预加载时精算）。
        int budget = 4000;
        int wanted = 0;
        for (int i = 0; i + 1 < n; i++) {
            net.minecraft.core.BlockPos a = points.get(i).pos();
            net.minecraft.core.BlockPos b = points.get(i + 1).pos();
            wanted += (int) Math.ceil(Math.max(
                    Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ())) / 8.0) + 1;
        }
        double stride = wanted <= budget ? 1.0 : (double) wanted / budget;

        for (int i = 0; i + 1 < n; i++) {
            net.minecraft.core.BlockPos a = points.get(i).pos();
            net.minecraft.core.BlockPos b = points.get(i + 1).pos();
            switch (points.get(i).kind()) {
                case ROAD -> road++;
                case BRIDGE -> bridge++;
                case TUNNEL -> tunnel++;
            }
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            length += Math.sqrt(dx * dx + dz * dz);
            // 沿线段按 ~8 格步进采区块（受上面的总预算约束）
            double stepLen = 8.0 * stride;
            int steps = (int) Math.max(1, Math.ceil(
                    Math.max(Math.abs(dx), Math.abs(dz)) / stepLen));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int x = (int) Math.round(a.getX() + dx * t);
                int z = (int) Math.round(a.getZ() + dz * t);
                chunks.add(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4));
            }
            // 保险：即使预算估算失准也不至于无限循环
            if (chunks.size() > 20000) {
                break;
            }
        }
        // 扩 1 圈（与服务端预加载口径一致，让玩家看到真实的量级）
        java.util.Set<Long> expanded = new java.util.HashSet<>();
        for (long key : chunks) {
            int cx = net.minecraft.world.level.ChunkPos.getX(key);
            int cz = net.minecraft.world.level.ChunkPos.getZ(key);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    expanded.add(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
        // 未探索：当前不在内存里的数量
        int unexplored = 0;
        var server = this.minecraft == null ? null : this.minecraft.getSingleplayerServer();
        var level = server == null ? null : server.overworld();
        if (level != null) {
            for (long key : expanded) {
                try {
                    if (level.getChunkSource().getChunkNow(
                            net.minecraft.world.level.ChunkPos.getX(key),
                            net.minecraft.world.level.ChunkPos.getZ(key)) == null) {
                        unexplored++;
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // 查不到就当已加载，避免警告误导
                }
            }
        }

        var lines = net.shiroha233.roadweaver.client.map.ui.RouteBuildConfirmScreen.describe(
                n, road, bridge, tunnel, length, expanded.size(), unexplored);
        // 传 null 作为 parent 也可以，但传自己更稳（取消后回到地图）
        this.minecraft.setScreen(
                new net.shiroha233.roadweaver.client.map.ui.RouteBuildConfirmScreen(
                        this, lines, confirmed -> {
                            if (confirmed) {
                                routeEditor.state().submitForBuild();
                            }
                        }));
    }

    /**
     * 重试预加载失败的区块（★ UX-5 的「重试铺设」按钮）。
     *
     * <p>预加载必须在**服务端线程**跑（会 managedBlock），所以这里
     * 经 {@code server.execute(...)} 投递 —— 与施工提交同一模式。</p>
     */
    private void retryFailedPreloads() {
        var mc = this.minecraft;
        if (mc == null) {
            return;
        }
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        var level = server.overworld();
        if (level == null) {
            return;
        }
        final var route = routeEditor.state().route();
        server.execute(() -> {
            try {
                int n = net.shiroha233.roadweaver.route.RouteBuildService
                        .retryFailedPreloads(level, route);
                // 重新跑一次预加载（只对当前编辑对象；其余的靠后续连接完成再触发）
                if (route != null) {
                    net.shiroha233.roadweaver.route.RouteBuildService
                            .preloadRouteChunks(level, route);
                }
                org.slf4j.LoggerFactory.getLogger("roadweaver/route-build")
                        .info("手绘路线预加载重试完成：{} 条路线", n);
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("roadweaver/route-build")
                        .warn("手绘路线预加载重试失败", t);
            }
        });
    }

    @Override
    public void onCenterToPlayer() {        if (this.minecraft == null || this.minecraft.player == null) return;
        int contentW = mapW;
        int contentH = mapH;
        view.calibrateInitialToPlayer(this.minecraft, contentW, contentH, MapTheme.GRID_TARGET_PX);
        onRequestView();
    }

    @Override
    public void onCenterToSpawn() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        
        BlockPos spawn = null;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                spawn = level.getSharedSpawnPos();
            }
        }
        if (spawn == null && mc.level != null) {
            spawn = mc.level.getSharedSpawnPos();
        }
        if (spawn == null) return;
        
        int contentW = mapW;
        int contentH = mapH;
        view.centerOn(spawn.getX(), spawn.getZ(), contentW, contentH);
        onRequestView();
    }

    public void acceptSnapshot(int requestSeq, ResourceLocation dimensionId, MapSnapshot snapshot) {
        acceptSnapshotPart(requestSeq, dimensionId, MapLoadPhase.CONNECTIONS, 0, snapshot);
    }

    public void acceptPatch(ResourceLocation dimensionId, MapSnapshotPatch patch) {
        if (dimensionId == null || patch == null) return;
        if (currentDimensionId != null && !dimensionId.equals(currentDimensionId)) return;
        snapshotStore.apply(patch);
        this.snapshot = snapshotStore.snapshot();
        MapSnapshotCache.put(dimensionId, this.snapshot);
    }

    public void acceptSearchResults(int requestSeq,
                                    ResourceLocation dimensionId,
                                    boolean success,
                                    List<MapSearchResult> serverResults) {
        if (requestSeq != searchRequestSeq || dimensionId == null || !dimensionId.equals(currentDimensionId)) return;
        if (!submittedSearchQuery.equals(searchQuery.trim())) return;
        searchLoading = false;
        searchStartedAtMs = 0L;
        searchFailed = !success;
        if (!success) return;

        java.util.LinkedHashMap<Long, MapSearchResult> merged = new java.util.LinkedHashMap<>();
        for (MapSearchResult local : ClientMapNotes.searchAliases(searchQuery, MapStructureSearchService.MAX_RESULTS)) {
            merged.put(searchResultKey(local.pos()), local);
        }
        if (serverResults != null) {
            for (MapSearchResult result : serverResults) {
                if (result == null || result.pos() == null) continue;
                if (!merged.containsKey(searchResultKey(result.pos()))
                        && merged.size() >= MapStructureSearchService.MAX_RESULTS) break;
                merged.put(searchResultKey(result.pos()), result);
            }
        }
        searchResults = List.copyOf(merged.values());
    }

    public void acceptSnapshotPart(int requestSeq,
                                   ResourceLocation dimensionId,
                                   MapLoadPhase phase,
                                   int responseIndex,
                                   MapSnapshot snapshot) {
        if (dimensionId == null || snapshot == null) return;
        if (requestSeq != state.getCurrentRequestSeq()) return;
        if (currentDimensionId != null && !dimensionId.equals(currentDimensionId)) return;
        if (loadSession == null || !loadSession.accepts(requestSeq, dimensionId)) return;

        MapLoadSession.ResponseRequest request = loadSession.requestAt(responseIndex);
        MapViewportController.RequestRect loadedRect = request != null ? request.rect() : null;
        MapLoadPhase loadedPhase = request != null ? request.phase() : phase;

        snapshotStore.merge(loadedPhase, loadedRect, snapshot);
        loadSession.markReceived(loadedPhase, responseIndex, snapshot);
        this.snapshot = snapshotStore.snapshot();
        MapSnapshotCache.put(dimensionId, this.snapshot);
        if (loadSession.isComplete()) {
            loadSession = null;
            onRequestView();
        }
    }

    private void scheduleSingleplayerSnapshotLoad(Minecraft mc,
                                                  ServerLevel level,
                                                  int requestSeq,
                                                  List<MapLoadSession.ResponseRequest> requests) {
        ResourceLocation dimensionId = level.dimension().location();
        for (int responseIndex = 0; responseIndex < requests.size(); responseIndex++) {
            MapLoadSession.ResponseRequest request = requests.get(responseIndex);
            MapViewportController.RequestRect rect = request.rect();
            scheduleSingleplayerPhase(mc, requestSeq, dimensionId, request.phase(), responseIndex,
                    () -> buildSnapshotForPhase(level, request.phase(), rect));
        }
    }

    private MapSnapshot buildSnapshotForPhase(ServerLevel level,
                                             MapLoadPhase phase,
                                             MapViewportController.RequestRect rect) {
        return switch (phase) {
            case STRUCTURES -> MapDataCollector.buildStructuresSnapshot(level, rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
            case ROADS -> MapDataCollector.buildRoadsSnapshot(level, rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
            case CONNECTIONS -> MapDataCollector.buildConnectionsSnapshot(level, rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
        };
    }

    private int scheduleSingleplayerPhase(Minecraft mc,
                                          int requestSeq,
                                          ResourceLocation dimensionId,
                                          MapLoadPhase phase,
                                          int responseIndex,
                                          java.util.function.Supplier<MapSnapshot> supplier) {
        final int currentIndex = responseIndex;
        final String worldId = MapSnapshotCache.getCurrentWorldId();
        final ServerLevel expectedLevel = MapViewportController.resolveSingleplayerLevel(mc);
        CompletableFuture
                .supplyAsync(supplier, ComputeService.mapReadExecutor())
                .thenAccept(snap -> mc.execute(() -> {
                    if (!java.util.Objects.equals(worldId, MapSnapshotCache.getCurrentWorldId())) return;
                    if (expectedLevel == null || MapViewportController.resolveSingleplayerLevel(mc) != expectedLevel) return;
                    acceptSnapshotPart(requestSeq, dimensionId, phase, currentIndex, snap);
                }));
        return responseIndex + 1;
    }

    private void dispatchRemoteSnapshotLoad(int requestSeq,
                                            ResourceLocation dimensionId,
                                            List<MapLoadSession.ResponseRequest> requests) {
        for (int responseIndex = 0; responseIndex < requests.size(); responseIndex++) {
            MapLoadSession.ResponseRequest request = requests.get(responseIndex);
            MapViewportController.RequestRect rect = request.rect();
            ClientNetBridge.requestSnapshot(requestSeq, dimensionId, request.phase(), responseIndex,
                    rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
        }
    }

    // ========== 辅助方法 ==========
    
    private void computeMapRect() {
        mapX = 0;
        mapY = 0;
        mapW = this.width;
        mapH = this.height;
    }

    private static int workspaceBottomLimit(MapDockRenderer.DockLayout layout) {
        return Math.max(1, layout.bounds().y() - WORKSPACE_DOCK_GAP);
    }

    private int computeThickness() {
        int contentW = mapW;
        int contentH = mapH;
        double ppb = Math.min(view.pxPerBlockX(contentW), view.pxPerBlockZ(contentH));
        int t = (int) Math.round(ppb);
        return Math.max(MapTheme.MIN_THICKNESS, Math.min(t, MapTheme.MAX_THICKNESS));
    }

    private int computePointSize() {
        return MapTheme.BASE_POINT_SIZE + computeThickness();
    }

    private void openContextMenuFor(BlockPos target, int x, int y) {
        contextMenu.clearItems();
        contextMenu.addItem(MENU_TELEPORT, () -> onTeleportTo(target));
        contextMenu.addSeparator();
        contextMenu.addItem(MENU_SET_ALIAS, () -> openAliasDialog(target));
        contextMenu.addItem(MENU_EDIT_NOTE, () -> openNoteEditor(target));
        contextMenu.open(x, y);
    }

    /** 打开别名设置对话框 */
    private void openAliasDialog(BlockPos target) {
        if (this.minecraft == null) return;
        String currentAlias = ClientMapNotes.getAlias(target);
        this.minecraft.setScreen(new SimpleTextInputScreen(
                DIALOG_ALIAS_TITLE,
                currentAlias != null ? currentAlias : "",
                alias -> ClientMapNotes.setAlias(target, alias),
                this  // 返回地图界面
        ));
    }

    /** 打开笔记编辑器（书与笔风格） */
    private void openNoteEditor(BlockPos target) {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new NoteEditScreen(target, this));
    }

    private boolean hasMapOpenPermission() {
        Minecraft mc = this.minecraft;
        if (mc == null) {
            return true;
        }
        if (!ClientMapAccessGuard.isAllowedOrUnknown()) {
            return false;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            return true;
        }
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
        return serverPlayer == null || MapAccessService.canOpenMap(serverPlayer);
    }

    private TerrainSamplingSessionSnapshot terrainSamplingSession(Minecraft minecraft) {
        if (minecraft == null) return null;
        ServerLevel level = MapViewportController.resolveSingleplayerLevel(minecraft);
        if (level == null) return null;
        return TerrainSamplingSessions.forLevel(level).snapshot();
    }

    private List<AutomaticPlanningSamplingBounds> activeAutomaticPlanningSamplingBounds(ServerLevel level) {
        if (level != null) {
            return AutomaticPlanningSamplingActivities.snapshot(level);
        }
        return MapAutomaticPlanningSamplingCache.snapshot(currentDimensionId);
    }

    private static long searchResultKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    private void denyMapAccessAndClose() {
        ClientMapAccessGuard.notifyDenied(this.minecraft);
        MapSnapshotCache.clearNow();
        this.onClose();
    }
}
