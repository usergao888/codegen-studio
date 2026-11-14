# 启动服务（http://127.0.0.1:18080）
. (Join-Path $PSScriptRoot "use-jdk.ps1")
. (Join-Path $PSScriptRoot "lib.ps1")

$root = Get-ProjectRoot
$classes = Join-Path $root "target\classes"
if (-not (Test-Path -LiteralPath $classes)) {
    Write-Host "还没编译，先跑 .\scripts\build.ps1" -ForegroundColor Red
    exit 1
}

$cp = "$classes;" + (Get-StudioClasspath -ProjectRoot $root)
Write-Host "classpath: $((($cp -split ';').Count) - 1) 个 jar" -ForegroundColor DarkGray

Push-Location $root
& "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" -cp $cp com.gaoyc.studio.StudioApplication
Pop-Location
