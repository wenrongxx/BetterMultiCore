# BetterMultiCore build (javac + jar, no Maven)
param(
    [string]$Version = "1.0.0"
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$api = Join-Path $root "..\_jar_inspect\leaves-api.jar"
$libs = Join-Path $root "..\_jar_inspect\libs"
$serverJar = Join-Path $root "..\test_server\versions\1.21.11\leaves-1.21.11.jar"
$brigadier = Join-Path $root "..\test_server\libraries\com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar"
$mojangLibs = Get-ChildItem -Path (Join-Path $root "..\test_server\libraries\com\mojang") -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName }
$src = Join-Path $root "src\main\java"
$res = Join-Path $root "src\main\resources"
$out = Join-Path $root "out"
$dist = Join-Path $root "dist"
$jarPath = Join-Path $dist "BetterMultiCore-$Version.jar"

if (-not (Test-Path $api)) { throw "missing $api" }
if (-not (Test-Path $serverJar)) { throw "missing $serverJar" }
if (-not (Test-Path $brigadier)) { throw "missing $brigadier" }

$extra = @()
if (Test-Path $libs) {
    $extra = Get-ChildItem -Path $libs -Recurse -Filter *.jar |
        Where-Object { $_.FullName -match 'adventure|bungeecord|examination|option|guava|gson|fastutil|jsr305' } |
        ForEach-Object { $_.FullName }
}
# NMS + brigadier + mojang libs first so plugin remap-time types resolve
$cp = (@($serverJar, $brigadier) + $mojangLibs + @($api) + $extra) -join ';'

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out, $dist -Force | Out-Null

$resp = Join-Path $root "javac_args.txt"
$sources = Get-ChildItem -Path $src -Recurse -Filter *.java | ForEach-Object { $_.FullName.Replace('\', '/') }
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("--release")
[void]$sb.AppendLine("21")
[void]$sb.AppendLine("-cp")
[void]$sb.AppendLine($cp.Replace('\', '/'))
[void]$sb.AppendLine("-d")
[void]$sb.AppendLine($out.Replace('\', '/'))
foreach ($s in $sources) { [void]$sb.AppendLine($s) }
[System.IO.File]::WriteAllText($resp, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))

& javac "@$resp"
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

Copy-Item -Path (Join-Path $res "*") -Destination $out -Recurse -Force
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& jar --create --file $jarPath -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }

Write-Host "Built: $jarPath ($((Get-Item $jarPath).Length) bytes)"
