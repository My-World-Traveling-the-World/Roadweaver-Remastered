/* 文件职责：用编译器解析冻结站循环和生产站动作，证明搬迁未改写世界操作顺序。 */
package net.shiroha233.roadweaver.architecture;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionStationSequenceTest {
    @Test void synchronousCursorRetainsFrozenWorldOperationBody() throws Exception {
        String old=Files.readString(Path.of("src/test/resources/construction/station-loop-3e2af6a.java.txt"));
        String production=Files.readString(Path.of("src/main/java/net/shiroha233/roadweaver/pathlogic/PathFeature.java"));
        String[] before={null},after={null};
        scan(old,new TreeScanner<Void,Void>() {
            @Override public Void visitForLoop(ForLoopTree tree,Void unused){
                if(tree.getCondition().toString().contains("localIndex < slice.segmentCount()"))before[0]=tree.getStatement().toString();
                return super.visitForLoop(tree,unused);
            }
        });
        scan(production,new TreeScanner<Void,Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree tree,Void unused){
                if(tree.getMethodSelect().toString().equals("state.cursor().runAll"))after[0]=((LambdaExpressionTree)tree.getArguments().getFirst()).getBody().toString();
                return super.visitMethodInvocation(tree,unused);
            }
        });
        assertNotNull(before[0]);assertNotNull(after[0]);
        String expected=before[0].replace("int i = slice.segmentIndexAt(localIndex);","").replace("continue;","return;")
                .replace("completed[0] = false;","state.fail();").replace("constructionRefused[i] = true;","state.refuse(i);")
                .replace("!(constructionRefused != null && i < constructionRefused.length && constructionRefused[i])","!state.refused(i)");
        assertEquals(compact(expected),compact(after[0]),"仅允许循环控制和状态载体转换，世界写/随机/拒绝/装饰顺序必须相同");
    }
    private static String compact(String value){return value.replaceAll("\\s+","");}
    private static void scan(String source,TreeScanner<Void,Void> visitor) throws Exception {
        var compiler=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var file=new SimpleJavaFileObject(java.net.URI.create("string:///Fixture.java"),JavaFileObject.Kind.SOURCE){
            @Override public CharSequence getCharContent(boolean ignore){return source;}};
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,List.of(file));
            for(var tree:task.parse())visitor.scan(tree,null);
        }
    }
}

