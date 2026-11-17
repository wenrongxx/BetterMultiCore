# v1.3: modules never pause; highLoad only shrinks main-thread drain.
$ErrorActionPreference = "Continue"
$server = "O:\Xiaomi\workspace\mc_better_single_core\test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$resultFile = Join-Path $server "test_result.txt"
$stdout = Join-Path $server "logs\stdout.log"
$stderr = Join-Path $server "logs\stderr.log"
New-Item -ItemType Directory -Path (Join-Path $server "logs") -Force | Out-Null
Remove-Item $resultFile, $stdout, $stderr -Force -ErrorAction SilentlyContinue

# Seed adaptive thresholds for test: lower high-load so storm can trigger
$cfgPath = Join-Path $server "plugins\BetterSingleCore\config.yml"
if (Test-Path $cfgPath) {
    $cfg = Get-Content $cfgPath -Raw -Encoding UTF8
    $cfg = $cfg -replace 'high-load-mspt:\s*[\d.]+', 'high-load-mspt: 5.0'
    $cfg = $cfg -replace 'normal-mspt:\s*[\d.]+', 'normal-mspt: 3.0'
    $cfg = $cfg -replace 'high-load-after-ticks:\s*\d+', 'high-load-after-ticks: 3'
    $cfg = $cfg -replace 'normal-after-ticks:\s*\d+', 'normal-after-ticks: 15'
    $cfg = $cfg -replace 'report-interval-ticks:\s*\d+', 'report-interval-ticks: 100'
    $cfg = $cfg -replace 'generate-if-missing:\s*false', 'generate-if-missing: true'
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($cfgPath, $cfg, $enc)
}

Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -like "*server.jar*"
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
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

$deadline = (Get-Date).AddSeconds(180)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    if ($proc.HasExited) { break }
    $t = Read-Log
    if ($t -match "Done \(\d+") { $ready = $true; break }
    if ($t -match "Failed to start|Encountered an unexpected exception") { break }
}
Start-Sleep -Seconds 4
Add-Content -Path $resultFile -Value "READY=$ready"

Send-Cmd "bsc status"
Start-Sleep -Seconds 1
Send-Cmd "bsc adaptive"
Start-Sleep -Seconds 1

Send-Cmd "bot create Tester Steve minecraft:overworld 512 -50 512"
Start-Sleep -Seconds 5
Send-Cmd "bot list"
Start-Sleep -Seconds 1

# Idle baseline with player
Start-Sleep -Seconds 4
Send-Cmd "bsc status"
Start-Sleep -Seconds 1
Send-Cmd "bsc preload"
Start-Sleep -Seconds 1
Send-Cmd "bsc pathwarm"
Start-Sleep -Seconds 1

# Command storm
$stormExists = Test-Path (Join-Path $server "world\datapacks\bsc_stress\data\bsc_stress\function\storm.mcfunction")
if ($stormExists) {
    Send-Cmd "function bsc_stress:storm"
} else {
    Send-Cmd "say storm pack missing"
}
Start-Sleep -Seconds 8
Send-Cmd "bsc adaptive"
Start-Sleep -Seconds 1
Send-Cmd "bsc status"
Start-Sleep -Seconds 1
Send-Cmd "bsc preload"
Start-Sleep -Seconds 1
Send-Cmd "bsc pathwarm"
Start-Sleep -Seconds 2

# Critical: during highLoad, preload/pathwarm must still show growing counters (not paused)
$midLog = Read-Log
if ($midLog -match "highLoad=true" -or $midLog -match "High-load SPREAD mode ON") {
    Add-Content -Path $resultFile -Value "HIGHLOAD_OBSERVED"
} else {
    Add-Content -Path $resultFile -Value "HIGHLOAD_NOT_OBSERVED"
}

# Stop storm
$stopFn = Join-Path $server "world\datapacks\bsc_stress\data\bsc_stress\function\storm.mcfunction"
if (Test-Path $stopFn) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($stopFn, "# stopped`n", $enc)
    Send-Cmd "reload"
    Start-Sleep -Seconds 10
}

Send-Cmd "bsc adaptive"
Start-Sleep -Seconds 1
Send-Cmd "bsc status"
Start-Sleep -Seconds 1
Send-Cmd "bsc preload"
Start-Sleep -Seconds 1
Send-Cmd "bsc pathwarm"
Start-Sleep -Seconds 1
Send-Cmd "plugins"
Start-Sleep -Seconds 1

$log = Read-Log
$lines = $log -split "`r?`n"
$rel = $lines | Where-Object {
    $_ -match "BetterSingleCore|BSC|highLoad|High-load|PathWarm|Preload|Multi-core|Tester|storm|Exception|ERROR|never pause|spread"
}

$summary = @()
$summary += "READY=$ready"
$summary += "EXITED=$($proc.HasExited)"
$summary += "----- RELEVANT -----"
$summary += $rel
$summary += "----- EXCEPTIONS -----"
$summary += $lines | Where-Object { $_ -match "Exception|ERROR" } | Select-Object -First 40

Set-Content -Path $resultFile -Value ($summary -join "`n") -Encoding UTF8

if (-not $proc.HasExited) {
    try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
    $stopDeadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $stopDeadline -and -not $proc.HasExited) { Start-Sleep -Milliseconds 400 }
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
}
Unregister-Event -SourceIdentifier $outEvent.Name -ErrorAction SilentlyContinue
Unregister-Event -SourceIdentifier $errEvent.Name -ErrorAction SilentlyContinue
$outWriter.Close(); $errWriter.Close()

Write-Host "=== TEST RESULT ==="
Get-Content $resultFile
