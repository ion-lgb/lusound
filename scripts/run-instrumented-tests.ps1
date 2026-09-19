#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Runs the LuSound instrumented (device) test suite in one command.

.DESCRIPTION
    The instrumented suite is the only verification path for real MediaStore/SAF scanning,
    Media3 decoding, the three server protocols and the Compose UI, but running it by hand is a
    long sequence of adb steps (see docs/testing.md), which is why it tends not to happen.

    This script performs the device-side half of that sequence and checks the prerequisites it
    cannot provide itself:
      * resolves adb and the target device, failing with an actionable message otherwise
      * reports whether the Navidrome and Jellyfin fixtures are reachable on the host ports
      * installs the debug and androidTest APKs
      * pushes the external samples the tests require
      * sets up adb reverse for both fixtures
      * runs the instrumentation runner and summarises the result
      * pulls the screenshots the visual tests write to the app's external files dir

    It never starts the fixtures: those are long-running and platform specific. Start them as
    documented in docs/testing.md and this script will detect them.

.PARAMETER Samples
    Directory holding the external samples. Recognised names, all optional but required by the
    tests that use them:
      ncm-sample.ncm         >= 70 KB, longer than 3 seconds, with embedded cover art
      standard-embedded.flac full tags with embedded lyrics and cover
      standard-missing.flac  full tags only; must be matchable on LRCLIB / MusicBrainz

.PARAMETER Class
    Instrumentation class filter, comma separated, e.g. "app.lusound.LibraryPlaybackTest".

.PARAMETER Build
    Assemble the debug and androidTest APKs before installing.

.PARAMETER SkipFixtures
    Do not set up adb reverse for the two fixtures (use for runs that need no server).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/run-instrumented-tests.ps1 -Samples C:\samples -Build
.EXAMPLE
    ./scripts/run-instrumented-tests.ps1 -Class app.lusound.NcmPlaybackTest,app.lusound.QueueEditingTest

.NOTES
    Windows blocks .ps1 files under the default execution policy, so invoke it as
    `powershell -ExecutionPolicy Bypass -File scripts/run-instrumented-tests.ps1 ...`
    if running `& .\scripts\run-instrumented-tests.ps1` reports that scripts are disabled.
    Written to work on Windows PowerShell 5.1 as well as PowerShell 7.
