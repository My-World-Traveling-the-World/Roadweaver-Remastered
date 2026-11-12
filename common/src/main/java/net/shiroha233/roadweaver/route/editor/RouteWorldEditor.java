package net.shiroha233.roadweaver.route.editor;

import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.shiroha233.roadweaver.route.PlayerRoute;
import net.shiroha233.roadweaver.route.RouteKind;
import org.jetbrains.annotations.Nullable;

/**
 * 世界内（3D）手绘路线的编辑逻辑。
 *
 * <h2>交互（全部为可自定义的 KeyMapping）</h2>
 * <pre>
 *   准星对准控制点           → 选中（每帧射线检测，带角度容差）
 *   滚轮                     → 调所选点的高度 Y
 *   MOVE_FORWARD/BACK/LEFT/RIGHT → 前后左右挪所选点（XZ）
 *   CYCLE_KIND               → 循环切换该段类型（路/桥/隧道）
 *   DELETE_POINT             → 删除所选点
 *   INSERT_POINT             → 在该段中点插入新点
 *   UNDO                     → 撤销
 *   CONFIRM_BUILD            → 确认施工
 *   TOGGLE_PREVIEW           → 开关施工预览
 *   SPEED_BOOST              → 加速（挪位/调高 ×8）
 *   J（ClientInit 里注册）   → 进出编辑模式
 * </pre>
 * 具体键位见 {@link RouteKeys}，玩家可在「选项 → 按键 → 阡陌交通·手绘路线」里改。
 *
 * <h2>为什么用「角度 + 距离」而不是精确射线求交</h2>
 * 控制点只是 0.3 格大小的线框方块，用射线与包围盒精确求交对玩家的手感很苛刻
 * （必须瞄得非常准）。改用「视线方向与目标方向的夹角 + 距离加权」后，
 * 玩家只要大致看向那个点就能选中 —— 这是 3D 编辑器更常见的手感。
 *
 * <h2>历史：为什么曾经用 {@code InputConstants.isKeyDown} 轮询</h2>
 * 第一版为了省事直接轮询 GLFW 键状态，代价是玩家改不了键、且与 Tab/Enter
 * 等原版绑定撞车时原版毫无察觉。现已全部改为 {@link KeyMapping}，
 * 只有 {@link RouteKeys#SPEED_BOOST} 作为可绑定的加速键（也不再硬编码）。
 */
public final class RouteWorldEditor {

    /** 射线选中距离上限。 */
    private static final double SELECT_MAX_DISTANCE = 96.0;

    /** 选中容差角（视线与目标方向的夹角余弦阈值；越大越宽松）。 */
    private static final double SELECT_COS_THRESHOLD = 0.985;

    /** 方向键挪点的步长（格）。按住 Shift 加速。 */
    private static final float MOVE_STEP = 1.0F;

    /** 每 tick 最多执行几次按键动作（防止连按过快）。 */
    private static final int MAX_ACTIONS_PER_TICK = 4;

    // 选中状态**只存在会话里**（审计 P3-4）。本类不再自己维护一份，
    // 避免两处不同步。所有读取都走 sel()。

    // 第一版这里有 prevTab / prevDelete / ... 六个「上一 tick 按键状态」字段，
    // 用于自己做边沿检测（避免按住狂触发）。
    // 改用真正的 KeyMapping 后，原版已经在维护按下/重复状态，
    // consumeClick() 本身就保证「按一次只算一次」—— 这些字段全部删除。

    /**
     * 世界内编辑自己的撤销栈。
     *
     * <p>为什么不复用地图界面那个：那是 {@code RouteEditorState} 的实例字段，
     * 与 Screen 同生命周期，而且世界内编辑时 Screen 根本不存在。
     * 两个界面各自一份栈，语义更清晰（各自撤销自己的操作）。</p>
     */
    private static final net.shiroha233.roadweaver.route.RouteUndoStack undo =
            new net.shiroha233.roadweaver.route.RouteUndoStack();

