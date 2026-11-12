/* 文件职责：手绘编辑器的**纯状态** —— 不含路线数据、不碰渲染。
 *
 * 路线存在 RouteEditorSession（进程级单例），因为玩家要「画完关地图 →
 * 跑进世界看 → 回来接着调」，路线必须活得比界面久。
 */
package net.shiroha233.roadweaver.route.editor;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.route.PlayerRoute;
import net.shiroha233.roadweaver.route.RouteKind;
import net.shiroha233.roadweaver.route.RoutePoint;
import net.shiroha233.roadweaver.route.RouteUndoStack;
import org.jetbrains.annotations.Nullable;

/**
 * 地图绘制界面的状态机。
 *
 * <h2>它管什么</h2>
 * <ul>
 *   <li>当前是否处于「路线编辑模式」</li>
 *   <li>撤销栈</li>
 *   <li>鼠标拖动的是「某个点」还是「地图视角」</li>
 *   <li>选中了哪个点、高度输入框开没开</li>
 * </ul>
 *
 * <h2>⚠️ 路线数据不在这里</h2>
 * 路线存在 {@link RouteEditorSession}（进程级单例）。
 * 本类挂在 {@code RoadMapScreen} 上，屏幕关掉就被 GC —— 而玩家要
 * 「画完关地图 → 跑进世界看 → 回来接着调」，路线必须活得比界面久。
 *
 * <h2>★ 拖动语义（与用户确认过）</h2>
 * <pre>
 *   按下时命中某个控制点 → 拖动 = 挪那个点
 *   按下时在空白处       → 拖动 = 平移地图
 * </pre>
 * 这是 CAD / 大多数编辑器的惯例，由 {@link #beginDrag} 决定。
 *
 * <p>本类**不碰渲染**，纯状态。</p>
 */
public final class RouteEditorState {

    /** 命中控制点的判定半径（屏幕像素）。 */
    public static final double HIT_RADIUS_PX = 10.0;

    /** 拖动类型。 */
    public enum DragMode {
        NONE,
        /** 拖动某个控制点（{@link #dragPointIndex} 有效）。 */
        POINT,
        /** 拖动地图视角。 */
        PAN
    }

    private final RouteEditorSession session = RouteEditorSession.get();

    /**
     * 地图界面的撤销栈。
     *
     * <h2>★ 审计 R3-P2-9：挪进会话，不再随 Screen 生死</h2>
     * 原先是本类的实例字段，而本类挂在 {@code RoadMapScreen} 上 ——
     * 关掉地图再打开就是**新对象、空栈**，玩家辛苦画的十几步撤销全没了。
     * 现在从会话取，跨界面存活（与世界内撤销栈各自独立，语义更清晰）。
     */
    private RouteUndoStack undoStack() {
        return session.mapUndoStack();
    }

    private DragMode dragMode = DragMode.NONE;
    private int dragPointIndex = -1;
    private double lastMouseX;
    private double lastMouseY;

    /** 高度输入框是否打开。 */
    private boolean heightInputOpen;
    private String heightInputText = "";

    /** 上次点「施工」的结果（供界面反馈；Integer.MIN_VALUE = 还没点过）。 */
    private int lastSubmitResult = Integer.MIN_VALUE;
    private long lastSubmitAtMs;

    // ───────────────────────── 模式 ─────────────────────────

    public boolean isActive() {
        return session.isActive();
    }

    /** 进入编辑模式；没有路线时自动新建一条空路线。 */
    public void activate() {
        session.setActive(true);
        session.currentOrCreate();
    }

    /** 退出编辑模式（路线数据保留在 session，便于再进来继续调）。 */
    public void deactivate() {
        session.setActive(false);
        this.dragMode = DragMode.NONE;
        this.dragPointIndex = -1;
        // ★ 审计 P2-10：编辑模式在拖动**中途**被关闭时，
        // 必须清掉连续编辑标志，否则下次拖动无法撤销。
        endContinuousEdit();
        session.setSelectedPointIndex(-1);
        this.heightInputOpen = false;
    }

    /** 丢弃当前路线（重新画）。 */
    public void discard() {
        session.discardCurrent();
        undoStack().clear();
        session.setSelectedPointIndex(-1);
        this.dragMode = DragMode.NONE;
        this.heightInputOpen = false;
    }