#>
[CmdletBinding()]
param(
    [string]$Samples,
    [string]$Device,
    [string]$Class,
    [switch]$Build,
    [switch]$SkipFixtures,
    [int]$NavidromePort = 4534,
    [int]$JellyfinPort = 8097,
    [string]$Adb
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $repoRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$deviceFiles = '/sdcard/Android/data/app.lusound.debug/files'
$runner = 'app.lusound.debug.test/androidx.test.runner.AndroidJUnitRunner'
$requiredSamples = @('ncm-sample.ncm', 'standard-embedded.flac', 'standard-missing.flac', 'standard-uslt.mp3', 'standard-lyrics.m4a')

function Write-Step([string]$message) { Write-Host "`n== $message" -ForegroundColor Cyan }
function Write-Note([string]$message) { Write-Host "   $message" -ForegroundColor DarkGray }
function Write-Warn2([string]$message) { Write-Host "   ! $message" -ForegroundColor Yellow }

function Resolve-Adb {
    if ($Adb) {
        if (-not (Test-Path $Adb)) { throw "-Adb points at something that does not exist: $Adb" }
        return $Adb
    }
    $candidates = @()
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($root) { $candidates += (Join-Path (Join-Path $root 'platform-tools') 'adb.exe') }
    }
    $local = Join-Path $repoRoot 'local.properties'
    if (Test-Path $local) {
        $line = (Get-Content $local | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1)
        if ($line) {
            # local.properties is a Java properties file: '\\' is one backslash and '\:' is a colon.
            $sdk = (($line -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '\\:', ':').Trim()
            $candidates += (Join-Path (Join-Path $sdk 'platform-tools') 'adb.exe')
        }
    }
    $candidates += (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    throw "adb not found. Pass -Adb, set ANDROID_HOME, or put platform-tools on PATH."
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    # adb writes ordinary progress ("daemon not running; starting now") to stderr. Redirection turns
    # that into an ErrorRecord, and with ErrorActionPreference = Stop (Windows PowerShell 5.1) it
    # would abort the script before the exit code is ever examined -- so capture output as text and
    # judge success by $LASTEXITCODE only.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $script:adbPath @Arguments 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed (exit $code):`n$($output -join "`n")"
    }
    return [pscustomobject]@{ Code = $code; Output = $output }
}

function Test-HostPort([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(300) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Resolve-Device([string]$requested) {
    # @() is required: a pipeline that yields nothing, or a single line, produces $null or a string,
    # and StrictMode makes .Count on those a terminating error.
    $lines = @(
        (Invoke-Adb -Arguments @('devices')).Output |
            Where-Object { $_ -match "`t" } |
            ForEach-Object { ($_ -split "`t")[0].Trim() } |
            Where-Object { $_ }
    )
    if ($requested) {
        if ($lines -notcontains $requested) {
            throw "Device `"$requested`" is not attached. Attached: $(if ($lines.Count -gt 0) { $lines -join ', ' } else { 'none' })"
        }
        return $requested
    }
    $ready = @($lines | Where-Object { $_ -notmatch '^emulator-.*offline$' })
    if ($ready.Count -eq 0) {
        throw "No device or emulator is attached. Start one, then re-run. 'adb devices' reported: $(if ($lines.Count -gt 0) { $lines -join ', ' } else { 'nothing' })"
    }
    if ($ready.Count -gt 1) {
        throw "$($ready.Count) devices are attached ($($ready -join ', ')); pass -Device to choose one."
    }
    return $ready[0]
}

function Get-SamplePath([string]$directory, [string]$name) {
    if (-not $directory) { return $null }
    $path = Join-Path $directory $name
    if (Test-Path $path) { return $path }
    return $null
}

# ---- resolve tools and target ------------------------------------------------

$script:adbPath = Resolve-Adb
Write-Step "adb: $script:adbPath"
$serial = Resolve-Device $Device
Write-Note "device: $serial"

# ---- prerequisites the suite needs but cannot provide ------------------------

Write-Step 'Prerequisites'
$haveNavidrome = Test-HostPort $NavidromePort
$haveJellyfin = Test-HostPort $JellyfinPort
if (-not $SkipFixtures) {
    if ($haveNavidrome) { Write-Note "Navidrome fixture reachable on 127.0.0.1:$NavidromePort" }
    else { Write-Warn2 "nothing is listening on 127.0.0.1:$NavidromePort - SubsonicIntegrationTest will fail. Start: python3 scripts/navidrome-fixture.py <navidrome-binary>" }
    if ($haveJellyfin) { Write-Note "Jellyfin fixture reachable on 127.0.0.1:$JellyfinPort" }
    else { Write-Warn2 "nothing is listening on 127.0.0.1:$JellyfinPort - JellyfinIntegrationTest will fail. See docs/testing.md for the Jellyfin fixture." }
}

$samplePaths = @{}
foreach ($name in $requiredSamples) {
    $path = Get-SamplePath $Samples $name
    if ($path) { $samplePaths[$name] = $path; Write-Note "sample: $name" }
    else { Write-Warn2 "sample missing: $name (needed by the NCM, metadata and visual tests; the library and queue tests run without it)" }
}
if (-not $Samples) {
    Write-Warn2 'no -Samples directory given: NCM, metadata and visual tests will fail on their sample checks'
}

# ---- build and install -------------------------------------------------------

if ($Build) {
    Write-Step 'Assembling debug and androidTest APKs'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & (Join-Path $repoRoot 'gradlew.bat') :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
        $gradleCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($gradleCode -ne 0) { throw "Gradle build failed (exit $gradleCode)" }
}
foreach ($path in @($apk, $testApk)) {
    if (-not (Test-Path $path)) { throw "missing $path - run with -Build or assemble it first" }
}

Write-Step 'Installing APKs'
# -r keeps app data, so already-pushed samples survive a re-install.
Invoke-Adb -Arguments @('-s', $serial, 'install', '-r', $apk) | Out-Null
Write-Note 'installed debug APK'
Invoke-Adb -Arguments @('-s', $serial, 'install', '-r', $testApk) | Out-Null
Write-Note 'installed androidTest APK'

# ---- push samples AFTER installing so the directory exists -------------------

if ($samplePaths.Count -gt 0) {
    Write-Step 'Pushing external samples'
    Invoke-Adb -Arguments @('-s', $serial, 'shell', 'mkdir', '-p', $deviceFiles) | Out-Null
    foreach ($name in $samplePaths.Keys) {
        Invoke-Adb -Arguments @('-s', $serial, 'push', $samplePaths[$name], "$deviceFiles/$name") | Out-Null
        Write-Note "pushed $name"
    }
}

# ---- fixtures over adb reverse ----------------------------------------------

if (-not $SkipFixtures) {
    Write-Step 'Setting up adb reverse'
    foreach ($entry in @(@{ Port = $NavidromePort; Up = $haveNavidrome }, @{ Port = $JellyfinPort; Up = $haveJellyfin })) {
        if (-not $entry.Up) { Write-Warn2 "skipped tcp:$($entry.Port) - nothing listening on the host"; continue }
        Invoke-Adb -Arguments @('-s', $serial, 'reverse', "tcp:$($entry.Port)", "tcp:$($entry.Port)") | Out-Null
        Write-Note "device tcp:$($entry.Port) -> host tcp:$($entry.Port)"
    }
}

# ---- run --------------------------------------------------------------------

Write-Step 'Running instrumented tests'
$arguments = @('-s', $serial, 'shell', 'am', 'instrument', '-w')
if ($Class) { $arguments += @('-e', 'class', $Class) }
$arguments += $runner
Write-Note ($arguments -join ' ')

$result = Invoke-Adb -Arguments $arguments -AllowFailure
$output = $result.Output
$output | Where-Object { $_ -match '^(OK|FAILURES|Tests run|INSTRUMENTATION|Time:|\s+\d+\))' } | ForEach-Object { Write-Host "   $_" }

# ---- pull artifacts and report ---------------------------------------------

$screenshots = Join-Path $repoRoot 'app/build/outputs/screenshots'
Write-Step 'Collecting screenshots'
$pull = Invoke-Adb -Arguments @('-s', $serial, 'pull', $deviceFiles, $screenshots) -AllowFailure
if ($pull.Code -eq 0) {
    New-Item -ItemType Directory -Force -Path $screenshots | Out-Null
    $count = (Get-ChildItem -Recurse -File $screenshots -ErrorAction SilentlyContinue | Measure-Object).Count
    Write-Note "$count file(s) in $screenshots"
} else {
    Write-Warn2 'no screenshots pulled (the app may not have written any)'
}

$failed = ($output -join "`n") -match 'FAILURES!!!' -or $result.Code -ne 0
Write-Step 'Result'
if ($failed) {
    Write-Host '   FAILED - see the output above' -ForegroundColor Red
    exit 1
}
Write-Host '   passed' -ForegroundColor Green
