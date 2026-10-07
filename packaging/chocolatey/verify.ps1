param(
	[Parameter(Mandatory = $true)][string]$Root,
	[Parameter(Mandatory = $true)][string]$Version,
	[Parameter(Mandatory = $true)][string]$Sums
)

$ErrorActionPreference = 'Stop'

# global because $script: resolves to the scope of the script being invoked
$global:calls = @()
$global:keys = @()

function Install-ChocolateyZipPackage {
	param($packageName, $unzipLocation, $url64bit, $checksum64, $checksumType64)
	$global:calls += [pscustomobject]@{ Name = 'Install-ChocolateyZipPackage'; Args = $PSBoundParameters }
}

function Uninstall-ChocolateyZipPackage {
	param($packageName, $zipFileName)
	$global:calls += [pscustomobject]@{ Name = 'Uninstall-ChocolateyZipPackage'; Args = $PSBoundParameters }
}

function Install-ChocolateyPackage {
	param($packageName, $fileType, $url64bit, $checksum64, $checksumType64, $silentArgs, $validExitCodes, $softwareName)
	$global:calls += [pscustomobject]@{ Name = 'Install-ChocolateyPackage'; Args = $PSBoundParameters }
}

function Uninstall-ChocolateyPackage {
	param($packageName, $fileType, $silentArgs, $validExitCodes, $file)
	$global:calls += [pscustomobject]@{ Name = 'Uninstall-ChocolateyPackage'; Args = $PSBoundParameters }
}

function Get-UninstallRegistryKey {
	param($softwareName)
	$global:softwareName = $softwareName
	$global:keys
}

function Assert-Equal($Actual, $Expected, $What) {
	if ("$Actual" -ne "$Expected") {
		throw "$What : expected '$Expected' but got '$Actual'"
	}
}

function Invoke-Script($Path) {
	$global:calls = @()
	& $Path
	$global:calls
}

$digests = @{}
foreach ($line in Get-Content -LiteralPath $Sums) {
	if ($line.Trim()) {
		$digest, $name = $line -split '  ', 2
		$digests[$name] = $digest
	}
}

$base = "https://github.com/gmitch215/Drift/releases/download/v$Version"

$cli = Invoke-Script (Join-Path $Root 'drift/tools/chocolateyInstall.ps1')
Assert-Equal $cli.Count 1 'cli install calls'
Assert-Equal $cli[0].Name 'Install-ChocolateyZipPackage' 'cli install helper'
$zip = "drift-$Version-windows-x64.zip"
Assert-Equal $cli[0].Args.packageName 'drift' 'cli package name'
Assert-Equal $cli[0].Args.url64bit "$base/$zip" 'cli url'
Assert-Equal $cli[0].Args.checksum64 $digests[$zip] 'cli checksum'
Assert-Equal $cli[0].Args.checksumType64 'sha256' 'cli checksum type'
Assert-Equal (Split-Path -Leaf $cli[0].Args.unzipLocation) 'tools' 'cli unzip location'

$gone = Invoke-Script (Join-Path $Root 'drift/tools/chocolateyUninstall.ps1')
Assert-Equal $gone.Count 1 'cli uninstall calls'
Assert-Equal $gone[0].Name 'Uninstall-ChocolateyZipPackage' 'cli uninstall helper'
Assert-Equal $gone[0].Args.packageName 'drift' 'cli uninstall package name'
Assert-Equal $gone[0].Args.zipFileName 'driftInstall.zip' 'cli uninstall zip name'

$studio = Invoke-Script (Join-Path $Root 'drift-studio/tools/chocolateyInstall.ps1')
Assert-Equal $studio.Count 1 'studio install calls'
Assert-Equal $studio[0].Name 'Install-ChocolateyPackage' 'studio install helper'
$msi = "drift-$Version-windows-x64.msi"
Assert-Equal $studio[0].Args.packageName 'drift-studio' 'studio package name'
Assert-Equal $studio[0].Args.fileType 'msi' 'studio file type'
Assert-Equal $studio[0].Args.url64bit "$base/$msi" 'studio url'
Assert-Equal $studio[0].Args.checksum64 $digests[$msi] 'studio checksum'
Assert-Equal $studio[0].Args.checksumType64 'sha256' 'studio checksum type'
Assert-Equal $studio[0].Args.silentArgs '/qn /norestart' 'studio silent args'
Assert-Equal ($studio[0].Args.validExitCodes -join ',') '0,3010' 'studio exit codes'

$uninstall = Join-Path $Root 'drift-studio/tools/chocolateyUninstall.ps1'
$guid = '{11111111-2222-3333-4444-555555555555}'

$global:keys = @([pscustomobject]@{ PSChildName = $guid; DisplayName = 'Drift' })
$removed = Invoke-Script $uninstall
Assert-Equal $removed.Count 1 'studio uninstall calls'
Assert-Equal $removed[0].Name 'Uninstall-ChocolateyPackage' 'studio uninstall helper'
Assert-Equal $removed[0].Args.packageName 'drift-studio' 'studio uninstall package name'
Assert-Equal $removed[0].Args.fileType 'msi' 'studio uninstall file type'
Assert-Equal $removed[0].Args.silentArgs "$guid /qn /norestart" 'studio uninstall silent args'
Assert-Equal ($removed[0].Args.validExitCodes -join ',') '0,3010' 'studio uninstall exit codes'
Assert-Equal $global:softwareName 'Drift*' 'studio registry lookup'

$global:keys = @()
$none = Invoke-Script $uninstall 3> $null
Assert-Equal @($none).Count 0 'studio uninstall with no registry key'

$global:keys = @(
	[pscustomobject]@{ PSChildName = $guid; DisplayName = 'Drift' },
	[pscustomobject]@{ PSChildName = '{AAAAAAAA-2222-3333-4444-555555555555}'; DisplayName = 'Drift Chat' }
)
$threw = $false
try {
	Invoke-Script $uninstall | Out-Null
} catch {
	$threw = $true
}
Assert-Equal $threw $true 'studio uninstall with two registry keys'

Write-Output 'chocolatey scripts: all checks passed'
