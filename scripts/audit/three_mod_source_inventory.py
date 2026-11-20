"""全量清点三仓已登记的Fabric生产Java，词法计数不把注释算代码，不冒充逐行功能审计。"""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(r"C:\Program Files\Java\jdk-21\bin")
EXPORTS = ["--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
           "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"]


def main():
    work = ROOT / ".analysis_tmp/source-size-probe"
    work.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(JAVA / "javac.exe"), *EXPORTS, "-encoding", "UTF-8", "-d", str(work),
                    str(ROOT / "scripts/audit/SourceSizeProbe.java")], check=True, capture_output=True)
    summary = {}
    for repo_name in ["streams-reflowing-src", "roadweaver-next", "plaincraft-great-wall"]:
        repo = ROOT.parent / repo_name
        tracked = subprocess.check_output(["git", "ls-files", "-z", "--", "*.java"], cwd=repo).decode("utf-8").split("\0")
        sources = sorted(p for p in tracked if "/src/main/java/" in "/" + p or "/src/client/java/" in "/" + p)
        if repo_name == "streams-reflowing-src":
            sources = [p for p in sources if not p.startswith(("src/main/java/dev/streamsreflowing/forge/",
                                                              "src/main/java/dev/streamsreflowing/neoforge/"))]
        assert sources, repo_name
        run = subprocess.run([str(JAVA / "java.exe"), *EXPORTS, "-cp", str(work), "SourceSizeProbe"],
                             input=("\n".join(str(repo / p) for p in sources) + "\n").encode("utf-8"),
                             check=True, capture_output=True)
        rows = [tuple(map(int, line.split(","))) for line in run.stdout.decode("ascii").splitlines()]
        assert len(rows) == len(sources)
        files = [dict(path=p, physical_lines=row[0], code_lines=row[1]) for p, row in zip(sources, rows)]
        tree = hashlib.sha256()
        for source in sources:
            tree.update(source.encode("utf-8") + b"\0" + (repo / source).read_bytes() + b"\0")
        summary[repo_name] = dict(
            head=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo).decode("ascii").strip(),
            source_tree_sha256=tree.hexdigest(), production_files=len(files),
            physical_lines=sum(f["physical_lines"] for f in files), code_lines=sum(f["code_lines"] for f in files),
            over_1000=sorted((f for f in files if f["code_lines"] > 1000), key=lambda f: -f["code_lines"]),
            largest=sorted(files, key=lambda f: -f["code_lines"])[:12])
        print(repo_name, len(files), "over1000", len(summary[repo_name]["over_1000"]))
    target = ROOT / ".analysis_tmp/three-mod-source-inventory.json"
    target.write_text(json.dumps(dict(scope="all tracked Fabric production Java; JDK21 lexer; includes client",
                                     repositories=summary), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
