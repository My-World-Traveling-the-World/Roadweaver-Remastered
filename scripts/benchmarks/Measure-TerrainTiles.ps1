param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(32, 4096)][int]$HeapMiB = 512,
    [switch]$HugeOnly
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$JavaHome) { throw 'Set JAVA_HOME to JDK 21.' }
$gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$slf4j = Get-ChildItem -LiteralPath (Join-Path $gradleCache 'caches/modules-2/files-2.1/org.slf4j/slf4j-api') -Filter '*.jar' -Recurse |
    Where-Object Name -Like 'slf4j-api-2.*.jar' | Select-Object -First 1 -ExpandProperty FullName
$fastutil = Get-ChildItem -LiteralPath (Join-Path $gradleCache 'caches/modules-2/files-2.1/it.unimi.dsi/fastutil') -Filter '*.jar' -Recurse |
    Select-Object -First 1 -ExpandProperty FullName
if (!$slf4j -or !$fastutil) { throw 'Cached SLF4J 2 and fastutil are required.' }
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('roadweaver-terrain-' + [Guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($scratch)
$source = Join-Path $repo 'common/src/main/java/net/shiroha233/roadweaver'
try {
    # 显式编译真实适配器与求解器；只有世界读入用固定三通道平地替代，没有 MC 桩类型。
    $metric = @('ArrivalTimeField', 'FastMarchingSolver', 'FinslerMetric', 'ObstacleField', 'TerrainField',
        'GridTerrainField', 'GridLayout', 'GridFlags', 'FmmTargets', 'FmmPages', 'FmmValues', 'FmmCostCache') |
        ForEach-Object { Join-Path $source "metric/$_.java" }
    $sampling = @('AccurateHeightGrid', 'AccurateHeightGridRequest') |
        ForEach-Object { Join-Path $source "pathfinding/sampling/$_.java" }
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp "$slf4j;$fastutil" -d $scratch @metric @sampling `
        (Join-Path $source 'bridge/TiledTerrainField.java') (Join-Path $PSScriptRoot 'TiledTerrainProbe.java')
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    $probeArgs = @(if ($HugeOnly) { 'huge-only' })
    & (Join-Path $JavaHome 'bin/java.exe') "-Xms$($HeapMiB)m" "-Xmx$($HeapMiB)m" -cp "$scratch;$slf4j;$fastutil" `
        net.shiroha233.roadweaver.metric.TiledTerrainProbe @probeArgs
    if ($LASTEXITCODE -ne 0) { throw "terrain probe failed: $LASTEXITCODE" }
} finally {
    # 只清本次固定临时目录的文件与已知空包目录，避免递归删除任何计算路径。
    foreach ($directory in @('net/shiroha233/roadweaver/pathfinding/sampling',
            'net/shiroha233/roadweaver/pathfinding', 'net/shiroha233/roadweaver/bridge',
            'net/shiroha233/roadweaver/metric', 'net/shiroha233/roadweaver', 'net/shiroha233', 'net', '')) {
        $path = if ($directory) { Join-Path $scratch $directory } else { $scratch }
        if (Test-Path -LiteralPath $path) {
            foreach ($file in Get-ChildItem -LiteralPath $path -File) { Remove-Item -LiteralPath $file.FullName }
            [System.IO.Directory]::Delete($path)
        }
    }
}
