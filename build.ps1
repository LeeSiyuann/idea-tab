param(
    [string]$IdePath = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.4',
    [switch]$Verify,
    [switch]$RunIde
)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskResolvedIde = (Resolve-Path -LiteralPath $IdePath).Path
if (-not (Test-Path -LiteralPath (Join-Path $taskResolvedIde 'product-info.json'))) {
    throw 'IdePath 必须指向 IntelliJ IDEA 安装目录。'
}
$env:JAVA_HOME = Join-Path $taskResolvedIde 'jbr'
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.tools\gradle-home'
$taskArguments = @('--no-daemon', "-PlocalIdePath=$taskResolvedIde")
if ($RunIde) { $taskArguments += 'runIde' }
else {
    $taskArguments += @('test', 'buildPlugin', 'verifyPluginProjectConfiguration', 'verifyPluginStructure')
    if ($Verify) { $taskArguments += 'verifyPlugin' }
}
Push-Location -LiteralPath $taskRoot
try {
    & (Join-Path $taskRoot 'gradlew.bat') @taskArguments
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码：$LASTEXITCODE" }
} finally { Pop-Location }
