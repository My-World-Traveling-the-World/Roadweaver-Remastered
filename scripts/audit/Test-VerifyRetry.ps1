# 文件职责：从真实验证脚本提取重试函数，以模拟调用验证不会终止无关Java进程。
$ErrorActionPreference = 'Stop'
$verify = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'verify.ps1'
$tokens = $null
$parseErrors = $null
$tree = [System.Management.Automation.Language.Parser]::ParseFile($verify, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw '验证脚本解析失败' }
$definitions = @($tree.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Invoke-GradleWithRetry'
}, $true))
if ($definitions.Count -ne 1) { throw '重试函数必须恰好一份' }
# 不执行脚本顶层的构建/环境设置，只执行原函数，避免另一份测试实现自证。
. ([scriptblock]::Create($definitions[0].Extent.Text))

$DaemonSignature = 'Could not connect to the Gradle daemon'
$MaxAttempts = 4
$opts = @()
$script:calls = 0
$script:sleeps = 0
$script:processQueries = 0
$script:processStops = 0
$script:scenario = ''
$gradle = {
    $script:calls++
    switch ($script:scenario) {
        'recover' {
            if ($script:calls -eq 1) { 'Could not connect to the Gradle daemon' }
            else { 'BUILD SUCCESSFUL' }
        }
        'persistent' { 'Could not connect to the Gradle daemon' }
        'compile-error' { 'BUILD FAILED: compile error' }
        default { throw '未知模拟场景' }
    }
}

# 即使临时恢复旧破坏，下面也只拦截模拟对象，不会读取或终止真实进程。
function Get-Process {
    [CmdletBinding()]
    param([string]$Name)
    $script:processQueries++
    [pscustomobject]@{ Id = -1; ProcessName = 'java' }
}
function Stop-Process {
    [CmdletBinding()]
    param([switch]$Force, [Parameter(ValueFromPipeline = $true)]$InputObject)
    process { $script:processStops++ }
}
function Start-Sleep {
    param([int]$Seconds)
    if ($Seconds -ne 5) { throw '重试间隔被改变' }
    $script:sleeps++
}

function Assert-Result {
    param([string]$Scenario, [int]$Calls, [int]$Sleeps, [string]$Expected)
    $script:scenario = $Scenario
    $script:calls = 0
    $script:sleeps = 0
    $result = (Invoke-GradleWithRetry -Arguments @(':fabric:compileJava') -Label '模拟回归' | Out-String)
    if ($script:calls -ne $Calls -or $script:sleeps -ne $Sleeps -or -not $result.Contains($Expected)) {
        throw "[VERIFY-RETRY] 重试结果错误 scenario=$Scenario calls=$script:calls sleeps=$script:sleeps"
    }
    if ($script:processQueries -ne 0 -or $script:processStops -ne 0) {
        throw "[VERIFY-RETRY] PROCESS_ISOLATION_FAILED 重试不得查询或终止同名Java进程 processQueries=$script:processQueries processStops=$script:processStops"
    }
}

Assert-Result 'recover' 2 1 'BUILD SUCCESSFUL'
Assert-Result 'persistent' 4 3 $DaemonSignature
Assert-Result 'compile-error' 1 0 'BUILD FAILED'
Write-Output '[VERIFY-RETRY] PASS cases=3 processQueries=0 processStops=0'
