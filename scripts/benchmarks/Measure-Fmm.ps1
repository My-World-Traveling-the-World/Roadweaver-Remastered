param(
    [string]$Baseline = '818b076',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Slf4jJar = '',
    [switch]$DirectionOnly
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
    $current = ($types + @('ObstacleField', 'TerrainField')) | ForEach-Object { Join-Path $repo "$metric/$_.java" }
    $topology = @('RoadNode', 'GeodesicMatrix', 'MinimumSpanningTree', 'TopologyPlan') |
        ForEach-Object { Join-Path $repo "$src/topology/$_.java" }
    $before = $types | ForEach-Object { Join-Path $scratch "Baseline$_.java" }
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $Slf4jJar -d $scratch @current @topology @before (Join-Path $PSScriptRoot 'FmmProbe.java')
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    # 方向夹具只读现有 FMM 的结果，无需重复做耗时统计。
    $probeArgs = @(if ($DirectionOnly) { 'direction' })
    & (Join-Path $JavaHome 'bin/java.exe') -Xms512m -Xmx512m -cp "$scratch;$Slf4jJar" net.shiroha233.roadweaver.metric.FmmProbe @probeArgs
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

