param([Parameter(Mandatory = $true)][string]$Installer)
$ErrorActionPreference = 'Stop'
# Author the policy into the package so it also applies to Settings > Uninstall.
# Run before signing / hashing. Database edits invalidate an existing signature.
$maintenanceScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'close-dock-for-maintenance.ps1') -Raw
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($maintenanceScript))
$command = '"[SystemFolder]WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand ' + $encoded
$engine = New-Object -ComObject WindowsInstaller.Installer
$database = $engine.OpenDatabase((Resolve-Path -LiteralPath $Installer).Path, 1)
function Invoke-MsiSql([string]$Sql) {
    $view = $database.OpenView($Sql)
    try { $view.Execute() } finally { $view.Close(); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) }
}
try {
    # Disables Restart Manager's cross-application close/restart flow. Genuine
    # locks still use FilesInUse / deferred replacement after a user-chosen reboot.
    foreach ($property in @('MSIRESTARTMANAGERCONTROL', 'REBOOT')) {
        Invoke-MsiSql ('DELETE FROM `Property` WHERE `Property` = ' + "'$property'")
    }
    Invoke-MsiSql "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('MSIRESTARTMANAGERCONTROL', 'Disable')"
    Invoke-MsiSql "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('REBOOT', 'ReallySuppress')"
    Invoke-MsiSql "DELETE FROM ``InstallExecuteSequence`` WHERE ``Action`` = 'WinDockCloseForMaintenance'"
    Invoke-MsiSql "DELETE FROM ``CustomAction`` WHERE ``Action`` = 'WinDockCloseForMaintenance'"
    # Type 34: synchronous EXE, working directory from Directory table. Ignore a
    # helper failure (64) so an unavailable PowerShell never blocks uninstall.
    Invoke-MsiSql ("INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('WinDockCloseForMaintenance', 98, 'TARGETDIR', '" + $command + "')")
    # InstallValidate (1400) performs files-in-use detection. Installed covers
    # uninstall (including major-upgrade removal) and repair, but not first install.
    Invoke-MsiSql "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('WinDockCloseForMaintenance', 'Installed', 1399)"
    $database.Commit()
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($engine)
}
Write-Output "Politica de manutencao incorporada ao MSI: $Installer"
