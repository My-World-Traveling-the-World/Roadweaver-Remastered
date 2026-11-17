/* 文件职责：预算生产接线不能只剩离线端口或注释。 */
package net.shiroha233.roadweaver.architecture;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionBudgetWiringTest {
    @Test void liveBackfillAndRouteUseBudgetAndAttemptedResultPorts() throws Exception {
        var backfill = methods("generation/LoadedChunkRoadBackfill.java");
        assertTrue(backfill.get("placeLoadedChunks").stream().anyMatch(body ->
            body.contains("new ConstructionTimeBudget(System::nanoTime)") && body.contains("verified, attempted")),
            "生产回填必须构造默认时间预算并回传真正尝试的集合");
        var route = methods("route/RouteChunkPreloader.java");
        assertTrue(route.get("placeBatch").stream().anyMatch(body -> body.contains(
            "LoadedChunkRoadBackfill.placePreloadedChunks(level, ready, attempted)")),
            "手绘生产端口不能丢弃预算等待状态");
        var deferred = methods("generation/DeferredInitialRoadConstructionService.java");
        assertTrue(deferred.get("tick").stream().anyMatch(body -> body.contains(
            "placeSelectedLoadedChunks(level, loadedForThisDrain, attempted)") && body.contains("!attempted.contains(key)")),
            "后台调度不能让未尝试区块消费失败次数");
    }
    private static Map<String,List<String>> methods(String relative) throws Exception {
        Path root=Path.of("src/main/java/net/shiroha233/roadweaver");
        assertTrue(Files.isRegularFile(root.resolve(relative)),"生产接线源文件必须存在");
        var compiler=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var result=new HashMap<String,List<String>>();
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,
                    manager.getJavaFileObjectsFromPaths(List.of(root.resolve(relative))));
            for(var unit:task.parse()) new TreeScanner<Void,Void>() {
                @Override public Void visitMethod(MethodTree tree,Void unused) {
                    if(tree.getBody()!=null) result.computeIfAbsent(tree.getName().toString(),key -> new ArrayList<>()).add(tree.getBody().toString());
                    return super.visitMethod(tree,unused);
                }
            }.scan(unit,null);
        }
        return result;
    }
}
