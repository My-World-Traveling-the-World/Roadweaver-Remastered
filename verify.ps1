# verify.ps1 - two-module verification gate.
#
# Why this exists: on 2026-11-13 we found that roadweaver-next's *fabric* module
# did NOT compile, while :common:roadJUnitRegressionSuite stayed green -- that
# task only compiles `common`. Two public static methods
# (RoadGenerationService.tick / RoadPlanningService.resetAll) had been moved to
# other classes during refactors and the callers were never updated; no
# test-only acceptance could ever catch it.
#
# So every commit must run both:
#     .\verify.ps1
#
# Exit code 0 = all green; non-zero = failure (stderr says which stage).
# Keep this file ASCII-only: Windows PowerShell 5 reads .ps1 as ANSI and would
# otherwise garble non-ASCII text into parse errors.

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:GRADLE_OPTS = '-Xmx2G -Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$gradle = 'C:\Users\18364\.gradle\wrapper\dists\gradle-8.8-bin\4u0rgm4geyrm56fyhoco0c9in\gradle-8.8\bin\gradle.bat'
if (-not (Test-Path $gradle)) {
    $found = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter 'gradle.bat' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'gradle-8\.8' } | Select-Object -First 1
    if (-not $found) { Write-Host "[verify] gradle.bat not found"; exit 2 }
    $gradle = $found.FullName
}

# Keep this file ASCII-only (Windows PowerShell 5 reads .ps1 as ANSI).
#
# -Dorg.gradle.internal.instrumentation.agent=false is a workaround, not a
# preference: on 2026-11-13 the Gradle daemon started but the client could not
# connect ("Could not connect to the Gradle daemon", daemon removed its own
# registry entry ~10s later) with no compile errors at all. Disabling the
# instrumentation agent made the same build succeed immediately. Keep the flag
# until the underlying agent issue is understood; removing it re-introduces a
# failure mode that looks exactly like "fabric does not compile".
$opts = @(
    '--no-daemon',
    '--console=plain',
    '--dependency-verification=off',
    '-Dorg.gradle.internal.instrumentation.agent=false'
)

Write-Host "[verify] stage 1/2: :fabric:compileJava (not covered by common tests)"
$out1 = & $gradle :fabric:compileJava @opts 2>&1
if (($out1 | Out-String) -notmatch 'BUILD SUCCESSFUL') {
    Write-Host "[verify] FAIL: fabric does not compile"
    ($out1 | Select-String -Pattern '\.java:\d+: |error:' | Select-Object -First 20) | ForEach-Object { "    " + $_.Line }
    exit 1
}
Write-Host "[verify] OK: fabric compiles"

Write-Host "[verify] stage 2/2: :common:roadJUnitRegressionSuite"
$out2 = & $gradle :common:roadJUnitRegressionSuite @opts 2>&1
$txt = ($out2 | Out-String)
if ($txt -match 'PASS tests=\d+') {
    Write-Host ("[verify] OK: " + $Matches[0])
} else {
    Write-Host "[verify] FAIL: regression suite"
    ($out2 | Select-String -Pattern 'AssertionFailedError|FAILED|error:' | Select-Object -First 20) | ForEach-Object { "    " + $_.Line }
    exit 1
}

Write-Host "[verify] ALL GREEN"
exit 0