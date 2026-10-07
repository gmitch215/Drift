$ErrorActionPreference = 'Stop'

# the install helper logs its unzip under the name <package>Install.zip
Uninstall-ChocolateyZipPackage -PackageName 'drift' -ZipFileName 'driftInstall.zip'
