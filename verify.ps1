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
# Exit code 0 = all green; non-zero = failure.
# 本文件使用UTF-8 BOM，让Windows PowerShell 5.1正确读取中文注释和诊断。

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
# 2026-11-13: switched from --no-daemon to --daemon.
# --no-daemon makes Gradle fork a ONE-SHOT daemon for the build, and that one-shot
# handshake is exactly what kept failing on this machine: the daemon started, logged
# "Advertising the daemon address", listened on 127.0.0.1, and then died without the
# client ever connecting ("no longer found in the daemon registry").
# A regular (reused) daemon connects fine - verified 14:40 on this machine.
# Side benefit: successive verify runs reuse the warm daemon, so they are faster too.
$opts = @('--daemon', '--console=plain', '--dependency-verification=off')

# Known-flaky, NON-CODE failure: the Gradle daemon process starts and listens on
# 127.0.0.1, but the client cannot connect and the daemon drops its own registry
# entry about 10 seconds later. Gradle reports it as:
#     * What went wrong:
#     Could not connect to the Gradle daemon.
# It is intermittent (same command, same tree: sometimes passes) and it is NOT a
# compile error -- but a naive wrapper happily prints "fabric does not compile".
#
# Do NOT treat the instrumentation-agent flag as a fix: on 2026-11-13 a build did
# succeed once right after adding -Dorg.gradle.internal.instrumentation.agent=false,
# and that looked like causation; a later run failed again WITH the flag present
# (daemon context showed applyInstrumentationAgent=false). So the flag is not the
# answer and the real cause is still unknown.
#
# What we do instead: retry the invocation a few times when (and only when) the
# output carries that daemon-connect signature. A genuine compile error never
# matches it, so retries cannot mask a real failure.
$DaemonSignature = 'Could not connect to the Gradle daemon'
$MaxAttempts = 4
# 已验收的最低数量不能因选择器漏登记而倒退；增加测试无需随次更新此下界。
$MinimumTests = 1744

function Invoke-GradleWithRetry {
    param([string[]]$Arguments, [string]$Label)

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $out = & $gradle @Arguments @opts 2>&1
        # 原生退出码必须随本次输出一起返回，后续格式化或另一阶段不能覆盖它。
        $code = $LASTEXITCODE
        $txt = ($out | Out-String)
        if ($txt -notmatch [regex]::Escape($DaemonSignature)) {
            return [pscustomobject]@{ Output = $out; ExitCode = $code }
        }
        if ($attempt -lt $MaxAttempts) {
            Write-Host ("[verify] {0}: gradle daemon did not accept the connection (attempt {1}/{2}); retrying" -f $Label, $attempt, $MaxAttempts)
            # 只重试当前调用；不能按java进程名终止游戏或其他模组共用的工作进程。
            Start-Sleep -Seconds 5
        }
    }
    return [pscustomobject]@{ Output = $out; ExitCode = $code }
}

function Test-GradleSucceeded {
    param($Result, [switch]$RequireSuite)
    $text = ($Result.Output | Out-String)
    # 任务可先打印PASS再在其他收尾任务失败，日志与退出码必须同时确认成功。
    if ($Result.ExitCode -ne 0 -or $text -notmatch '(?m)^BUILD SUCCESSFUL(?: in .*)?\r?$' -or
            $text -match '(?m)^BUILD FAILED(?: in .*)?\r?$') { return $false }
    if (-not $RequireSuite) { return $true }
    $summaries = [regex]::Matches($text, '(?m)^[^\r\n]*RoadJUnitRegressionSuite: PASS[^\r\n]*\r?$')
    # 只接受唯一完整摘要；截断、跳过、终止、失败或重复的摘要都不能算一次完整回归。
    if ($summaries.Count -ne 1 -or $summaries[0].Value -notmatch
            'RoadJUnitRegressionSuite: PASS tests=(\d+) skipped=0 aborted=0 failed=0\r?$') { return $false }
    $tests = 0L
    return [long]::TryParse($Matches[1], [ref]$tests) -and $tests -ge $MinimumTests
}

Write-Host "[verify] stage 1/2: :fabric:compileJava (not covered by common tests)"
$result1 = Invoke-GradleWithRetry -Arguments @(':fabric:compileJava') -Label 'stage 1'
$out1 = $result1.Output
$txt1 = ($out1 | Out-String)
if (-not (Test-GradleSucceeded -Result $result1)) {
    if ($txt1 -match [regex]::Escape($DaemonSignature)) {
        # Say what actually happened; do not blame the source tree.
        Write-Host "[verify] FAIL: gradle daemon unreachable after $MaxAttempts attempts (NOT a compile error)"
        Write-Host "[verify]       the source tree is unverified; re-run, and if it persists check loopback/firewall"
    } else {
        Write-Host "[verify] FAIL: Fabric构建未成功，exit=$($result1.ExitCode)"
        ($out1 | Select-String -Pattern '\.java:\d+: |error:' | Select-Object -First 20) | ForEach-Object { "    " + $_.Line }
    }
    exit 1
}
Write-Host "[verify] OK: fabric compiles"

Write-Host "[verify] stage 2/2: :common:roadJUnitRegressionSuite"
$result2 = Invoke-GradleWithRetry -Arguments @(':common:roadJUnitRegressionSuite') -Label 'stage 2'
$out2 = $result2.Output
$txt = ($out2 | Out-String)
if (Test-GradleSucceeded -Result $result2 -RequireSuite) {
    # 成功时保留完整计数，便于复核没有跳过或提前终止。
    $summary = [regex]::Match($txt, 'RoadJUnitRegressionSuite: PASS[^\r\n]*').Value
    Write-Host ("[verify] OK: " + $summary)
} else {
    if ($txt -match [regex]::Escape($DaemonSignature)) {
        Write-Host "[verify] FAIL: gradle daemon unreachable after $MaxAttempts attempts (NOT a test failure)"
    } else {
        Write-Host "[verify] FAIL: 回归未完整通过，exit=$($result2.ExitCode)，要求tests>=$MinimumTests且skipped/aborted/failed均为0"
        ($out2 | Select-String -Pattern 'AssertionFailedError|FAILED|error:' | Select-Object -First 20) | ForEach-Object { "    " + $_.Line }
    }
    exit 1
}

Write-Host "[verify] ALL GREEN"
exit 0
