$ErrorActionPreference = 'Stop'

$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

$packageArgs = @{
	packageName    = 'drift'
	unzipLocation  = $toolsDir
	url64bit       = 'https://github.com/gmitch215/Drift/releases/download/v@version@/drift-@version@-windows-x64.zip'
	checksum64     = '@sha256:windows-x64.zip@'
	checksumType64 = 'sha256'
}

Install-ChocolateyZipPackage @packageArgs
