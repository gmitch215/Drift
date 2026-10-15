param(
    [string]$Dir,
    [string]$Arm,
    [string]$Out,
    [switch]$Hash
)

$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$invariant = [System.Globalization.CultureInfo]::InvariantCulture

function Get-GateHash([long]$Trial, [long]$Salt) {
    $m = 4294967296L
    $h = ($Trial * 2654435761L + $Salt) % $m
    $h = ((($h -shr 16) -bxor $h) * 73244475L) % $m
    $h = ((($h -shr 16) -bxor $h) * 73244475L) % $m
    return ($h -shr 16) -bxor $h
}

function ConvertFrom-ShellQuote([string]$Text) {
    if ($Text.Length -ge 2 -and $Text.StartsWith("'") -and $Text.EndsWith("'")) {
        return $Text.Substring(1, $Text.Length - 2).Replace("'\''", "'")
    }
    return $Text
}

function Read-Assignments([string]$Path, [string]$Prefix) {
    $values = [ordered]@{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path, $utf8)) {
        if ($line -eq '') { continue }
        if ($line -notmatch ('^' + $Prefix + '([A-Za-z_][A-Za-z0-9_]*)=(.*)$')) {
            throw "$Path`: cannot read line: $line"
        }
        $values[$Matches[1]] = ConvertFrom-ShellQuote $Matches[2]
    }
    return $values
}

if ($Hash) {
    $stdout = [Console]::Out
    while ($true) {
        $line = [Console]::In.ReadLine()
        if ($null -eq $line) { break }
        $trial, $salt = $line.Trim() -split '\s+'
        $stdout.Write((Get-GateHash ([long]$trial) ([long]$salt)).ToString($invariant) + "`n")
    }
    return
}

$Dir = (Resolve-Path $Dir).Path
Set-Location $Dir
$plan = Read-Assignments (Join-Path $Dir "$Arm.plan") ''
$envFile = Join-Path $Dir "$Arm.env"
if (Test-Path $envFile) {
    foreach ($pair in (Read-Assignments $envFile 'export ').GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($pair.Key, $pair.Value)
    }
}
$sh = (Get-Command sh -ErrorAction SilentlyContinue).Source
if (-not $sh) { $sh = Join-Path $env:ProgramFiles 'Git/bin/sh.exe' }

$lines = [System.Collections.Generic.List[string]]::new()
$drift = $env:DRIFT_BIN
if ($drift -and (Test-Path $drift)) {
    $lines.Add('CAPSULE-BEGIN')
    foreach ($row in (& $drift capture --label $Arm --tools)) { $lines.Add([string]$row) }
    $lines.Add('CAPSULE-END')
}

$trials = [int]$plan['TRIALS']
for ($i = 0; $i -lt $trials; $i++) {
    $flake = if ((Get-GateHash $i ([long]$plan['SALT'])) % 1000 -lt [int]$plan['GATE']) { 1 } else { 0 }
    $psi = [System.Diagnostics.ProcessStartInfo]::new($sh)
    $psi.ArgumentList.Add('-c')
    $psi.ArgumentList.Add($plan['CMD'] + ' 2>&1')
    $psi.RedirectStandardOutput = $true
    $psi.StandardOutputEncoding = $utf8
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = $Dir
    $psi.Environment['BENCH_TRIAL'] = "$i"
    $psi.Environment['BENCH_FLAKE'] = "$flake"
    $proc = [System.Diagnostics.Process]::Start($psi)
    $reader = $proc.StandardOutput.ReadToEndAsync()
    if ($proc.WaitForExit(60000)) {
        $code = $proc.ExitCode
    } else {
        $proc.Kill($true)
        $proc.WaitForExit()
        $code = 124
    }
    $text = $reader.GetAwaiter().GetResult().TrimEnd("`n")
    $sig = 0
    if ($code -ne 0) {
        if ($plan['SIG'] -ne '' -and $text.Contains($plan['SIG'])) { $sig = 1 }
        if ($plan['SIGCODE'] -ne '' -and $code -eq [int]$plan['SIGCODE']) { $sig = 1 }
    }
    $tail = $text.Substring($text.LastIndexOf("`n") + 1)
    if ($tail.Length -gt 120) { $tail = $tail.Substring(0, 120) }
    $tail = $tail.Replace("`t", ' ').Replace("`r", ' ')
    $lines.Add("TRIAL`t$i`t$code`t$flake`t$sig`t$tail")
}
$lines.Add('DONE')

$text = ($lines -join "`n") + "`n"
if ($Out) {
    [System.IO.File]::WriteAllText($Out, $text, $utf8)
} else {
    [Console]::Out.Write($text)
}
