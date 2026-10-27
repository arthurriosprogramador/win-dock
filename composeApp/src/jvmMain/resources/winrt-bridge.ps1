# WinDock WinRT bridge.
# Windows PowerShell 5.1 can call WinRT, which the JVM can't easily do, so the dock runs this script as a
# child process. It watches the system media session (GlobalSystemMediaTransportControls) and prints one
# line per change on stdout, and answers location requests from the Windows location service. It reads
# commands from stdin and exits when stdin closes, i.e. when the dock goes away for any reason.
#
# Commands: toggle | next | previous | locate | taskbar-hidden <original state> | taskbar-restored
#
# Safety net: while the dock has the Windows taskbar hidden it says so ("taskbar-hidden"). If our stdin then
# closes without a "taskbar-restored" — the dock crashed or was killed — this script puts the taskbar back.
#
# Output lines (tab separated):
#   READY
#   MEDIA     none
#   MEDIA     <status> <app> <title> <artist> <positionMs> <durationMs> <updatedAtEpochMs> <canPrevious> <canNext>
#   ART       <base64 image, or empty>
#   LOCATION  <latitude> <longitude>   |   LOCATION denied   |   LOCATION unavailable

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

# Never wait forever: a player that is still loading can leave an operation pending for many seconds,
# which would freeze this loop and every command queued behind it.
function Await($operation, [Type]$resultType, [int]$timeoutMs = 2000) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (-not $task.Wait($timeoutMs)) { throw 'WinRT call timed out' }
    $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$null = [Windows.Devices.Geolocation.Geolocator, Windows.Devices.Geolocation, ContentType = WindowsRuntime]
$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propertiesType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$manager = Await ($managerType::RequestAsync()) $managerType

function Clean([string]$text) {
    if ($null -eq $text) { return '' }
    return ($text -replace "[`t`r`n]", ' ').Trim()
}

function Send([string]$line) {
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
}

$taskbarOriginal = $null

function Restore-Taskbar([int]$state) {
    Add-Type -Namespace WinDock -Name Taskbar -MemberDefinition @'
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int l, t, r, b; }
[StructLayout(LayoutKind.Sequential)] public struct APPBARDATA { public int cbSize; public IntPtr hWnd; public uint uCallbackMessage; public uint uEdge; public RECT rc; public IntPtr lParam; }
[DllImport("shell32.dll")] public static extern UIntPtr SHAppBarMessage(uint msg, ref APPBARDATA data);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string cls, string title);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int cmd);
'@
    $tray = [WinDock.Taskbar]::FindWindow('Shell_TrayWnd', $null)
    $data = New-Object WinDock.Taskbar+APPBARDATA
    $data.cbSize = [Runtime.InteropServices.Marshal]::SizeOf($data)
    $data.hWnd = $tray
    $data.lParam = [IntPtr]$state
    [void][WinDock.Taskbar]::SHAppBarMessage(10, [ref]$data)   # ABM_SETSTATE
    [void][WinDock.Taskbar]::ShowWindow($tray, 8)              # SW_SHOWNA
}

# City-level accuracy is all the weather needs; Wi-Fi positioning answers that in a second or two.
function Send-Location {
    try {
        $access = Await ([Windows.Devices.Geolocation.Geolocator]::RequestAccessAsync()) ([Windows.Devices.Geolocation.GeolocationAccessStatus]) 10000
        if ($access -ne [Windows.Devices.Geolocation.GeolocationAccessStatus]::Allowed) {
            Send "LOCATION`tdenied"
            return
        }
        $locator = New-Object Windows.Devices.Geolocation.Geolocator
        $locator.DesiredAccuracyInMeters = 3000
        $position = (Await ($locator.GetGeopositionAsync()) ([Windows.Devices.Geolocation.Geoposition]) 15000).Coordinate.Point.Position
        $invariant = [Globalization.CultureInfo]::InvariantCulture
        Send ("LOCATION`t" + $position.Latitude.ToString($invariant) + "`t" + $position.Longitude.ToString($invariant))
    } catch {
        Send "LOCATION`tunavailable"
    }
}

function Read-Artwork($reference) {
    if ($null -eq $reference) { return '' }
    try {
        $stream = Await ($reference.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType]) 2500
        $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
        $buffer = New-Object System.IO.MemoryStream
        $net.CopyTo($buffer)
        $net.Dispose()
        return [Convert]::ToBase64String($buffer.ToArray())
    } catch {
        return ''
    }
}

