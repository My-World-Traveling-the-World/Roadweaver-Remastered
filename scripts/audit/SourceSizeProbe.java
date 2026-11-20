import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.util.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

/** 使用JDK21真实词法器统计代码所在行，注释不计，字符串/文本块及孤立CR不漏算。 */
public final class SourceSizeProbe {
    private static int[] counts(String text) {
        int[] lines = new int[text.length() + 1];
        int line = 0;
        for (int i = 0; i < text.length(); i++) {
            lines[i] = line;
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' && (i == 0 || text.charAt(i - 1) != '\r')) line++;
        }
        lines[text.length()] = line;
        boolean ended = !text.isEmpty() && (text.endsWith("\n") || text.endsWith("\r"));
        int physical = text.isEmpty() ? 0 : line + (ended ? 0 : 1);
        var scanner = ScannerFactory.instance(new Context()).newScanner(text, false);
        var code = new BitSet();
        scanner.nextToken();
        while (scanner.token().kind != TokenKind.EOF) {
            var token = scanner.token();
            code.set(lines[token.pos], lines[Math.max(token.pos, token.endPos - 1)] + 1);
            scanner.nextToken();
        }
        return new int[]{physical, code.cardinality()};
    }

    private static void check(String fixture, int physical, int code) {
        if (!java.util.Arrays.equals(new int[]{physical, code}, counts(fixture)))
            throw new AssertionError("词法计数前置夹具失败");
    }

    public static void main(String[] args) throws Exception {
        check("", 0, 0);
        check("// 注释\rint x=1;\r/* 块 */\nint y=2;", 4, 2);
        check("class X {\n String s = \"\"\"\n // 字符串数据\n value\n \"\"\";\n}\n", 6, 6);
        check("class X { String url = \"http://x\"; /* 注释 */ }\r\n", 1, 1);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String file; (file = input.readLine()) != null;) {
                int[] count = counts(Files.readString(Path.of(file), StandardCharsets.UTF_8));
                // 路径由调用者按输入顺序关联，输出只含ASCII数字，避免Windows控制台转码。
                System.out.println(count[0] + "," + count[1]);
            }
        }
    }
}
