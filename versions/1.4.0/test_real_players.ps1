$ErrorActionPreference = "Continue"
$root = "O:\Xiaomi\workspace\mc_better_single_core"
$server = Join-Path $root "test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$node = $env:MIMO_NODE
$stdout = Join-Path $server "logs\stdout.log"
Remove-Item $stdout -Force -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*server.jar*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -like "*minimal_client*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
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
$w = [System.IO.StreamWriter]::new($stdout, $true, [System.Text.UTF8Encoding]::new($false))
$w.AutoFlush = $true
$ev = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $w
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()

function Send-Cmd([string]$c) {
    if ($proc.HasExited) { return }
    try {
        $proc.StandardInput.WriteLine($c)
        $proc.StandardInput.Flush()
        Write-Host ">> $c"
    } catch {}
}
function Read-Log {
    if (Test-Path $stdout) { Get-Content $stdout -Raw -ErrorAction SilentlyContinue } else { "" }
}

$dl = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $dl) {
    Start-Sleep -Milliseconds 400
    if ($proc.HasExited) { break }
    if ((Read-Log) -match "Done \(\d+") { break }
}
Start-Sleep -Seconds 3
Write-Host "Starting clients..."
Start-Process -FilePath $node -ArgumentList "minimal_client.js" -WorkingDirectory (Join-Path $root "mc_clients") -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root "mc_clients\run_out.txt") -RedirectStandardError (Join-Path $root "mc_clients\run_err.txt")
Start-Sleep -Seconds 20
Write-Host "=== CLIENT OUT ==="
$co = Join-Path $root "mc_clients\run_out.txt"
if (Test-Path $co) { Get-Content $co }
Write-Host "=== CLIENT ERR ==="
$ce = Join-Path $root "mc_clients\run_err.txt"
if (Test-Path $ce) { Get-Content $ce }
Send-Cmd "list"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:flame 0 -59 0 0.2 0.2 0.2 1 40 normal @a"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:crit 0 -59 0 0.2 0.2 0.2 0.1 30 force @a"
Start-Sleep -Seconds 1
Send-Cmd "bsc particle"
Start-Sleep -Seconds 1
Send-Cmd "bsc status"
Start-Sleep -Seconds 1

$log = Read-Log
$all = $log -split "`r?`n"
$rel = $all | Where-Object { $_ -match "Particle|particle|not visible|Real|BSC|Incorrect|No player|Exception|ERROR|installed|players online|client|list" }
Write-Host "=== RESULT ==="
$rel | Select-Object -Last 50

if (-not $proc.HasExited) {
    try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
    Start-Sleep -Seconds 12
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -like "*minimal_client*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Unregister-Event -SourceIdentifier $ev.Name -ErrorAction SilentlyContinue
$w.Close()