    private RouteWorldEditor() {
    }

    /**
     * 当前选中的控制点下标（-1 表示没选）。
     *
     * <p>★ 审计 P3-4：**唯一数据源是会话**。本类不再自己维护一份 static 字段 ——
     * 原先两边都写，任何一处漏写就会不同步（HUD 显示 A、实际操作 B）。</p>
     */
    private static int sel() {
        return RouteEditorSession.get().selectedPointIndex();
    }

    /** 取当前选中的控制点下标（渲染器读它做高亮）。 */
    public static int selectedIndex() {
        return sel();
    }

    /**
     * 重置（进/出编辑模式、进世界时调用）。
     *
     * <p>★ 审计 P2-7：必须连撤销栈一起清。原来只清 {@code selected}，
     * 于是换世界/换存档后，**旧世界的撤销记录还能被 pop 出来应用到新世界的路线上**
     * —— 按一下 Ctrl+Z 就把这条路改成了别的世界的样子。</p>
     */
    public static void reset() {
        RouteEditorSession.get().setSelectedPointIndex(-1);
        undo.clear();
        lastScrollUndoAtMs = 0L;
    }

    /** 清空选中。 */
    public static void clearSelection() {
        RouteEditorSession.get().setSelectedPointIndex(-1);
    }

    // ───────────────────── 每帧：射线选中 ─────────────────────

    /**
     * 每帧调用（渲染时或 tick 时均可）：根据准星方向更新选中项。
     *
     * <p>每帧都跑，保证高亮跟随视线；计算量是「点数 × 一次点积」，可以忽略。</p>
     *
     * <h2>★ 审计 P3-4：选中状态只存一份</h2>
     * 原来这里同时维护两个字段：本类的 static {@code selected} 与
     * {@code RouteEditorSession.selectedPointIndex}。两处都写、任何一处漏写
     * 就会不同步（HUD 显示 A、实际操作 B）。
     * 现在**只写会话**，本类的 {@link #selectedIndex()} 直接读会话 ——
     * 单一数据源，不可能不一致。
     */
    public static void updateSelection(Minecraft mc) {
        RouteEditorSession session = RouteEditorSession.get();
        if (!session.isActive() || mc == null || mc.player == null) {
            session.setSelectedPointIndex(-1);
            return;
        }
        PlayerRoute route = session.current();
        if (route == null || route.isEmpty()) {
            session.setSelectedPointIndex(-1);
            return;
        }

        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getLookAngle();
        List<net.shiroha233.roadweaver.route.RoutePoint> pts = route.points();

        int best = -1;
        double bestScore = -1.0;
        for (int i = 0; i < pts.size(); i++) {
            BlockPos p = pts.get(i).pos();
            double dx = (p.getX() + 0.5) - eye.x;
            double dy = (p.getY() + 0.5) - eye.y;
            double dz = (p.getZ() + 0.5) - eye.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 0.01 || dist > SELECT_MAX_DISTANCE) {
                continue;
            }
            double cos = (dx * look.x + dy * look.y + dz * look.z) / dist;
            if (cos < SELECT_COS_THRESHOLD) {
                continue;
            }
            // 同角度时偏向近的点（更符合「我瞄的是这个」的直觉）
            double score = cos - dist / SELECT_MAX_DISTANCE * 0.01;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        // 唯一数据源：会话（地图界面与 HUD 都读这里）
        session.setSelectedPointIndex(best);
    }

    // ───────────────────── 每 tick：键盘 ─────────────────────

