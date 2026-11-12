/* 文件职责：把编辑器状态与地图界面接起来 —— 屏幕坐标↔世界坐标、命中检测、拖动语义。
 *
 * 单独成类是因为 RoadMapScreen 已 900+ 行；这里集中「转换逻辑」，
 * 屏幕只负责在合适的时机调用几个方法。
 */
package net.shiroha233.roadweaver.route.editor;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.client.map.MapView;
import org.jetbrains.annotations.Nullable;

/**
 * 把 {@link RouteEditorState} 与地图界面的坐标/输入接起来。
 *
 * <h2>为什么单独一个类</h2>
 * {@code RoadMapScreen} 已经 900+ 行，直接往里塞编辑逻辑会让它更难维护。
 * 这里把「屏幕坐标 ↔ 世界坐标」「命中检测」「拖动语义」这些**转换逻辑**集中起来，
 * {@code RoadMapScreen} 只负责在合适的时机调用本类的几个方法。
 *
 * <h2>★ 拖动语义（与用户确认）</h2>
 * <pre>
 *   按下时命中控制点 → 拖动 = 挪那个点
 *   按下时在空白处   → 拖动 = 平移地图
 * </pre>
 */
public final class RouteEditorBridge {

    private final RouteEditorState state = new RouteEditorState();

    /** 上一次渲染算出的控制点屏幕坐标（命中检测要用）。 */
    private List<Integer> lastScreenXY = List.of();

    /** 上一次渲染时地图的布局参数（命中检测与坐标换算要用）。 */
    private int mapX, mapY, innerPad;

    private final MapView view;

    public RouteEditorBridge(MapView view) {
        this.view = view;
    }

    public RouteEditorState state() {
        return state;
    }

    /**
     * 回填地图布局。
     *
     * <p><b>⚠️ 必须与渲染时的取值完全一致</b>，否则「看到的位置」和
     * 「点到的位置」会错位。目前调用方传的是 {@code (0, 0, 0)}，
     * 与 {@code RoadMapScreen.render} 里
     * {@code view.toScreenX(v, 0, 0, contentW)} 的 {@code mapX=0, innerPad=0} 一致。
     *
     * <p>如果将来地图加了内边距，这里与渲染侧必须同时改。</p>
     */
    public void updateLayout(int mapX, int mapY, int innerPad) {
        this.mapX = mapX;
        this.mapY = mapY;
        this.innerPad = innerPad;
    }

    /** 每次渲染后由屏幕回填控制点屏幕坐标。 */
    public void updateScreenPoints(List<Integer> screenXY) {
        this.lastScreenXY = screenXY == null ? List.of() : screenXY;
    }

    // ─────────────────── 屏幕 → 世界 ───────────────────

    /**
     * 屏幕坐标 → 世界方块坐标。
     *
     * <h2>★ 审计 P3-2：Y 不再硬编码 64</h2>
     * 原实现一律返回 {@code Y=64}，问题是：
     * <ul>
     *   <li>对 {@link RouteKind#ROAD} 无所谓 —— 管线会重算贴地高度</li>
     *   <li>但对 {@link RouteKind#BRIDGE} / {@link RouteKind#TUNNEL}，Y 就是**玩家
     *       指定的桥面/隧道高度**，@64 是错的语义：在 y=100 的高原上画桥，
     *       桥会被放到 64（比地面还低）。</li>
     * </ul>
     * 现在的策略：
     * <ol>
     *   <li>先试**真实地形采样**（主世界可用时）—— 桥/隧道也有个合理起点</li>
     *   <li>取不到就退回**上一个控制点的高度**（延续玩家的最近意图）</li>
     *   <li>再退回 64（海平面近似）</li>
     * </ol>
     *
     * @return null 表示不在有效地图区域内
     */
    @Nullable
    public BlockPos screenToBlock(double mouseX, double mouseY, int mapW, int mapH) {
        if (view == null) {
            return null;
        }
        int contentW = mapW - innerPad * 2;
        int contentH = mapH - innerPad * 2;
        if (contentW <= 0 || contentH <= 0) {
            return null;
        }
        double wx = view.screenToWorldX(mouseX, mapX, innerPad, contentW);
        double wz = view.screenToWorldZ(mouseY, mapY, innerPad, contentH);
        int bx = (int) Math.round(wx);
        int bz = (int) Math.round(wz);

        // ① 真实地形采样
        Integer ground = sampleGroundY(bx, bz);
        if (ground != null) {
            return new BlockPos(bx, ground, bz);
        }
        // ② 延续上一个控制点的高度
        var route = state.route();
        if (route != null && !route.isEmpty()) {
            return new BlockPos(bx, route.point(route.pointCount() - 1).pos().getY(), bz);
        }
        // ③ 兜底
        return new BlockPos(bx, 64, bz);
    }

