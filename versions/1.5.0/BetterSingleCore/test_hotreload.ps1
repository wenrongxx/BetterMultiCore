# Hot unload/reload test for BetterSingleCore 1.5 with PlugManX 3.0.4
# Success criteria (log-based):
#  - unload: "has been unloaded" + "restored N vanilla" + no ClassNotFound
#  - load:   "loaded and enabled" + "fan-out installed" + bsc status installed=true
#  - final:  plugin listed + particle invocations work after last load
param(
  [int]$Cycles = 3,
  [int]$WaitReadySec = 180
)
$ErrorActionPreference = "Continue"
$root = "O:\Xiaomi\workspace\mc_better_single_core"
$server = Join-Path $root "test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$stdout = Join-Path $server "logs\stdout.log"
$resultFile = Join-Path $server "hotreload_result.txt"
New-Item -ItemType Directory -Path (Join-Path $server "logs") -Force | Out-Null
Remove-Item $stdout, $resultFile -Force -ErrorAction SilentlyContinue

$plugins = Join-Path $server "plugins"
$jar15 = Join-Path $plugins "BetterSingleCore-1.5.0.jar"
$srcJar = Join-Path $root "BetterSingleCore\dist\BetterSingleCore-1.5.0.jar"
if (Test-Path $srcJar) { Copy-Item $srcJar $jar15 -Force }

$plugman = Get-ChildItem $plugins -Filter "PlugMan*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
Add-Content -Path $resultFile -Value "HAS_PLUGMAN=$([bool]$plugman) jar=$($plugman.Name)"
Add-Content -Path $resultFile -Value "BSC_JAR=$((Get-Item $jar15).Length)"

Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*server.jar*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

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
$w = [System.IO.StreamWriter]::new($stdout, $true, [System.Text.UTF8Encoding]::new($false))
$w.AutoFlush = $true
$ev = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
  if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $w
$proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()

function Send-Cmd([string]$c) {
  if ($proc.HasExited) { return }
  try { $proc.StandardInput.WriteLine($c); $proc.StandardInput.Flush(); Add-Content $resultFile ">> $c" } catch {
    Add-Content $resultFile "CMD_FAIL $c"
  }
}
function Read-Log {
  if (Test-Path $stdout) { Get-Content $stdout -Raw -ErrorAction SilentlyContinue } else { "" }
}
function Tail-Text([int]$n = 150) {
  $t = Read-Log
  if (-not $t) { return "" }
  return (($t -split "`r?`n") | Select-Object -Last $n) -join "`n"
}

$dl = (Get-Date).AddSeconds($WaitReadySec)
$ready = $false
while ((Get-Date) -lt $dl) {
  Start-Sleep -Milliseconds 400
  if ($proc.HasExited) { break }
  $t = Read-Log
  if ($t -match "Done \(\d+") { $ready = $true; break }
  if ($t -match "Failed to start|Encountered an unexpected exception") { break }
}
Start-Sleep -Seconds 5
Add-Content $resultFile "READY=$ready"
if (-not $ready) {
  Add-Content $resultFile "FAIL=not ready"
  if (-not $proc.HasExited) {
    try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
    Start-Sleep -Seconds 8
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
  }
  Get-Content $resultFile
  exit 1
}

# Bot so particle @a has a viewer (optional but useful)
Send-Cmd "bot create HotBot Steve minecraft:overworld 0 64 0"
Start-Sleep -Seconds 3
Send-Cmd "plugins"
Start-Sleep -Seconds 1
Send-Cmd "bsc particle"
Start-Sleep -Seconds 2

$init = Tail-Text 80
$initInstall = $init -match "fan-out installed"
$initStatus = $init -match "installed=true leaves=\d+"
Add-Content $resultFile "INITIAL install=$initInstall status=$initStatus"
if (-not ($initInstall -and $initStatus)) { $failInit = $true } else { $failInit = $false }

$fail = 0
if ($failInit) { $fail++ }

