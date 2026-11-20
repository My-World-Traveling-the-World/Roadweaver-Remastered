# 文件职责：执行真实验证入口的阶段正文，模拟构建输出，确认失败绝不会打印ALL GREEN。
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$tokens = $null
$errors = $null
$tree = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $root 'verify.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw '验证入口无法解析' }
$helpers = @($tree.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Test-GradleSucceeded'
}, $true))
$floor = @($tree.EndBlock.Statements | Where-Object {
    $_ -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $_.Left.Extent.Text -eq '$MinimumTests'
})
if ($helpers.Count -ne 1 -or $floor.Count -ne 1) { throw '成功判据和最低计数必须各有一份' }
. ([scriptblock]::Create($floor[0].Extent.Text))
$statements = @($tree.EndBlock.Statements)
$start = @($statements | Where-Object {
    $_.Extent.Text.StartsWith('Write-Host "[verify] stage 1/2:')
})
if ($start.Count -ne 1) { throw '阶段入口必须唯一，不能漏测真实接线' }
$source = [IO.File]::ReadAllText((Join-Path $root 'verify.ps1'))
$gate = $source.Substring($start[0].Extent.StartOffset,
    $tree.EndBlock.Extent.EndOffset - $start[0].Extent.StartOffset)
$good = "RoadJUnitRegressionSuite: PASS tests=$MinimumTests skipped=0 aborted=0 failed=0"
$success = 'BUILD SUCCESSFUL in 1s'
function Result($Text, $Code = 0) { [pscustomobject]@{ Output = $Text; ExitCode = $Code } }
function Case($Name, $Stage1, $Stage2, $ExpectedCode, $Calls) {
    [pscustomobject]@{ Name = $Name; Stage1 = $Stage1; Stage2 = $Stage2;
        ExpectedCode = $ExpectedCode; Calls = $Calls }
}
$cases = @(
    (Case 'complete' (Result $success) (Result "$good`n$success") 0 2),
    (Case 'prefixed' (Result $success) (Result "[12:00:00] [main/INFO]: [STDOUT]: $good`r`n$success") 0 2),
    (Case 'skipped' (Result $success) (Result "$($good.Replace('skipped=0', 'skipped=1'))`n$success") 1 2),
    (Case 'aborted' (Result $success) (Result "$($good.Replace('aborted=0', 'aborted=1'))`n$success") 1 2),
    (Case 'failed' (Result $success) (Result "$($good.Replace('failed=0', 'failed=1'))`n$success") 1 2),
    (Case 'zero' (Result $success) (Result "RoadJUnitRegressionSuite: PASS tests=0 skipped=0 aborted=0 failed=0`n$success") 1 2),
    (Case 'below-floor' (Result $success) (Result "RoadJUnitRegressionSuite: PASS tests=$($MinimumTests-1) skipped=0 aborted=0 failed=0`n$success") 1 2),
    (Case 'overflow' (Result $success) (Result "RoadJUnitRegressionSuite: PASS tests=999999999999999999999 skipped=0 aborted=0 failed=0`n$success") 1 2),
    (Case 'truncated' (Result $success) (Result "RoadJUnitRegressionSuite: PASS tests=$MinimumTests`n$success") 1 2),
    (Case 'duplicate' (Result $success) (Result "$good`n$good`n$success") 1 2),
    (Case 'mixed-summaries' (Result $success) (Result "$good`n$($good.Replace('skipped=0', 'skipped=1'))`n$success") 1 2),
    (Case 'missing-summary' (Result $success) (Result $success) 1 2),
    (Case 'missing-build-result' (Result $success) (Result $good) 1 2),
    (Case 'failure-after-pass' (Result $success) (Result "$good`n$success`nBUILD FAILED in 1s") 1 2),
    (Case 'nonzero-suite' (Result $success) (Result "$good`n$success" 7) 1 2),
    (Case 'nonzero-compile' (Result $success 7) (Result "$good`n$success") 1 1),
    (Case 'compile-failed' (Result 'BUILD FAILED in 1s' 1) (Result "$good`n$success") 1 1),
    (Case 'quoted-success' (Result 'diagnostic: expected BUILD SUCCESSFUL') (Result "$good`n$success") 1 1)
)
# 子进程只执行原判据函数和原阶段正文；假调用不运行Gradle、不读取或终止宿主进程。
$directory = Join-Path $root ("build/verify-gate-" + [guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($directory)
$fixturePath = Join-Path $directory 'fixture.xml'
$runnerPath = Join-Path $directory 'runner.ps1'
$prefix = @'
$ErrorActionPreference = 'Stop'
$fixture = Import-Clixml -LiteralPath (Join-Path $PSScriptRoot 'fixture.xml')
$DaemonSignature = 'Could not connect to the Gradle daemon'
$MaxAttempts = 4
function Invoke-GradleWithRetry {
    param($Arguments, $Label)
    Write-Host "[VERIFY-GATE-CALL] $Label"
    if ($Label -eq 'stage 1') { return $fixture.Stage1 }
    return $fixture.Stage2
}
'@
try {
    $program = $prefix + "`n" + $floor[0].Extent.Text + "`n" + $helpers[0].Extent.Text + "`n" + $gate
    [IO.File]::WriteAllText($runnerPath, $program, [Text.UTF8Encoding]::new($true))
    foreach ($case in $cases) {
        $case | Export-Clixml -LiteralPath $fixturePath -Depth 5
        # 原生stderr也作为测试输出收集；随后核对明确的FAIL分支，异常退出不能冒充正常拒绝。
        $ErrorActionPreference = 'Continue'
        $output = & "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" `
            -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $runnerPath 2>&1
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        $text = $output | Out-String
        $calls = [regex]::Matches($text, '\[VERIFY-GATE-CALL\]').Count
        $green = $text.Contains('[verify] ALL GREEN')
        # 失败案例必须走入口正常拒绝分支；语法错误、异常退出或根本没走两阶段均不算守卫命中。
        if ($code -ne $case.ExpectedCode -or $calls -ne $case.Calls -or
                $green -ne ($case.ExpectedCode -eq 0) -or
                ($code -ne 0 -and -not $text.Contains('[verify] FAIL:'))) {
            throw "[VERIFY-GATE] CASE_FAILED name=$($case.Name) exit=$code calls=$calls`n$text"
        }
    }
    Write-Output "[VERIFY-GATE] PASS cases=$($cases.Count) minimumTests=$MinimumTests"
} finally {
    # 只删除本次创建的两份文件及已空目录，不递归操作计算路径。
    foreach ($file in @($fixturePath, $runnerPath)) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    Remove-Item -LiteralPath $directory
}
