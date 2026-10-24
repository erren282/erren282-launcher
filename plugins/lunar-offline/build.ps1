# 插件打包脚本（Windows）
# 用法：
#   .\build.ps1                                    # 自动在本仓库 HMCL/build/libs 找 HMCL jar
#   .\build.ps1 -HMCLJar C:\path\to\HMCL-3.16.4.jar
#   .\build.ps1 -JavaFXDir C:\path\to\javafx\lib   # 编译用了 JavaFX 的插件时手动指定
# 产物：dist\<identifier>_<version>.jar（直接放入 .hmcl\plugins 文件夹即可）
param(
    [string]$HMCLJar = "",
    [string]$JavaFXDir = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# 1. 定位 HMCL jar（插件代码编译时的 classpath）
if (-not $HMCLJar) {
    $candidates = Get-ChildItem -Path (Join-Path $root "..\..\..\HMCL\build\libs") -Filter "HMCL*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources*" } | Sort-Object LastWriteTime -Descending
    if (-not $candidates) {
        Write-Error "找不到 HMCL jar。请先用 gradle 构建，或通过 -HMCLJar 指定路径。"
        exit 1
    }
    $HMCLJar = $candidates[0].FullName
}
Write-Host "使用 HMCL jar: $HMCLJar"

# 2. 收集 JavaFX jar（启动器 fat jar 不内嵌 JavaFX；默认从 Gradle 缓存发现）
$fxJars = @()
if ($JavaFXDir) {
    $fxJars = Get-ChildItem -Path $JavaFXDir -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
} else {
    $gradleFxCache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.openjfx"
    if (Test-Path $gradleFxCache) {
        $fxJars = Get-ChildItem -Path $gradleFxCache -Recurse -Filter "*-win.jar" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    }
}
if ($fxJars.Count -gt 0) {
    Write-Host ("使用 JavaFX: " + (($fxJars | ForEach-Object { Split-Path -Leaf $_ }) -join ", "))
}

# 3. 编译
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    Write-Error "找不到 javac。请安装 JDK 17+ 并加入 PATH，或设置 JAVA_HOME。"
    exit 1
}
$outDir = Join-Path $root "out"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Path $outDir | Out-Null
$classpath = (@($HMCLJar) + $fxJars) -join ";"
javac -encoding UTF-8 --release 17 -cp $classpath -d $outDir (Get-ChildItem -Path (Join-Path $root "src") -Filter "*.java" -Recurse).FullName
if ($LASTEXITCODE -ne 0) { Write-Error "编译失败"; exit 1 }

# 3b. 编译 Lunar 版本的入口包装类，并把它打成一个“库” jar 放进 resources/
#     （游戏进程的 classpath 由版本的 libraries 决定，拿不到插件 jar 里的类，所以必须单独出一个 jar）
$wrapperSrc = Join-Path $root "wrapper-src"
$authSrc = Join-Path $root 'src/org/dsh/lunarofl/auth'
if (Test-Path $wrapperSrc) {
    $wrapperOut = Join-Path $root "out-wrapper"
    if (Test-Path $wrapperOut) { Remove-Item -Recurse -Force $wrapperOut }
    New-Item -ItemType Directory -Path $wrapperOut | Out-Null
    # 包装类依赖 auth 包（会话刷新 + gameipc 服务），一并编进包装 jar。
    # 但 LunarAccountBridge 是**插件侧**的桥（要读 HMCL 的 MicrosoftAccount / Accounts），
    # 游戏进程的 classpath 里没有启动器的类，所以必须排除，否则这里编译不过。
    $wrapperSources = @((Get-ChildItem -Path $wrapperSrc -Filter "*.java" -Recurse).FullName)
    if (Test-Path $authSrc) {
        $wrapperSources += (Get-ChildItem -Path $authSrc -Filter "*.java" |
            Where-Object { $_.Name -ne "LunarAccountBridge.java" }).FullName
    }
    javac -encoding UTF-8 --release 17 -d $wrapperOut $wrapperSources
    if ($LASTEXITCODE -ne 0) { Write-Error "包装类编译失败"; exit 1 }
    $wrapperResDir = Join-Path $root "resources\wrapper"
    New-Item -ItemType Directory -Force -Path $wrapperResDir | Out-Null
    $wrapperJar = Join-Path $wrapperResDir "lunar-wrapper.jar"
    if (Test-Path $wrapperJar) { Remove-Item -Force $wrapperJar }
    & jar --create --file $wrapperJar -C $wrapperOut .
    if ($LASTEXITCODE -ne 0) { Write-Error "包装类打包失败"; exit 1 }
    Write-Host "已生成入口包装库: $wrapperJar"
}

# 4. 打 jar：hmcl.plugin.json 必须位于 jar 根目录
$manifest = Get-Content (Join-Path $root "hmcl.plugin.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$distDir = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$jarFile = Join-Path $distDir ("{0}_{1}.jar" -f $manifest.id, $manifest.version)

$jarCmd = Get-Command jar -ErrorAction SilentlyContinue
if ($jarCmd) {
    $resourcesArg = @()
    if (Test-Path (Join-Path $root "resources")) {
        # 把 resources 目录下的内容原样放进 jar 根（servers.json、celestial-core/ 等）
        $resourcesArg = @("-C", (Join-Path $root "resources"), ".")
    }
    & jar --create --file $jarFile -C $root hmcl.plugin.json -C $outDir . @resourcesArg
} else {
    # 没有 jar 工具时用 .NET 压缩（zip 与 jar 格式兼容）
    $staging = Join-Path $env:TEMP ("hmcl-plugin-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $staging | Out-Null
    Copy-Item (Join-Path $root "hmcl.plugin.json") $staging
    Copy-Item -Recurse (Join-Path $outDir "*") $staging
    if (Test-Path (Join-Path $root "resources\servers.json")) {
        Copy-Item (Join-Path $root "resources\servers.json") (Join-Path $staging "servers.json")
    }
    Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $jarFile -Force
    Remove-Item -Recurse -Force $staging
}

Write-Host "已生成插件包: $jarFile"
Write-Host "安装：把它放入 .hmcl\plugins 文件夹，然后重启启动器或在 设置→插件 里点击“重新加载插件”。"
