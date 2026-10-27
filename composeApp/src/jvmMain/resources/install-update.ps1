param(
    [Parameter(Mandatory = $true)][int]$DockPid,
    [Parameter(Mandatory = $true)][string]$Installer,
    [Parameter(Mandatory = $true)][string]$Launcher,
    [Parameter(Mandatory = $true)][string]$Sha256,
    [Parameter(Mandatory = $true)][string]$ReadyFile
)
$ErrorActionPreference = 'Stop'
$updateFolder = Split-Path -Parent $Installer
$logFile = Join-Path $updateFolder 'installer.log'
$dockExited = $false
try {
    if (!(Test-Path -LiteralPath $Installer) -or !(Test-Path -LiteralPath $Launcher)) {
        throw 'Instalador ou aplicativo nao encontrado.'
    }
    # Hold the process handle before acknowledging readiness (no PID reuse race).
    $dockProcess = Get-Process -Id $DockPid -ErrorAction Stop
    $null = $dockProcess.Handle
    [IO.File]::WriteAllText($ReadyFile, 'ready')
    if (!$dockProcess.WaitForExit(90000)) { throw 'O WinDock nao encerrou a tempo. Instalacao cancelada.' }
    $dockExited = $true
    $hash = (Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash
    if ($hash -ne $Sha256) { throw 'O instalador foi alterado. A verificacao de integridade falhou.' }
    # The installer shows its own progress UI; never force a reboot.
    $arguments = @('/i', ('"' + $Installer + '"'), '/passive', '/norestart', 'MSIRESTARTMANAGERCONTROL=Disable', '/L*v', ('"' + $logFile + '"'))
    $msiexec = Join-Path $env:SystemRoot 'System32\msiexec.exe'
    $result = Start-Process -FilePath $msiexec -ArgumentList $arguments -Wait -PassThru -WindowStyle Normal
    if ($result.ExitCode -notin @(0, 3010)) {
        throw ('Windows Installer retornou o codigo ' + $result.ExitCode + '. Log: ' + $logFile)
    }
    [IO.File]::WriteAllText((Join-Path $updateFolder 'result.txt'), ('success: ' + $result.ExitCode))
    Start-Process -FilePath $Launcher -WindowStyle Normal
    Remove-Item -LiteralPath $Installer -Force -ErrorAction SilentlyContinue
    if ($result.ExitCode -eq 3010) {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show('Atualizacao instalada. O Windows solicitou uma reinicializacao; reinicie o computador quando puder.', 'WinDock') | Out-Null
    }
} catch {
    $message = $_.Exception.Message
    [IO.File]::WriteAllText((Join-Path $updateFolder 'result.txt'), ('error: ' + $message))
    # MSI rolls back a failed installation; reopen the existing dock when it is still available.
    if ($dockExited -and (Test-Path -LiteralPath $Launcher)) {
        Start-Process -FilePath $Launcher -WindowStyle Normal -ErrorAction SilentlyContinue
    }
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show(('Nao foi possivel concluir a atualizacao. ' + $message), 'WinDock') | Out-Null
    exit 1
}
