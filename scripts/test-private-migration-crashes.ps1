param(
    [Parameter(Mandatory = $true)][string]$Device,
    [Parameter(Mandatory = $true)][string]$PrivatePassword,
    [string]$Adb = 'D:\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
$testClass = 'com.nkls.nekovideo.components.helpers.PrivateVideoMigrationDeviceTest'
$runner = 'com.nkls.nekovideo.debug.test/androidx.test.runner.AndroidJUnitRunner'
$stages = @('COPY_PAYLOAD_WRITTEN', 'COPY_SYNCED', 'COPY_READY', 'SESSION_READY',
    'VIDEO_REPLACED', 'INDEX_SAVED', 'NEW_MARKER_SAVED', 'LEGACY_MARKER_REMOVED')
foreach ($stage in $stages) {
    $stopped = & $Adb -s $Device shell am instrument -w -e privatePassword $PrivatePassword -e stopAt $stage -e class "${testClass}#prepareAndStopMigrationProcess" $runner 2>&1
    if (($stopped -join "`n") -notmatch 'Process crashed|Process has crashed') {
        throw "The test process did not stop at $stage. $($stopped -join "`n")"
    }
    $resumed = & $Adb -s $Device shell am instrument -w -e privatePassword $PrivatePassword -e resumeMigration true -e class "${testClass}#resumeAfterProcessTermination" $runner 2>&1
    if (($resumed -join "`n") -notmatch 'OK \(1 test\)') {
        throw "Resume failed at $stage. $($resumed -join "`n")"
    }
    Write-Output "${stage}: process terminated; playback and resumed migration verified."
}
