package net.shiroha233.roadweaver.pathlogic.core;

import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.meta.ConfigOption;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 失败道路使用的重绑键必须跟着实际菜单配置，否则局部波次会漏读旧走廊。 */
final class RoadRetryGridTest {
    @Test
    void roadRebindUsesLocalSettingFromRealMenuAndTaskSnapshot() {
        var config = new ModConfig();
        var control = ConfigOption.bind(config).stream()
                .filter(option -> option.id().equals("pathfindingCost.localRepairGridStep"))
                .findFirst().orElseThrow();
        for (int step : List.of(1, 4, 8)) {
            control.save(step);
            var task = RoadGenerationConfig.from(config);
            assertEquals(8, task.pathfinding().effectivePathGridStep());
            // 首轮或候补可以覆盖本次主步长，但失败时的缓存键仍应按局部档位保存。
            task.pathfinding().setPathGridStep(16);
            var road = new Road(null, null, null, task);
            assertEquals(step, road.localRetryStep());
        }
    }
}
