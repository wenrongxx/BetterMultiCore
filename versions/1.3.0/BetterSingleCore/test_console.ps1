# BetterSingleCore test harness: start server, send console cmds, capture results, stop.
param(
    [int]$WaitReadySec = 120,
    [int]$AfterReadySec = 8
)
$ErrorActionPreference = "Continue"
$server = "O:\Xiaomi\workspace\mc_better_single_core\test_server"
$java = "N:\Minecraft\Java\Java21\bin\java.exe"
$resultFile = Join-Path $server "test_result.txt"
$stdout = Join-Path $server "logs\stdout.log"
$stderr = Join-Path $server "logs\stderr.log"
New-Item -ItemType Directory -Path (Join-Path $server "logs") -Force | Out-Null
Remove-Item $resultFile -Force -ErrorAction SilentlyContinue
Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue

# Kill leftover server
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

function Read-Log {
    if (Test-Path $stdout) { Get-Content $stdout -Raw -ErrorAction SilentlyContinue } else { "" }
}

# Wait for Done
$deadline = (Get-Date).AddSeconds($WaitReadySec)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    if ($proc.HasExited) { break }
    $t = Read-Log
    if ($t -match "Done \(\d+") { $ready = $true; break }
    if ($t -match "Failed to start|Encountered an unexpected exception") { break }
}
Start-Sleep -Seconds $AfterReadySec

$cmds = @(
    "bsc status",
    "bsc workers",
    "bsc preload",
    "bsc pathwarm",
    "bsc monitor",
    "bsc reload",
    "bsc status",
    "plugins"
)
foreach ($c in $cmds) {
    if ($proc.HasExited) { break }
    try {
        $proc.StandardInput.WriteLine($c)
        $proc.StandardInput.Flush()
    } catch {
        Add-Content -Path $resultFile -Value "CMD_FAIL $c : $_"
    }
    Start-Sleep -Seconds 1
}

# Let preload/pathwarm cycles run
Start-Sleep -Seconds 6

# Collect results
$log = Read-Log
$lines = $log -split "`r?`n"
$bsc = $lines | Where-Object { $_ -match "BetterSingleCore|BSC|bsc |Exception|ERROR.*[Bb]etter|PathWarm|Preload|Multi-core|workers=" }

$summary = @()
$summary += "READY=$ready"
$summary += "EXITED=$($proc.HasExited)"
$summary += "----- BSC LINES -----"
$summary += $bsc
$summary += "----- PLUGIN ENABLE -----"
$summary += $lines | Where-Object { $_ -match "Enabling BetterSingleCore|Loading server plugin|Initialized.*BetterSingleCore" }
$summary += "----- ERRORS -----"
$summary += $lines | Where-Object { $_ -match "Exception|ERROR" } | Select-Object -First 40

Set-Content -Path $resultFile -Value ($summary -join "`n") -Encoding UTF8

# Stop server cleanly
if (-not $proc.HasExited) {
    try {
        $proc.StandardInput.WriteLine("stop")
        $proc.StandardInput.Flush()
    } catch {}
    $stopDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $stopDeadline -and -not $proc.HasExited) {
        Start-Sleep -Milliseconds 500
    }
    if (-not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
}

Unregister-Event -SourceIdentifier $outEvent.Name -ErrorAction SilentlyContinue
Unregister-Event -SourceIdentifier $errEvent.Name -ErrorAction SilentlyContinue
$outWriter.Close(); $errWriter.Close()

Write-Host "=== TEST RESULT ==="
Get-Content $resultFile
