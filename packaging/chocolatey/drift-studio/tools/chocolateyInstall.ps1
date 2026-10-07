$ErrorActionPreference = 'Stop'

$packageArgs = @{
	packageName    = 'drift-studio'
	fileType       = 'msi'
	url64bit       = 'https://github.com/gmitch215/Drift/releases/download/v@version@/drift-@version@-windows-x64.msi'
	checksum64     = '@sha256:windows-x64.msi@'
	checksumType64 = 'sha256'
	silentArgs     = '/qn /norestart'
	validExitCodes = @(0, 3010)
	softwareName   = 'Drift*'
}

Install-ChocolateyPackage @packageArgs
