# Runs load tests at increasing rates.
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string[]] $Rates = @('100', '200', '300', '400'),

    [ValidateNotNullOrEmpty()]
    [string] $Mix = 'read-heavy',

    [ValidateSet('on', 'off')]
    [string] $Cache = 'on',

    [ValidatePattern('^[0-9]+(\.[0-9]+)?$')]
    [string] $AppCpus = '2',

    [ValidateRange(1, 3600)]
    [int] $Warmup = 60,

    [ValidateRange(1, 7200)]
    [int] $Duration = 120,

    [switch] $Jfr,

    [ValidateSet('default', 'parallel')]
    [string] $Gc = 'default',

    [ValidateRange(0, 150)]
    [int] $DbPool = 0,

    [ValidateRange(0, 16384)]
    [int] $RedoLogCapacityMiB = 0,
    [ValidateRange(0, 100000)]
    [int] $IoCapacity = 0,

    [switch] $SkipBuild
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'
$Invariant = [Globalization.CultureInfo]::InvariantCulture

$MaxDroppedShare = 0.01
$MinThroughputShare = 0.95
$MaxSaturatedP95Ms = 500

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

$Mix = $Mix.ToLowerInvariant()
$Cache = $Cache.ToLowerInvariant()
$runScript = Join-Path $PSScriptRoot 'run.ps1'
$resultsDir = Join-Path $PSScriptRoot 'results'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runSuffix = $(if ($Gc -cne 'default') { "-gc-$Gc" } else { '' }) + $(if ($DbPool -gt 0) { "-pool$DbPool" } else { '' }) +
    $(if ($RedoLogCapacityMiB -gt 0) { "-redo$RedoLogCapacityMiB" } else { '' }) + $(if ($IoCapacity -gt 0) { "-io$IoCapacity" } else { '' })
$summaryFile = Join-Path $resultsDir ('capacity-{0}-{1}-cache-{2}-{3}cpu{4}.md' -f $stamp, $Mix, $Cache, $AppCpus, $runSuffix)
$parsedRates = New-Object Collections.Generic.List[int]
foreach ($part in @($Rates | ForEach-Object { "$_".Split(',') })) {
    $text = $part.Trim()
    if ($text -ceq '') { continue }
    [int] $value = 0
    if (-not [int]::TryParse($text, [Globalization.NumberStyles]::None, $Invariant, [ref] $value) -or $value -lt 1 -or $value -gt 20000) {
        throw "-Rates: '$text' is not a request rate between 1 and 20000"
    }
    $parsedRates.Add($value)
}
if ($parsedRates.Count -eq 0) { throw '-Rates: no request rate given' }
$sortedRates = @($parsedRates | Sort-Object -Unique)

