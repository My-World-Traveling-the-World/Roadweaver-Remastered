param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Baseline = '89666f6'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$JavaHome) { throw 'Set JAVA_HOME to JDK 21.' }
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('roadweaver-ready-' + [Guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($scratch)
try {
    # 冻结状态集中后的全量扫描版本；同 JVM 对照，只有类型名发生替换。
    $old = (git -C $repo show "$($Baseline):common/src/main/java/net/shiroha233/roadweaver/planning/ReadinessRegionTable.java") -join "`n"
    if ($LASTEXITCODE -ne 0 -or $old -notmatch 'final class ReadinessRegionTable') { throw 'Baseline state table unavailable.' }
    $baselineFile = Join-Path $scratch 'BaselineReadinessRegionTable.java'
    [System.IO.File]::WriteAllText($baselineFile, ($old -creplace '\bReadinessRegionTable\b', 'BaselineReadinessRegionTable'),
        [System.Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $scratch $baselineFile `
        (Join-Path $repo 'common/src/main/java/net/shiroha233/roadweaver/planning/ReadinessRegionTable.java') `
        (Join-Path $PSScriptRoot 'ReadinessStateProbe.java')
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    & (Join-Path $JavaHome 'bin/java.exe') -Xms256m -Xmx256m -cp $scratch net.shiroha233.roadweaver.planning.ReadinessStateProbe
    if ($LASTEXITCODE -ne 0) { throw "readiness probe failed: $LASTEXITCODE" }
} finally {
    # 只移除本次固定目录中的文件和已知空包目录，避免递归删除计算出来的路径。
    foreach ($directory in @('net/shiroha233/roadweaver/planning', 'net/shiroha233/roadweaver', 'net/shiroha233', 'net', '')) {
        $path = if ($directory) { Join-Path $scratch $directory } else { $scratch }
        if (Test-Path -LiteralPath $path) {
            foreach ($file in Get-ChildItem -LiteralPath $path -File) { Remove-Item -LiteralPath $file.FullName }
            [System.IO.Directory]::Delete($path)
        }
    }
}
