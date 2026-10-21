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

function Get-PoolSize($Run) {
    $size = Get-Field $Run @('settings', 'dbPoolMax')
    if ($null -eq $size) { $size = Get-Field $Run @('appGauges', 'hikaricp.connections.max', 'max') }
    return $size
}

function Get-EndpointP95($Run, [string] $Endpoint) {
    foreach ($row in @(Get-Field $Run @('k6', 'report', 'steady'))) {
        if ($null -ne $row -and $row.endpoint -ceq $Endpoint) { return Get-Field $row @('p95') }
    }
    return $null
}

function Get-MysqlRate($Run, [string] $Counter) {
    $entry = Get-Field $Run @('mysql', $Counter)
    if ($null -eq $entry -or (Get-Field $entry @('steadyStatus')) -cne 'ok') { return $null }
    return Get-Field $entry @('steady')
}

function Get-Ratio($Numerator, $Denominator) {
    if ($null -eq $Numerator -or $null -eq $Denominator -or [double] $Denominator -le 0) { return $null }
    return [double] $Numerator / [double] $Denominator
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
$runs = @($files | ForEach-Object { [IO.File]::ReadAllText($_) | ConvertFrom-Json })
$lines.Add('| run | mix | cache | CPU limit | JFR | GC | DB pool | target req/s | steady req/s | p50 ms | p95 ms | p99 ms | errors | dropped | app CPU | app CPU ms/req (approx.) | JVM ms/req (JFR) | MySQL CPU | Redis CPU | k6 CPU | team cache hit (approx.) | window | result |')
$lines.Add('|---|---|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|')
foreach ($run in $runs) {
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
    $lines.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} | {14} | {15} | {16} | {17} | {18} | {19} | {20} | {21} | {22} |' -f
        $run.runId, $run.settings.mix, $run.settings.cache, $run.settings.appCpus, $jfr, $gc,
        (Format-Number (Get-PoolSize $run) 0), $run.settings.rate,
        (Format-Number (Get-Field $all @('rps')) 1), (Format-Number (Get-Field $all @('p50')) 2),
        (Format-Number (Get-Field $all @('p95')) 2), (Format-Number (Get-Field $all @('p99')) 2), $errors,
        (Format-Number (Get-Field $all @('dropped')) 0),
        (Format-Number $appCores 2), (Format-Number $msPerRequest 2),
        (Format-Number (Get-Field $run @('jfr', 'summary', 'jvmMsPerRequest')) 2),
        (Format-Number (Get-Cores $run 'mysql') 2), (Format-Number (Get-Cores $run 'redis') 2), (Format-Number (Get-Cores $run 'k6') 2),
        $hitText, $window, $(if ($run.passed) { 'PASS' } else { 'FAIL' })))
}
$lines.Add('')
$lines.Add('Database path (steady phase):')
$lines.Add('')
$lines.Add('| run | DB pool | p95 ms | get p95 | progress p95 | join p95 | claim p95 | dropped | Hikari active avg | Hikari pending avg | Hikari pending max | pool wait ms | commits/s | COMMIT ms | redo sync ms | binlog sync ms | commits per redo sync | commits per binlog sync | redo capacity MiB | checkpoint age max MiB | pages flushed/s | data file syncs/s | invariant violations |')
$lines.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
foreach ($run in $runs) {
    $all = $null
    foreach ($row in @(Get-Field $run @('k6', 'report', 'steady'))) {
        if ($null -ne $row -and $row.endpoint -ceq 'all') { $all = $row }
    }
    $commits = Get-MysqlRate $run 'statements.commit.count'
    $redoCapacity = Get-Field $run @('innodbFlushing', 'redoLogCapacityBytes')
    $checkpointMax = Get-Field $run @('innodbFlushing', 'checkpointAgeBytes', 'max')
    $violationMap = Get-Field $run @('invariants', 'violations')
    $violations = $null
    if ($null -ne $violationMap) { $violations = @($violationMap.PSObject.Properties).Count }
    $lines.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} | {14} | {15} | {16} | {17} | {18} | {19} | {20} | {21} | {22} |' -f
        $run.runId, (Format-Number (Get-PoolSize $run) 0), (Format-Number (Get-Field $all @('p95')) 2),
        (Format-Number (Get-EndpointP95 $run 'get') 2), (Format-Number (Get-EndpointP95 $run 'progress') 2),
        (Format-Number (Get-EndpointP95 $run 'join') 2), (Format-Number (Get-EndpointP95 $run 'claim') 2),
        (Format-Number (Get-Field $all @('dropped')) 0),
        (Format-Number (Get-Field $run @('appGauges', 'hikaricp.connections.active', 'avg')) 1),
        (Format-Number (Get-Field $run @('appGauges', 'hikaricp.connections.pending', 'avg')) 1),
        (Format-Number (Get-Field $run @('appGauges', 'hikaricp.connections.pending', 'max')) 0),
        (Format-Number (Get-Field $run @('appTimers', 'db.pool.acquire', 'steadyMeanMs')) 2),
        (Format-Number $commits 1), (Format-Number (Get-Field $run @('commitPath', 'commitMeanMs')) 2),
        (Format-Number (Get-Field $run @('commitPath', 'redoLogSyncMeanMs')) 2),
        (Format-Number (Get-Field $run @('commitPath', 'binlogSyncMeanMs')) 2),
        (Format-Number (Get-Ratio $commits (Get-MysqlRate $run 'file_io.redo_log.sync_count')) 2),
        (Format-Number (Get-Ratio $commits (Get-MysqlRate $run 'file_io.binlog.sync_count')) 2),
        $(if ($null -ne $redoCapacity) { Format-Number ($redoCapacity / 1MB) 0 } else { '-' }),
        $(if ($null -ne $checkpointMax) { Format-Number ($checkpointMax / 1MB) 1 } else { '-' }),
        (Format-Number (Get-Field $run @('innodbFlushing', 'pagesFlushedPerSecond')) 1),
        (Format-Number (Get-Field $run @('innodbFlushing', 'dataFileSyncsPerSecond')) 1),
        (Format-Number $violations 0)))
}
$lines.Add('')
$lines.Add('Database path: p95 per endpoint from k6; Hikari gauges sampled every 2 s; pool wait = db.pool.acquire mean; ' +
    'COMMIT = MySQL''s statement timer (valid intervals only); redo/binlog sync = mean fsync of the log file (CPU cycle ' +
    'counter); commits per sync = how many commits one fsync made durable (MySQL''s group commit). DB pool: run.ps1 -DbPool, ' +
    'or the largest hikaricp.connections.max sample for runs from before it. Redo capacity, checkpoint age (maximum of the ' +
    'samples), pages flushed and data file syncs: report.md "Redo log and page flushing" ("-" for runs from before these ' +
    'counters). Invariant violations: failed checks of perf/sql/verify.sql after the run (0 = all passed).')
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