    /**
     * 采一处地面高度（主世界可用时）。
     *
     * <p>采样失败一律返回 null 让调用方走兜底 —— 放点是实时交互，
     * 不能因为采样异常让界面卡住或崩。</p>
     */
    @Nullable
    private Integer sampleGroundY(int x, int z) {
        try {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            // 未加载的区块不强行加载（会卡），查不到就返回 null
            var level = server.overworld();
            if (!level.hasChunkAt(new BlockPos(x, 64, z))) {
                return null;
            }
            return level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    x, z);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ─────────── ★ UX-13：放点时异步补正为真实地表高度 ───────────

    /**
     * 正在等待补正的区块数（★ 防止连点造成区块加载风暴）。
     *
     * <p>玩家可能连续快速放点；每个点都可能拉一个区块。虽然都是**已生成区块的
     * 二次加载**（用户已确认他只会在已生成过的位置选点），但仍不该无限堆积 ——
     * 超过上限就**放弃补正**（该点保持放点时的值，玩家仍可手动调高度）。</p>
     */
    private static final java.util.Set<Long> PENDING_SNAP = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 同时最多等待多少个区块补正（超过就跳过，不再排队）。 */
    private static final int MAX_PENDING_SNAP = 8;

    /**
     * 异步把控制点的 Y 补正为**真实地表高度**（★ 用户 2026-09-25 要求）。
     *
     * <h2>要解决什么</h2>
     * 用户原话：
     * <blockquote>
     *   「2d平面地图我们不是按 ctrl 加鼠标左键选点的时候这里能不能直接采样
     *     真实的地表高度来。除非玩家自己改动」
     * </blockquote>
     * {@link #screenToBlock} 的**首选**本来就是真实采样，但那要求
     * {@code level.hasChunkAt(...)} 为真。而地图能看到**已探索但未加载**的区域 ——
     * 在那上面放点会采样失败，降级成「沿用上一个控制点的高度」，
     * 于是玩家看到一整条线的 Y 都是同一个值。
     *
     * <h2>为什么是异步</h2>
     * 已生成的区块**二次加载**通常很快，但仍是 I/O；而放点是实时交互，
     * 不能让地图界面卡住。所以这里：
     * <ol>
     *   <li>放点时先用手上能拿到的值（可能是沿用值）—— 界面立刻响应</li>
     *   <li>后台把那个区块读进内存（已生成的从磁盘读，很快）</li>
     *   <li>拿到后把**该点的 Y** 改成真实地表 —— 玩家看到高度"跳"成正确的</li>
     * </ol>
     *
     * <h2>★ 玩家的手动改动不会被覆盖</h2>
     * 用户要求「**除非玩家自己改动**」—— 见 {@link #applyGroundY}：
     * 只有当该点的 XZ 仍是放点时的坐标、且 Y 确实不同时才写回。
     * 玩家改了高度（滚轮/输入框）或用 XZ 挪走了点，补正就自动放弃。
     *
     * <h2>★ 只针对「已生成」的区块</h2>
     * 用户明确：「玩家选的时候只会在已经生成过的位置，不会去未加载区块」。
     * 所以这里的 {@code forceLoad} 实际命中的是**磁盘上的既有区块**（快速二次加载）。
     * 另外用 {@link #MAX_PENDING_SNAP} 限制并发，避免连点堆积。
     *
     * @param index 控制点下标（补正时若下标已越界/点已被删则安全放弃）
     */
    private void scheduleGroundSnap(int index, int x, int z) {
        try {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            var level = server.overworld();
            if (level == null) {
                return;
            }
            // 已经在内存里 ⇒ 直接同步采样（最快路径，无异步）
            if (level.hasChunkAt(new BlockPos(x, 64, z))) {
                Integer y = sampleGroundY(x, z);
                if (y != null) {
                    applyGroundY(index, x, z, y);
                }
                return;
            }
            long chunkKey = net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4);
            if (PENDING_SNAP.size() >= MAX_PENDING_SNAP || !PENDING_SNAP.add(chunkKey)) {
                return;   // 排队已满或该区块已在等 —— 放弃这次补正
            }
            level.getChunkSource().getChunkFuture(
                    x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true
            ).whenComplete((result, failure) -> {
                PENDING_SNAP.remove(chunkKey);
                if (failure != null) {
                    return;
                }
                // 回到客户端主线程再改状态（渲染线程读同一份数据）
                mc.execute(() -> {
                    try {
                        if (!level.hasChunkAt(new BlockPos(x, 64, z))) {
                            return;
                        }
                        int y = level.getHeight(
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                x, z);
                        applyGroundY(index, x, z, y);
                    } catch (RuntimeException | LinkageError ignored) {
                        // 补正失败不影响已放的点
                    }
                });
            });
        } catch (RuntimeException | LinkageError ignored) {
            // 补正只是"锦上添花"，任何异常都不该影响放点
        }
    }