    /**
     * 每 tick 调用：处理挪位、换类型、删点、插点、撤销、确认施工、预览开关。
     *
     * <h2>★ 全部走真正的 KeyMapping</h2>
     * 原先这些键是硬编码轮询（{@code InputConstants.isKeyDown(window, GLFW_KEY_...)}），
     * 玩家在「选项 → 按键」里看不到也改不了，而且和原版撞车时原版毫无察觉
     * （Tab 会同时弹玩家列表、Enter 会同时开聊天）。
     *
     * <p>现在改成 {@link RouteKeys} 里的 {@link KeyMapping}：</p>
     * <ul>
     *   <li><b>移动键</b>用 {@code isDown()} —— 需要「按住连续挪」的手感</li>
     *   <li><b>动作键</b>用 {@code consumeClick()} —— 一次性触发，
     *       由原版帮我们做「按下一次只算一次」的去抖，不再需要自己维护
     *       prevTab / prevDelete 之类的边沿检测</li>
     * </ul>
     */
    public static void tick(Minecraft mc) {
        RouteEditorSession session = RouteEditorSession.get();
        if (!session.isActive() || mc == null || mc.player == null || mc.screen != null) {
            return;
        }
        PlayerRoute route = session.current();
        if (route == null) {
            // 没有当前路线也要 drain（R3-P1-1）：否则这段时间按下的动作键
            // 会攒到「重新有路线 + 按住 Ctrl」那一刻集中触发。
            drainActionKeys();
            return;
        }

        // ── 预览开关（P）：**不受 Ctrl 约束** ──
        //
        // 用户要求「按着 Ctrl 才能移动/施工/增删节点」—— 那些都是**编辑**。
        // 而预览开关是**看**（只是想看看路长什么样），把它也锁在 Ctrl 后面
        // 会让「我就想看一眼」变得别扭。所以单独放行。
        if (consume(RouteKeys.TOGGLE_PREVIEW)) {
            RoutePreviewRenderer.toggle();
        }

        // ★ 2026-09-25 用户要求：「hud 改成左侧竖排，按某键才显示」——
        //   同样属于**看**，所以与预览开关一样**不要求 Ctrl**。
        if (consume(RouteKeys.TOGGLE_HUD)) {
            RouteMapRenderer.toggleHud();
        }

        // ══════════ ★ 编辑修饰符闸（用户要求，与世界内/地图统一）══════════
        //
        // 不按 Ctrl 时**完全不动**路线点：
        //   · 方向键保持原版语义（其他 mod / 玩家自定义可能用它做别的事）
        //   · 滚轮不被吞（保留原版切换快捷栏）
        //   · Tab/Delete/Insert 不误触发编辑
        //
        // 按住 Ctrl 才进入编辑：方向键挪点、滚轮调高、动作键生效。
        //
        // 为什么必须加：编辑模式下「方向键挪点」会与玩家走路/其他 mod 抢键，
        // 而滚轮被吞掉后玩家连快捷栏都切不了 —— 世界内体验会明显被破坏。
        boolean editing = RouteKeys.isEditModifierHeld();
        if (!editing) {
            // ★ 审计 R3-P1-1：必须把一次性动作键**消费掉**，不能只是 return。
            //
            // 原实现只做「不进入编辑逻辑」，假设「不消费 = 交给原版」。
            // 但原版对这些键的处理是**另一条路**（KeyMapping.click 早已把
            // clickCount 累加完毕），consumeClick 的队列是编辑器私有的：
            //
            //   KeyMapping.click(key)      → clickCount += 1        （只累加）
            //   KeyMapping.consumeClick()  → clickCount -= 1        （读一次减一）
            //   KeyMapping.releaseAll()    → clickCount = 0 / isDown = false
            //                                ↑ 只在 Minecraft.setScreen 里调用
            //
            // 且 KeyboardHandler.keyPress 只在 screen == null 时才 click
            // （已用 javap 核对 1.21.1 字节码：偏移 598 `ifnonnull` / 899 `KeyMapping.click`）。
            // 于是「世界里不按 Ctrl 按 F/G/Delete/Insert/Z」会一直攒着，
            // **不打开任何界面就不会被清空**，之后一按住 Ctrl 挪点就集中触发。
            //
            // 最狠的是 CONFIRM_BUILD 默认键 F 与原版 key.swapOffhand 同键
            // （javap Options: `key.swapOffhand` 默认键码 70 = F）——
            // 日常换个副手就埋雷：之后按住 Ctrl 敲一下方向键，路线当场被提交施工，
            // 并连锁 R3-P1-2 让整条路线永久失联（不可见 / 不可编辑 / 不可删）。
            //
            // 所以这里把全部一次性动作键 drain 掉。连续键（方向键 / 滚轮）不处理 ——
            // 它们走 isDown() / 事件，没有队列。
            drainActionKeys();
            // 松开 Ctrl 时把连续编辑手势收尾（撤销分组不跨手势）
            endContinuousEditIfNeeded();
            return;
        }

        // 加速键：现在也是可绑定的 KeyMapping（RouteKeys.SPEED_BOOST），
        // 统一入口避免三处行为不一致。
        boolean shift = RouteKeys.isBoostHeld();

        // ── 挪点（按住连续移动）──
        if (sel() >= 0 && sel() < route.pointCount()) {
            float step = MOVE_STEP * (shift ? RouteKeys.BOOST_MULTIPLIER : 1.0F);
            // 方向相对**玩家朝向**前后左右，符合「我往哪看就往哪挪」的直觉
            float yaw = (float) Math.toRadians(mc.player.getYRot());
            float fx = -((float) Math.sin(yaw));
            float fz = (float) Math.cos(yaw);
            float rx = -fz;
            float rz = fx;

            float mx = 0, mz = 0;
            if (isDown(RouteKeys.MOVE_FORWARD)) { mx += fx; mz += fz; }
            if (isDown(RouteKeys.MOVE_BACK)) { mx -= fx; mz -= fz; }
            if (isDown(RouteKeys.MOVE_LEFT)) { mx += rx; mz += rz; }
            if (isDown(RouteKeys.MOVE_RIGHT)) { mx -= rx; mz -= rz; }

            if (mx != 0 || mz != 0) {
                BlockPos cur = route.point(sel()).pos();
                int dx = Math.round(mx * step);
                int dz = Math.round(mz * step);
                if (dx == 0 && dz == 0) {
                    // 小步长时保证至少动 1 格，否则玩家感觉「按了没反应」
                    dx = mx > 0 ? 1 : (mx < 0 ? -1 : 0);
                    dz = mz > 0 ? 1 : (mz < 0 ? -1 : 0);
                }
                session.movePointXZUndoable(sel(), cur.getX() + dx, cur.getZ() + dz, undo);
            }
        }

        // ── 换类型：循环 路 → 桥 → 隧道 ──
        if (consume(RouteKeys.CYCLE_KIND) && sel() >= 0 && sel() < route.pointCount()) {
            RouteKind cur = route.point(sel()).kind();
            undo.record(route);
            session.setSegmentKind(sel(), cur.next());
        }

        // ── 删除所选点 ──
        if (consume(RouteKeys.DELETE_POINT) && sel() >= 0) {
            undo.record(route);
            session.removePoint(sel());
            if (sel() >= (session.current() == null ? 0 : session.current().pointCount())) {
                session.setSelectedPointIndex((session.current() == null ? 0 : session.current().pointCount()) - 1);
            }
        }

        // ── 在当前点与下一点之间插入中点 ──
        if (consume(RouteKeys.INSERT_POINT) && sel() >= 0 && sel() + 1 < route.pointCount()) {
            BlockPos a = route.point(sel()).pos();
            BlockPos b = route.point(sel() + 1).pos();
            BlockPos mid = new BlockPos((a.getX() + b.getX()) / 2,
                    (a.getY() + b.getY()) / 2,
                    (a.getZ() + b.getZ()) / 2);
            undo.record(route);
            session.insertPointAfter(sel(), mid);
            session.setSelectedPointIndex(sel() + 1);
        }

        // ── 撤销世界内的编辑 ──
        if (consume(RouteKeys.UNDO)) {
            PlayerRoute prev = undo.undo(session.current());
            if (prev != null) {
                session.update(prev);
                if (sel() >= prev.pointCount()) {
                    session.setSelectedPointIndex(prev.pointCount() - 1);
                }
            }
        }

        // ── 施工：**3D 世界内不提供**（用户 2026-09-25 明确）──
        //
        // 用户原话：「3d世界里只允许进行编辑。施工由 2D 地图那个控制。」
        //
        // 所以这里**故意不响应 CONFIRM_BUILD**，但仍要把它 consume 掉 ——
        // 否则按键会一直攒在 KeyMapping.clickCount 里，等玩家回到地图界面
        // 时集中触发误提交（这正是审计 R3-P1-1 修过的那类问题）。
        consume(RouteKeys.CONFIRM_BUILD);

        // ── ★ UX-11：中止预加载（必须 Ctrl + 键，用户 2026-09-25 要求）──
        //
        // 放在**编辑修饰符闸之内**（本段代码已在 Ctrl 闸之后执行），
        // 所以自动满足「必须按 Ctrl 才行」；不按 Ctrl 时这里根本不会跑到。
        if (consume(RouteKeys.ABORT_PRELOAD)) {
            var srv = session.serverLevelOrNull();
            if (srv != null && route.id() != null) {
                net.shiroha233.roadweaver.route.RoutePreloadStatus.requestStop(srv, route.id());
            }
        }
    }

