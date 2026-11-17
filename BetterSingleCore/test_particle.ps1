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

# Spawn bot so realPlayers is non-empty (threshold=4 means we need >=4 viewers for parallel; with 1 bot sequential path is used - still validates correctness)
Send-Cmd "bot create Tester Steve minecraft:overworld 512 -50 512"
Start-Sleep -Seconds 5
Send-Cmd "bot list"
Start-Sleep -Seconds 1

# Create more bots to hit parallel threshold (4+)
Send-Cmd "bot create Tester2 Alex minecraft:overworld 514 -50 512"
Start-Sleep -Seconds 3
Send-Cmd "bot create Tester3 Steve minecraft:overworld 516 -50 514"
Start-Sleep -Seconds 3
Send-Cmd "bot create Tester4 Alex minecraft:overworld 518 -50 516"
Start-Sleep -Seconds 3
Send-Cmd "bot list"
Start-Sleep -Seconds 1

# Particle commands - vanilla semantics
Send-Cmd "particle minecraft:flame 512 -49 512 0.5 0.5 0.5 0 50"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:crit 512 -49 512 0.2 0.2 0.2 0.1 30 force"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:happy_villager 512 -49 512 0 0 0 0 5 viewers @a"
Start-Sleep -Seconds 1
Send-Cmd "execute as @a at @s run particle minecraft:flame ~ ~ ~ 0.3 0.3 0.3 0 20"
Start-Sleep -Seconds 2

# bsc status / particle
Send-Cmd "bsc status"
Start-Sleep -Seconds 1
Send-Cmd "bsc particle"
Start-Sleep -Seconds 1
Send-Cmd "bsc adaptive"
Start-Sleep -Seconds 1

# Run stress storm if present
if (Test-Path (Join-Path $server "world\datapacks\bsc_stress\data\bsc_stress\function\storm.mcfunction")) {
    Send-Cmd "function bsc_stress:storm"
    Start-Sleep -Seconds 6
    Send-Cmd "bsc status"
    Start-Sleep -Seconds 1
    # stop storm
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $server "world\datapacks\bsc_stress\data\bsc_stress\function\storm.mcfunction"), "# stopped`n", $enc)
    Send-Cmd "reload"
    Start-Sleep -Seconds 8
}

Send-Cmd "plugins"
Start-Sleep -Seconds 1

$log = Read-Log
$lines = $log -split "`r?`n"
$rel = $lines | Where-Object {
    $_ -match "BetterSingleCore|BSC|particle|Particle|Tester|SPREAD|highLoad|Exception|ERROR|commands.particle"
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