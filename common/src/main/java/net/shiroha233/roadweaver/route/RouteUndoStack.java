/* 文件职责：手绘路线的**撤销栈**。 */
package net.shiroha233.roadweaver.route;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 手绘路线的**撤销栈**。
 *
 * <h2>为什么存整份快照而不是「反向操作」</h2>
 * {@link PlayerRoute} 是不可变的，每次编辑都产生一个新实例。
 * 所以撤销栈最自然的实现就是「存历史副本」—— 不需要为每种操作
 * （放点/删点/挪位/改高/改类型）各写一份逆操作，也就不会出现
 * 「逆操作和正操作对不上」这类难查的 bug。
 *
 * <p>代价是内存。但一条路线撑死几百个控制点，每个点是个小 record，
 * 栈深默认 {@value #DEFAULT_DEPTH} 层 —— 实测占用可以忽略。</p>
 *
 * <h2>语义</h2>
 * <pre>
 *   record(before)   在**修改前**调用，把当前状态压栈
 *   undo()           弹出上一个状态（并把「当前」压进 redo 栈）
 *   redo()           反向操作
 * </pre>
 */
public final class RouteUndoStack {

    /** 默认栈深。超过后丢弃最早的记录（FIFO）。 */
    public static final int DEFAULT_DEPTH = 64;

    private final Deque<PlayerRoute> undo = new ArrayDeque<>();
    private final Deque<PlayerRoute> redo = new ArrayDeque<>();
    private final int maxDepth;

    public RouteUndoStack() {
        this(DEFAULT_DEPTH);
    }

    public RouteUndoStack(int maxDepth) {
        this.maxDepth = Math.max(1, maxDepth);
    }

    /**
     * 记录「修改前」的状态。
     *
     * <p><b>必须在真正改动之前调用</b>，否则撤销的是改完之后的状态。</p>
     */
    public void record(PlayerRoute before) {
        if (before == null) {
            return;
        }
        undo.push(before);
        while (undo.size() > maxDepth) {
            undo.removeLast();      // 丢掉最早的
        }
        // 任何新操作都让 redo 失效（标准撤销语义）
        redo.clear();
    }

    /** 能否撤销。 */
    public boolean canUndo() {
        return !undo.isEmpty();
    }

    /** 能否重做。 */
    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /**
     * 撤销一步。
     *
     * @param current 当前状态（会被压入 redo 栈）
     * @return 撤销后的状态；没有可撤销内容时返回 {@code current}
     */
    public PlayerRoute undo(PlayerRoute current) {
        if (undo.isEmpty() || current == null) {
            return current;
        }
        redo.push(current);
        return undo.pop();
    }

    /**
     * 重做一步。
     *
     * @param current 当前状态（会被压回 undo 栈）
     * @return 重做后的状态；没有可重做内容时返回 {@code current}
     */
    public PlayerRoute redo(PlayerRoute current) {
        if (redo.isEmpty() || current == null) {
            return current;
        }
        undo.push(current);
        return redo.pop();
    }

    /** 清空（切换路线 / 退出编辑时调用）。 */
    public void clear() {
        undo.clear();
        redo.clear();
    }

    public int undoDepth() {
        return undo.size();
    }

    public int redoDepth() {
        return redo.size();
    }
}
