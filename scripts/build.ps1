# 编译（离线可用）
. (Join-Path $PSScriptRoot "use-jdk.ps1")
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

Push-Location $root
mvn -o -B "-Dmaven.legacyLocalRepo=true" compile
$code = $LASTEXITCODE
Pop-Location

if ($code -eq 0) { Write-Host "`n编译成功：target/classes" -ForegroundColor Green }
else { Write-Host "`n编译失败（exit=$code）" -ForegroundColor Red }
exit $code
