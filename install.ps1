[CmdletBinding()]
param(
    [switch]$User,
    [switch]$Global,
    [string]$Dir,
    [switch]$NoModifyPath,
    [switch]$DryRun,
    [switch]$FromSource,
    [string]$Ref,
    [switch]$Help
)

$RepoUrl = if ($env:DRIFT_INSTALL_REPO_URL) { $env:DRIFT_INSTALL_REPO_URL } else { 'https://github.com/gmitch215/Drift' }

function Write-Status([string]$Message) {
    [Console]::Error.WriteLine("drift-install: $Message")
}

function Show-Usage {
    @'
Install the drift command line tool. When no release exists yet, it builds drift from source.

usage: install.ps1 [-User | -Global] [-Dir PATH] [-NoModifyPath] [-DryRun] [-FromSource] [-Ref REF]

  -User             install for the current user (the default)
  -Global           install for every user; run from an elevated prompt
  -Dir PATH         absolute directory to install into
  -NoModifyPath     leave the PATH environment variable alone
  -DryRun           print every change without making any
  -FromSource       build drift from source instead of downloading a release
  -Ref REF          branch, tag or commit to build; implies -FromSource (default: master)
  -Help             print this help

The flags are handed to the downloaded drift.exe's `drift install`.

environment:
  DRIFT_INSTALL_BASE_URL   release download URL, laid out as BASE/v<version>/<asset>
                           (default: the GitHub releases of the repository)
  DRIFT_VERSION            version to install, for example 1.0.0 (default: the latest release;
                           required when DRIFT_INSTALL_BASE_URL is set)
  DRIFT_NO_MODIFY_PATH=1   same as -NoModifyPath
  DRIFT_FROM_SOURCE=1      same as -FromSource
  DRIFT_REF=REF            same as -Ref
  DRIFT_SOURCE_URL         git URL or local path to clone when building from source
                           (default: the GitHub repository)
  DRIFT_INSTALL_REPO_URL   repository URL for releases/latest and source archives
                           (default: the GitHub repository)
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

function Invoke-Handover {
    param(
        [string]$Exe,
        [switch]$User,
        [switch]$Global,
        [string]$Dir,
        [switch]$NoModifyPath,
        [switch]$DryRun
    )
    $flags = @()
    if ($User) { $flags += '--user' }
    if ($Global) { $flags += '--global' }
    if ($NoModifyPath) { $flags += '--no-modify-path' }
    if ($DryRun) { $flags += '--dry-run' }
    if ($Dir) { $flags += @('--dir', $Dir) }
    return [int](Invoke-Drift $Exe (@('install') + $flags))
}

function Find-Java {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command java -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

function Get-JavaBanner([string]$Java) {
    (& $Java -version 2>&1 | Out-String)
}

function Get-JavaVersion([string]$Java) {
    if ((Get-JavaBanner $Java) -match 'version "([^"]+)"') { return $Matches[1] }
    return ''
}

function Get-JdkMajor([string]$Version) {
    $parts = $Version -split '\.'
    if ($parts[0] -eq '1' -and $parts.Count -gt 1) { return ($parts[1] -as [int]) }
    return ($parts[0] -as [int])
}

function Test-GitAvailable {
    [bool](Get-Command git -ErrorAction SilentlyContinue)
}

function Test-CommitName([string]$Name) {
    $Name -match '^[0-9a-f]{7,40}$'
}

function Test-RemoteRef([string]$Source, [string]$Name) {
    [bool](& git ls-remote -- $Source $Name 2> $null)
}

function Invoke-Git([string[]]$Arguments) {
    & git @Arguments | Out-Host
    return $LASTEXITCODE
}

function Get-DriftSource([string]$Name, [string]$Source, [string]$Dest) {
    if (-not (Test-GitAvailable)) {
        $parent = Split-Path -Parent $Dest
        $zip = Join-Path $parent 'source.zip'
        $unpacked = Join-Path $parent 'unpacked'
        try {
            Get-DriftFile "$RepoUrl/archive/$Name.zip" $zip
            Expand-Archive -LiteralPath $zip -DestinationPath $unpacked -Force
        } catch {
            return $false
        }
        $top = Get-ChildItem -LiteralPath $unpacked -Directory | Select-Object -First 1
        Move-Item -LiteralPath $top.FullName -Destination $Dest
        return $true
    }
    # a hex name that is not a branch or tag is a commit
    if ((Test-RemoteRef $Source $Name) -or -not (Test-CommitName $Name)) {
        return ((Invoke-Git @('clone', '-q', '--depth', '1', '--branch', $Name, '--', $Source, $Dest)) -eq 0)
    }
    if ($Name.Length -eq 40) {
        return ((Invoke-Git @('init', '-q', $Dest)) -eq 0) -and
            ((Invoke-Git @('-C', $Dest, 'fetch', '-q', '--depth', '1', '--', $Source, $Name)) -eq 0) -and
            ((Invoke-Git @('-C', $Dest, 'checkout', '-q', 'FETCH_HEAD')) -eq 0)
    }
    return ((Invoke-Git @('clone', '-q', '--no-checkout', '--', $Source, $Dest)) -eq 0) -and
        ((Invoke-Git @('-C', $Dest, 'checkout', '-q', $Name)) -eq 0)
}

function Invoke-Gradle([string]$Dir, [string[]]$Arguments, [string]$Log) {
    Push-Location -LiteralPath $Dir
    try {
        & .\gradlew.bat --console=plain @Arguments *> $Log
        return $LASTEXITCODE
    } finally {
        Pop-Location
    }
}

function Install-DriftFromSource {
    param(
        [string]$Name,
        [string]$Source,
        [switch]$User,
        [switch]$Global,
        [string]$Dir,
        [switch]$NoModifyPath,
        [switch]$DryRun
    )
    $java = Find-Java
    if (-not $java) {
        Write-Status 'error: building from source needs a JDK 17 or newer on PATH or in JAVA_HOME'
        return 1
    }
    $javaVersion = Get-JavaVersion $java
    if (-not $javaVersion) {
        Write-Status "error: cannot read the version of $java"
        return 1
    }
    $major = Get-JdkMajor $javaVersion
    if (-not $major -or $major -lt 17) {
        Write-Status "error: building from source needs a JDK 17 or newer; $java is Java $javaVersion"
        return 1
    }

    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('drift-install-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    $keep = $false
    try {
        $src = Join-Path $tmp 'source'
        if (-not (Get-DriftSource $Name $Source $src)) {
            Write-Status "error: cannot fetch $Name from $Source"
            return 1
        }
        Write-Status 'building drift (several minutes the first time)'
        $log = Join-Path $tmp 'build.log'
        if ((Invoke-Gradle $src @(':cli:linkReleaseExecutableMingwX64') $log) -ne 0) {
            if (Test-Path -LiteralPath $log) {
                Get-Content -LiteralPath $log -Tail 40 | ForEach-Object { [Console]::Error.WriteLine($_) }
            }
            Write-Status "error: the build failed; the full log is $log and $tmp is kept"
            Write-Status 'check the JDK version, free disk space and the first error above'
            $keep = $true
            return 1
        }
        $built = Join-Path $src 'cli\build\bin\mingwX64\releaseExecutable\drift.exe'
        if (-not (Test-Path -LiteralPath $built)) {
            Write-Status "error: the build did not produce $built"
            return 1
        }
        $extract = Join-Path $tmp 'extract'
        New-Item -ItemType Directory -Path $extract | Out-Null
        $exe = Join-Path $extract 'drift.exe'
        Copy-Item -LiteralPath $built -Destination $exe
        if ((Invoke-Drift $exe @('--version') -Quiet) -ne 0) {
            Write-Status 'error: the built executable does not run on this system'
            return 1
        }
        if (Test-Path -LiteralPath (Join-Path $src '.git')) {
            $sha = (& git -C $src rev-parse --short HEAD 2> $null)
            Write-Status "built drift from $Name ($sha)"
        } else {
            Write-Status "built drift from $Name"
        }
        return (Invoke-Handover $exe -User:$User -Global:$Global -Dir $Dir -NoModifyPath:$NoModifyPath -DryRun:$DryRun)
    } finally {
        if (-not $keep -and (Test-Path -LiteralPath $tmp)) {
            Remove-Item -LiteralPath $tmp -Recurse -Force
        }
    }
}

function Install-Drift {
    param(
        [switch]$User,
        [switch]$Global,
        [string]$Dir,
        [switch]$NoModifyPath,
        [switch]$DryRun,
        [switch]$FromSource,
        [string]$Ref
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
    if ($env:DRIFT_FROM_SOURCE -eq '1') { $FromSource = $true }
    if (-not $Ref) { $Ref = $env:DRIFT_REF }
    if ($Ref) { $FromSource = $true }
    $source = if ($env:DRIFT_SOURCE_URL) { $env:DRIFT_SOURCE_URL } else { "$RepoUrl.git" }
    $handover = @{ User = $User; Global = $Global; Dir = $Dir; NoModifyPath = $NoModifyPath; DryRun = $DryRun }

    if ($FromSource) {
        if (-not $Ref) { $Ref = 'master' }
        return Install-DriftFromSource -Name $Ref -Source $source @handover
    }
    if (-not $version) {
        try {
            $version = Get-LatestVersion
        } catch {
            Write-Status 'no release of drift exists yet, building from source (needs git or the archive download, a JDK 17 or newer, and several minutes; pass -Ref to choose what to build)'
            return Install-DriftFromSource -Name 'master' -Source $source @handover
        }
    }

    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('drift-install-' + [guid]::NewGuid().ToString('N'))
    try {
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
        return (Invoke-Handover $exe.FullName -User:$User -Global:$Global -Dir $Dir -NoModifyPath:$NoModifyPath -DryRun:$DryRun)
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
    $code = Install-Drift -User:$User -Global:$Global -Dir $Dir -NoModifyPath:$NoModifyPath -DryRun:$DryRun -FromSource:$FromSource -Ref $Ref
    if ($MyInvocation.MyCommand.Path) { exit $code }
    if ($code -ne 0) { throw "drift install failed with exit code $code" }
}
