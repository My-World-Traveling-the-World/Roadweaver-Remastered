param(
    [string]$Baseline = 'b62cd4e',
    [Parameter(Mandatory = $true)][string]$Gradle,
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME to JDK 21 or pass -Jdk.' }
$gradlePath = (Resolve-Path -LiteralPath $Gradle).Path
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('roadweaver-chunk-raster-' + [guid]::NewGuid())
$oldScratch = $env:ROADWEAVER_RASTER_SCRATCH
$oldJdk = $env:JAVA_HOME
$oldEncoding = [Console]::OutputEncoding
New-Item -ItemType Directory -Path $scratch | Out-Null

Push-Location -LiteralPath $repo
try {
    # 只从 Git 读取旧编译器，避免切换分支或改写当前工作区。
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new()
    $commit = git rev-parse --verify "${Baseline}^{commit}"
    if ($LASTEXITCODE -ne 0) { throw "Cannot find baseline commit: $Baseline" }
    $Package = 'common/src/main/java/net/shiroha233/roadweaver/worldgen/road'
    $ClassNames = @('RoadChunkPlanCompiler', 'RoadPhysicalReuseNormalizer')
    foreach ($ClassName in $ClassNames) {
        $src = (git show "${commit}:${Package}/${ClassName}.java") -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Cannot read ${ClassName} from baseline: $commit" }
        if ([regex]::Matches($src, "\b${ClassName}\b").Count -lt 2) {
            throw "Baseline compiler name anchors changed: $ClassName"
        }
        # 旧编译器和旧归属判定相互引用，必须成对改名，避免对照组混用当前实现。
        foreach ($Name in $ClassNames) { $src = $src.Replace($Name, "Baseline$Name") }
        [IO.File]::WriteAllText((Join-Path $scratch "Baseline$ClassName.java"), $src)
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'RasterProbe.java') -Destination $scratch
    $env:JAVA_HOME = $Jdk
    $env:ROADWEAVER_RASTER_SCRATCH = $scratch
    & $gradlePath :common:rasterProbe --init-script (Join-Path $PSScriptRoot 'chunk-raster.init.gradle') `
        --daemon --console=plain --dependency-verification=off
    if ($LASTEXITCODE -ne 0) { throw "Raster probe failed, exit code $LASTEXITCODE." }
} finally {
    [Console]::OutputEncoding = $oldEncoding
    $env:JAVA_HOME = $oldJdk
    $env:ROADWEAVER_RASTER_SCRATCH = $oldScratch
    Pop-Location
    # 只清理由本脚本新建、且确认在系统临时目录下的目录。
    $resolved = (Resolve-Path -LiteralPath $scratch).Path
    $tempRoot = (Resolve-Path -LiteralPath ([System.IO.Path]::GetTempPath())).Path.TrimEnd('\', '/')
    if ((Split-Path -Parent $resolved) -eq $tempRoot -and
        (Split-Path -Leaf $resolved) -match '^roadweaver-chunk-raster-[0-9a-f-]{36}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
