#requires -Version 5.1
<#
  Warwall smoke test.

  Why it works this way:
  - Paper's console reader will happily execute commands piped in *before* the level is
    ready, and then NPEs inside CommandSourceStack.getLevel(). So we must NOT pipe
    commands up front: we start the server, poll logs/latest.log until "Done (", and only
    then feed stdin line by line.
  - Output is not redirected (stdout is inherited); the authoritative log is the server's
    own logs/latest.log, which avoids PowerShell async-output-event flakiness.

  Usage:
    powershell -NoProfile -ExecutionPolicy Bypass -File .ops\smoke-test.ps1
      -PluginJar  (Resolve-Path 'build\libs\warwall-1.0.0.jar')
      -ServerDir  (Resolve-Path '.ops\testserver-1.21.11')
#>
param(
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [string]$ServerDir = 'D:\Larkdreamcode\warwall\.ops\testserver-1.21.11',
    [string]$Java = 'C:\Program Files\Java\jdk-21\bin\java.exe',
    [string[]]$Commands = @(
        'warwall status',
        'warwall debug arena world 0 100 0',
        'warwall setup select _debug',
        'warwall setup validate',
        'warwall debug fast on',
        'warwall debug solo on',
        'warwall start',
        'warwall status',
        'warwall status',
        'warwall status',
        'warwall status',
        'warwall status',
        'warwall debug wither red kill',
        'warwall debug phase FINAL_FIGHT',
        'warwall status',
        'warwall shop',
        'warwall coins',
        'warwall give breacher',
        'warwall building farm',
        'warwall debug end',
        'warwall stop'
    ),
    [int]$BootTimeoutSec = 240,
    [int]$CommandIntervalSec = 3,
    [int]$SettleSec = 15
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $PluginJar)) { throw "plugin jar not found: $PluginJar" }
if (-not (Test-Path (Join-Path $ServerDir 'paper.jar'))) { throw "paper.jar not found in $ServerDir" }

$pluginsDir = Join-Path $ServerDir 'plugins'
New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
Get-ChildItem $pluginsDir -Filter 'warwall*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $PluginJar (Join-Path $pluginsDir (Split-Path $PluginJar -Leaf)) -Force

$logDir = Join-Path $ServerDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'latest.log'
if (Test-Path $log) { Remove-Item $log -Force }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Java
$psi.Arguments = '-Xms512M -Xmx1200M -XX:+UseSerialGC -jar paper.jar nogui'
$psi.WorkingDirectory = $ServerDir
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError = $false
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$null = $proc.Start()

function Wait-LogPattern {
    param([string]$Pattern, [int]$TimeoutSec)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { return $false }
        if (Test-Path $log) {
            $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
            if ($text -and $text -match $Pattern) { return $true }
        }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

function Get-LogText {
    if (-not (Test-Path $log)) { return '' }
    return (Get-Content $log -Raw -ErrorAction SilentlyContinue)
}

function Count-Matches {
    param([string]$Text, [string]$Pattern)
    if (-not $Text) { return 0 }
    return ([regex]::Matches($Text, $Pattern)).Count
}

$booted = Wait-LogPattern -Pattern 'Done \(' -TimeoutSec $BootTimeoutSec
$sent = 0

if ($booted) {
    Start-Sleep -Seconds 2
    foreach ($cmd in $Commands) {
        if ([string]::IsNullOrWhiteSpace($cmd)) { continue }
        try {
            $proc.StandardInput.WriteLine($cmd)
            $proc.StandardInput.Flush()
            $sent++
        } catch {
            Write-Output "stdin write failed at: $cmd"
            break
        }
        Start-Sleep -Seconds $CommandIntervalSec
    }
    Start-Sleep -Seconds $SettleSec
    try { $proc.StandardInput.WriteLine('stop'); $proc.StandardInput.Flush() } catch { }
    $deadline = (Get-Date).AddSeconds(120)
    while (-not $proc.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
}

if (-not $proc.HasExited) { try { $proc.Kill() } catch { } }
Start-Sleep -Seconds 2
Get-Process java -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }

$text = Get-LogText
$stackCount = Count-Matches -Text $text -Pattern '(?m)^\s+at '
$exceptionCount = Count-Matches -Text $text -Pattern 'Exception|Caused by'
$commandErrors = Count-Matches -Text $text -Pattern 'Command exception'
$warwallLines = @()
if ($text) {
    $warwallLines = [regex]::Matches($text, '(?m)^\[.*?\]: \[warwall\].*$') |
        ForEach-Object { $_.Value.Trim() } | Select-Object -Last 30
}

Write-Output ''
Write-Output '===== SMOKE RESULT ====='
Write-Output ("booted         = {0}" -f $booted)
Write-Output ("commandsSent   = {0}" -f $sent)
Write-Output ("pluginLoaded   = {0}" -f [bool]($text -match 'Enabling warwall|\[warwall\]'))
Write-Output ("exceptions     = {0}" -f $exceptionCount)
Write-Output ("commandErrors  = {0}" -f $commandErrors)
Write-Output ("stackFrames    = {0}" -f $stackCount)
Write-Output ("log            = {0}" -f $log)
Write-Output ''
Write-Output '===== [warwall] log lines (last 30) ====='
$warwallLines | ForEach-Object { Write-Output $_ }
Write-Output ''
Write-Output '===== exception headlines (max 12) ====='
if ($text) {
    [regex]::Matches($text, '(?m)^.*(ERROR|Exception|Caused by).*$') |
        ForEach-Object { $_.Value.Trim() } | Select-Object -First 12 | ForEach-Object { Write-Output $_ }
}

if (-not $booted) { exit 3 }
if ($commandErrors -gt 0) { exit 4 }
if ($exceptionCount -gt 0) { exit 2 }
exit 0