for ($i = 1; $i -le $Cycles; $i++) {
  Write-Host "=== Cycle $i/$Cycles ==="
  Add-Content $resultFile "=== CYCLE $i ==="

  # UNLOAD
  Send-Cmd "plugman unload BetterSingleCore"
  Start-Sleep -Seconds 4
  $u = Tail-Text 100
  $unloaded = $u -match "has been unloaded|Successfully unloaded|has been unloaded\."
  $uninstallLog = $u -match "ParticleCommand offload uninstalled; restored \d+ vanilla"
  $noClashU = $u -notmatch "ClassNotFoundException|NoClassDefFoundError|LinkageError"

  # bsc should be gone
  Send-Cmd "bsc particle"
  Start-Sleep -Seconds 1
  $u2 = Tail-Text 40
  $bscGone = $u2 -match "Unknown or incomplete command|Unknown command|<--\[HERE\]"

  # particle still executes without ClassLoader errors (bot may not count as @a viewer)
  Send-Cmd "particle minecraft:flame 0 64 0 0.1 0.1 0.1 1 5 normal @a"
  Start-Sleep -Seconds 2
  $u3 = Tail-Text 60
  $vanillaParticle = ($u3 -match "Displaying particle") -or ($u3 -notmatch "ClassNotFoundException|NoClassDefFoundError|Unknown command")

  Add-Content $resultFile "UNLOAD unloaded=$unloaded uninstallLog=$uninstallLog noClash=$noClashU bscGone=$bscGone particleOk=$vanillaParticle"
  if (-not ($unloaded -and $uninstallLog -and $noClashU -and $bscGone -and $vanillaParticle)) {
    Add-Content $resultFile "FAIL unload cycle $i"
    $fail++
  }

  # LOAD
  Send-Cmd "plugman load BetterSingleCore"
  Start-Sleep -Seconds 5
  $l = Tail-Text 120
  $enabled = $l -match "has been loaded and enabled|Enabling BetterSingleCore"
  $reinstall = $l -match "ParticleCommand parallel fan-out installed"
  $noClashL = $l -notmatch "ClassNotFoundException|NoClassDefFoundError|LinkageError"

  Send-Cmd "bsc particle"
  Start-Sleep -Seconds 2
  $l2 = Tail-Text 50
  $statusInstalled = $l2 -match "installed=true leaves=\d+"

  function Get-LastInvocations([string]$text) {
    $ms = [regex]::Matches($text, "invocations=(\d+)")
    if ($ms.Count -eq 0) { return -1 }
    return [int]$ms[$ms.Count - 1].Groups[1].Value
  }
  $invBefore = Get-LastInvocations $l2

  Send-Cmd "particle minecraft:flame 0 64 0 0.1 0.1 0.1 1 5 normal @a"
  Start-Sleep -Seconds 3
  Send-Cmd "bsc particle"
  Start-Sleep -Seconds 2
  $l4 = Tail-Text 80
  $invAfter = Get-LastInvocations $l4
  $particleAfter = $l4 -match "Displaying particle"
  $invBump = ($invBefore -ge 0 -and $invAfter -gt $invBefore) -or $particleAfter -or ($invAfter -ge 1)

  Add-Content $resultFile "LOAD enabled=$enabled reinstall=$reinstall noClash=$noClashL statusInstalled=$statusInstalled particle=$particleAfter invBefore=$invBefore invAfter=$invAfter invBump=$invBump"
  if (-not ($enabled -and $reinstall -and $noClashL -and $statusInstalled -and $invBump)) {
    Add-Content $resultFile "FAIL load cycle $i"
    $fail++
  }

  $errs = ((Tail-Text 150) -split "`n") | Where-Object {
    $_ -match "ClassNotFoundException|NoClassDefFoundError|Exception.*[Bb]etterSingleCore|Failed to install particle"
  }
  if ($errs) {
    Add-Content $resultFile "ERRS: $($errs -join ' || ')"
    $fail++
  }
}

# Final checks
Send-Cmd "plugins"
Start-Sleep -Seconds 1
Send-Cmd "bsc status"
Start-Sleep -Seconds 2
Send-Cmd "particle minecraft:flame 0 64 0 0.1 0.1 0.1 1 5 normal @a"
Start-Sleep -Seconds 2
Send-Cmd "bsc particle"
Start-Sleep -Seconds 1

$final = Tail-Text 120
$finalOk = ($final -match "BetterSingleCore") -and
           ($final -match "installed=true leaves=\d+") -and
           ($final -notmatch "ClassNotFoundException|NoClassDefFoundError")
Add-Content $resultFile "FINAL_OK=$finalOk"
Add-Content $resultFile "FAIL_COUNT=$fail"
Add-Content $resultFile "----- RELEVANT -----"
(($final -split "`n") | Where-Object {
  $_ -match "BetterSingleCore|Particle|uninstalled|fan-out|Displaying particle|PlugMan|unloaded|loaded and|Exception|ClassNotFound|invocations"
} | Select-Object -Last 80) | ForEach-Object { Add-Content $resultFile $_ }

if (-not $proc.HasExited) {
  try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
  $dl = (Get-Date).AddSeconds(30)
  while ((Get-Date) -lt $dl -and -not $proc.HasExited) { Start-Sleep -Milliseconds 400 }
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
Unregister-Event -SourceIdentifier $ev.Name -ErrorAction SilentlyContinue
$w.Close()

Write-Host "=== HOTRELOAD RESULT fail=$fail finalOk=$finalOk ==="
Get-Content $resultFile
if ($fail -gt 0 -or -not $finalOk) { exit 1 }
exit 0
