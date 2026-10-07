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

$leftover = @(Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Filter 'drift-install-*' -ErrorAction SilentlyContinue).Count
Check 'no temporary directory is left behind' ($leftover -eq 0)

exit $failed