    /**
     * 丢弃所有累积的「一次性动作键」点击。
     *
     * <h2>为什么必须存在（审计 R3-P1-1）</h2>
     * 见 {@link #tick(Minecraft)} 里 Ctrl 闸处的详细字节码说明。
     * 简言之：{@code KeyMapping} 的 {@code clickCount} 只在
     * {@code Minecraft.setScreen} 时被 {@code releaseAll()} 清空，
     * 不消费就会一直攒着，之后集中触发 —— 包括误提交施工。
     *
     * <p>只 drain **动作键**（一次按下 = 一次动作），不碰方向键与滚轮：
     * 前者走 {@code isDown()} 连续查询，后者是事件驱动，都没有队列。</p>
     *
     * <p><b>public</b>：{@code ClientInit} 在**未进入编辑模式**时也要调它 ——
     * 否则「没开编辑模式时按下的 F（换副手）」会攒到第一次进编辑模式
     * 再按住 Ctrl 时爆发（审计里第 5 种触发场景）。</p>
     */
    public static void drainActionKeys() {
        // 与 tick() 里 consume 的是同一组键，保持一致：
        //   CYCLE_KIND / DELETE_POINT / INSERT_POINT / UNDO / CONFIRM_BUILD / TOGGLE_PREVIEW
        // 只调 consumeClick() 丢弃，不执行任何动作。
        // ★ 3D 世界不再执行 CONFIRM_BUILD，但**仍要 drain** —— 见 tick() 里的说明。
        consume(RouteKeys.CYCLE_KIND);
        consume(RouteKeys.DELETE_POINT);
        consume(RouteKeys.INSERT_POINT);
        consume(RouteKeys.UNDO);
        consume(RouteKeys.CONFIRM_BUILD);
        consume(RouteKeys.TOGGLE_PREVIEW);
        // ★ 2026-09-25：HUD 开关也要 drain（否则攒到进编辑模式时集中触发）
        consume(RouteKeys.TOGGLE_HUD);
        // ★ UX-11：中止预加载键同样要 drain（理由同上）
        consume(RouteKeys.ABORT_PRELOAD);
    }