$steps = New-Object Collections.Generic.List[object]
$stopReason = ''
$build = -not $SkipBuild
foreach ($rate in $sortedRates) {
    Write-Host ''
    Write-Host ("==== capacity step: {0} req/s ({1}, cache {2}, {3} CPU, GC {4}, DB pool {5}) ====" -f $rate, $Mix, $Cache, $AppCpus, $Gc,
        $(if ($DbPool -gt 0) { "$DbPool" } else { 'application default' })) -ForegroundColor Magenta
    $before = @()
    if (Test-Path $resultsDir) { $before = @(Get-ChildItem -Path $resultsDir -Directory | ForEach-Object { $_.Name }) }
    $arguments = @{ Mix = $Mix; Cache = $Cache; Rate = $rate; Warmup = $Warmup; Duration = $Duration; AppCpus = $AppCpus; Gc = $Gc; DbPool = $DbPool
        RedoLogCapacityMiB = $RedoLogCapacityMiB; IoCapacity = $IoCapacity }
    if ($Jfr) { $arguments['Jfr'] = $true }
    if (-not $build) { $arguments['SkipBuild'] = $true }
    $failure = ''
    try {
        & $runScript @arguments
        $code = $LASTEXITCODE
    } catch {
        $code = -1
        $failure = $_.Exception.Message
    }
    $build = $false

    $suffix = '-{0}-cache-{1}-{2}rps{3}' -f $Mix, $Cache, $rate, $runSuffix
    $runDir = @(Get-ChildItem -Path $resultsDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $before -cnotcontains $_.Name -and $_.Name.EndsWith($suffix) } | Sort-Object Name | Select-Object -Last 1)
    $run = $null
    if ($runDir.Count -gt 0 -and (Test-Path (Join-Path $runDir[0].FullName 'run.json'))) {
        $run = [IO.File]::ReadAllText((Join-Path $runDir[0].FullName 'run.json')) | ConvertFrom-Json
    }
    if ($null -eq $run) {
        $steps.Add([ordered]@{ rate = $rate; run = $null; verdict = 'no result'; note = $failure })
        $stopReason = "the step at $rate req/s produced no result$(if ($failure) { ": $failure" })"
        break
    }

    $all = $null
    foreach ($row in @(Get-Field $run @('k6', 'report', 'steady'))) {
        if ($null -ne $row -and $row.endpoint -ceq 'all') { $all = $row }
    }
    $planned = Get-Field $all @('planned')
    $sent = Get-Field $all @('requests')
    $dropped = $null
    if ($null -ne $planned -and $null -ne $sent) { $dropped = [Math]::Max(0, [double] $planned - [double] $sent) }
    $rps = Get-Field $all @('rps')
    $p95 = Get-Field $all @('p95')
    $reasons = @()
    if ($null -eq $all -or $null -eq $rps) {
        $reasons += 'no steady-phase result from k6'
    } else {
        if ($null -ne $planned -and $planned -gt 0 -and $null -ne $dropped -and $dropped -gt $MaxDroppedShare * $planned) {
            $reasons += ('{0} planned steady requests not sent ({1} %)' -f $dropped, (Format-Number (100.0 * $dropped / $planned) 1))
        }
        if ($rps -lt $MinThroughputShare * $rate) {
            $reasons += ('throughput {0} req/s < {1} % of {2}' -f (Format-Number $rps 1), (100 * $MinThroughputShare), $rate)
        }
        if ($null -ne $p95 -and $p95 -gt $MaxSaturatedP95Ms) {
            $reasons += ('p95 {0} ms > {1} ms' -f (Format-Number $p95 0), $MaxSaturatedP95Ms)
        }
    }
    $saturated = $reasons.Count -gt 0
    $verdict = 'target met'
    if (-not $run.passed) { $verdict = 'target missed' }
    if ($saturated) { $verdict = 'saturated' }
    $steps.Add([ordered]@{ rate = $rate; run = $run; all = $all; verdict = $verdict; note = ($reasons -join '; '); exitCode = $code })
    if ($saturated) {
        $stopReason = "the application saturated at $rate req/s ($($reasons -join '; '))"
        break
    }
}

