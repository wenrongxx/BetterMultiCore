$ErrorActionPreference = "Continue"
$server = "O:\Xiaomi\workspace\mc_better_single_core\test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$resultFile = Join-Path $server "test_result.txt"
$stdout = Join-Path $server "logs\stdout.log"
$stderr = Join-Path $server "logs\stderr.log"
Remove-Item $resultFile, $stdout, $stderr -Force -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*server.jar*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
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
$outWriter.AutoFlush = $true
$errWriter = [System.IO.StreamWriter]::new($stderr, $true, [System.Text.UTF8Encoding]::new($false))
$errWriter.AutoFlush = $true
$outEvent = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
  if ($null -ne $EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $outWriter
$proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()
function Send-Cmd([string]$c) {
  if ($proc.HasExited) { return }
  try { $proc.StandardInput.WriteLine($c); $proc.StandardInput.Flush() } catch {}
}
function Read-Log { if (Test-Path $stdout) { Get-Content $stdout -Raw -EA SilentlyContinue } else { "" } }
$deadline = (Get-Date).AddSeconds(180)
$ready = $false
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 400
  if ($proc.HasExited) { break }
  $t = Read-Log
  if ($t -match "Done \(\d+") { $ready = $true; break }
}
Start-Sleep -Seconds 3
Send-Cmd "bot create Tester Steve minecraft:overworld 512 -50 512"
Start-Sleep -Seconds 4
Send-Cmd "function bsc_stress:storm"
# wait for yield to engage (need 3 ticks > 2ms)
Start-Sleep -Seconds 8
Send-Cmd "bsc adaptive"
Start-Sleep -Seconds 1
Send-Cmd "bsc status"
Start-Sleep -Seconds 2
# stop storm
$stopFn = Join-Path $server "world\datapacks\bsc_stress\data\bsc_stress\function\storm.mcfunction"
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($stopFn, "# stopped`n", $enc)
Send-Cmd "reload"
Start-Sleep -Seconds 12
Send-Cmd "bsc adaptive"
Start-Sleep -Seconds 1
Send-Cmd "bsc status"
Start-Sleep -Seconds 1
$log = Read-Log
$lines = $log -split "`r?`n"
$rel = $lines | Where-Object { $_ -match "yield|Yield|BSC|Preload|PathWarm|Tester|storm|Exception|ERROR" }
$summary = @("READY=$ready", "----- RELEVANT -----") + $rel + @("----- EXCEPTIONS -----") + ($lines | Where-Object { $_ -match "Exception" } | Select-Object -First 20)
Set-Content -Path $resultFile -Value ($summary -join "`n") -Encoding UTF8
if (-not $proc.HasExited) {
  try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
  Start-Sleep -Seconds 20
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
Unregister-Event -SourceIdentifier $outEvent.Name -EA SilentlyContinue
$outWriter.Close(); $errWriter.Close()
Write-Host "=== YIELD TEST ==="
Get-Content $resultFile