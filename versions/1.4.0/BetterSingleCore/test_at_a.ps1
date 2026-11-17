$ErrorActionPreference = "Continue"
$server = "O:\Xiaomi\workspace\mc_better_single_core\test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$stdout = Join-Path $server "logs\stdout.log"
Remove-Item $stdout -Force -EA SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*server.jar*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue }
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
$proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()
function Send-Cmd([string]$c) {
  if ($proc.HasExited) { return }
  try { $proc.StandardInput.WriteLine($c); $proc.StandardInput.Flush(); Write-Host ">> $c" } catch {}
}
function Read-Log { if (Test-Path $stdout) { Get-Content $stdout -Raw -EA SilentlyContinue } else { "" } }
$dl = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $dl) {
  Start-Sleep -Milliseconds 400
  if ($proc.HasExited) { break }
  if ((Read-Log) -match "Done \(\d+") { break }
}
Start-Sleep -Seconds 3
Send-Cmd "bot create BotAlpha Steve minecraft:overworld 0 -57 0"
Start-Sleep -Seconds 3
Send-Cmd "bot list"
Start-Sleep -Seconds 1
Send-Cmd "execute as @a run say hello"
Start-Sleep -Seconds 1
Send-Cmd "particle minecraft:flame 0 -56 0 0.2 0.2 0.2 1 40 normal @a"
Start-Sleep -Seconds 1
Send-Cmd "list"
Start-Sleep -Seconds 1
$log = Read-Log
$lines = $log -split "?
" | Where-Object { $_ -match "Bot|player|particle|not visible|No player|hello|list|online|Incorrect" }
Write-Host "=== NO PLUGIN @a TEST ==="
$lines | Select-Object -Last 40
if (-not $proc.HasExited) {
  try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
  Start-Sleep -Seconds 10
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
Unregister-Event -SourceIdentifier $ev.Name -EA SilentlyContinue
$w.Close()