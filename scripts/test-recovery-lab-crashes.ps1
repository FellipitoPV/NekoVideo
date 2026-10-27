param(
    [string]$Adb = 'adb',
    [string[]]$Modes = @('protect', 'export', 'index', 'rotation')
)

$ErrorActionPreference = 'Stop'
$labPackage = 'com.nkls.nekovideo.recoverylab'
$labRoot = "/storage/emulated/0/Android/data/$labPackage/files/recovery-lab"
$testClass = 'com.nkls.nekovideo.recoverylab.CrashRecoveryLabDeviceTest'
$runner = "$labPackage.test/androidx.test.runner.AndroidJUnitRunner"
$adbPath = (Get-Command $Adb).Source
$logRoot = Join-Path $PSScriptRoot '../build/recovery-lab-crash-logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

foreach ($mode in $Modes) {
    if ($mode -notin @('protect', 'export', 'index', 'rotation')) { throw 'Unsupported crash mode' }
    $operationId = [guid]::NewGuid().ToString()
    $activeFile = "$labRoot/crash-active-$mode-$operationId.txt"
    $stdout = Join-Path $logRoot "$mode-$operationId.out.txt"
    $stderr = Join-Path $logRoot "$mode-$operationId.err.txt"
    $arguments = @('shell', 'am', 'instrument', '-w', '-e', 'class',
        "$testClass#waitForExternalForceStop", '-e', 'crashMode', $mode,
        '-e', 'operationId', $operationId, $runner)
    # This is an adb client for the test only; no visible helper window is needed.
    $process = Start-Process -FilePath $adbPath -ArgumentList $arguments -WindowStyle Hidden `
        -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $ready = $false
    $relative = ''
    while ($timer.ElapsedMilliseconds -lt 30000 -and -not $process.HasExited) {
        # Native stderr is expected until the new, uniquely named pointer has been created.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $candidate = (& $adbPath shell cat $activeFile 2>$null) -join ''
        $ErrorActionPreference = $previousPreference
        $candidate = $candidate.Trim()
        if ($candidate -match "^advanced/crash-$mode-[0-9a-f-]{36}$") {
            $relative = $candidate
            & $adbPath shell test -f "$labRoot/$relative/ready-for-force-stop"
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) {
        # Still stop only this test package before reporting a failed setup.
        & $adbPath shell am force-stop $labPackage
        $process.WaitForExit(10000) | Out-Null
        Get-Content -LiteralPath $stdout -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $stderr -ErrorAction SilentlyContinue
        throw "No synced checkpoint reached for $mode"
    }
    Write-Output "[$mode] Reached synced checkpoint in $relative; stopping laboratory process."
    & $adbPath shell am force-stop $labPackage
    if (-not $process.WaitForExit(10000)) { throw 'Instrumentation client did not finish after force-stop' }
    $verify = & $adbPath shell am instrument -w -e class "$testClass#verifyAndRetryAfterForceStop" `
        -e crashMode $mode -e operationId $operationId $runner
    $verify | Write-Output
    if (($verify -join "`n") -notmatch 'OK \(1 test\)') { throw "Recovery failed for $mode" }
}
