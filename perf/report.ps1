# Compares load-test runs in one table.
[CmdletBinding()]
param(
    [ValidateRange(0, 1000)]
    [int] $Last = 0,

    [string] $Match = ''
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'
$Invariant = [Globalization.CultureInfo]::InvariantCulture

function Format-Number($Value, [int] $Digits = 1) {
    if ($null -eq $Value) { return '-' }
    return ([double] $Value).ToString("F$Digits", $Invariant)
}

function Get-Field($Object, [string[]] $Path) {
    $current = $Object
    foreach ($name in $Path) {
        if ($null -eq $current) { return $null }
        if ($current -is [System.Collections.IDictionary]) { $current = $current[$name]; continue }
        $property = $current.PSObject.Properties[$name]
        if ($null -eq $property) { return $null }
        $current = $property.Value
    }
    return $current
}

function Get-Cores($Run, [string] $Container) {
    $avg = Get-Field $Run @('containers', $Container, 'cpuPercent', 'avg')
    if ($null -eq $avg) { return $null }
    return [double] $avg / 100
}

$resultsDir = Join-Path $PSScriptRoot 'results'
if (-not (Test-Path $resultsDir)) { throw 'No results yet: run perf/run.ps1 first' }

$files = @(Get-ChildItem -Path $resultsDir -Directory | Where-Object { $_.Name.Contains($Match) } | Sort-Object Name |
    ForEach-Object { Join-Path $_.FullName 'run.json' } | Where-Object { Test-Path $_ })
if ($Last -gt 0) { $files = @($files | Select-Object -Last $Last) }
if ($files.Count -eq 0) { throw 'No run.json found under perf/results' }

$lines = New-Object Collections.Generic.List[string]
$lines.Add('| run | mix | cache | CPU limit | JFR | GC | target req/s | steady req/s | p50 ms | p95 ms | p99 ms | errors | dropped | app CPU | app CPU ms/req (approx.) | JVM ms/req (JFR) | MySQL CPU | Redis CPU | k6 CPU | team cache hit (approx.) | window | result |')
$lines.Add('|---|---|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|')
foreach ($file in $files) {
    $run = [IO.File]::ReadAllText($file) | ConvertFrom-Json
    $all = $null
    foreach ($row in @(Get-Field $run @('k6', 'report', 'steady'))) {
        if ($null -ne $row -and $row.endpoint -ceq 'all') { $all = $row }
    }
    $errors = '-'
    if ($null -ne $all -and $null -ne $all.errorRate) { $errors = (Format-Number ($all.errorRate * 100) 3) + ' %' }
    $appCores = Get-Cores $run 'app'
    $msPerRequest = Get-Field $run @('appCpuMsPerRequest')
    $window = Get-Field $run @('window', 'alignment', 'status')
    if ($null -eq $window) { $window = '-' }
    $hit = Get-Field $run @('cacheEstimate', 'teamHitRatioApprox')
    $hitText = '-'
    if ($null -ne $hit) { $hitText = (Format-Number (100 * $hit) 1) + ' %' }
    $jfr = 'no'
    if (Get-Field $run @('settings', 'jfr')) { $jfr = 'yes' }
    $gc = Get-Field $run @('settings', 'gc')
    if ($null -eq $gc) { $gc = '-' }
    $lines.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} | {14} | {15} | {16} | {17} | {18} | {19} | {20} | {21} |' -f
        $run.runId, $run.settings.mix, $run.settings.cache, $run.settings.appCpus, $jfr, $gc, $run.settings.rate,
        (Format-Number (Get-Field $all @('rps')) 1), (Format-Number (Get-Field $all @('p50')) 2),
        (Format-Number (Get-Field $all @('p95')) 2), (Format-Number (Get-Field $all @('p99')) 2), $errors,
        (Format-Number (Get-Field $all @('dropped')) 0),
        (Format-Number $appCores 2), (Format-Number $msPerRequest 2),
        (Format-Number (Get-Field $run @('jfr', 'summary', 'jvmMsPerRequest')) 2),
        (Format-Number (Get-Cores $run 'mysql') 2), (Format-Number (Get-Cores $run 'redis') 2), (Format-Number (Get-Cores $run 'k6') 2),
        $hitText, $window, $(if ($run.passed) { 'PASS' } else { 'FAIL' })))
}
$lines.Add('')
$lines.Add('CPU columns: average cores used in the steady phase (docker stats; 1.00 = one core). app CPU ms/req: app ' +
    'cores x 1000 / requests the server finished per VM second (docker stats measures with the Docker VM''s clock), an ' +
    'approximation that includes everything the container did. window: the check that the evaluated window lies in ' +
    'the steady phase ("possibly shifted": see the run''s report.md). JVM ms/req: the JVM''s CPU from the Java Flight Recording (cgroup) per k6 ' +
    'request, runs with -Jfr only; GC: run.ps1 -Gc ("-": a run from before it was recorded, the JVM chose); the recording itself costs CPU, so compare with the same run without it. team cache ' +
    'hit: estimated from Redis command counts. dropped: whole run. "-": not measured or not applicable (see the run''s ' +
    'report.md). Details: perf/results/<run>/report.md.')

$text = ($lines -join "`n") + "`n"
[IO.File]::WriteAllText((Join-Path $resultsDir 'summary.md'), $text, (New-Object Text.UTF8Encoding $false))
Write-Host $text
Write-Host 'Written to perf\results\summary.md'
