# Integration test: bot far from spawn + mob for pathwarm + chunk preload.
$ErrorActionPreference = "Continue"
$server = "O:\Xiaomi\workspace\mc_better_single_core\test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$resultFile = Join-Path $server "test_result.txt"
$stdout = Join-Path $server "logs\stdout.log"
$stderr = Join-Path $server "logs\stderr.log"
New-Item -ItemType Directory -Path (Join-Path $server "logs") -Force | Out-Null
Remove-Item $resultFile, $stdout, $stderr -Force -ErrorAction SilentlyContinue

Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -like "*server.jar*"
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

# Ensure plugin config allows generating distant chunks so preload has work to do
$cfgDir = Join-Path $server "plugins\BetterSingleCore"
New-Item -ItemType Directory -Path $cfgDir -Force | Out-Null
$cfgPath = Join-Path $cfgDir "config.yml"
if (Test-Path $cfgPath) {
    $cfg = Get-Content $cfgPath -Raw -Encoding UTF8
    $cfg = $cfg -replace 'generate-if-missing:\s*false', 'generate-if-missing: true'
    $cfg = $cfg -replace 'radius-chunks:\s*\d+', 'radius-chunks: 4'
    $cfg = $cfg -replace 'max-loads-per-player:\s*\d+', 'max-loads-per-player: 64'
    Set-Content -Path $cfgPath -Value $cfg -Encoding UTF8
    Add-Content -Path $resultFile -Value "CONFIG_PATCHED generate-if-missing=true radius=4"
}

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
$outWriter.AutoFlush = $true
$errWriter.AutoFlush = $true
$outEvent = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $outWriter
$errEvent = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
    if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $errWriter
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()

function Send-Cmd([string]$c) {
    if ($proc.HasExited) { return }
    try {
        $proc.StandardInput.WriteLine($c)
        $proc.StandardInput.Flush()
        Add-Content -Path $resultFile -Value ">> $c"
    } catch {
        Add-Content -Path $resultFile -Value "CMD_FAIL $c : $_"
    }
}
function Read-Log {
    if (Test-Path $stdout) { Get-Content $stdout -Raw -ErrorAction SilentlyContinue } else { "" }
}

$deadline = (Get-Date).AddSeconds(150)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    if ($proc.HasExited) { break }
    $t = Read-Log
    if ($t -match "Done \(\d+") { $ready = $true; break }
    if ($t -match "Failed to start|Encountered an unexpected exception") { break }
}
Start-Sleep -Seconds 3
Add-Content -Path $resultFile -Value "READY=$ready"

# Baseline
Send-Cmd "bsc status"
Start-Sleep -Seconds 1

# Spawn bot at far coordinates so surrounding chunks are NOT preloaded
# Flat world surface ~ y=-60 in 1.21; use y=-50 to be above ground after load
# Far from spawn (0,0) so radius-2 chunks need loading
Send-Cmd "bot create Tester Steve minecraft:overworld 512 -50 512"
Start-Sleep -Seconds 4
Send-Cmd "bot list"
Start-Sleep -Seconds 1

# If bot died/fell, try alternate Y after chunks generate
$logMid = Read-Log
if ($logMid -match "Tester (fell|left|died)") {
    Write-Host "Bot died, retrying at different Y..."
    Send-Cmd "bot create Tester Steve minecraft:overworld 520 -60 520"
    Start-Sleep -Seconds 4
    Send-Cmd "bot list"
    Start-Sleep -Seconds 1
}

# Summon a cow near bot for pathwarm (needs a mob within player-distance)
Send-Cmd "execute as @e[name=Tester] run summon minecraft:cow ~ ~ ~"
Start-Sleep -Seconds 1
Send-Cmd "execute as @e[name=Tester] run summon minecraft:cow ~2 ~ ~"
Start-Sleep -Seconds 1
Send-Cmd "execute as @e[name=Tester] run summon minecraft:pig ~ ~2 ~"
Start-Sleep -Seconds 2

# Observation window: preload interval 20t=1s, pathwarm 40t=2s
Start-Sleep -Seconds 12

Send-Cmd "bsc status"
Start-Sleep -Seconds 1
Send-Cmd "bsc preload"
Start-Sleep -Seconds 1
Send-Cmd "bsc pathwarm"
Start-Sleep -Seconds 1
Send-Cmd "bsc monitor"
Start-Sleep -Seconds 1

# Second window after reload
Send-Cmd "bsc reload"
Start-Sleep -Seconds 6
Send-Cmd "bsc preload"
Start-Sleep -Seconds 1
Send-Cmd "bsc pathwarm"
Start-Sleep -Seconds 1
Send-Cmd "bsc status"
Start-Sleep -Seconds 2

# Also verify plugins list
Send-Cmd "plugins"
Start-Sleep -Seconds 1

$log = Read-Log
$lines = $log -split "`r?`n"
$bsc = $lines | Where-Object {
    $_ -match "BetterSingleCore|BSC|PathWarm|Preload|Multi-core|workers=|Tester|cow|pig|joined|left|fell|Exception|ERROR|Unknown|Invalid"
}

$summary = @()
$summary += "READY=$ready"
$summary += "EXITED=$($proc.HasExited)"
$summary += "----- RELEVANT -----"
$summary += $bsc
$summary += "----- EXCEPTIONS -----"
$summary += $lines | Where-Object { $_ -match "Exception|ERROR" } | Select-Object -First 50

Set-Content -Path $resultFile -Value ($summary -join "`n") -Encoding UTF8

if (-not $proc.HasExited) {
    try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
    $stopDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $stopDeadline -and -not $proc.HasExited) { Start-Sleep -Milliseconds 400 }
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
}
Unregister-Event -SourceIdentifier $outEvent.Name -ErrorAction SilentlyContinue
Unregister-Event -SourceIdentifier $errEvent.Name -ErrorAction SilentlyContinue
$outWriter.Close(); $errWriter.Close()

Write-Host "=== TEST RESULT ==="
Get-Content $resultFile
