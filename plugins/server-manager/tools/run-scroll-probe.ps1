# Compile & run the empirical scroll probe for the three server plugins.
#   pwsh -File tools\run-scroll-probe.ps1
param(
    [string]$HMCLJar = "E:\game\gamea\mcl\HMCL-test\HMCL-3.16.4.jar",
    [string]$JavaFXDir = "E:\game\gamea\mcl\HMCL-test\_fx21",
    [string]$Java = "E:\ZULU21\bin\java.exe"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$pluginRoot = Split-Path -Parent $root      # docs\plugin

# the probe needs all three plugins on the classpath
$pluginOut = @(
    (Join-Path $pluginRoot "server-manager\out"),
    (Join-Path $pluginRoot "server-list\out"),
    (Join-Path $pluginRoot "server-hub\out")
)
foreach ($dir in $pluginOut) {
    if (-not (Test-Path $dir)) { Write-Error "缺少 $dir，请先运行对应插件的 build.ps1"; exit 1 }
}

$fxJars = Get-ChildItem -Path $JavaFXDir -Filter "*.jar" | Select-Object -ExpandProperty FullName
$classpath = (@($HMCLJar) + $fxJars + $pluginOut) -join ";"

$testOut = Join-Path $root "out-probe"
if (Test-Path $testOut) { Remove-Item -Recurse -Force $testOut }
New-Item -ItemType Directory -Path $testOut | Out-Null

Write-Host "编译 SmoothScrollProbe ..."
javac -encoding UTF-8 --release 17 -cp $classpath -d $testOut (Join-Path $root "tools\SmoothScrollProbe.java")
if ($LASTEXITCODE -ne 0) { Write-Error "探针编译失败"; exit 1 }

$runCp = $classpath + ";" + $testOut
Write-Host "运行 SmoothScrollProbe ..."
& $Java "-Dfile.encoding=UTF-8" -cp $runCp SmoothScrollProbe
exit $LASTEXITCODE
