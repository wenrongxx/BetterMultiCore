# Controlled A/B/C/D benchmark: fixed load, spark profiler MSPT
# A: no plugin, no GD | B: plugin, no GD | C: no plugin + GD | D: plugin + GD
param(
  [int]$ProfilerSec = 30,
  [int]$WarmupSec = 12,
  [int]$Players = 4
)
$ErrorActionPreference = "Continue"
$root = "O:\Xiaomi\workspace\mc_better_single_core"
$server = Join-Path $root "test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$node = $env:MIMO_NODE
if (-not $node) { $node = "node" }
$plugins = Join-Path $server "plugins"
$pluginsOff = Join-Path $server "plugins_off"
$gdOn = Join-Path $server "world\datapacks\GravestoneDungeons"
$gdOff = Join-Path $server "world\datapacks_off\GravestoneDungeons"
$outRoot = Join-Path $root "bench_results"
New-Item -ItemType Directory -Path $outRoot -Force | Out-Null
New-Item -ItemType Directory -Path $pluginsOff -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path $gdOff) -Force | Out-Null

$pluginJar = Join-Path $plugins "BetterSingleCore-1.4.0.jar"
$pluginJarOff = Join-Path $pluginsOff "BetterSingleCore-1.4.0.jar"
$cfgPath = Join-Path $plugins "BetterSingleCore\config.yml"

function Stop-Server {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*server.jar*" } | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }
  Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -like "*minimal_client*" } | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }
  Start-Sleep -Seconds 2
}

function Set-Config {
  if (-not (Test-Path $cfgPath)) { return }
  $cfg = Get-Content $cfgPath -Raw -Encoding UTF8
  $cfg = $cfg -replace 'report-interval-ticks:\s*\d+', 'report-interval-ticks: 100'
  $cfg = $cfg -replace 'parallel-viewers-threshold:\s*\d+', 'parallel-viewers-threshold: 2'
  $enc = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($cfgPath, $cfg, $enc)
}

function Set-Scenario([bool]$usePlugin, [bool]$useGd) {
  if ($usePlugin) {
    if (-not (Test-Path $pluginJar) -and (Test-Path $pluginJarOff)) { Move-Item $pluginJarOff $pluginJar -Force }
  } else {
    if (Test-Path $pluginJar) { Move-Item $pluginJar $pluginJarOff -Force }
  }
  $remapped = Join-Path $plugins ".paper-remapped"
  if (Test-Path $remapped) { Remove-Item $remapped -Recurse -Force -ErrorAction SilentlyContinue }

  if ($useGd) {
    if (-not (Test-Path $gdOn) -and (Test-Path $gdOff)) { Move-Item $gdOff $gdOn -Force }
  } else {
    if (Test-Path $gdOn) { Move-Item $gdOn $gdOff -Force }
  }
}

function Start-ServerProc {
  $stdout = Join-Path $server "logs\stdout.log"
  $stderr = Join-Path $server "logs\stderr.log"
  New-Item -ItemType Directory -Path (Join-Path $server "logs") -Force | Out-Null
  Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue

  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $java
  $psi.Arguments = "-Xms512M -Xmx1024M -jar server.jar nogui"
  $psi.WorkingDirectory = $server
  $psi.RedirectStandardInput = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
  $psi.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
  $proc = [System.Diagnostics.Process]::Start($psi)
  $outWriter = [System.IO.StreamWriter]::new($stdout, $true, [System.Text.UTF8Encoding]::new($false))
  $errWriter = [System.IO.StreamWriter]::new($stderr, $true, [System.Text.UTF8Encoding]::new($false))
  $outWriter.AutoFlush = $true; $errWriter.AutoFlush = $true
  $outEvent = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
  } -MessageData $outWriter
  $errEvent = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
    if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
  } -MessageData $errWriter
  $proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()
  return @{ Proc=$proc; Out=$outWriter; Err=$errWriter; OutEv=$outEvent; ErrEv=$errEvent; Path=$stdout }
}

function Wait-Ready($ctx, [int]$sec=180) {
  $deadline = (Get-Date).AddSeconds($sec)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    if ($ctx.Proc.HasExited) { return $false }
    $t = if (Test-Path $ctx.Path) { Get-Content $ctx.Path -Raw -ErrorAction SilentlyContinue } else { "" }
    if ($t -match "Done \(\d+") { return $true }
    if ($t -match "Failed to start|Encountered an unexpected exception") { return $false }
  }
  return $false
}

function Send-Cmd($ctx, [string]$c) {
  if ($ctx.Proc.HasExited) { return }
  try {
    $ctx.Proc.StandardInput.WriteLine($c)
    $ctx.Proc.StandardInput.Flush()
  } catch {}
}

function Read-Log($ctx) {
  if (Test-Path $ctx.Path) { Get-Content $ctx.Path -Raw -ErrorAction SilentlyContinue } else { "" }
}