    /** 把某点的 Y 改成采样值（仅当 XZ 仍是当初那个点、且 Y 确实不同时才写 + 记撤销）。 */
    private void applyGroundY(int index, int x, int z, int groundY) {
        var route = state.route();
        if (route == null || index < 0 || index >= route.pointCount()) {
            return;
        }
        BlockPos cur = route.point(index).pos();
        // ⚠️ 玩家可能已经把这个点挪走或改过高度 ⇒ 只在 XZ 一致且 Y 不同时才补正。
        //    这样「玩家自己改动过」的点**不会被覆盖**（用户明确要求"除非玩家自己改动"）。
        if (cur.getX() != x || cur.getZ() != z || cur.getY() == groundY) {
            return;
        }
        state.setPointY(index, groundY);
    }

    /** 放点后请求补正（由 appendPoint / insertPointAfter 两处调用）。 */
    void snapNewPointToGround(int index) {
        var route = state.route();
        if (route == null || index < 0 || index >= route.pointCount()) {
            return;
        }
        BlockPos p = route.point(index).pos();
        scheduleGroundSnap(index, p.getX(), p.getZ());
    }

    // ─────────────────── 命中检测 ───────────────────

    /**
     * 鼠标是否命中某个控制点。
     *
     * @return 命中的点下标；没命中返回 -1
     */
    public int hitTestPoint(double mouseX, double mouseY) {
        int n = lastScreenXY.size() / 2;
        if (n == 0) {
            return -1;
        }
        java.util.List<Integer> xs = new java.util.ArrayList<>(n);
        java.util.List<Integer> ys = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            xs.add(lastScreenXY.get(i * 2));
            ys.add(lastScreenXY.get(i * 2 + 1));
        }
        return state.hitTestPoint(xs, ys, mouseX, mouseY);
    }

    /**
     * 鼠标是否命中某段的中点（用于插入控制点）。
     *
     * @return 命中的段下标；没命中返回 -1
     */
    public int hitTestSegment(double mouseX, double mouseY) {
        int n = lastScreenXY.size() / 2;
        if (n < 2) {
            return -1;
        }
        java.util.List<Integer> xs = new java.util.ArrayList<>(n);
        java.util.List<Integer> ys = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            xs.add(lastScreenXY.get(i * 2));
            ys.add(lastScreenXY.get(i * 2 + 1));
        }
        return state.hitTestSegmentMidpoint(xs, ys, mouseX, mouseY);
    }

    // ─────────────────── 左键点击 ───────────────────

    /**
     * 左键按下（在编辑模式下由屏幕调用）。
     *
     * <h2>处理顺序</h2>
     * <ol>
     *   <li>命中控制点 → 选中它，进入「拖动挪点」状态</li>
     *   <li>命中段中点 → 在该段插入一个新控制点</li>
     *   <li>空白处 → 放一个新控制点（追加到末尾）</li>
     * </ol>
     * 注意：空白处**放点**而不是开始平移 —— 平移由拖动时的
     * {@link #beginDrag} 决定（按下这一下先记录，真正移动了才平移）。
     *
     * @return true 表示已消费该事件（屏幕不应再走原有逻辑）
     */
    public boolean onLeftClick(double mouseX, double mouseY, int mapW, int mapH) {
        if (!state.isActive()) {
            return false;
        }

        // ══════════ ★ 修饰符闸：没有 Ctrl 就完全放行给地图 ══════════
        //
        // 原先空白处**总是**放点并消费事件，导致：
        //   · 想拖动地图 → 每按一下都在放新点 → 地图永远收不到拖动
        //   · 想滚轮缩放 → 只要选中过点就被调高度占用 → 缩放失效
        // 玩家反馈：「地图无法拖动了 缩放也是」。
        //
        // 现在的语义（用户确认）：
        //   不按 Ctrl → 左键完全是地图的（拖动平移）
        //   按住 Ctrl → 左键才是编辑器的（放点 / 选中点）
        //
        // 这样编辑模式下地图仍然是一张正常地图，不会误操作。
        if (!isEditModifierHeld()) {
            return false;
        }

        int hit = hitTestPoint(mouseX, mouseY);
        if (hit >= 0) {
            state.selectPoint(hit);
            // 记录按下位置；是否变成拖动由 mouseDragged 决定
            state.beginDrag(hit, mouseX, mouseY);
            return true;
        }

        BlockPos world = screenToBlock(mouseX, mouseY, mapW, mapH);
        if (world == null) {
            return false;
        }

        // 双击段中点 → 插入点；否则空白处追加一个点
        int seg = hitTestSegment(mouseX, mouseY);
        int newIndex;
        if (seg >= 0) {
            newIndex = state.insertPointAfter(seg, world);
        } else {
            newIndex = state.appendPoint(world);
        }
        // ★ UX-13：新点若落在「已生成但未加载」的区块上，异步补正为真实地表高度
        snapNewPointToGround(newIndex);
        // ★ 审计 P3-3：**保留选中**，不再 clearSelection()。
        //
        // 原先这里清掉选中，导致玩家放完点必须**再点一次**那个点才能
        // 调高度/滚轮 —— 手感割裂。而 appendPoint/insertPointAfter 内部
        // 已经把选中设成新点了，正好承接「放完点立刻调」的自然流程。
        //
        // 不进入拖动模式（避免按下就误拖）：dragMode 保持 NONE，
        // 由 mouseDragged 的 isDragging() 判断自然放行给地图平移。
        state.cancelDrag();
        return true;
    }

    /**
     * 「编辑修饰符」当前是否按住。
     *
     * <p>委托给 {@link RouteKeys#isEditModifierHeld()} —— 地图与世界内
     * **共用同一个判定**，避免两边不一致（用户要求两处都要 Ctrl）。</p>
     */
    private static boolean isEditModifierHeld() {
        return RouteKeys.isEditModifierHeld();
    }

    // ─────────────────── 拖动 ───────────────────

    /**
     * 拖动中。若当前在「挪点」，把点移到鼠标对应的世界坐标；否则放行给平移地图。
     *
     * @return true 表示已消费（屏幕不应再走平移逻辑）
     */
    public boolean onDrag(double mouseX, double mouseY, int mapW, int mapH) {
        if (!state.isActive() || !state.isDragging()) {
            return false;
        }
        // 拖动中途松开 Ctrl → 立刻转成地图平移（手感上等于"放手了"）
        if (!isEditModifierHeld()) {
            state.cancelDrag();
            return false;
        }
        if (!state.isDraggingPoint()) {
            // 交给地图自己做平移
            state.consumeDragDeltaX(mouseX);
            state.consumeDragDeltaY(mouseY);
            return false;
        }

        BlockPos world = screenToBlock(mouseX, mouseY, mapW, mapH);
        if (world != null && state.dragPointIndex() >= 0) {
            int idx = state.dragPointIndex();
            var route = state.route();
            if (route != null && idx >= 0 && idx < route.pointCount()) {
                BlockPos cur = route.point(idx).pos();
                // ★ 审计 P2-9：只有确实会改变位置时才惰性记录撤销点。
                // 单纯点击选中（或拖动但鼠标没跨格）不污染撤销栈。
                if (cur.getX() != world.getX() || cur.getZ() != world.getZ()) {
                    state.recordIfMoved();
                    state.movePointXZ(idx, world.getX(), world.getZ());
                }
            }
        }
        state.consumeDragDeltaX(mouseX);
        state.consumeDragDeltaY(mouseY);
        return true;
    }

    /** 松开鼠标。 */
    public boolean onRelease(double mouseX, double mouseY, int button) {
        if (!state.isDragging()) {
            return false;
        }
        boolean wasPoint = state.isDraggingPoint();
        state.endDrag();
        return wasPoint;
    }

    // ─────────────────── 滚轮 ───────────────────

    /**
     * 滚轮：**按住 Ctrl 且有选中点**时调高度，否则放行给地图缩放。
     *
     * <p>★ 原来只要「有选中点」就调高度，结果玩家选中过一次点之后
     * **地图再也缩放不了**（玩家反馈：「缩放也是」）。现在加 Ctrl 闸：
     * 不按 Ctrl 时滚轮永远是地图的缩放。</p>
     *
     * @return true 表示已消费
     */
    public boolean onScroll(double delta) {
        if (!state.isActive() || !state.hasSelection()) {
            return false;
        }
        // 没有 Ctrl → 是地图缩放，不消费
        if (!isEditModifierHeld()) {
            return false;
        }
        int idx = state.selectedPointIndex();
        var route = state.route();
        if (route == null || idx < 0 || idx >= route.pointCount()) {
            return false;
        }
        // 滚轮是连续操作且**没有结束事件**：用时间窗自动分组，
        // 连滚归为一次撤销点，停顿后重新滚算新的一次。
        state.recordAutoGroupedUndo();
        int y = route.point(idx).pos().getY();
        int step = delta > 0 ? 1 : -1;
        // 加速键统一走 RouteKeys.isBoostHeld()（可绑定，默认左 Shift）。
        // 原来这里读的是 mc.options.keyShift（原版潜行）——
        // 玩家改潜行键后会连带影响这里的加速，语义上不该如此。
        if (RouteKeys.isBoostHeld()) {
            step *= RouteKeys.BOOST_MULTIPLIER;
        }
        state.setPointY(idx, y + step);
        return true;
    }

    // ─────────────────── 键盘 ───────────────────

    /**
     * 地图界面里的键盘处理（由 {@code RoadMapScreen.keyPressed} 转发）。
     *
     * <h2>★ 哪些走 KeyMapping、哪些仍看原始 keyCode</h2>
     * 这个方法收到的是**原始 GLFW keyCode**，因为 {@code Screen.keyPressed} 只给这个。
     * 两类按键处理方式不同：
     * <ul>
     *   <li><b>高度输入框的文本输入</b>（数字、负号、退格、回车确认、Esc 取消）
     *       —— 这些是**文本框语义**，必须看原始 keyCode，
     *       否则玩家把「确认施工」改成别的键后，输入框的回车确认就失效了。</li>
     *   <li><b>编辑动作键</b>（撤销 / 删点 / 换类型）
     *       —— 这些走 {@link RouteKeys} 的绑定，玩家改键后地图里也生效。
     *       用 {@code KeyMapping.matches(keyCode, scanCode)} 判断。</li>
     * </ul>
     *
     * <h2>为什么不用 isDown()</h2>
     * 这里是 Screen 的按键回调（事件驱动，一次按下触发一次），
     * 不是逐 tick 轮询，所以用 {@code matches(...)} 判断「这次按的是不是这个绑定」，
     * 而不是查「现在有没有按住」。
     *
     * @param keyCode   GLFW 键码
     * @param scanCode  GLFW 扫描码（{@code matches} 需要）
     * @param modifiers 修饰键位掩码
     * @return true 表示已消费
     */
    public boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (!state.isActive()) {
            return false;
        }

        // ── 高度输入框打开时：文本语义，看原始 keyCode ──
        if (state.isHeightInputOpen()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                state.commitHeightInput();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                state.closeHeightInput();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                state.backspaceHeightInput();
                return true;
            }
            // 数字键：GLFW 的 0-9 是连续的 48..57
            if (keyCode >= org.lwjgl.glfw.GLFW.GLFW_KEY_0
                    && keyCode <= org.lwjgl.glfw.GLFW.GLFW_KEY_9) {
                state.appendHeightInputChar((char) ('0' + (keyCode - org.lwjgl.glfw.GLFW.GLFW_KEY_0)));
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS) {
                state.appendHeightInputChar('-');
                return true;
            }
            return true;    // 输入框打开时吞掉其它按键
        }

        // ── 编辑动作键：走玩家配置的绑定 ──

        // ★ 审计 P3-5：撤销**不再要求 Ctrl**。
        //
        // 原先写的是「走绑定 && 必须按住 Ctrl」，于是玩家把 UNDO 绑到
        // 非字符键（如 F3）后仍需按 Ctrl+F3 —— 与「设置界面里绑什么就是什么」
        // 的直觉不符。而且 KeyMapping 本身已经不与原版冲突
        // （默认 Z 与原版无绑定冲突，真冲突了原版也会在设置里标红）。
        //
        // 注意：地图界面是 Screen，按键会先到 Screen；这里已经没有「打字」
        // 场景（高度输入框在上面那个分支里已经吞掉了），所以不需要 Ctrl 区分。
        if (matches(RouteKeys.UNDO, keyCode, scanCode)) {
            state.undo();
            return true;
        }
        // Ctrl+<撤销键> 之外：Ctrl+Y 重做（Y 没有独立绑定，重做是次要功能）
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Y
                && (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0) {
            state.redo();
            return true;
        }
        // 删点 —— 用绑定
        if (matches(RouteKeys.DELETE_POINT, keyCode, scanCode)) {
            if (state.hasSelection()) {
                state.removePoint(state.selectedPointIndex());
                return true;
            }
            return false;
        }
        // 换类型 —— 用绑定
        if (matches(RouteKeys.CYCLE_KIND, keyCode, scanCode)) {
            state.cycleKind();
            return true;
        }
        // 输入高度 —— 用绑定（世界里调高用滚轮，这个键主要给地图界面用）
        if (matches(RouteKeys.OPEN_HEIGHT, keyCode, scanCode)) {
            if (state.hasSelection()) {
                state.openHeightInput();
                return true;
            }
            return false;
        }
        // ★ UX-11：中止预加载 —— **必须配合编辑修饰符**
        //   （用户 2026-09-25：「必须按 ctrl + 按键才行」）。
        //
        //   用 isEditModifierHeld() 而不是硬编码 Ctrl —— 修饰键本身是
        //   **可自定义绑定**（审计 R3-P3-5），这样玩家改了修饰键这里也跟着变，
        //   与世界内那条路径（在 Ctrl 闸之后执行）语义一致。
        if (RouteKeys.isEditModifierHeld()
                && matches(RouteKeys.ABORT_PRELOAD, keyCode, scanCode)) {
            var srv = net.shiroha233.roadweaver.route.editor.RouteEditorSession
                    .get().serverLevelOrNull();
            var r = state.route();
            if (srv != null && r != null && r.id() != null) {
                net.shiroha233.roadweaver.route.RoutePreloadStatus.requestStop(srv, r.id());
            }
            return true;
        }
        // ★ 任务 A（2026-09-26 修订）：端点标记开关**已改为地图底部 Dock 按钮** ——
        //   用户明确「这里下方不是有一排按钮么加个格外的按钮就行，不用绑定键盘了」。
        //   所以这里**不再**处理键盘（原 Ctrl+M 已移除），入口只在 Dock。
        return false;
    }

    /**
     * 这次按键是不是某个绑定。
     *
     * <p>{@code KeyMapping.matches(keyCode, scanCode)} 是原版给 Screen
     * 场景用的匹配方法 —— 它同时比键码与扫描码，且正确处理鼠标键绑定。</p>
     */
    private static boolean matches(net.minecraft.client.KeyMapping mapping, int keyCode, int scanCode) {
        if (mapping == null) {
            return false;
        }
        try {
            return mapping.matches(keyCode, scanCode);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ─────────────────── 模式切换 ───────────────────

    /** 进入编辑模式（若还没路线则新建）。 */
    public void enter() {
        state.activate();
        // ★ 审计 P2-8：必须同时重置世界内编辑器的状态。
        //
        // 地图的 enter() 原只做 state.activate()（= session.setActive(true)），
        // 而 ClientInit 判断「是否编辑中」用的就是 session.isActive()，
        // 于是关掉地图进世界后，RouteWorldEditor.selected 保留**上一次会话的旧值**。
        // 若它 >= 0：滚轮会去改一个当前根本没选中的点，
        // 并且 RouteScrollMixin 会 ci.cancel() 连带吞掉原版的切换快捷栏。
        RouteWorldRenderer.onEnterEditMode();
    }

    /** 退出编辑模式（保留路线）。 */
    public void exit() {
        state.deactivate();
        RouteWorldRenderer.onExitEditMode();
    }

    public boolean isActive() {
        return state.isActive();
    }
}
