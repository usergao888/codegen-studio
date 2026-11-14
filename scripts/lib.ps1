# 公共函数：解析 Maven 依赖的真实 classpath。
#
# 为什么需要它：这台机器的 ~/.m2 缺 maven-dependency-plugin 和 spring-boot-maven-plugin
# 的依赖链，`mvn dependency:build-classpath` 和 `mvn spring-boot:run` 在离线模式下都用不了。
# 退而求其次：让 maven-compiler-plugin 在 -X 日志里把它解析好的 classpath 打出来，再抠出来用。
#
# 注意：java -version / javac -version 会往 stderr 写东西，PowerShell 5.1 在 Stop 模式下
# 会把任何 stderr 当成终止错误，所以这里统一用 Continue。

$ErrorActionPreference = "Continue"

function Get-ProjectRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Get-StudioClasspath {
    param([string]$ProjectRoot, [switch]$Force)

    $cacheFile = Join-Path $ProjectRoot "var\classpath.txt"
    if (-not $Force -and (Test-Path -LiteralPath $cacheFile)) {
        $cached = (Get-Content -LiteralPath $cacheFile -Raw).Trim()
        if ($cached) { return $cached }
    }

    Push-Location $ProjectRoot
    $debugLog = Join-Path $ProjectRoot "var\mvn-debug.log"
    New-Item -ItemType Directory -Path (Join-Path $ProjectRoot "var") -Force | Out-Null
    mvn -o -B -X "-Dmaven.legacyLocalRepo=true" compile *> $debugLog
    Pop-Location

    $line = Select-String -Path $debugLog -Pattern "\(f\) compilePath = \[" | Select-Object -First 1
    if (-not $line) {
        throw "没能从 Maven 日志里解析出 classpath，请先确认 'mvn -o compile' 能跑通"
    }
    $text = $line.Line
    $raw = $text.Substring($text.IndexOf("[") + 1, $text.LastIndexOf("]") - $text.IndexOf("[") - 1)
    $cp = (($raw -split ",\s*") | Where-Object { $_ -match "\.jar$" } | ForEach-Object { $_.Trim() }) -join ";"
    Set-Content -LiteralPath $cacheFile -Value $cp -Encoding ascii
    return $cp
}
