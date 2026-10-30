param(
    [string]$Work = (Join-Path ([System.IO.Path]::GetTempPath()) 'drift-ps1-test')
)

$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'install.ps1')

$failed = 0
$script:calls = @()
$script:platform = @{ Windows = $true; Arch = 'x64' }
$script:exitCode = 0
$script:versionCode = 0

function Get-DriftPlatform { $script:platform }

function Reset-Source {
    $script:java = 'C:\jdk\bin\java.exe'
    $script:banner = 'openjdk version "21.0.8" 2025-07-15'
    $script:git = $true
    $script:remote = $true
    $script:gitCode = 0
    $script:gradleCode = 0
    $script:latest = $null
    $script:lookups = 0
    $script:calls = @()
    foreach ($name in 'DRIFT_INSTALL_BASE_URL', 'DRIFT_VERSION', 'DRIFT_FROM_SOURCE', 'DRIFT_REF', 'DRIFT_SOURCE_URL') {
        Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue
    }
}

function Test-Called([string]$Needle) {
    @($script:calls | Where-Object { $_ -contains $Needle }).Count -gt 0
}

function Find-Java { $script:java }

function Get-JavaBanner([string]$Java) { $script:banner }

function Test-GitAvailable { $script:git }

function Test-RemoteRef([string]$Source, [string]$Name) { $script:remote }

function Get-LatestVersion {
    $script:lookups += 1
    if ($script:latest) { return $script:latest }
    throw 'no release'
}

function Invoke-Git([string[]]$Arguments) {
    $script:calls += , (@('git') + $Arguments)
    if ($Arguments[0] -eq 'clone') { New-Item -ItemType Directory -Force -Path $Arguments[-1] | Out-Null }
    return $script:gitCode
}

function Invoke-Gradle([string]$Dir, [string[]]$Arguments, [string]$Log) {
    $script:calls += , (@('gradle') + $Arguments)
    if ($script:gradleCode -eq 0) {
        $out = Join-Path $Dir 'cli\build\bin\mingwX64\releaseExecutable'
        New-Item -ItemType Directory -Force -Path $out | Out-Null
        Set-Content -LiteralPath (Join-Path $out 'drift.exe') -Value 'built'
    }
    return $script:gradleCode
}

function Invoke-Drift([string]$Exe, [string[]]$Arguments, [switch]$Quiet) {
    $script:calls += , (@($Exe) + $Arguments)
    if ($Arguments[0] -eq '--version') { return $script:versionCode }
    return $script:exitCode
}

