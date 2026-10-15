param(
    [Parameter(Mandatory)][string]$Root
)

$ErrorActionPreference = 'Stop'
$pwsh = (Get-Process -Id $PID).Path
foreach ($d in Get-ChildItem -Directory -Path $Root | Sort-Object Name) {
    if (-not (Test-Path (Join-Path $d.FullName 'green.plan'))) { continue }
    $skip = Join-Path $d.FullName 'native.skip'
    if (Test-Path $skip) {
        Write-Output "$($d.Name) skipped: $([System.IO.File]::ReadAllText($skip).Trim())"
        continue
    }
    foreach ($arm in 'green', 'red') {
        $out = Join-Path $d.FullName "$arm.out"
        if ((Test-Path $out) -and (Get-Item $out).Length -gt 0) { continue }
        $start = Get-Date
        & $pwsh -NoProfile -File (Join-Path $PSScriptRoot 'harness.ps1') -Dir $d.FullName -Arm $arm `
            -Out "$out.tmp" 2> (Join-Path $d.FullName "$arm.err")
        $code = $LASTEXITCODE
        Move-Item -Force "$out.tmp" $out
        Write-Output "$($d.Name) $arm exit=$code secs=$([int]((Get-Date) - $start).TotalSeconds)"
    }
}
Write-Output 'BATCH DONE'