    // ───────────────────────── 路线 ─────────────────────────

    @Nullable
    public PlayerRoute route() {
        return session.current();
    }

    /** 用一条现有路线替换（切换编辑对象时用）。 */
    public void setRoute(PlayerRoute newRoute) {
        session.update(newRoute);
        undoStack().clear();
        session.setSelectedPointIndex(-1);
    }

    public boolean hasRoute() {
        return session.current() != null;
    }

    public boolean hasDrawableRoute() {
        PlayerRoute r = session.current();
        return r != null && r.isDrawable();
    }

    // ───────────────────────── 工具条 ─────────────────────────

    public RouteKind currentKind() {
        return session.currentKind();
    }

    public void setCurrentKind(RouteKind kind) {
        session.setCurrentKind(kind == null ? RouteKind.ROAD : kind);
    }

    /** 工具条循环切换：路 → 桥 → 隧道 → 路。 */
    public void cycleKind() {
        session.cycleKind();
    }

    // ─────────────────── 编辑操作（全部走撤销栈）───────────────────

    /**
     * 追加一个控制点（点击放点）。
     *
     * <p>审计 P3-2：原签名有个 {@code groundSnappedY} 参数但**从未使用**，
     * 已删除。放点时的 Y 由调用方（{@code RouteEditorBridge.screenToBlock}）
     * 决定 —— 那里对 ROAD 段做地形采样、对 BRIDGE/TUNNEL 用玩家上次的高度。</p>
     *
     * @return 新点的下标；路线不存在时返回 -1
     */
    public int appendPoint(BlockPos pos) {
        PlayerRoute r = session.currentOrCreate();
        undoStack().record(r);
        PlayerRoute next = r.append(pos, session.currentKind());
        session.update(next);
        int idx = next.pointCount() - 1;
        session.setSelectedPointIndex(idx);
        return idx;
    }

    /** 在指定段中间插入控制点（线段中点点击）。 */
    public int insertPointAfter(int segmentIndex, BlockPos pos) {
        PlayerRoute r = session.currentOrCreate();
        undoStack().record(r);
        PlayerRoute next = r.insertAfter(segmentIndex, pos);
        session.update(next);
        session.setSelectedPointIndex(Math.min(segmentIndex + 1, next.pointCount() - 1));
        return session.selectedPointIndex();
    }

