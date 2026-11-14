# 自动挑选本机版本最高的 JDK，并在「当前会话」设置 JAVA_HOME / PATH。
#
# 用法（必须 dot-source，前面那个点不能省，否则环境变量只影响子进程）：
#     . .\scripts\use-jdk.ps1            # 自动挑最高版本
#     . .\scripts\use-jdk.ps1 -Major 25  # 指定大版本
#     . .\scripts\use-jdk.ps1 -List      # 只列出，不切换
#
# 装新 JDK 之后（winget install -e --id EclipseAdoptium.Temurin.25.JDK）再跑一次即可。

[CmdletBinding()]
param(
    [int]$Major = 0,
    [switch]$List
)

# 注意：不能用 $ErrorActionPreference = "Stop"。
# java -version / javac -version 把版本信息写到 stderr，PowerShell 5.1 在 Stop 模式下
# 会把任何 stderr 输出当成终止错误，函数还没返回就被打断。
$ErrorActionPreference = "Continue"

function Get-JdkVersion {
    param([string]$JdkHome)
    $javaExe = Join-Path $JdkHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe)) { return $null }
    $raw = (& $javaExe -version 2>&1 | Out-String)
    # JDK 8 报的是 "1.8.0_181"，要取第二段；9+ 报的是 "21.0.9"
    if ($raw -match 'version "(?:1\.)?(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Find-Jdks {
    $roots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft\jdk",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        "C:\Program Files\BellSoft",
        "D:\Program Files",
        "D:\Program Files\Java",
        "D:\Java",
        $env:LOCALAPPDATA + "\Programs\Eclipse Adoptium"
    )

    $found = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME -and (Test-Path -LiteralPath $env:JAVA_HOME)) {
        $found.Add($env:JAVA_HOME)
    }

    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        if (Test-Path -LiteralPath (Join-Path $root "bin\javac.exe")) {
            $found.Add($root)
        }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            if (Test-Path -LiteralPath (Join-Path $_.FullName "bin\javac.exe")) {
                $found.Add($_.FullName)
            }
        }
    }

    # PATH 上的 java 也纳入候选（取它上两级目录）
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) {
        $pathHome = Split-Path (Split-Path $onPath.Source -Parent) -Parent
        if ($pathHome -and (Test-Path -LiteralPath (Join-Path $pathHome "bin\javac.exe"))) {
            $found.Add($pathHome)
        }
    }

    $seen = @{}
    $result = @()
    foreach ($path in $found) {
        $normalized = $path.TrimEnd('\')
        if ($seen.ContainsKey($normalized.ToLowerInvariant())) { continue }
        $seen[$normalized.ToLowerInvariant()] = $true
        $version = Get-JdkVersion -JdkHome $normalized
        if ($null -ne $version) {
            $result += [pscustomobject]@{
                Path    = $normalized
                Major   = $version
                Version = (& (Join-Path $normalized "bin\javac.exe") -version 2>&1 | Out-String).Trim()
            }
        }
    }
    return $result | Sort-Object -Property Major -Descending
}

$jdks = Find-Jdks
if (-not $jdks -or $jdks.Count -eq 0) {
    Write-Host "没找到任何 JDK（目录里没有 bin\javac.exe）。请先安装：winget install -e --id EclipseAdoptium.Temurin.25.JDK" -ForegroundColor Red
    return
}

Write-Host "`n检测到以下 JDK（按版本从高到低）：" -ForegroundColor Cyan
$jdks | ForEach-Object {
    Write-Host ("  [{0,2}] {1}" -f $_.Major, $_.Path)
}

if ($List) { Write-Host ""; return }

$target = if ($Major -gt 0) {
    $jdks | Where-Object { $_.Major -eq $Major } | Select-Object -First 1
} else {
    $jdks | Select-Object -First 1
}

if (-not $target) {
    Write-Host "`n没找到大版本 $Major 的 JDK。" -ForegroundColor Red
    return
}

$env:JAVA_HOME = $target.Path
$env:Path = "$($target.Path)\bin;" + ($env:Path -replace [regex]::Escape("$env:JAVA_HOME\bin;"), "")

Write-Host "`n已切换（仅当前会话生效）：" -ForegroundColor Green
Write-Host "  JAVA_HOME = $env:JAVA_HOME"
Write-Host "  $($target.Version)"
Write-Host "`n构建项目：mvn -o -B `"-Dmaven.legacyLocalRepo=true`" compile" -ForegroundColor DarkGray
