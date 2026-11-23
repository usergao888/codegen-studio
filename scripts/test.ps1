# 跑测试（92 个 JUnit 5 用例）
#
# 为什么不用 mvn test：本机 ~/.m2 缺 maven-surefire-plugin 的依赖链，离线跑不起来。
# 这里用 TestMain 反射直接调 @Test 方法，断言仍是 JUnit 的；换到有网的机器上 mvn test 一样能跑。
. (Join-Path $PSScriptRoot "use-jdk.ps1")
. (Join-Path $PSScriptRoot "lib.ps1")

$root = Get-ProjectRoot
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

Push-Location $root
mvn -o -B -q "-Dmaven.legacyLocalRepo=true" test-compile
$compileCode = $LASTEXITCODE
Pop-Location

if ($compileCode -ne 0) {
    Write-Host "测试代码编译失败" -ForegroundColor Red
    exit $compileCode
}

$m2 = Join-Path $env:USERPROFILE ".m2\repository"
$junit = @(
    (Join-Path $m2 "org\junit\jupiter\junit-jupiter-api\5.12.2\junit-jupiter-api-5.12.2.jar"),
    (Join-Path $m2 "org\junit\platform\junit-platform-commons\1.12.2\junit-platform-commons-1.12.2.jar"),
    (Join-Path $m2 "org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar")
)
$guard = Get-ChildItem -LiteralPath (Join-Path $m2 "org\apiguardian\apiguardian-api") -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($guard) { $junit += $guard.FullName }

$cp = (Join-Path $root "target\test-classes") + ";" +
      (Join-Path $root "target\classes") + ";" +
      (Get-StudioClasspath -ProjectRoot $root) + ";" +
      ($junit -join ";")

& "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" -cp $cp com.gaoyc.studio.TestMain
$code = $LASTEXITCODE
if ($code -eq 0) { Write-Host "`n全部通过" -ForegroundColor Green } else { Write-Host "`n有失败用例" -ForegroundColor Red }
exit $code