$md = New-Object Collections.Generic.List[string]
$md.Add("# Capacity steps: mix $Mix, cache $Cache, $AppCpus CPU, GC $Gc, DB pool $(if ($DbPool -gt 0) { $DbPool } else { 'application default' }), redo log $(if ($RedoLogCapacityMiB -gt 0) { "$RedoLogCapacityMiB MiB" } else { 'default' }), io_capacity $(if ($IoCapacity -gt 0) { $IoCapacity } else { 'default' }), $Warmup s warm-up + $Duration s steady per step")
$md.Add('')
$md.Add('| req/s | steady req/s | p50 ms | p95 ms | p99 ms | errors | steady not sent | dropped (whole run) | app CPU (cores) | app CPU ms/req (approx.) | JVM ms/req (JFR) | MySQL CPU | Tomcat busy avg | Hikari pending avg | team cache hit (approx.) | window | verdict | run |')
$md.Add('|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|')
foreach ($step in $steps) {
    $run = $step.run
    if ($null -eq $run) {
        $md.Add(('| {0} | | | | | | | | | | | | | | | | {1} | {2} |' -f $step.rate, $step.verdict, $step.note))
        continue
    }
    $all = $step.all
    $appCores = $null
    $avg = Get-Field $run @('containers', 'app', 'cpuPercent', 'avg')
    if ($null -ne $avg) { $appCores = [double] $avg / 100 }
    $msPerRequest = Get-Field $run @('appCpuMsPerRequest')
    $window = Get-Field $run @('window', 'alignment', 'status')
    if ($null -eq $window) { $window = '-' }
    $mysqlAvg = Get-Field $run @('containers', 'mysql', 'cpuPercent', 'avg')
    $errors = '-'
    if ($null -ne (Get-Field $all @('errorRate'))) { $errors = (Format-Number ($all.errorRate * 100) 3) + ' %' }
    $hit = Get-Field $run @('cacheEstimate', 'teamHitRatioApprox')
    $hitText = '-'
    if ($null -ne $hit) { $hitText = (Format-Number (100 * $hit) 1) + ' %' }
    $verdictText = $step.verdict
    if ($step.note) { $verdictText = "$verdictText ($($step.note))" }
    $notSent = $null
    if ($null -ne (Get-Field $all @('planned')) -and $null -ne (Get-Field $all @('requests'))) {
        $notSent = [Math]::Max(0, [double] $all.planned - [double] $all.requests)
    }
    $line = '| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} | {14} | {15} | {16} | {17} |' -f
        $step.rate, (Format-Number (Get-Field $all @('rps')) 1), (Format-Number (Get-Field $all @('p50')) 2),
        (Format-Number (Get-Field $all @('p95')) 2), (Format-Number (Get-Field $all @('p99')) 2), $errors,
        (Format-Number $notSent 0), (Format-Number (Get-Field $all @('dropped')) 0), (Format-Number $appCores 2), (Format-Number $msPerRequest 2),
        (Format-Number (Get-Field $run @('jfr', 'summary', 'jvmMsPerRequest')) 2),
        $(if ($null -ne $mysqlAvg) { Format-Number ([double] $mysqlAvg / 100) 2 } else { '-' }),
        (Format-Number (Get-Field $run @('appGauges', 'tomcat.threads.busy', 'avg')) 1),
        (Format-Number (Get-Field $run @('appGauges', 'hikaricp.connections.pending', 'avg')) 1),
        $hitText, $window, $verdictText, $run.runId
    $md.Add($line)
}
$md.Add('')
$met = @($steps | Where-Object { $_.verdict -ceq 'target met' } | ForEach-Object { $_.rate })
$highest = $null
if ($met.Count -gt 0) { $highest = ($met | Measure-Object -Maximum).Maximum }
$skipped = @($sortedRates | Where-Object { $_ -gt $steps[$steps.Count - 1].rate })
$md.Add('Highest step that met the target: ' + $(if ($null -ne $highest) { "$highest req/s" } else { 'none' }) + '.')
if ($stopReason) {
    $md.Add("Stopped: $stopReason; not run: $(if ($skipped.Count -gt 0) { ($skipped -join ', ') + ' req/s' } else { 'nothing' }).")
}
$md.Add('')
$md.Add('The capacity lies between the highest step that met the target and the next step; these steps do not ' +
    'measure it more precisely. app CPU ms/req: app container cores (docker stats) x 1000 / requests the server ' +
    'finished per VM second (docker stats measures with the Docker VM''s clock); an approximation that includes ' +
    'everything the container did. window: the check that the evaluated window lies in the steady phase; a ' +
    '"possibly shifted" step should be repeated before its numbers are compared. JVM ms/req: from the Java Flight Recording (runs with -Jfr; the recording costs CPU itself). Saturated: ' +
    "more than $(100 * $MaxDroppedShare) % of the planned steady requests not sent (k6's dropped iterations also count the warm-up), throughput below $(100 * $MinThroughputShare) % of the rate, or p95 above $MaxSaturatedP95Ms ms. " +
    'Every value comes from perf/results/<run>/run.json; "-": not measured or not applicable (see that run''s report.md).')

New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$text = ($md -join "`n") + "`n"
[IO.File]::WriteAllText($summaryFile, $text, (New-Object Text.UTF8Encoding $false))
Write-Host ''
Write-Host $text
Write-Host "Written to perf\results\$(Split-Path $summaryFile -Leaf)"
if ($steps.Count -gt 0 -and $null -eq $steps[$steps.Count - 1].run) { exit 1 }
exit 0
