# LogicSugar 功能展厅地图生成脚本
#
# 用法（在 tools/maplab 目录下）：
#   powershell -ExecutionPolicy Bypass -File build.ps1              # 编译 + 生成地图
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Check       # 只编译自检
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Dump for    # 打印某个展台的编译分层
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Install     # 生成后复制进游戏 maps 目录
#
# 依赖：JDK 17+、一个 Mindustry 桌面 jar（默认用本机安装的客户端）、编译好的 LogicSugar 类。
#
# 目标 jar 决定地图的存档格式，详见 README「目标游戏 jar 为什么重要」：
# 新 reader 认老格式，老 reader 不认新格式，所以默认挑最老的那个（本机客户端）来写。
param(
    [string]$MindustryJar = "",
    [string]$LogicSugarClasses = "..\..\build\classes\java\main",
    [string]$Out = "out\LogicSugar-Lab.msav",
    [switch]$Check,
    [string]$Dump = "",
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if([string]::IsNullOrWhiteSpace($MindustryJar)){
    $candidates = New-Object System.Collections.ArrayList
    if(${env:ProgramFiles(x86)}){ [void]$candidates.Add((Join-Path ${env:ProgramFiles(x86)} "Steam\steamapps\common\Mindustry\jre\desktop.jar")) }
    if($env:ProgramFiles){ [void]$candidates.Add((Join-Path $env:ProgramFiles "Steam\steamapps\common\Mindustry\jre\desktop.jar")) }
    [void]$candidates.Add("..\..\..\Mindustry-master\desktop\build\libs\Mindustry.jar")

    foreach($candidate in $candidates){
        if(Test-Path $candidate){
            $MindustryJar = $candidate
            break
        }
    }
}

if([string]::IsNullOrWhiteSpace($MindustryJar) -or -not (Test-Path $MindustryJar)){
    throw "找不到可用的游戏 jar，请用 -MindustryJar <path> 指定"
}

$MindustryJar = (Resolve-Path $MindustryJar).Path
$LogicSugarClasses = (Resolve-Path $LogicSugarClasses).Path
$classpath = "$MindustryJar;$LogicSugarClasses"

Write-Host "目标游戏 jar: $MindustryJar"
Write-Host "LogicSugar 类: $LogicSugarClasses"

New-Item -ItemType Directory -Force -Path "out" | Out-Null
New-Item -ItemType Directory -Force -Path "out\classes" | Out-Null

$sources = Get-ChildItem -Path "src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "javac: $($sources.Count) 个源文件"
& javac -encoding UTF-8 --release 17 -cp $classpath -d "out\classes" $sources
if($LASTEXITCODE -ne 0){ throw "javac failed" }

$arguments = @("-Dfile.encoding=UTF-8", "-cp", "out\classes;$classpath", "lab.Lab",
    "--demos", (Join-Path $root "demos"), "--out", (Join-Path $root $Out))
if($Check){ $arguments += "--check" }
if($Dump -ne ""){ $arguments += @("--dump", $Dump) }
if($Install){ $arguments += "--install" }

& java @arguments
if($LASTEXITCODE -ne 0){ throw "map generation failed" }