function New-FileUrl([string]$Path) {
    'file:///' + $Path.TrimStart('/').Replace('\', '/')
}

function Check([string]$Name, [bool]$Condition) {
    if ($Condition) {
        Write-Output "ok   $Name"
    } else {
        Write-Output "FAIL $Name"
        $script:failed = 1
    }
}

function New-Release([string]$Dir, [string]$Version, [switch]$Corrupt, [string]$Sidecar) {
    $stage = Join-Path $Work "stage-$Version"
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Set-Content -LiteralPath (Join-Path $stage 'drift.exe') -Value 'fake executable'
    $out = Join-Path $Dir "v$Version"
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $zip = Join-Path $out "drift-$Version-windows-x64.zip"
    Compress-Archive -Path (Join-Path $stage 'drift.exe') -DestinationPath $zip -Force
    $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLowerInvariant()
    if ($Sidecar) { $digest = $Sidecar }
    Set-Content -LiteralPath "$zip.sha256" -Value "$digest  drift-$Version-windows-x64.zip"
    if ($Corrupt) { Add-Content -LiteralPath $zip -Value 'x' }
}

function Run-Install([string]$Base, [string]$Version, [hashtable]$Flags = @{}) {
    $script:calls = @()
    $env:DRIFT_INSTALL_BASE_URL = $Base
    $env:DRIFT_VERSION = $Version
    Install-Drift @Flags
}

Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$good = Join-Path $Work 'good'
$bad = Join-Path $Work 'bad'
New-Release $good '1.0.0'
New-Release $bad '1.0.0' -Corrupt
$goodUrl = New-FileUrl $good
$badUrl = New-FileUrl $bad

$code = Run-Install $goodUrl '1.0.0' @{ Global = $true; NoModifyPath = $true; DryRun = $true; Dir = 'D:\tools' }
Check 'a good release exits 0' ($code -eq 0)
Check 'the version is checked first' ($script:calls[0][1] -eq '--version')
Check 'the flags are handed to drift install' (($script:calls[1][1..($script:calls[1].Count - 1)] -join ' ') -eq 'install --global --no-modify-path --dry-run --dir D:\tools')
Check 'the executable comes from the extracted archive' ($script:calls[1][0] -like '*drift.exe')

$code = Run-Install $goodUrl 'v1.0.0' @{ User = $true }
Check 'a version with a leading v is accepted' ($code -eq 0)
Check 'user flag is forwarded' (($script:calls[1][1..($script:calls[1].Count - 1)] -join ' ') -eq 'install --user')

$code = Run-Install $badUrl '1.0.0'
Check 'a corrupted asset exits 1' ($code -eq 1)
Check 'a corrupted asset never runs drift' ($script:calls.Count -eq 0)

New-Release (Join-Path $Work 'short') '1.0.0' -Sidecar 'abc'
$code = Run-Install (New-FileUrl (Join-Path $Work 'short')) '1.0.0'
Check 'a sidecar that is not a digest exits 1' ($code -eq 1)

$code = Run-Install (New-FileUrl (Join-Path $Work 'nowhere')) '1.0.0'
Check 'no network exits 1' ($code -eq 1)

$code = Run-Install $goodUrl '9.9.9'
Check 'a missing version exits 1' ($code -eq 1)

$script:platform = @{ Windows = $true; Arch = 'arm64' }
$code = Run-Install $goodUrl '1.0.0'
Check 'an unsupported architecture exits 1' ($code -eq 1)
$script:platform = @{ Windows = $false; Arch = 'x64' }
$code = Run-Install $goodUrl '1.0.0'
Check 'a non-Windows system exits 1' ($code -eq 1)
$script:platform = @{ Windows = $true; Arch = 'x64' }

$code = Run-Install $goodUrl '1.0.0' @{ User = $true; Global = $true }
Check 'conflicting flags exit 2' ($code -eq 2)

$script:exitCode = 3
$code = Run-Install $goodUrl '1.0.0'
Check 'the exit code of drift install is returned' ($code -eq 3)
$script:exitCode = 0

$script:versionCode = 1
$code = Run-Install $goodUrl '1.0.0'
Check 'an executable that cannot run is reported' ($code -eq 1)
$script:versionCode = 0

$env:DRIFT_INSTALL_BASE_URL = $goodUrl
Remove-Item Env:\DRIFT_VERSION -ErrorAction SilentlyContinue
Check 'a base URL without a version exits 1' ((Install-Drift) -eq 1)

Reset-Source
$code = Install-Drift
Check 'no release builds from source' ($code -eq 0)
Check 'the fallback runs the Windows target' (Test-Called ':cli:linkReleaseExecutableMingwX64')
Check 'the fallback hands the built executable to drift install' ($script:calls[-1][1] -eq 'install')

Reset-Source
$script:latest = '1.0.0'
$code = Install-Drift -FromSource
Check '-FromSource builds even when a release exists' ($code -eq 0 -and (Test-Called ':cli:linkReleaseExecutableMingwX64'))
Check '-FromSource does not look up the release' ($script:lookups -eq 0)

Reset-Source
$env:DRIFT_FROM_SOURCE = '1'
$code = Install-Drift
Check 'DRIFT_FROM_SOURCE=1 builds from source' ($code -eq 0 -and (Test-Called ':cli:linkReleaseExecutableMingwX64'))

Reset-Source
$code = Install-Drift -Ref 'exp'
Check '-Ref builds the named branch' ($code -eq 0 -and (Test-Called 'exp'))

Reset-Source
$env:DRIFT_REF = 'v9.9.9'
$code = Install-Drift
Check 'DRIFT_REF builds the named tag' ($code -eq 0 -and (Test-Called 'v9.9.9'))

Reset-Source
$script:remote = $false
$code = Install-Drift -Ref '0123456789abcdef0123456789abcdef01234567'
Check 'a full commit hash is fetched by name' ($code -eq 0 -and (Test-Called 'FETCH_HEAD'))

Reset-Source
$script:remote = $false
$code = Install-Drift -Ref '0123456'
Check 'a short commit hash is cloned in full and checked out' ($code -eq 0 -and (Test-Called '--no-checkout'))

Reset-Source
$script:remote = $false
$script:gitCode = 128
$code = Install-Drift -Ref 'no-such-ref'
Check 'an unknown ref exits 1' ($code -eq 1 -and -not (Test-Called ':cli:linkReleaseExecutableMingwX64'))

Reset-Source
$script:java = $null
$code = Install-Drift
Check 'no JDK exits 1 without building' ($code -eq 1 -and -not (Test-Called ':cli:linkReleaseExecutableMingwX64'))

Reset-Source
$script:banner = 'openjdk version "11.0.2" 2019-01-15'
$code = Install-Drift
Check 'Java 11 exits 1 without building' ($code -eq 1 -and -not (Test-Called ':cli:linkReleaseExecutableMingwX64'))

Reset-Source
$script:gradleCode = 1
$code = Install-Drift
Check 'a failed build exits 1' ($code -eq 1)
$kept = @(Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Filter 'drift-install-*' -ErrorAction SilentlyContinue)
Check 'a failed build keeps its temporary directory' ($kept.Count -eq 1)
$kept | Remove-Item -Recurse -Force

Reset-Source
$script:git = $false
$archive = Join-Path $Work 'archive-site'
$stage = Join-Path $Work 'archive-stage\Drift-master'
New-Item -ItemType Directory -Force -Path $stage, (Join-Path $archive 'archive') | Out-Null
Set-Content -LiteralPath (Join-Path $stage 'gradlew.bat') -Value 'rem'
Compress-Archive -Path $stage -DestinationPath (Join-Path $archive 'archive\master.zip') -Force
$RepoUrl = New-FileUrl $archive
$code = Install-Drift
$RepoUrl = 'https://github.com/gmitch215/Drift'
Check 'without git the archive is built' ($code -eq 0 -and (Test-Called ':cli:linkReleaseExecutableMingwX64'))
Reset-Source

$leftover = @(Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Filter 'drift-install-*' -ErrorAction SilentlyContinue).Count
Check 'no temporary directory is left behind' ($leftover -eq 0)

exit $failed
