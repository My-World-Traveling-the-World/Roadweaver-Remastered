param(
    [string]$Baseline = '818b076',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Slf4jJar = '',
    [switch]$DirectionOnly,
    [switch]$WorkingSetOnly,
    [switch]$PagingOnly,
    [ValidateRange(32, 4096)][int]$HeapMiB = 512,
    [string]$FastutilJar = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$src = 'common/src/main/java/net/shiroha233/roadweaver'
$metric = "$src/metric"
if (!$JavaHome) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
if (!$Slf4jJar) {
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $Slf4jJar = Get-ChildItem -LiteralPath (Join-Path $gradleCache 'caches/modules-2/files-2.1/org.slf4j/slf4j-api') -Filter '*.jar' -Recurse |
        Where-Object Name -Like 'slf4j-api-2.*.jar' | Select-Object -First 1 -ExpandProperty FullName
}
if (!$Slf4jJar -or !(Test-Path -LiteralPath $Slf4jJar -PathType Leaf)) { throw 'Pass a cached SLF4J 2 API JAR with -Slf4jJar.' }
# 当前稀疏拓扑使用 fastutil；独立探针必须编译真实依赖，不能靠旧清单漏掉生产类型。
if (!$FastutilJar) {
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $FastutilJar = Get-ChildItem -LiteralPath (Join-Path $gradleCache 'caches/modules-2/files-2.1/it.unimi.dsi/fastutil') -Filter '*.jar' -Recurse |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$FastutilJar -or !(Test-Path -LiteralPath $FastutilJar -PathType Leaf)) { throw 'Pass a cached fastutil JAR with -FastutilJar.' }
if (@($WorkingSetOnly, $DirectionOnly, $PagingOnly).Where({ $_ }).Count -gt 1) { throw 'Choose one probe mode.' }
$probeClasspath = "$Slf4jJar;$FastutilJar"
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('roadweaver-fmm-' + [Guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($scratch)
$types = @('ArrivalTimeField', 'FastMarchingSolver', 'FinslerMetric')
try {
    # 只改类型名，确保对照组保留基线的真实实现并共享同一个地形对象与 JVM。
    foreach ($type in $types) {
        $source = (git -C $repo show "$($Baseline):$metric/$type.java") -join "`n"
        if ($LASTEXITCODE -ne 0 -or $source -notmatch "\bclass $type\b") { throw "Baseline source not found: $type" }
        foreach ($name in $types) { $source = $source -creplace "\b$name\b", "Baseline$name" }
        [System.IO.File]::WriteAllText((Join-Path $scratch "Baseline$type.java"), $source, [System.Text.UTF8Encoding]::new($false))
    }
    $current = ($types + @('ObstacleField', 'TerrainField', 'GridTerrainField', 'GridLayout', 'GridFlags', 'FmmTargets', 'DenseGridWorkload', 'FmmPages', 'FmmValues', 'FmmCostCache')) | ForEach-Object { Join-Path $repo "$metric/$_.java" }
    $topology = @('RoadNode', 'GeodesicMatrix', 'MinimumSpanningTree', 'TopologyPlan', 'TopologyCandidates') |
        ForEach-Object { Join-Path $repo "$src/topology/$_.java" }
    $before = $types | ForEach-Object { Join-Path $scratch "Baseline$_.java" }
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $probeClasspath -d $scratch @current @topology @before (Join-Path $PSScriptRoot 'FmmProbe.java') (Join-Path $PSScriptRoot 'FmmPagingProbe.java')
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    # 方向夹具只读现有 FMM 的结果，无需重复做耗时统计。
    $probeArgs = @(if ($DirectionOnly) { 'direction' } elseif ($WorkingSetOnly) { 'working-set' })
    $probeClass = if ($PagingOnly) { 'FmmPagingProbe' } else { 'FmmProbe' }
    # 堆上限由调用显式指定，固定小堆只能证明当前夹具，不能外推整个游戏或采样缓存。
    & (Join-Path $JavaHome 'bin/java.exe') "-Xms$($HeapMiB)m" "-Xmx$($HeapMiB)m" -cp "$scratch;$probeClasspath" "net.shiroha233.roadweaver.metric.$probeClass" @probeArgs
    if ($LASTEXITCODE -ne 0) { throw "probe failed: $LASTEXITCODE" }
} finally {
    # 文件清单固定，逐个清理后只移除本次创建的空目录；不递归删除任意路径。
    foreach ($file in Get-ChildItem -LiteralPath $scratch -File) { Remove-Item -LiteralPath $file.FullName }
    foreach ($dir in @('net/shiroha233/roadweaver/topology', 'net/shiroha233/roadweaver/metric',
            'net/shiroha233/roadweaver', 'net/shiroha233', 'net')) {
        $p = Join-Path $scratch $dir
        if (Test-Path -LiteralPath $p) {
            foreach ($file in Get-ChildItem -LiteralPath $p -File) { Remove-Item -LiteralPath $file.FullName }
            [System.IO.Directory]::Delete($p)
        }
    }
    [System.IO.Directory]::Delete($scratch)
}

