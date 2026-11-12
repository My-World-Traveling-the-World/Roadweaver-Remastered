<#
.SYNOPSIS
  只读审计「新构建的 jar」与「线上在用的 jar」是否语义等价。

.DESCRIPTION
  用于重构/改动后确认没有破坏运行能力。全程只读，只在 $env:TEMP 解压，不改写任何 jar。
  判定分五层：
    1. class / 资源 条目数与路径集合
    2. 资源文件逐字节
    3. 同名 class 三档分类（A 字节相同 / B 规范化后相同 / C 仍有差异）
    4. fabric.mod.json、mixins.json、refmap 逐字节 + MANIFEST 的 Fabric-Loom 字段
    5. 每个 mixin 类的 net/minecraft 引用集合（注入点是否相同）

.PARAMETER Built
  新构建件路径（通常 fabric/build/libs/*.jar）

.PARAMETER Deployed
  线上部署件路径（客户端 mods 内）

.PARAMETER Javap
  javap.exe 路径，默认 JDK 21

.EXAMPLE
  .\tools\Audit-JarEquivalence.ps1 -Built fabric\build\libs\x.jar -Deployed "D:\...\mods\x.jar"
#>
param(
  [Parameter(Mandatory)][string]$Built,
  [Parameter(Mandatory)][string]$Deployed,
  [string]$Javap = 'C:\Program Files\Java\jdk-21\bin\javap.exe'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-JarReadable {
  param([string]$JarPath)
  if (-not (Test-Path -LiteralPath $JarPath)) { throw "找不到 jar: $JarPath" }
  $item = Get-Item -LiteralPath $JarPath
  if ($item.Length -le 0) { throw "jar 大小为 0（路径可能被通配符解释）: $JarPath" }
  return $item
}
$ib = Assert-JarReadable $Built
$id = Assert-JarReadable $Deployed
'构建件 {0}  {1:N0} B  {2}' -f $ib.Name, $ib.Length, (Get-FileHash -LiteralPath $Built -Algorithm SHA256).Hash
'部署件 {0}  {1:N0} B  {2}' -f $id.Name, $id.Length, (Get-FileHash -LiteralPath $Deployed -Algorithm SHA256).Hash

$tmpB = Join-Path $env:TEMP 'rw-audit-built'
$tmpD = Join-Path $env:TEMP 'rw-audit-deploy'
Remove-Item -LiteralPath $tmpB,$tmpD -Recurse -Force -ErrorAction SilentlyContinue
[System.IO.Compression.ZipFile]::ExtractToDirectory($Built,$tmpB)
[System.IO.Compression.ZipFile]::ExtractToDirectory($Deployed,$tmpD)

function Get-JarFileEntry {
  param([string]$JarPath)
  $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
  try { @($zip.Entries | Where-Object { -not $_.FullName.EndsWith('/') } | ForEach-Object FullName) }
  finally { $zip.Dispose() }
}
$eb = Get-JarFileEntry $Built
$ed = Get-JarFileEntry $Deployed
$cb = @($eb | Where-Object { $_ -like '*.class' })
$cd = @($ed | Where-Object { $_ -like '*.class' })
$rb = @($eb | Where-Object { $_ -notlike '*.class' })
$rd = @($ed | Where-Object { $_ -notlike '*.class' })

"`n== 条目 =="
'  文件条目 {0}/{1}   class {2}/{3}   资源 {4}/{5}' -f $eb.Count,$ed.Count,$cb.Count,$cd.Count,$rb.Count,$rd.Count
'  资源路径集合一致: {0}' -f ((Compare-Object $rb $rd).Count -eq 0)

$resDiff = @()
foreach ($f in $rb) {
  $h1 = (Get-FileHash -LiteralPath (Join-Path $tmpB $f) -Algorithm SHA256).Hash
  $p2 = Join-Path $tmpD $f
  if (-not (Test-Path -LiteralPath $p2)) { $resDiff += "$f (缺)"; continue }
  if ($h1 -ne (Get-FileHash -LiteralPath $p2 -Algorithm SHA256).Hash) { $resDiff += $f }
}
'  资源逐字节不同: {0}' -f $resDiff.Count
$resDiff | Select-Object -First 10 | ForEach-Object { "    x $_" }

function ConvertTo-ClassMap {
  param([string[]]$Entries)
  $map = @{}
  foreach ($e in $Entries) {
    $fq = $e.Substring(0, $e.Length - 6).Replace('/', '.')
    $map[$fq.Split('.')[-1]] = $fq
  }
  ,$map
}
$mb = ConvertTo-ClassMap $cb
$md = ConvertTo-ClassMap $cd
$onlyB = @($mb.Keys | Where-Object { -not $md.ContainsKey($_) })
$onlyD = @($md.Keys | Where-Object { -not $mb.ContainsKey($_) })
$matched = @($mb.Keys | Where-Object { $md.ContainsKey($_) })
"`n== class 简单名 =="
'  同名 {0}   只在构建 {1}   只在部署 {2}' -f $matched.Count, $onlyB.Count, $onlyD.Count
if ($onlyB.Count) { $onlyB | Select-Object -First 10 | ForEach-Object { "    + $_" } }
if ($onlyD.Count) { $onlyD | Select-Object -First 10 | ForEach-Object { "    - $_" } }

$moved = @($matched | Where-Object { $mb[$_] -ne $md[$_] })
'  包路径改变 {0}（重构预期；必须 0 个类丢失）' -f $moved.Count

$sameBytes = 0; $differing = @()
foreach ($s in $matched) {
  $p1 = Join-Path $tmpB ($mb[$s].Replace('.', [IO.Path]::DirectorySeparatorChar) + '.class')
  $p2 = Join-Path $tmpD ($md[$s].Replace('.', [IO.Path]::DirectorySeparatorChar) + '.class')
  if ((Get-FileHash -LiteralPath $p1 -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $p2 -Algorithm SHA256).Hash) { $sameBytes++ }
  else { $differing += $s }
}
"`n== 逐类三档 =="
'  A 字节完全相同        : {0}' -f $sameBytes
'  字节不同（待规范化）  : {0}' -f $differing.Count

$rename = @()
foreach ($s in $moved) {
  $rename += ,@($md[$s], $mb[$s])
  $rename += ,@($md[$s].Replace('.','/'), $mb[$s].Replace('.','/'))
  # javac 为 switch-over-enum 生成的合成字段名（$SwitchMap$...）把枚举二进制名里的
  # '.' 全换成了 '$' —— 只做点号与斜杠两种归一化会漏掉这一类，实测把
  # OpenCLBridge / OpenCLBridge$1 误报成「真差异」。必须同时生成 $ 分隔形式。
  $rename += ,@($md[$s].Replace('.','$'), $mb[$s].Replace('.','$'))
}
$rename = @($rename | Sort-Object { - ($_[0].Length) })

function Get-JavapBlocks {
  param([string]$Dir, [string[]]$Fqns, [int]$Batch = 200)
  # 用 ArrayList 累积：PowerShell 的 $arr += $x 会重新绑定变量，
  # 哈希表里保存的仍是旧的空数组（实测导致全部块为空、假阴性）。
  $res = @{}
  for ($i = 0; $i -lt $Fqns.Count; $i += $Batch) {
    $hi = [Math]::Min($i + $Batch - 1, $Fqns.Count - 1)
    $chunk = $Fqns[$i..$hi]
    $raw = & $Javap -classpath $Dir -p -c @chunk 2>&1
    $cur = $null
    foreach ($ln in $raw) {
      if ($ln -match '^(?:public |final |abstract |static )*(?:class|interface|enum|record|@interface) ([\w.$]+)') {
        $cur = New-Object System.Collections.ArrayList
        $res[$Matches[1]] = $cur
        continue
      }
      if ($null -ne $cur) {
        if ($ln.Trim() -eq '}') { $cur = $null; continue }
        $null = $cur.Add($ln)
      }
    }
  }
  ,$res
}
$diffB = Get-JavapBlocks -Dir $tmpB -Fqns @($differing | ForEach-Object { $mb[$_] })
$diffD = Get-JavapBlocks -Dir $tmpD -Fqns @($differing | ForEach-Object { $md[$_] })

function Get-NormalizedLines {
  param([string[]]$Lines, [bool]$ApplyRename)
  $out = @()
  foreach ($ln in $Lines) {
    if (-not $ln.Trim()) { continue }
    $s = $ln
    if ($ApplyRename) { foreach ($pair in $rename) { $s = $s.Replace($pair[0], $pair[1]) } }
    $s = [regex]::Replace($s, '#\d+', '#N')
    $s = [regex]::Replace($s, 'lambda\$([^$]+)\$\d+', 'lambda$1$#')
    $s = [regex]::Replace($s, '^\s*\d+:\s*', '')
    $s = [regex]::Replace($s, '\s+', ' ').Trim()
    if ($s) { $out += $s }
  }
  ,$out
}
$eqCount = 0; $realDiff = @()
foreach ($s in $differing) {
  $nb = Get-NormalizedLines -Lines @($diffB[$mb[$s]]) -ApplyRename $false
  $nd = Get-NormalizedLines -Lines @($diffD[$md[$s]]) -ApplyRename $true
  if (($nb -join "`n") -eq ($nd -join "`n")) { $eqCount++ } else { $realDiff += $s }
}
'  B 规范化后一致        : {0}' -f $eqCount
'  C 规范化后仍有差异    : {0}' -f $realDiff.Count
foreach ($s in $realDiff) {
  $nb = Get-NormalizedLines -Lines @($diffB[$mb[$s]]) -ApplyRename $false
  $nd = Get-NormalizedLines -Lines @($diffD[$md[$s]]) -ApplyRename $true
  $dl = @(Compare-Object $nd $nb | Select-Object -First 4 | ForEach-Object { '      ' + $_.SideIndicator + ' ' + $_.InputObject.Substring(0,[Math]::Min(120,$_.InputObject.Length)) })
  '    * {0}  ({1} 处)' -f $s, $dl.Count
  $dl | ForEach-Object { $_ }
}

"`n== 配置与 MANIFEST =="
foreach ($f in @('fabric.mod.json','roadweaver.mixins.json','roadweaver-fabric-fabric-refmap.json')) {
  $p1 = Join-Path $tmpB $f; $p2 = Join-Path $tmpD $f
  if ((Test-Path -LiteralPath $p1) -and (Test-Path -LiteralPath $p2)) {
    $h1 = (Get-FileHash -LiteralPath $p1 -Algorithm SHA256).Hash
    $h2 = (Get-FileHash -LiteralPath $p2 -Algorithm SHA256).Hash
    if ($h1 -eq $h2) { '  {0,-42} 逐字节一致 OK' -f $f } else { '  {0,-42} 不同 X' -f $f }
  }
}
foreach ($tag in @('built','deploy')) {
  if ($tag -eq 'built') { $dir = $tmpB } else { $dir = $tmpD }
  $mf = Join-Path $dir 'META-INF/MANIFEST.MF'
  if (Test-Path -LiteralPath $mf) {
    $keys = Get-Content -LiteralPath $mf | Where-Object { $_ -match '^(Fabric-Loom|Manifest-Version)' }
    '  {0,-7} {1}' -f $tag, ($keys -join '; ')
  }
}

"`n== mixin 注入点（net/minecraft 引用集合）=="
function Get-McReferences {
  param([string]$Dir, [string]$Fqn)
  $raw = & $Javap -classpath $Dir -c -p $Fqn 2>&1
  $refs = @()
  foreach ($ln in $raw) {
    foreach ($m in [regex]::Matches($ln, '(?:Method|Field|InterfaceMethod) net/minecraft[^\s;,]+')) { $refs += ($m.Value -replace '#\d+', '#N') }
  }
  ,@($refs | Sort-Object -Unique)
}
$mixinNames = @($cb | Where-Object { $_ -like '*/mixin/*' } | ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_) } | Sort-Object -Unique)
$bad = 0
foreach ($n in $mixinNames) {
  $fb = @($cb | Where-Object { $_ -like "*/$n.class" })
  $fd = @($cd | Where-Object { $_ -like "*/$n.class" })
  if ($fb.Count -ne 1 -or $fd.Count -ne 1) { '  {0,-34} 跳过（缺一侧）' -f $n; continue }
  $a = Get-McReferences -Dir $tmpB -Fqn ($fb[0].Substring(0, $fb[0].Length - 6).Replace('/', '.'))
  $b = Get-McReferences -Dir $tmpD -Fqn ($fd[0].Substring(0, $fd[0].Length - 6).Replace('/', '.'))
  if (($a -join '|') -eq ($b -join '|')) { '  {0,-34} {1,2}/{2,2}  一致 OK' -f $n, $a.Count, $b.Count }
  else { '  {0,-34} {1,2}/{2,2}  不同 X' -f $n, $a.Count, $b.Count; $bad++ }
}

"`n== 结论 =="
'  class 丢失/新增: {0}   资源差异: {1}   mixin 注入点差异: {2}   C 档类: {3}' -f ($onlyB.Count + $onlyD.Count), $resDiff.Count, $bad, $realDiff.Count
''
'  整体 SHA-256 不同 != 代码不同（压缩顺序/时间戳/目录条目都会改哈希）。'
'  等价性以「条目级 + 签名级 + 注入点级」证据为准。'
'  本审计证明部署等价，但不等于已启动游戏验证。'