    /**
     * 按键是否按住（用于需要连续响应的移动键）。
     *
     * <p>KeyMapping 可能尚未注册（初始化顺序问题），null 时返回 false
     * 而不是 NPE —— 按键功能失效总比崩游戏好。</p>
     */
    private static boolean isDown(@Nullable KeyMapping mapping) {
        return mapping != null && mapping.isDown();
    }

    /**
     * 消费一次点击（用于一次性动作键）。
     *
     * <p>用 {@code consumeClick()} 而不是自己维护 prevXxx 边沿检测：
     * 原版已经在维护按下/重复状态，自己再来一套只会引入不同步的 bug。
     * 另一个好处是玩家把键改成鼠标侧键等非键盘输入时也能正常工作。</p>
     */
    private static boolean consume(@Nullable KeyMapping mapping) {
        return mapping != null && mapping.consumeClick();
    }

    // ─────────────────── 施工提交反馈 ───────────────────

    private static int lastSubmitResult = Integer.MIN_VALUE;
    private static long lastSubmitAtMs;

    /** 上一次提交的结果（供 HUD 显示，-1 表示环境不支持，显示 6 秒后过期）。 */
    public static int lastSubmitResult() {
        if (System.currentTimeMillis() - lastSubmitAtMs > 6000L) {
            return Integer.MIN_VALUE;
        }
        return lastSubmitResult;
    }