# [Console]::In is a synchronized reader whose ReadLineAsync actually blocks; a plain StreamReader over the
# raw stdin stream reads in the background, so the loop keeps polling while waiting for commands.
$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
$pendingCommand = $stdin.ReadLineAsync()
$lastState = ''
$lastTrack = $null
$artRetries = 0

# Most of PowerShell's footprint is start-up code that's never touched again. Collecting and then
# re-applying the working-set limits hands those pages back to Windows; whatever the loop still uses
# simply faults back in.
$self = [System.Diagnostics.Process]::GetCurrentProcess()
function Trim-Memory {
    [System.GC]::Collect()
    [System.GC]::WaitForPendingFinalizers()
    try { $self.MaxWorkingSet = $self.MaxWorkingSet } catch { }
}
Trim-Memory
$lastTrim = [DateTime]::UtcNow
Send 'READY'

while ($true) {
    # Commands from the dock; a null line means the dock closed our stdin, so we're done.
    if ($pendingCommand.IsCompleted) {
        $command = $pendingCommand.Result
        if ($null -eq $command) {
            if ($null -ne $taskbarOriginal) { try { Restore-Taskbar $taskbarOriginal } catch { } }
            break
        }
        try {
            $command = $command.Trim()
            if ($command -like 'taskbar-hidden *') {
                $taskbarOriginal = [int]($command.Substring(15))
            } elseif ($command -eq 'taskbar-restored') {
                $taskbarOriginal = $null
            } elseif ($command -eq 'locate') {
                Send-Location
            } else {
                $session = $manager.GetCurrentSession()
                if ($session) {
                    switch ($command) {
                        'toggle' { $null = Await ($session.TryTogglePlayPauseAsync()) ([bool]) }
                        'next' { $null = Await ($session.TrySkipNextAsync()) ([bool]) }
                        'previous' { $null = Await ($session.TrySkipPreviousAsync()) ([bool]) }
                    }
                }
            }
        } catch { }
        $pendingCommand = $stdin.ReadLineAsync()
        $lastState = ''  # report the result right away
    }

    try {
        $session = $manager.GetCurrentSession()
        if ($null -eq $session) {
            $state = "MEDIA`tnone"
            $track = ''
            $properties = $null
        } else {
            $properties = Await ($session.TryGetMediaPropertiesAsync()) $propertiesType
            $playback = $session.GetPlaybackInfo()
            $timeline = $session.GetTimelineProperties()
            $track = (Clean $session.SourceAppUserModelId) + '|' + (Clean $properties.Title) + '|' + (Clean $properties.Artist)
            $state = @(
                'MEDIA',
                $playback.PlaybackStatus,
                (Clean $session.SourceAppUserModelId),
                (Clean $properties.Title),
                (Clean $properties.Artist),
                [long]$timeline.Position.TotalMilliseconds,
                [long]$timeline.EndTime.TotalMilliseconds,
                $timeline.LastUpdatedTime.ToUnixTimeMilliseconds(),
                $playback.Controls.IsPreviousEnabled,
                $playback.Controls.IsNextEnabled
            ) -join "`t"
        }
        # Title and playback first, so the card reacts at once; cover art follows.
        if ($state -ne $lastState) {
            Send $state
            $lastState = $state
        }
        if ($track -ne $lastTrack) {
            # Cover art only travels when the track changes; it can be tens of kilobytes. Players often
            # publish it a moment after the title, so an empty result is retried for a few ticks.
            $art = if ($properties) { Read-Artwork $properties.Thumbnail } else { '' }
            Send ("ART`t" + $art)
            $lastTrack = $track
            $artRetries = if ($art -eq '' -and $properties) { 5 } else { 0 }
        } elseif ($artRetries -gt 0) {
            $artRetries--
            $art = Read-Artwork $properties.Thumbnail
            if ($art -ne '') {
                Send ("ART`t" + $art)
                $artRetries = 0
            }
        }
    } catch {
        # Sessions come and go while we query them; try again on the next tick.
    }
    if (([DateTime]::UtcNow - $lastTrim).TotalMinutes -ge 5) {
        Trim-Memory
        $lastTrim = [DateTime]::UtcNow
    }
    Start-Sleep -Milliseconds 600
}
