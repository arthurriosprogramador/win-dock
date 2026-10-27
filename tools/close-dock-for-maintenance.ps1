# Embedded in the MSI custom action; no installed script or Java runtime is required.
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DockMaintenance {
    public delegate bool EnumWindowsProc(IntPtr window, IntPtr parameter);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr SendMessageTimeout(
        IntPtr window, uint message, UIntPtr wParam, IntPtr lParam, uint flags, uint timeout, out UIntPtr result);
}
'@

$session = (Get-Process -Id $PID).SessionId
$processes = @(Get-Process -Name 'WinDock' -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -eq $session })
foreach ($dockProcess in $processes) {
    try { $null = $dockProcess.Handle } catch { continue }
    $dockProcessId = $dockProcess.Id
    $windows = [Collections.Generic.List[IntPtr]]::new()
    [DockMaintenance]::EnumWindows({
        param($window, $parameter)
        [uint32]$ownerId = 0
        [void][DockMaintenance]::GetWindowThreadProcessId($window, [ref]$ownerId)
        if ($ownerId -eq $dockProcessId) { $windows.Add($window) }
        return $true
    }, [IntPtr]::Zero) | Out-Null
    foreach ($window in $windows) {
        if ($dockProcess.HasExited) { break }
        [UIntPtr]$answer = [UIntPtr]::Zero
        # ENDSESSION_CLOSEAPP asks AWT to shut down the JVM normally, including the
        # shutdown hook that restores the Windows taskbar. Never force-kill it.
        $sent = [DockMaintenance]::SendMessageTimeout($window, 0x0011, [UIntPtr]::Zero, [IntPtr]1, 2, 1000, [ref]$answer)
        if ($sent -ne [IntPtr]::Zero -and $answer -ne [UIntPtr]::Zero) {
            [void][DockMaintenance]::SendMessageTimeout($window, 0x0016, [UIntPtr]1, [IntPtr]1, 2, 1000, [ref]$answer)
        }
    }
    if (!$dockProcess.WaitForExit(20000)) {
        # Let Windows Installer handle remaining locks and, if necessary, a reboot.
        Write-Warning 'O WinDock ainda esta em execucao.'
    }
    $dockProcess.Dispose()
}
