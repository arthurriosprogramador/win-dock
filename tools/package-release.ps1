param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[1-9][0-9]*\.[0-9]+\.[0-9]+$')]
    [string]$Version
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & .\gradlew.bat ":composeApp:jvmTest" ":composeApp:packageMsi" "-PappVersion=$Version" "--console=plain"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao testar ou gerar o instalador.' }
    $source = Join-Path $projectRoot "composeApp\build\compose\binaries\main\msi\WinDock-$Version.msi"
    if (!(Test-Path -LiteralPath $source)) { throw "Instalador esperado nao encontrado: $source" }
    $destination = Join-Path $projectRoot "build\release\$Version"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $asset = Join-Path $destination 'WinDock.msi'
    Copy-Item -LiteralPath $source -Destination $asset -Force
    $hash = (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $destination 'WinDock.msi.sha256'), "$hash  WinDock.msi" + [Environment]::NewLine)
    Write-Output "Release v$Version preparada em $destination. Nenhum arquivo foi publicado."
} finally {
    Pop-Location
}
