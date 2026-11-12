package net.shiroha233.roadweaver.route;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;

/**
 * {@link RouteBuildProgress.Snapshot} 的纯逻辑自测。
 *
 * <h2>为什么测这个</h2>
 * 进度汇总是「登记总数 − 当前列表数 = 已结算」这种**差值算法**，
 * 边界情况（全失败 / 全冗余 / 混合 / 还没开始）容易算错，
 * 而算错的后果是玩家看到「施工中 0/0」或永远不显示「已完成」。
 *
 * <p>这里只测纯函数部分（{@code Snapshot} 的判定与文案），
 * 不碰 {@code ServerLevel} —— 那需要真实世界，属于真机验证范围。</p>
 */
public final class RouteBuildProgressTest {

    private RouteBuildProgressTest() {
    }

    private static int checks = 0;

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
        System.out.println("  ok  " + what);
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testEmpty();
        testAllCompleted();
        testInProgress();
        testAllFailed();
        testMixed();
        testRemovedCountsAsSettled();
        testRatioBounds();

        System.out.println("\n全部通过（" + checks + " 项）");
    }

    private static void testEmpty() {
        System.out.println("[1] 未提交");
        var s = RouteBuildProgress.Snapshot.EMPTY;
        require(s.total() == 0, "总数 0");
        require(!s.isSettled(), "不算已结算（没提交过）");
        require(!s.isDone(), "不算完成");
        require(s.describe().contains("尚未提交"), "文案正确：" + s.describe());
        require(s.ratio() == 0.0F, "进度 0");
    }

    private static void testAllCompleted() {
        System.out.println("[2] 全部建成");
        var s = new RouteBuildProgress.Snapshot(5, 0, 0, 5, 0, 0);
        require(s.isSettled(), "已结算");
        require(s.isDone(), "已完成");
        require(s.ratio() == 1.0F, "进度 100%");
        require(s.describe().contains("已完成 5"), "文案：" + s.describe());
    }

    private static void testInProgress() {
        System.out.println("[3] 施工中");
        var s = new RouteBuildProgress.Snapshot(10, 4, 2, 4, 0, 0);
        require(!s.isSettled(), "还没结算（有在排队/在建）");
        require(s.active() == 6, "在途 6（排队4+在建2）");
        require(s.describe().contains("施工中"), "文案：" + s.describe());
        // 进度按「已结算」算，所以是 (4+0+0)/10
        require(Math.abs(s.ratio() - 0.4F) < 1e-6, "进度 40%");
    }

    private static void testAllFailed() {
        System.out.println("[4] 全部失败");
        var s = new RouteBuildProgress.Snapshot(3, 0, 0, 0, 3, 0);
        require(s.isSettled(), "已结算");
        require(!s.isDone(), "不算完成（有失败）");
        require(s.describe().contains("全部失败"), "文案：" + s.describe());
        require(s.ratio() == 1.0F, "进度 100%（都试过了）");
    }

    private static void testMixed() {
        System.out.println("[5] 部分成功部分失败");
        var s = new RouteBuildProgress.Snapshot(6, 0, 0, 4, 2, 0);
        require(s.isSettled(), "已结算");
        require(!s.isDone(), "不算完成");
        require(s.describe().contains("完成 4"), "文案含完成数：" + s.describe());
        require(s.describe().contains("失败 2"), "文案含失败数：" + s.describe());
    }

    private static void testRemovedCountsAsSettled() {
        System.out.println("[6] 冗余移除算已结算（两端已被别的路连通）");
        // 登记 5 段，3 段被判冗余移除，2 段建成
        var s = new RouteBuildProgress.Snapshot(5, 0, 0, 2, 0, 3);
        require(s.isSettled(), "已结算");
        require(s.isDone(), "算完成（冗余不等于失败）");
        require(s.ratio() == 1.0F, "进度 100%");
        require(s.describe().contains("已完成 5"), "文案：" + s.describe());
    }

    private static void testRatioBounds() {
        System.out.println("[7] 进度不超过 100%");
        // 异常输入：completed 比 total 还大
        var s = new RouteBuildProgress.Snapshot(2, 0, 0, 5, 0, 0);
        require(s.ratio() <= 1.0F, "被夹在 1.0 以内（实得 " + s.ratio() + "）");
        require(s.ratio() >= 0.0F, "不为负");
    }
}
