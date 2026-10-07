$ErrorActionPreference = 'Stop'

$silentArgs = '/qn /norestart'
$validExitCodes = @(0, 3010)

[array]$keys = Get-UninstallRegistryKey -SoftwareName 'Drift*'

if ($keys.Count -eq 0) {
	Write-Warning 'Drift Studio is not listed in Programs and Features; nothing to uninstall.'
} elseif ($keys.Count -gt 1) {
	$names = ($keys | ForEach-Object { $_.DisplayName }) -join ', '
	throw "More than one installed program matches 'Drift*' ($names); uninstall Drift Studio by hand."
} else {
	Uninstall-ChocolateyPackage -PackageName 'drift-studio' -FileType 'msi' -SilentArgs "$($keys[0].PSChildName) $silentArgs" -ValidExitCodes $validExitCodes -File ''
}
