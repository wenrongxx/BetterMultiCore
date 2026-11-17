$ErrorActionPreference = "Continue"
$server = "O:\Xiaomi\workspace\mc_better_single_core\test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$resultFile = Join-Path $server "test_result.txt"
$stdout = Join-Path $server "logs\stdout.log"
New-Item -ItemType Directory -Path (Join-Path $server "logs") -Force | Out-Null
Remove-Item $resultFile, $stdout -Force -ErrorAction SilentlyContinue

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
$proc = [System.Diagnostics.Process]::Start($psi)
$outWriter = [System.IO.StreamWriter]::new($stdout, $true, [System.Text.UTF8Encoding]::new($false))
$outWriter.AutoFlush = $true
$outEvent = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $outWriter
$proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()

function Send-Cmd([string]$c) {
    if ($proc.HasExited) { return }
    try { $proc.StandardInput.WriteLine($c); $proc.StandardInput.Flush(); Write-Host ">> $c" } catch {}
}
function Read-Log { if (Test-Path $stdout) { Get-Content $stdout -Raw -EA SilentlyContinue } else { "" } }

$deadline = (Get-Date).AddSeconds(180)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    if ($proc.HasExited) { break }
    if ((Read-Log) -match "Done \(\d+") { $ready = $true; break }
}
Start-Sleep -Seconds 3

# Platform at y=64
Send-Cmd "fill 0 64 0 16 64 16 minecraft:stone"
Start-Sleep -Seconds 1

# Spawn bots on platform
Send-Cmd "bot create BotOne Steve minecraft:overworld 4 65 4"
Start-Sleep -Seconds 3
Send-Cmd "bot create BotTwo Alex minecraft:overworld 6 65 4"
Start-Sleep -Seconds 3
Send-Cmd "bot create BotThree Steve minecraft:overworld 4 65 6"
Start-Sleep -Seconds 3
Send-Cmd "bot create BotFour Alex minecraft:overworld 6 65 6"
Start-Sleep -Seconds 3
Send-Cmd "bot list"
Start-Sleep -Seconds 1

# Test particle syntax step by step
Send-Cmd "particle minecraft:flame 4 66 4"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:flame 4 66 4 0.5 0.5 0.5"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:flame 4 66 4 0.5 0.5 0.5 0 20"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:flame 4 66 4 0.5 0.5 0.5 0 20 normal"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:flame 4 66 4 0.5 0.5 0.5 0 20 normal viewers @a"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:flame 4 66 4 0.5 0.5 0.5 0 20 force viewers @a"
Start-Sleep -Seconds 2

Send-Cmd "bsc particle"
Start-Sleep -Seconds 1

$log = Read-Log
$lines = $log -split "`r?`n"
$rel = $lines | Where-Object { $_ -match "Particle|particle|not visible|Bot|BSC|Incorrect|Unknown|Exception|ERROR|commands.particle|stone|fill" }
$summary = @("READY=$ready", "----- RELEVANT -----") + $rel
Set-Content -Path $resultFile -Value ($summary -join "`n") -Encoding UTF8

if (-not $proc.HasExited) {
    try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
    Start-Sleep -Seconds 12
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
Unregister-Event -SourceIdentifier $outEvent.Name -EA SilentlyContinue
$outWriter.Close()
Write-Host "=== RESULT ==="
Get-Content $resultFile