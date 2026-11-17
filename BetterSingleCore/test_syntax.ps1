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
Start-Sleep -Seconds 2
Send-Cmd "bot create GroundBot Steve minecraft:overworld 0 64 0"
Start-Sleep -Seconds 4

$cmds = @(
  "particle minecraft:flame 0 64 0",
  "particle minecraft:flame 0 64 0 0 0 0",
  "particle minecraft:flame 0 64 0 0 0 0 1",
  "particle minecraft:flame 0 64 0 0 0 0 1 10",
  "particle minecraft:flame 0 64 0 0 0 0 1 10 normal",
  "particle minecraft:flame 0 64 0 0 0 0 1 10 force",
  "particle minecraft:flame 0 64 0 0 0 0 1 10 normal GroundBot",
  "particle minecraft:flame 0 64 0 0 0 0 1 10 normal @a",
  "particle minecraft:flame 0 64 0 0 0 0 1 10 normal force GroundBot",
  "particle minecraft:flame 0 64 0 0 0 0 1 10 viewers GroundBot",
  "particle minecraft:flame 0 64 0 0 0 0 1 10 @a",
  "particle minecraft:flame ~ ~ ~ 0 0 0 1 10 normal viewers @a"
)
foreach ($c in $cmds) {
  Send-Cmd $c
  Start-Sleep -Seconds 1
}
$log = Read-Log
$lines = $log -split "`r?`n" | Where-Object { $_ -match "particle|Incorrect|not visible|Unknown|Usage|GroundBot|error|Error|commands\." }
Write-Host "=== SYNTAX PROBE ==="
# Get from GroundBot join onward
$idx = 0
$all = $log -split "`r?`n"
for ($i=0; $i -lt $all.Count; $i++) { if ($all[$i] -match "GroundBot joined") { $idx = $i; break } }
$all[$idx..([Math]::Min($idx+50, $all.Count-1))]

if (-not $proc.HasExited) {
  try { $proc.StandardInput.WriteLine("stop"); $proc.StandardInput.Flush() } catch {}
  Start-Sleep -Seconds 10
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
Unregister-Event -SourceIdentifier $ev.Name -EA SilentlyContinue
$w.Close()