    // ───────────────────── 滚轮：调高度 ─────────────────────

    /**
     * 滚轮事件（由 mixin 转发）。
     *
     * @return true 表示已消费（应取消原版滚轮行为，如切换物品栏）
     */
    public static boolean onScroll(double delta) {
        RouteEditorSession session = RouteEditorSession.get();
        if (!session.isActive()) {
            return false;
        }
        // ★ 没有 Ctrl → 不消费，滚轮仍归原版（切换快捷栏）
        // 用户要求世界内也要 Ctrl 才能编辑，与地图界面统一。
        if (!RouteKeys.isEditModifierHeld()) {
            return false;
        }
        PlayerRoute route = session.current();
        if (route == null || sel() < 0 || sel() >= route.pointCount()) {
            return false;
        }
        // 加速键统一走 RouteKeys.isBoostHeld()。
        // 原来这里用的是 mc.options.keyShift（跟随原版潜行），而挪位那处
        // 用的是硬编码 GLFW 左右 Shift —— 两处行为不一致：玩家把「潜行」
        // 改到别的键后，调高与挪位的加速键就不再是同一个了。
        boolean boost = RouteKeys.isBoostHeld();
        int step = (delta > 0 ? 1 : -1) * (boost ? RouteKeys.BOOST_MULTIPLIER : 1);
        int y = route.point(sel()).pos().getY();
        // 滚轮没有结束事件，用时间窗分组，避免连滚塞满撤销栈
        long now = System.currentTimeMillis();
        if (now - lastScrollUndoAtMs >= 600L) {
            undo.record(route);
            lastScrollUndoAtMs = now;
        }
        session.setPointY(sel(), y + step);
        return true;
    }

    private static long lastScrollUndoAtMs;

    /**
     * 松开 Ctrl 时收尾连续编辑手势。
     *
     * <p>撤销栈按「一次手势 = 一个撤销点」分组（时间窗见
     * {@code RouteEditorSession.movePointXZUndoable}）。松开 Ctrl 意味着
     * 这次编辑结束，下一次按 Ctrl 应算**新手势** —— 否则两次不同的调整
     * 会被合并成一次撤销，玩家按一次 Z 会连上一次的改动一起退掉。</p>
     */
    private static void endContinuousEditIfNeeded() {
        lastScrollUndoAtMs = 0L;
        // 同时结束会话侧的挪位时间窗
        RouteEditorSession.get().endMoveUndoWindow();
    }

    /** 当前选中的点（供 HUD 显示坐标/高度）。 */
    @Nullable
    public static BlockPos selectedPos() {
        RouteEditorSession session = RouteEditorSession.get();
        PlayerRoute route = session.current();
        if (route == null || sel() < 0 || sel() >= route.pointCount()) {
            return null;
        }
        return route.point(sel()).pos();
    }
}
