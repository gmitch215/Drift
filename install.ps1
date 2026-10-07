[CmdletBinding()]
param(
	[switch]$User,
	[switch]$Global,
	[string]$Dir,
	[switch]$NoModifyPath,
	[switch]$DryRun,
	[switch]$Help
)

$RepoUrl = 'https://github.com/gmitch215/Drift'

function Write-Status([string]$Message) {
	[Console]::Error.WriteLine("drift-install: $Message")
}

function Show-Usage {
	@'
Install the drift command line tool.

usage: install.ps1 [-User | -Global] [-Dir PATH] [-NoModifyPath] [-DryRun]

  -User             install for the current user (the default)
  -Global           install for every user; run from an elevated prompt
  -Dir PATH         absolute directory to install into
  -NoModifyPath     leave the PATH environment variable alone
  -DryRun           print every change without making any
  -Help             print this help

The flags are handed to the downloaded drift.exe's `drift install`.

environment:
  DRIFT_INSTALL_BASE_URL   release download URL, laid out as BASE/v<version>/<asset>
                           (default: the GitHub releases of the repository)
  DRIFT_VERSION            version to install, for example 1.0.0 (default: the latest release;
                           required when DRIFT_INSTALL_BASE_URL is set)
  DRIFT_NO_MODIFY_PATH=1   same as -NoModifyPath
'@
}

function Get-DriftPlatform {
	$windows = [System.Environment]::OSVersion.Platform -eq 'Win32NT'
	$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
		'AMD64' { 'x64' }
		'ARM64' { 'arm64' }
		default { [string]$env:PROCESSOR_ARCHITECTURE }
	}
	@{ Windows = $windows; Arch = $arch }
}

function Invoke-Drift([string]$Exe, [string[]]$Arguments, [switch]$Quiet) {
	if ($Quiet) {
		& $Exe @Arguments | Out-Null
	} else {
		& $Exe @Arguments | Out-Host
	}
	return $LASTEXITCODE
}

function Get-DriftFile([string]$Url, [string]$Path) {
	if ($Url.StartsWith('file:')) {
		Copy-Item -LiteralPath ([Uri]$Url).LocalPath -Destination $Path
		return
	}
	$saved = $ProgressPreference
	$ProgressPreference = 'SilentlyContinue'
	try {
		Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Path
	} finally {
		$ProgressPreference = $saved
	}
}

function Get-LatestVersion {
	$saved = $ProgressPreference
	$ProgressPreference = 'SilentlyContinue'
	try {
		$page = Invoke-WebRequest -UseBasicParsing -Uri "$RepoUrl/releases/latest"
	} finally {
		$ProgressPreference = $saved
	}
	$final = $page.BaseResponse.ResponseUri
	if (-not $final) { $final = $page.BaseResponse.RequestMessage.RequestUri }
	$tag = ([string]$final).Split('/')[-1]
	if ($tag -notmatch '^v\d') { throw "cannot find the latest release of $RepoUrl; set DRIFT_VERSION" }
	$tag.Substring(1)
}

function Install-Drift {
	param(
		[switch]$User,
		[switch]$Global,
		[string]$Dir,
		[switch]$NoModifyPath,
		[switch]$DryRun
	)
	if ($User -and $Global) {
		Write-Status 'error: -User and -Global cannot be combined'
		return 2
	}
	$platform = Get-DriftPlatform
	if (-not $platform.Windows) {
		Write-Status 'error: this is not Windows; use install.sh instead'
		return 1
	}
	if ($platform.Arch -ne 'x64') {
		Write-Status "error: no release asset is built for Windows on $($platform.Arch)"
		return 1
	}
	$base = $env:DRIFT_INSTALL_BASE_URL
	$version = $env:DRIFT_VERSION
	if ($base -and -not $version) {
		Write-Status 'error: set DRIFT_VERSION when DRIFT_INSTALL_BASE_URL is set'
		return 1
	}
	if (-not $base) { $base = "$RepoUrl/releases/download" }
	if ($version) { $version = $version.TrimStart('v') }
	$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('drift-install-' + [guid]::NewGuid().ToString('N'))
	try {
		if (-not $version) { $version = Get-LatestVersion }
		$asset = "drift-$version-windows-x64.zip"
		$url = $base.TrimEnd('/') + "/v$version/$asset"
		New-Item -ItemType Directory -Path $tmp | Out-Null
		Write-Status "downloading $asset"
		try {
			Get-DriftFile $url (Join-Path $tmp $asset)
			Get-DriftFile "$url.sha256" (Join-Path $tmp "$asset.sha256")
		} catch {
			Write-Status "error: cannot download $url ($($_.Exception.Message))"
			return 1
		}
		$sidecar = (Get-Content -LiteralPath (Join-Path $tmp "$asset.sha256") -TotalCount 1)
		$expected = if ($sidecar) { ($sidecar -split '\s+')[0].ToLowerInvariant() } else { '' }
		if ($expected -notmatch '^[0-9a-f]{64}$') {
			Write-Status "error: $asset.sha256 does not start with a sha256 digest"
			return 1
		}
		$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $tmp $asset)).Hash.ToLowerInvariant()
		if ($actual -ne $expected) {
			Write-Status "error: checksum mismatch for $asset (expected $expected, got $actual); nothing was installed"
			return 1
		}
		Write-Status 'sha256 verified'
		$extract = Join-Path $tmp 'extract'
		Expand-Archive -LiteralPath (Join-Path $tmp $asset) -DestinationPath $extract -Force
		$exe = Get-ChildItem -LiteralPath $extract -Filter 'drift.exe' -Recurse -Depth 1 -File | Select-Object -First 1
		if (-not $exe) {
			Write-Status "error: $asset does not contain drift.exe"
			return 1
		}
		if ((Invoke-Drift $exe.FullName @('--version') -Quiet) -ne 0) {
			Write-Status 'error: the downloaded executable does not run on this system'
			return 1
		}
		$flags = @()
		if ($User) { $flags += '--user' }
		if ($Global) { $flags += '--global' }
		if ($NoModifyPath) { $flags += '--no-modify-path' }
		if ($DryRun) { $flags += '--dry-run' }
		if ($Dir) { $flags += @('--dir', $Dir) }
		return [int](Invoke-Drift $exe.FullName (@('install') + $flags))
	} finally {
		if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
	}
}

if ($MyInvocation.InvocationName -ne '.') {
	if ($Help) {
		Show-Usage
		return
	}
	if ($PSVersionTable.PSEdition -ne 'Core') {
		[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
	}
	$code = Install-Drift -User:$User -Global:$Global -Dir $Dir -NoModifyPath:$NoModifyPath -DryRun:$DryRun
	if ($MyInvocation.MyCommand.Path) { exit $code }
	if ($code -ne 0) { throw "drift install failed with exit code $code" }
}
