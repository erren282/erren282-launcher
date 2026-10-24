# 运行 tools 下的端到端测试（编译并执行）
# 用法：
#   .\tools\run-test.ps1                          # 运行 LunarDownloadTest（默认 1.8.9）
#   .\tools\run-test.ps1 -Class LunarVersionTest
#   .\tools\run-test.ps1 -Args 1.21.11
param(
    [string]$Class = "LunarDownloadTest",
    [string]$TestArgs = "",
    [string]$HMCLJar = "",
    [string]$Java = "",
    [string]$JavaFXDir = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if (-not $HMCLJar) {
    $candidates = Get-ChildItem -Path (Join-Path $root "..\..\..\HMCL\build\libs") -Filter "HMCL*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources*" } | Sort-Object LastWriteTime -Descending
    if (-not $candidates) { Write-Error "找不到 HMCL jar"; exit 1 }
    $HMCLJar = $candidates[0].FullName
}

$fxJars = @()
if ($JavaFXDir) {
    $fxJars = Get-ChildItem -Path $JavaFXDir -Filter "*.jar" | Select-Object -ExpandProperty FullName
} else {
    $cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.openjfx"
    if (Test-Path $cache) {
        $fxJars = Get-ChildItem -Path $cache -Recurse -Filter "*-win.jar" | Select-Object -ExpandProperty FullName
    }
}

if (-not $Java) {
    $zulu = "E:\ZULU21\bin\java.exe"
    $Java = if (Test-Path $zulu) { $zulu } else { "java" }
}

$outDir = Join-Path $root "out"
if (-not (Test-Path $outDir)) {
    Write-Error "请先运行 build.ps1 编译插件"
    exit 1
}
$testOut = Join-Path $root "out-test"
if (Test-Path $testOut) { Remove-Item -Recurse -Force $testOut }
New-Item -ItemType Directory -Path $testOut | Out-Null

# resources 也要在 classpath 上：插件运行时它们是 jar 里的资源（例如 wrapper/lunar-wrapper.jar）
$resourcesDir = Join-Path $root "resources"
$classpath = (@($HMCLJar) + $fxJars + @($outDir) + @($resourcesDir)) -join ";"
Write-Host "编译测试类 $Class ..."
javac -encoding UTF-8 --release 17 -cp $classpath -d $testOut (Join-Path $root "tools\$Class.java")
if ($LASTEXITCODE -ne 0) { Write-Error "测试编译失败"; exit 1 }

$runCp = $classpath + ";" + $testOut
Write-Host "运行 $Class $TestArgs（Java: $Java）"
& $Java "-Dfile.encoding=UTF-8" -cp $runCp $Class $TestArgs.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
exit $LASTEXITCODE