function Stop-ServerCtx($ctx) {
  if ($ctx -and -not $ctx.Proc.HasExited) {
    try { $ctx.Proc.StandardInput.WriteLine("stop"); $ctx.Proc.StandardInput.Flush() } catch {}
    $dl = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $dl -and -not $ctx.Proc.HasExited) { Start-Sleep -Milliseconds 400 }
    if (-not $ctx.Proc.HasExited) { Stop-Process -Id $ctx.Proc.Id -Force -ErrorAction SilentlyContinue }
  }
  if ($ctx) {
    Unregister-Event -SourceIdentifier $ctx.OutEv.Name -ErrorAction SilentlyContinue
    Unregister-Event -SourceIdentifier $ctx.ErrEv.Name -ErrorAction SilentlyContinue
    try { $ctx.Out.Close(); $ctx.Err.Close() } catch {}
  }
  Stop-Server
}

function Start-Clients([int]$n) {
  $clientDir = Join-Path $root "mc_clients"
  $out = Join-Path $clientDir "run_out.txt"
  Remove-Item $out -Force -ErrorAction SilentlyContinue
  Start-Process -FilePath $node -ArgumentList "minimal_client.js" -WorkingDirectory $clientDir -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError (Join-Path $clientDir "run_err.txt")
  Start-Sleep -Seconds ([Math]::Max(15, 4 * $n))
}

$scenarios = @(
  @{ Id="A_noplug_nogd"; Plugin=$false; Gd=$false; Label="vanilla no-plugin fixed-load" },
  @{ Id="B_plug_nogd";   Plugin=$true;  Gd=$false; Label="plugin-only fixed-load" },
  @{ Id="C_noplug_gd";   Plugin=$false; Gd=$true;  Label="vanilla + GravestoneDungeons" },
  @{ Id="D_plug_gd";     Plugin=$true;  Gd=$true;  Label="plugin + GravestoneDungeons" }
)

$results = @()
foreach ($s in $scenarios) {
  Write-Host "`n======== SCENARIO $($s.Id): $($s.Label) ========"
  Stop-Server
  Set-Scenario -usePlugin $s.Plugin -useGd $s.Gd
  if ($s.Plugin -and (Test-Path $cfgPath)) { Set-Config }

  $ctx = Start-ServerProc
  $ready = Wait-Ready $ctx 180
  Write-Host "READY=$ready"
  if (-not $ready) {
    $results += [PSCustomObject]@{ Id=$s.Id; Label=$s.Label; Ready=$false }
    Stop-ServerCtx $ctx
    continue
  }
  Start-Sleep -Seconds 4

  Send-Cmd $ctx "function bsc_stress:setup"
  Start-Sleep -Seconds 2
  Send-Cmd $ctx "execute at @a run summon minecraft:cow ~3 ~ ~"
  Start-Sleep -Milliseconds 300
  Send-Cmd $ctx "execute at @a run summon minecraft:cow ~-3 ~ ~"
  Start-Sleep -Milliseconds 300
  Send-Cmd $ctx "execute at @a run summon minecraft:pig ~ ~ ~3"
  Start-Sleep -Seconds 1

  Write-Host "Starting $Players clients..."
  Start-Clients $Players
  Send-Cmd $ctx "list"
  Start-Sleep -Seconds 2

  if ($s.Plugin) {
    Send-Cmd $ctx "bsc status"
    Start-Sleep -Seconds 1
  }

  Write-Host "Warmup ${WarmupSec}s..."
  Start-Sleep -Seconds $WarmupSec

  $sparkFile = Join-Path $outRoot "$($s.Id).sparkprofile"
  Remove-Item $sparkFile -Force -ErrorAction SilentlyContinue
  $rel = "..\bench_results\$($s.Id).sparkprofile"
  Send-Cmd $ctx "spark profiler start --timeout $ProfilerSec --save-to-file $rel"
  Write-Host "Profiler ${ProfilerSec}s..."
  Start-Sleep -Seconds ($ProfilerSec + 8)

  if ($s.Plugin) {
    Send-Cmd $ctx "bsc monitor"
    Start-Sleep -Seconds 1
    Send-Cmd $ctx "bsc status"
    Start-Sleep -Seconds 1
  }
  Send-Cmd $ctx "spark health"
  Start-Sleep -Seconds 2

  $log = Read-Log $ctx
  $lines = $log -split "`r?`n"
  $extract = $lines | Where-Object {
    $_ -match "MSPT|Monitor:|Preload:|PathWarm:|Particle:|TPS|Profiler|sparkprofile|save complete|Upload complete|Exception|BetterSingleCore|players online|joined the game|Tick time|mspt"
  }

  $meta = [PSCustomObject]@{
    Id = $s.Id
    Label = $s.Label
    Plugin = $s.Plugin
    Gd = $s.Gd
    Ready = $ready
    ProfilePath = $sparkFile
    ProfileExists = (Test-Path $sparkFile)
    Extract = $extract
    LogPath = $ctx.Path
  }
  $results += $meta
  $meta | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $outRoot "$($s.Id).json") -Encoding UTF8

  Stop-ServerCtx $ctx
  Start-Sleep -Seconds 3
}

$summaryPath = Join-Path $outRoot "SUMMARY.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8
Write-Host "`n==== BENCH DONE ===="
Write-Host "Results: $summaryPath"
Get-ChildItem $outRoot | Select-Object Name, Length