    /** 删除指定控制点。 */
    public boolean removePoint(int index) {
        PlayerRoute r = session.current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return false;
        }
        undoStack().record(r);
        PlayerRoute next = r.removePoint(index);
        session.update(next);
        if (session.selectedPointIndex() >= next.pointCount()) {
            session.setSelectedPointIndex(next.pointCount() - 1);
        }
        return true;
    }

    /** 拖动挪点（只改 XZ，保持 Y）。 */
    public void movePointXZ(int index, int x, int z) {
        PlayerRoute r = session.current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return;
        }
        session.update(r.replacePoint(index, r.point(index).withXZ(x, z)));
    }

    /** 改高度（滚轮 / 输入框）。 */
    public void setPointY(int index, int y) {
        PlayerRoute r = session.current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return;
        }
        session.update(r.replacePoint(index, r.point(index).withY(y)));
    }

    /** 改某一段的类型。 */
    public void setSegmentKind(int segmentIndex, RouteKind kind) {
        PlayerRoute r = session.current();
        if (r == null) {
            return;
        }
        undoStack().record(r);
        session.update(r.withSegmentKind(segmentIndex, kind));
    }

    /** 改当前选中点所属段的类型。 */
    public void setSelectedSegmentKind(RouteKind kind) {
        if (session.selectedPointIndex() >= 0) {
            setSegmentKind(session.selectedPointIndex(), kind);
        }
    }

    // ─────────────────── 确认施工（地图界面的按钮）───────────────────

    /**
     * 玩家点「施工」：把当前路线提交给 RoadWeaver。
     *
     * @return 提交结果（新增的分段数；-1 = 环境不支持；0 = 没有可施工分段）
     */
    public int submitForBuild() {
        int result = session.submitForBuild(net.minecraft.client.Minecraft.getInstance());
        this.lastSubmitResult = result;
        this.lastSubmitAtMs = System.currentTimeMillis();
        return result;
    }

    /** 上次施工提交的结果（6 秒后过期，避免一直挂着提示）。 */
    public int lastSubmitResult() {
        if (System.currentTimeMillis() - lastSubmitAtMs > 6000L) {
            return Integer.MIN_VALUE;
        }
        return lastSubmitResult;
    }

    // ───────────────────────── 撤销 ─────────────────────────

    public boolean canUndo() {
        return undoStack().canUndo();
    }

    public boolean canRedo() {
        return undoStack().canRedo();
    }

    public void undo() {
        PlayerRoute r = session.current();
        if (r == null) {
            return;
        }
        // ★ 审计 R3-P2-9：撤销必须检查状态。
        //
        // 对 READY（已提交施工）路线撤销，会让路线点回退，但已入队的连接
        // 仍按**旧路径**在生成 ⇒ 路线与施工结果永久不一致，且没有任何
        // 机制会重新提交。所以已提交的路线不允许撤销/编辑，
        // 想改必须先「重新编辑」（reopenForEdit → 退回 DRAFT）。
        if (!r.isEditable()) {
            return;
        }
        PlayerRoute prev = undoStack().undo(r);
        if (prev != null) {
            session.update(prev);
            if (session.selectedPointIndex() >= prev.pointCount()) {
                session.setSelectedPointIndex(prev.pointCount() - 1);
            }
        }
    }

    public void redo() {
        PlayerRoute r = session.current();
        if (r == null) {
            return;
        }
        // ★ 审计 R3-P2-9：同上，已提交的路线不允许重做
        if (!r.isEditable()) {
            return;
        }
        PlayerRoute next = undoStack().redo(r);
        if (next != null) {
            session.update(next);
        }
    }

    /** 当前路线是否允许编辑（DRAFT）。已提交施工的路线需先「重新编辑」（★ R3-P2-9）。 */
    public boolean isEditable() {
        PlayerRoute r = session.current();
        return r == null || r.isEditable();
    }

    /**
     * 把当前路线退回 DRAFT 以便继续编辑（★ R3-P2-8 / R3-P1-2）。
     *
     * @return 是否确实改了状态
     */
    public boolean reopenForEdit() {
        if (session.reopenForEdit()) {
            undoStack().clear();
            return true;
        }
        return false;
    }

    /** 新建一条路线并切过去（★ R3-P2-8）。 */
    public void newRoute() {
        session.createNew();
        undoStack().clear();
        session.setSelectedPointIndex(-1);
        this.dragMode = DragMode.NONE;
        this.heightInputOpen = false;
    }

    /** 删除当前路线（★ R3-P2-8）。 */
    public boolean deleteCurrent() {
        PlayerRoute r = session.current();
        if (r == null) {
            return false;
        }
        boolean ok = session.deleteRoute(net.minecraft.client.Minecraft.getInstance(), r.id());
        if (ok) {
            undoStack().clear();
            session.setSelectedPointIndex(-1);
            this.dragMode = DragMode.NONE;
            this.heightInputOpen = false;
        }
        return ok;
    }

    public int undoDepth() {
        return undoStack().undoDepth();
    }

    // ───────────────────── 拖动（含命中检测）─────────────────────

    /**
     * 按下鼠标：先做命中检测，决定这次拖动是「挪点」还是「平移地图」。
     *
     * @param hitPointIndex 调用方已经算好的命中点下标（-1 表示没命中）
     */
    public void beginDrag(int hitPointIndex, double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        // ★ 审计 P2-10：无条件先结束上一次连续编辑。
        //
        // 若上次拖动没收到 mouseReleased（鼠标在界面外松开、
        // 或编辑模式在拖动中途被关闭），continuousEditRecorded 会卡在 true，
        // 于是下一次拖动 beginContinuousEdit() 直接 return、不记快照
        // → **那次拖动完全无法撤销**。
        endContinuousEdit();
        if (hitPointIndex >= 0) {
            this.dragMode = DragMode.POINT;
            this.dragPointIndex = hitPointIndex;
            session.setSelectedPointIndex(hitPointIndex);
            // ★ 审计 P2-9：这里**不再**立即记录快照。
            // 单纯点击（没有真实位移）不该污染撤销栈 ——
            // 否则玩家按 Ctrl+Z 看起来「没反应」（其实退掉的是一个空操作）。
            // 快照改由 recordIfMoved() 在首次产生真实位移时惰性记录。
        } else {
            this.dragMode = DragMode.PAN;
            this.dragPointIndex = -1;
        }
    }

    /**
     * 拖动确实产生位移时调用：惰性记录本次手势的撤销点（只记一次）。
     *
     * @see #beginDrag 里的 P2-9 说明
     */
    public void recordIfMoved() {
        beginContinuousEdit();
    }

    /** 取鼠标位移并更新基准点。 */
    public double consumeDragDeltaX(double mouseX) {
        double d = mouseX - lastMouseX;
        lastMouseX = mouseX;
        return d;
    }

    public double consumeDragDeltaY(double mouseY) {
        double d = mouseY - lastMouseY;
        lastMouseY = mouseY;
        return d;
    }

    public void endDrag() {
        this.dragMode = DragMode.NONE;
        this.dragPointIndex = -1;
        endContinuousEdit();
    }

    /**
     * 取消拖动状态但**不移除选中**（放点后调用）。
     *
     * <p>与 {@link #endDrag} 的区别：endDrag 是拖动手势正常结束；
     * 这个是「刚放完点，别让随后的 mouseDragged 误判成拖动」。
     * 两者都不动选中项。</p>
     */
    public void cancelDrag() {
        this.dragMode = DragMode.NONE;
        this.dragPointIndex = -1;
        endContinuousEdit();
    }

    // ─────────────────── 连续操作的撤销支持 ───────────────────
    //
    // 拖动挪位与滚轮调高都是**连续操作**（一帧一次 / 一次滚动一格）。
    // 如果每次变更都记一次，一次拖动就能塞满 64 层撤销栈，
    // 玩家按 Ctrl+Z 只会一点点退回去，体验极差。
    //
    // 正确做法：在**操作开始时**记录一次快照，整个手势共用。
    // 由 beginContinuousEdit() / endContinuousEdit() 显式括起来。

    /** 是否已经为当前连续操作记录过快照。 */
    private boolean continuousEditRecorded;

    /** 上一次「自动分组」记录的时间戳（滚轮那种没有结束事件的操作用）。 */
    private long lastAutoGroupedAtMs;

    /**
     * 连续操作自动分组的时间窗：同一窗口内的变更共用一次撤销点。
     *
     * <p>{@value} 毫秒是手感折中 —— 滚轮连滚通常间隔 &lt;100ms，
     * 会归为一次；明显停顿后重新滚，则算新的一次，可以单独撤销。</p>
     */
    private static final long AUTO_GROUP_WINDOW_MS = 600L;

    /**
     * 开始一次连续编辑（拖动开始）。
     *
     * <p>拖动有明确的「松开」事件，所以用 {@link #endContinuousEdit}
     * 收尾，一次手势 = 一个撤销点。重复调用幂等。</p>
     */
    public void beginContinuousEdit() {
        if (continuousEditRecorded) {
            return;
        }
        PlayerRoute r = session.current();
        if (r != null) {
            undoStack().record(r);
            continuousEditRecorded = true;
        }
    }

    /** 结束连续编辑（拖动松开）。 */
    public void endContinuousEdit() {
        continuousEditRecorded = false;
    }

    /**
     * 为**没有结束事件**的连续操作记录撤销点（如滚轮调高）。
     *
     * <p>滚轮只会来一连串 scroll 回调，永远等不到「滚完」事件，
     * 用 {@link #beginContinuousEdit} 的话第一次之后就再也不记了。
     * 所以改用时间窗自动分组：窗口内共用一次撤销点，停顿后算新的一次。</p>
     */
    public void recordAutoGroupedUndo() {
        long now = System.currentTimeMillis();
        if (now - lastAutoGroupedAtMs < AUTO_GROUP_WINDOW_MS) {
            return;
        }
        PlayerRoute r = session.current();
        if (r != null) {
            undoStack().record(r);
            lastAutoGroupedAtMs = now;
        }
    }

    public DragMode dragMode() {
        return dragMode;
    }

    public boolean isDraggingPoint() {
        return dragMode == DragMode.POINT;
    }

    public boolean isPanning() {
        return dragMode == DragMode.PAN;
    }

    public int dragPointIndex() {
        return dragPointIndex;
    }

    public boolean isDragging() {
        return dragMode != DragMode.NONE;
    }

    // ───────────────────────── 选中 ─────────────────────────

    public int selectedPointIndex() {
        return session.selectedPointIndex();
    }

    public void selectPoint(int index) {
        session.setSelectedPointIndex(index);
    }

    public void clearSelection() {
        session.setSelectedPointIndex(-1);
    }

    public boolean hasSelection() {
        PlayerRoute r = session.current();
        return session.selectedPointIndex() >= 0 && r != null && session.selectedPointIndex() < r.pointCount();
    }

    /** 选中的点（没有则 null）。 */
    @Nullable
    public RoutePoint selectedPoint() {
        if (!hasSelection()) {
            return null;
        }
        return session.current().point(session.selectedPointIndex());
    }

    // ─────────────────── 高度输入框 ───────────────────

    public boolean isHeightInputOpen() {
        return heightInputOpen;
    }

    public String heightInputText() {
        return heightInputText;
    }

    /** 打开高度输入框，预填当前选中点的 Y。 */
    public void openHeightInput() {
        if (!hasSelection()) {
            return;
        }
        this.heightInputText = Integer.toString(selectedPoint().pos().getY());
        this.heightInputOpen = true;
    }

    public void closeHeightInput() {
        this.heightInputOpen = false;
    }

    public void setHeightInputText(String text) {
        this.heightInputText = text == null ? "" : text;
    }

    public void appendHeightInputChar(char c) {
        if (Character.isDigit(c) || (c == '-' && heightInputText.isEmpty())) {
            heightInputText += c;
        }
    }

    public void backspaceHeightInput() {
        if (!heightInputText.isEmpty()) {
            heightInputText = heightInputText.substring(0, heightInputText.length() - 1);
        }
    }

    /**
     * 提交高度输入。
     *
     * @return 是否成功应用
     */
    public boolean commitHeightInput() {
        if (!heightInputOpen || !hasSelection()) {
            heightInputOpen = false;
            return false;
        }
        try {
            int y = Integer.parseInt(heightInputText.trim());
            PlayerRoute r = session.current();
            undoStack().record(r);
            setPointY(session.selectedPointIndex(), y);
            heightInputOpen = false;
            return true;
        } catch (NumberFormatException e) {
            // 输入非法：保持输入框开着，让玩家改
            return false;
        }
    }

    // ─────────────────── 供渲染/命中用的查询 ───────────────────

    /**
     * 在屏幕坐标下找最近的控制点。
     *
     * @return 命中的下标；没命中返回 -1
     */
    public int hitTestPoint(List<Integer> screenXs, List<Integer> screenYs,
                            double mouseX, double mouseY) {
        PlayerRoute route = session.current();
        if (route == null || screenXs == null || screenYs == null) {
            return -1;
        }
        int n = Math.min(Math.min(screenXs.size(), screenYs.size()), route.pointCount());
        int best = -1;
        double bestD2 = HIT_RADIUS_PX * HIT_RADIUS_PX;
        for (int i = 0; i < n; i++) {
            double dx = screenXs.get(i) - mouseX;
            double dy = screenYs.get(i) - mouseY;
            double d2 = dx * dx + dy * dy;
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    /**
     * 在屏幕坐标下找最近的「段中点」——用于在其中插入控制点。
     *
     * @return 命中的段下标；没命中返回 -1
     */
    public int hitTestSegmentMidpoint(List<Integer> screenXs, List<Integer> screenYs,
                                      double mouseX, double mouseY) {
        PlayerRoute route = session.current();
        if (route == null || screenXs == null || screenYs == null || route.segmentCount() <= 0) {
            return -1;
        }
        int n = Math.min(Math.min(screenXs.size(), screenYs.size()), route.pointCount());
        int best = -1;
        double bestD2 = HIT_RADIUS_PX * HIT_RADIUS_PX;
        for (int i = 0; i + 1 < n; i++) {
            double mx = (screenXs.get(i) + screenXs.get(i + 1)) / 2.0;
            double my = (screenYs.get(i) + screenYs.get(i + 1)) / 2.0;
            double dx = mx - mouseX;
            double dy = my - mouseY;
            double d2 = dx * dx + dy * dy;
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    /** 点列表快照，供渲染遍历。 */
    public List<RoutePoint> pointsSnapshot() {
        PlayerRoute r = session.current();
        return r == null ? List.of() : new ArrayList<>(r.points());
    }
}
