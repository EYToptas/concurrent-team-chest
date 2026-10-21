# Builds the report of a collected load test.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ResultDir,

    [switch] $SkipJfr
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
. (Join-Path $PSScriptRoot 'common.ps1')

$MinWindowMarginSeconds = 10
$WindowMarginShare = 0.05
$MinCoverage = 0.8
$StepThresholdMs = 50
$StatementGuardMs = 1000
$MinStatementShare = 0.5
$MaxConcurrentStatements = 200
$WrappedTimerMs = 9223372036.854775808
$AlignmentTolerance = 0.05

function Import-Window([string] $Path, [int64] $Start, [int64] $End) {
    if (-not (Test-Path $Path)) { return @() }
    return @(Import-Csv $Path | Where-Object { [int64] $_.epoch_ms -ge $Start -and [int64] $_.epoch_ms -le $End })
}

function Get-Coverage($Times, [int] $Failures, [int64] $Start, [int64] $End) {
    $sorted = @($Times | ForEach-Object { [int64] $_ } | Sort-Object -Unique)
    $length = [double] ($End - $Start)
    $result = [ordered]@{ samples = $sorted.Count; failures = $Failures; coverage = 0.0; maxGapSeconds = $length / 1000.0 }
    if ($sorted.Count -eq 0 -or $length -le 0) { return $result }
    $gap = [Math]::Max([double] ($sorted[0] - $Start), [double] ($End - $sorted[$sorted.Count - 1]))
    for ($i = 1; $i -lt $sorted.Count; $i++) { $gap = [Math]::Max($gap, [double] ($sorted[$i] - $sorted[$i - 1])) }
    $result.coverage = [Math]::Min(1.0, [double] ($sorted[$sorted.Count - 1] - $sorted[0]) / $length)
    $result.maxGapSeconds = $gap / 1000.0
    return $result
}

function Test-Covered($Coverage) {
    return ($null -ne $Coverage) -and ($Coverage.samples -ge 2) -and ($Coverage.coverage -ge $MinCoverage)
}

function Get-CounterDelta($Rows, [string] $Name) {
    $points = @($Rows | Where-Object { $_.name -ceq $Name } | Sort-Object { [int64] $_.epoch_ms })
    if ($points.Count -lt 2) { return $null }
    $first = $points[0]
    $last = $points[$points.Count - 1]
    return [ordered]@{
        value = (ConvertTo-Double $last.value) - (ConvertTo-Double $first.value)
        seconds = ([int64] $last.epoch_ms - [int64] $first.epoch_ms) / 1000.0
    }
}

function Measure-Window($Rows, [string] $Property, [int64] $Start, [int64] $End) {
    $values = @($Rows | ForEach-Object { ConvertTo-Double $_.$Property })
    $coverage = Get-Coverage @($Rows | ForEach-Object { $_.epoch_ms }) 0 $Start $End
    if ($values.Count -eq 0 -or -not (Test-Covered $coverage)) { return $null }
    $m = $values | Measure-Object -Average -Maximum
    return [ordered]@{ avg = $m.Average; max = $m.Maximum; samples = $values.Count }
}

function Get-ValueAt($Points, [double] $At) {
    for ($i = 1; $i -lt $Points.Count; $i++) {
        $a = $Points[$i - 1]
        $b = $Points[$i]
        if ($a.t -le $At -and $b.t -ge $At) {
            if ($b.t -eq $a.t) { return $a.v }
            return $a.v + ($b.v - $a.v) * ($At - $a.t) / ($b.t - $a.t)
        }
    }
    return $null
}

function Get-ClockCheck($Start, $End) {
    if ($null -eq $Start -or $null -eq $End) { return $null }
    $hostSeconds = ([double] $End.hostMs - [double] $Start.hostMs) / 1000.0
    if ($hostSeconds -le 0) { return $null }
    $rate = ([double] $End.vmUptimeSeconds - [double] $Start.vmUptimeSeconds) / $hostSeconds
    $expectedEndWall = [double] $Start.vmWallMs + ([double] $End.hostMs - [double] $Start.hostMs) * $rate
    return [ordered]@{
        hostSeconds = $hostSeconds
        monotonicRate = $rate
        rateUncertainty = ([double] $Start.uncertaintyMs + [double] $End.uncertaintyMs + 20) / 1000.0 / $hostSeconds
        wallOffsetStartMs = [double] $Start.vmWallMs - [double] $Start.hostMs
        wallOffsetEndMs = [double] $End.vmWallMs - [double] $End.hostMs
        wallCorrectionMs = [double] $End.vmWallMs - $expectedEndWall
    }
}

function Get-ClockTimeline([string] $Path) {
    if (-not (Test-Path $Path)) { return $null }
    $points = @(Import-Csv $Path | ForEach-Object {
            [pscustomobject]@{ hostMs = [double] $_.epoch_ms; uptime = (ConvertTo-Double $_.vm_uptime_s); wallMs = (ConvertTo-Double $_.vm_wall_ms) }
        } | Sort-Object hostMs)
    if ($points.Count -lt 10) { return $null }
    $h0 = $points[0].hostMs
    $u0 = $points[0].uptime
    $n = $points.Count
    $sx = 0.0; $sy = 0.0; $sxx = 0.0; $sxy = 0.0
    foreach ($p in $points) {
        $x = ($p.hostMs - $h0) / 1000.0
        $y = $p.uptime - $u0
        $sx += $x; $sy += $y; $sxx += $x * $x; $sxy += $x * $y
    }
    $denominator = $n * $sxx - $sx * $sx
    if ($denominator -le 0) { return $null }
    $rate = ($n * $sxy - $sx * $sy) / $denominator
    $intercept = ($sy - $rate * $sx) / $n
    $squares = 0.0
    foreach ($p in $points) {
        $residual = ($p.uptime - $u0) - ($intercept + $rate * ($p.hostMs - $h0) / 1000.0)
        $squares += $residual * $residual
    }
    $sigma = [Math]::Sqrt($squares / [Math]::Max(1, $n - 2))
    $centered = $sxx - $sx * $sx / $n
    $steps = New-Object Collections.Generic.List[object]
    $offsets = @()
    for ($i = 0; $i -lt $n; $i++) {
        $offsets += $points[$i].wallMs - $points[$i].hostMs
        if ($i -eq 0) { continue }
        $before = $points[$i - 1].wallMs - $points[$i - 1].uptime * 1000.0
        $after = $points[$i].wallMs - $points[$i].uptime * 1000.0
        if ([Math]::Abs($after - $before) -gt $StepThresholdMs) {
            $steps.Add([ordered]@{ fromHostMs = $points[$i - 1].hostMs; toHostMs = $points[$i].hostMs; ms = $after - $before })
        }
    }
    $stepTotal = 0.0
    $largest = 0.0
    foreach ($step in $steps) {
        $stepTotal += $step.ms
        if ([Math]::Abs($step.ms) -gt [Math]::Abs($largest)) { $largest = $step.ms }
    }
    $driftTotal = ($points[$n - 1].wallMs - $points[$n - 1].uptime * 1000.0) - ($points[0].wallMs - $points[0].uptime * 1000.0)
    $offsetStats = $offsets | Measure-Object -Minimum -Maximum
    return [ordered]@{
        readings = $n
        firstHostMs = $h0
        lastHostMs = $points[$n - 1].hostMs
        uptime0 = $u0
        monotonicRate = $rate
        intercept = $intercept
        rateUncertainty = 2 * $sigma / [Math]::Sqrt([Math]::Max($centered, 1e-9))
        jitterMs = $sigma * 1000.0
        steps = $steps.ToArray()
        stepCount = $steps.Count
        stepTotalMs = $stepTotal
        largestStepMs = $largest
        wallCorrectionMs = $driftTotal
        wallOffsetStartMs = $offsets[0]
        wallOffsetEndMs = $offsets[$n - 1]
        wallOffsetMinMs = $offsetStats.Minimum
        wallOffsetMaxMs = $offsetStats.Maximum
        points = $points
    }
}

function Convert-UptimeToHost($Timeline, [double] $Uptime) {
    return $Timeline.firstHostMs + (($Uptime - $Timeline.uptime0) - $Timeline.intercept) / $Timeline.monotonicRate * 1000.0
}

function Get-WallOffsetAt($Timeline, [double] $WallMs) {
    $points = $Timeline.points
    $best = 0
    for ($i = 1; $i -lt $points.Count; $i++) {
        if ([Math]::Abs($points[$i].wallMs - $WallMs) -lt [Math]::Abs($points[$best].wallMs - $WallMs)) { $best = $i }
    }
    $offset = $points[$best].wallMs - $points[$best].uptime * 1000.0
    $ambiguity = 0.0
    foreach ($j in @(($best - 1), ($best + 1))) {
        if ($j -ge 0 -and $j -lt $points.Count) {
            $ambiguity = [Math]::Max($ambiguity, [Math]::Abs(($points[$j].wallMs - $points[$j].uptime * 1000.0) - $offset))
        }
    }
    return [ordered]@{ offsetMs = $offset; ambiguityMs = $ambiguity; distanceMs = [Math]::Abs($points[$best].wallMs - $WallMs) }
}

function Test-StepIn($Timeline, [double] $FromMs, [double] $ToMs) {
    if ($null -eq $Timeline) { return $null }
    foreach ($step in $Timeline.steps) {
        if ($step.toHostMs -ge $FromMs -and $step.fromHostMs -le $ToMs) { return $true }
    }
    return $false
}

function Get-StatementDelta($Rows, [string] $TimeKey, [string] $CountKey, [string] $WrapKey) {
    $times = @($Rows | Where-Object { $_.name -ceq $TimeKey } | Sort-Object { [int64] $_.epoch_ms })
    $counts = @{}
    foreach ($row in @($Rows | Where-Object { $_.name -ceq $CountKey })) { $counts[[string] $row.epoch_ms] = ConvertTo-Double $row.value }
    $wraps = @{}
    foreach ($row in @($Rows | Where-Object { $_.name -ceq $WrapKey })) { $wraps[[string] $row.epoch_ms] = ConvertTo-Double $row.value }
    $result = [ordered]@{ time = 0.0; count = 0.0; seconds = 0.0; intervals = 0; used = 0; reasons = @() }
    $reasons = @{}
    for ($i = 1; $i -lt $times.Count; $i++) {
        $a = $times[$i - 1]
        $b = $times[$i]
        $result.intervals++
        $seconds = ([int64] $b.epoch_ms - [int64] $a.epoch_ms) / 1000.0
        $delta = (ConvertTo-Double $b.value) - (ConvertTo-Double $a.value)
        $reason = ''
        $step = Test-StepIn $clockTimeline ([double] $a.epoch_ms - $StatementGuardMs) ([double] $b.epoch_ms + 1000)
        if ($null -eq $step) {
            if ($wallStepped) { $reason = 'the VM wall clock was stepped during the run and this run has no per-second clock record' }
        } elseif ($step) {
            $reason = 'a VM wall-clock step'
        }
        if (-not $reason -and $wraps.ContainsKey([string] $a.epoch_ms) -and $wraps.ContainsKey([string] $b.epoch_ms) -and
                $wraps[[string] $a.epoch_ms] -lt $wraps[[string] $b.epoch_ms]) { $reason = 'a statement timer ran backwards (wrapped)' }
        if (-not $reason -and ($delta -lt 0 -or $delta -gt $seconds * $MaxConcurrentStatements * 1e6)) { $reason = 'an impossible value' }
        $countDelta = $null
        if ($counts.ContainsKey([string] $a.epoch_ms) -and $counts.ContainsKey([string] $b.epoch_ms)) {
            $countDelta = $counts[[string] $b.epoch_ms] - $counts[[string] $a.epoch_ms]
        }
        if (-not $reason -and $null -eq $countDelta) { $reason = 'no statement count' }
        if ($reason) { $reasons[$reason] = 1 + $(if ($reasons.ContainsKey($reason)) { $reasons[$reason] } else { 0 }); continue }
        $result.used++
        $result.time += $delta
        $result.count += $countDelta
        $result.seconds += $seconds
    }
    $result.reasons = @($reasons.Keys | Sort-Object | ForEach-Object { '{0} x {1}' -f $reasons[$_], $_ })
    return $result
}

$resultDir = (Resolve-Path $ResultDir).Path
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$rawFile = Join-Path $resultDir 'raw.json'
if (-not (Test-Path $rawFile)) { throw "No raw.json in $resultDir (written by perf/run.ps1)" }
$raw = [IO.File]::ReadAllText($rawFile) | ConvertFrom-Json

$runId = [string] $raw.runId
$Mix = [string] $raw.settings.mix
$Cache = [string] $raw.settings.cache
$Rate = [int] $raw.settings.rate
$Warmup = [int] $raw.settings.warmupSeconds
$Duration = [int] $raw.settings.durationSeconds
$AppCpus = [string] $raw.settings.appCpus
$AppMemory = [string] $raw.settings.appMemory
$Jfr = [bool] $raw.settings.jfr
$GcOption = Get-JsonValue $raw.settings 'gc'
$DbPoolOption = Get-JsonValue $raw.settings 'dbPool'
$DbPoolMax = Get-JsonValue $raw.settings 'dbPoolMax'
$RedoOption = Get-JsonValue $raw.settings 'redoLogCapacityMiB'
$IoOption = Get-JsonValue $raw.settings 'ioCapacity'
$sharePercent = [ordered]@{}
foreach ($endpoint in $Endpoints) {
    $value = Get-JsonValue $raw.settings.sharesPercent $endpoint
    $sharePercent[$endpoint] = $(if ($null -ne $value) { [int] $value } else { 0 })
}
$activeEndpoints = @($Endpoints | Where-Object { $sharePercent[$_] -gt 0 })
$teams = [int] $raw.data.teams
$completedTeams = [int] $raw.data.completedTeams
$joinUsers = [int] $raw.data.joinUsersNeeded
$loadStart = [DateTimeOffset]::Parse([string] $raw.load.startUtc, $Invariant).UtcDateTime
$loadEnd = [DateTimeOffset]::Parse([string] $raw.load.endUtc, $Invariant).UtcDateTime
$k6ExitCode = [int] $raw.load.k6ExitCode
$clockStart = $raw.clockSamples.start
$clockEnd = $raw.clockSamples.end
$appStartedVmMs = Get-JsonValue $raw 'appStartedVmMs'
if ($null -ne $appStartedVmMs) { $appStartedVmMs = [double] $appStartedVmMs }
$gaugeMeters = @($raw.meters.gauges)
$timerMeters = @($raw.meters.timers)
$appMeters = $gaugeMeters + $timerMeters
$tomcatPool = $raw.tomcatPool
$mysqlBefore = ConvertTo-Map $raw.mysqlBefore
$mysqlAfter = ConvertTo-Map $raw.mysqlAfter
$redisBefore = ConvertTo-Map $raw.redisBefore
$redisAfter = ConvertTo-Map $raw.redisAfter
$timersBefore = [ordered]@{}
$timersAfter = [ordered]@{}
foreach ($t in $AppTimers) {
    $timersBefore[$t.key] = ConvertTo-Map (Get-JsonValue $raw.timersBefore $t.key)
    $timersAfter[$t.key] = ConvertTo-Map (Get-JsonValue $raw.timersAfter $t.key)
}
$topStatements = @($raw.topStatements)
$verify = ConvertTo-Map $raw.verify
$jvm = $raw.jvm
$environment = $raw.environment
$rawNotes = @(Get-JsonValue $raw 'notes' | Where-Object { $null -ne $_ })
$marginMs = [int64] ([Math]::Max([double] $MinWindowMarginSeconds, $WindowMarginShare * $Duration) * 1000)

$k6ReportFile = Join-Path $resultDir 'k6-report.json'
$k6 = $null
if (Test-Path $k6ReportFile) { $k6 = [IO.File]::ReadAllText($k6ReportFile) | ConvertFrom-Json }
$k6Rows = @{}
if ($null -ne $k6) { foreach ($row in $k6.steady) { $k6Rows[[string] $row.endpoint] = $row } }

$clockTimeline = Get-ClockTimeline (Join-Path $resultDir 'clock.csv')
$clock = Get-ClockCheck $clockStart $clockEnd
$clockRate = $null
$clockSource = 'none'
if ($null -ne $clockTimeline) {
    $clockRate = $clockTimeline.monotonicRate
    $clockSource = 'per-second readings'
} elseif ($null -ne $clock -and $clock.monotonicRate -gt 0.5 -and $clock.monotonicRate -lt 1.5) {
    $clockRate = $clock.monotonicRate
    $clockSource = 'two readings'
}
$wallStepped = $false
if ($null -ne $clockTimeline) {
    $wallStepped = $clockTimeline.stepCount -gt 0
} elseif ($null -ne $clock) {
    $wallStepped = [Math]::Abs($clock.wallCorrectionMs) -gt 100
}

$windowStart = [DateTimeOffset]::new($loadStart).ToUnixTimeMilliseconds()
$windowEnd = [DateTimeOffset]::new($loadEnd).ToUnixTimeMilliseconds()
$windowName = 'whole k6 run'
$windowBasis = 'k6 not reported: the whole k6 run on this computer''s clock, without margins'
$anchorAmbiguityMs = $null
$vmWindow = $null
$jfrWindow = $null
$jfrWindowBasis = 'no Java Flight Recording'
$k6StartUptime = $null
$k6StartHostMs = $null
if ($null -ne (Get-JsonValue $k6 'steadyWindow')) {
    $startedAt = [double] $k6.steadyWindow.startEpochMs - $Warmup * 1000.0
    $vmWindow = [ordered]@{ start = [int64] ($startedAt + $Warmup * 1000.0 + $marginMs)
        end = [int64] ($startedAt + ($Warmup + $Duration) * 1000.0 - $marginMs) }
    $windowName = 'steady phase'
    $converted = $false
    if ($null -ne $clockTimeline) {
        $atSetup = Get-WallOffsetAt $clockTimeline $startedAt
        $uptimeSetup = ($startedAt - $atSetup.offsetMs) / 1000.0
        $k6StartUptime = $uptimeSetup
        $windowStart = [int64] ((Convert-UptimeToHost $clockTimeline ($uptimeSetup + $Warmup)) + $marginMs)
        $windowEnd = [int64] ((Convert-UptimeToHost $clockTimeline ($uptimeSetup + $Warmup + $Duration)) - $marginMs)
        $anchorAmbiguityMs = $atSetup.ambiguityMs + $atSetup.distanceMs * [Math]::Abs($clockRate - 1)
        $windowBasis = 'converted from the Docker VM''s clocks with the per-second clock record'
        $converted = $true
        if ($Jfr -and $null -ne $appStartedVmMs) {
            $atJvmStart = Get-WallOffsetAt $clockTimeline ($appStartedVmMs + 1000)
            $shift = $atJvmStart.offsetMs - $atSetup.offsetMs
            $jfrWindow = [ordered]@{ start = [int64] ($vmWindow.start + $shift); end = [int64] ($vmWindow.end + $shift)
                shiftMs = $shift; ambiguityMs = $atJvmStart.ambiguityMs + $atSetup.ambiguityMs }
            $jfrWindowBasis = 'corrected for the wall-clock steps between the JVM start and k6''s start'
        }
    } elseif ($null -ne $clockRate) {
        $setupEndHost = [double] $clockStart.hostMs + ($startedAt - [double] $clockStart.vmWallMs) / $clockRate
        if ($setupEndHost -ge [double] $clockStart.hostMs - 1000 -and $setupEndHost -le [double] $clockEnd.hostMs) {
            $windowStart = [int64] ($setupEndHost + $Warmup * 1000.0 / $clockRate + $marginMs)
            $windowEnd = [int64] ($setupEndHost + ($Warmup + $Duration) * 1000.0 / $clockRate - $marginMs)
            $windowBasis = 'converted from the Docker VM''s clocks with the two clock readings (no per-second record)'
            $converted = $true
            $k6StartHostMs = $setupEndHost
        }
    }
    if (-not $converted) {
        $windowStart = $vmWindow.start
        $windowEnd = $vmWindow.end
        $windowBasis = 'the Docker VM''s wall clock as k6 reported it, NOT converted (no usable clock readings)'
    }
    if ($Jfr -and $null -eq $jfrWindow) {
        $jfrWindow = [ordered]@{ start = $vmWindow.start; end = $vmWindow.end; shiftMs = $null; ambiguityMs = $null }
        $jfrWindowBasis = 'the VM wall-clock window, NOT corrected for wall-clock steps (no per-second clock record)'
    }
}
$windowSeconds = ($windowEnd - $windowStart) / 1000.0

$statsRows = Import-Window (Join-Path $resultDir 'docker-stats.csv') $windowStart $windowEnd
$appRows = @(Import-Window (Join-Path $resultDir 'app-gauges.csv') $windowStart $windowEnd) +
    @(Import-Window (Join-Path $resultDir 'app-timers.csv') $windowStart $windowEnd)
$mysqlRows = Import-Window (Join-Path $resultDir 'mysql-status.csv') $windowStart $windowEnd
$redisRows = Import-Window (Join-Path $resultDir 'redis-status.csv') $windowStart $windowEnd

$quality = [ordered]@{}
$okStats = @($statsRows | Where-Object { $_.container -cne 'sample:FAILED' })
$appContainerRows = @($okStats | Where-Object { $_.container -clike "$Project-app-*" })
$failedStats = @($statsRows | Where-Object { $_.container -ceq 'sample:FAILED' }).Count
$quality['docker stats (app container)'] = Get-Coverage @($appContainerRows | ForEach-Object { $_.epoch_ms }) $failedStats $windowStart $windowEnd
$appFailures = @($appRows | Where-Object { $_.name -clike '*:FAILED' })
$appMissing = @($appRows | Where-Object { $_.name -clike '*:MISSING' })
$timerKeys = @($AppTimers | ForEach-Object { $_.key })
$timerRows = @{}
foreach ($key in $timerKeys) {
    $rows = @($appRows | Where-Object { $_.name -ceq "$($key):COUNT" -or $_.name -ceq "$($key):TOTAL_TIME" })
    foreach ($missing in @($appRows | Where-Object { $_.name -ceq "$($key):MISSING" })) {
        $rows += [pscustomobject]@{ epoch_ms = $missing.epoch_ms; name = "$($key):COUNT"; value = '0' }
        $rows += [pscustomobject]@{ epoch_ms = $missing.epoch_ms; name = "$($key):TOTAL_TIME"; value = '0' }
    }
    $timerRows[$key] = $rows
}
$appCoverage = [ordered]@{}
foreach ($meter in $appMeters) {
    $key = $meter.Split('|')[0]
    if ($timerKeys -ccontains $key) {
        $times = @($timerRows[$key] | Where-Object { $_.name -ceq "$($key):COUNT" } | ForEach-Object { $_.epoch_ms })
    } else {
        $times = @($appRows | Where-Object { $_.name -ceq "$($key):VALUE" } | ForEach-Object { $_.epoch_ms })
    }
    $failures = @($appFailures | Where-Object { $_.name -ceq "$($key):FAILED" }).Count
    $appCoverage[$key] = Get-Coverage $times $failures $windowStart $windowEnd
}
$quality['application metrics (process.cpu.usage)'] = $appCoverage['process.cpu.usage']
$okMysql = @($mysqlRows | Where-Object { $_.name -cne 'sample:FAILED' })
$failedMysql = @($mysqlRows | Where-Object { $_.name -ceq 'sample:FAILED' }).Count
$quality['MySQL counters'] = Get-Coverage @($okMysql | ForEach-Object { $_.epoch_ms }) $failedMysql $windowStart $windowEnd
$redisTimes = @($redisRows | Where-Object { $_.name -ceq 'total_commands_processed' } | ForEach-Object { $_.epoch_ms })
$failedRedis = @($redisRows | Where-Object { $_.name -ceq 'sample:FAILED' }).Count
$quality['Redis counters'] = Get-Coverage $redisTimes $failedRedis $windowStart $windowEnd
$mysqlCovered = Test-Covered $quality['MySQL counters']
$redisCovered = Test-Covered $quality['Redis counters']

$containers = [ordered]@{}
foreach ($group in ($okStats | Group-Object container | Sort-Object Name)) {
    $service = $group.Name.Substring($Project.Length + 1) -creplace '-run-[0-9a-f]+$', '' -creplace '-[0-9]+$', ''
    $containers[$service] = [ordered]@{
        cpuPercent = Measure-Window $group.Group 'cpu_percent' $windowStart $windowEnd
        memoryBytes = Measure-Window $group.Group 'mem_bytes' $windowStart $windowEnd
    }
}
$gauges = [ordered]@{}
foreach ($group in ($appRows | Where-Object { $_.name -clike '*:VALUE' } | Group-Object name | Sort-Object Name)) {
    $gauges[$group.Name.Substring(0, $group.Name.Length - 6)] = Measure-Window $group.Group 'value' $windowStart $windowEnd
}

$timers = [ordered]@{}
foreach ($t in $AppTimers) {
    $wholeCalls = $null
    $wholeMean = $null
    if ($null -ne $timersBefore[$t.key] -and $null -ne $timersAfter[$t.key]) {
        $wholeCalls = [double] $timersAfter[$t.key]['COUNT'] - [double] $timersBefore[$t.key]['COUNT']
        $wholeSeconds = [double] $timersAfter[$t.key]['TOTAL_TIME'] - [double] $timersBefore[$t.key]['TOTAL_TIME']
        if ($wholeCalls -gt 0) { $wholeMean = $wholeSeconds * 1000.0 / $wholeCalls }
    }
    $steadyMean = $null
    $steadyPerSecond = $null
    $steadyCount = $null
    if (Test-Covered $appCoverage[$t.key]) {
        $steadyCalls = Get-CounterDelta $timerRows[$t.key] "$($t.key):COUNT"
        $steadyTime = Get-CounterDelta $timerRows[$t.key] "$($t.key):TOTAL_TIME"
        if ($null -ne $steadyCalls -and $null -ne $steadyTime -and $steadyCalls.seconds -gt 0) {
            $steadyPerSecond = $steadyCalls.value / $steadyCalls.seconds
            $steadyCount = $steadyCalls.value
            if ($steadyCalls.value -gt 0) { $steadyMean = $steadyTime.value * 1000.0 / $steadyCalls.value }
        }
    }
    $timerCoverage = $null
    if ($null -ne $appCoverage[$t.key]) { $timerCoverage = $appCoverage[$t.key].coverage }
    $timers[$t.key] = [ordered]@{ steadyPerSecond = $steadyPerSecond; steadyCalls = $steadyCount; steadyMeanMs = $steadyMean
        wholeRunCalls = $wholeCalls; wholeRunMeanMs = $wholeMean; coverage = $timerCoverage }
}

$serverPerSecond = 0.0
foreach ($endpoint in $activeEndpoints) {
    if ($null -eq $timers["http.$endpoint"].steadyPerSecond) { $serverPerSecond = $null; break }
    $serverPerSecond += $timers["http.$endpoint"].steadyPerSecond
}
$serverPerVmSecond = $null
if ($null -ne $serverPerSecond -and $null -ne $clockRate) { $serverPerVmSecond = $serverPerSecond / $clockRate }
$appCpu = $null
if ($containers.Contains('app') -and $null -ne $containers['app'].cpuPercent) { $appCpu = $containers['app'].cpuPercent.avg / 100 }
$appCpuMsPerRequest = $null
if ($null -ne $appCpu -and $null -ne $serverPerVmSecond -and $serverPerVmSecond -gt 0) { $appCpuMsPerRequest = $appCpu * 1000 / $serverPerVmSecond }

$alignment = [ordered]@{ status = 'not verified'; reason = ''; firstHalf = $null; secondHalf = $null; expected = $null }
$aroundRows = @(Import-Window (Join-Path $resultDir 'app-timers.csv') ($windowStart - 10000) ($windowEnd + 10000))
$requestPoints = @{}
$pointsAvailable = $true
foreach ($endpoint in $activeEndpoints) {
    $key = "http.$endpoint"
    if (-not (Test-Covered $appCoverage[$key])) { $pointsAvailable = $false; break }
    $points = @($aroundRows | Where-Object { $_.name -ceq "$($key):COUNT" } |
        ForEach-Object { [pscustomobject]@{ t = [double] $_.epoch_ms; v = (ConvertTo-Double $_.value) } })
    $points += @($aroundRows | Where-Object { $_.name -ceq "$($key):MISSING" } |
        ForEach-Object { [pscustomobject]@{ t = [double] $_.epoch_ms; v = 0.0 } })
    $requestPoints[$endpoint] = @($points | Sort-Object t)
}
if (-not $pointsAvailable -or $activeEndpoints.Count -eq 0) {
    $alignment.reason = 'the server''s request timers do not cover the window'
} else {
    $middle = ($windowStart + $windowEnd) / 2.0
    $marks = @([double] $windowStart, $middle, [double] $windowEnd)
    $totals = @()
    foreach ($mark in $marks) {
        $sum = 0.0
        foreach ($endpoint in $activeEndpoints) {
            $value = Get-ValueAt $requestPoints[$endpoint] $mark
            if ($null -eq $value) { $sum = $null; break }
            $sum += $value
        }
        $totals += , $sum
    }
    if (@($totals | Where-Object { $null -eq $_ }).Count -gt 0) {
        $alignment.reason = 'the timer readings do not reach the window edges'
    } else {
        $half = ($windowEnd - $windowStart) / 2000.0
        $alignment.firstHalf = ($totals[1] - $totals[0]) / $half
        $alignment.secondHalf = ($totals[2] - $totals[1]) / $half
        if ($k6Rows.ContainsKey('all') -and $null -ne $k6Rows['all'].rps -and $null -ne $clockRate) {
            $alignment.expected = [double] $k6Rows['all'].rps * $clockRate
        }
        $mean = ($alignment.firstHalf + $alignment.secondHalf) / 2
        $problems = @()
        if ($mean -le 0) {
            $problems += 'the server finished no requests in the window'
        } else {
            if ([Math]::Abs($alignment.firstHalf - $alignment.secondHalf) / $mean -gt $AlignmentTolerance) {
                if ($alignment.firstHalf -lt $alignment.secondHalf) {
                    $problems += 'the first half has fewer requests'
                } else {
                    $problems += 'the second half has fewer requests'
                }
            }
            if ($null -ne $alignment.expected -and [Math]::Abs($mean - $alignment.expected) / $alignment.expected -gt $AlignmentTolerance) {
                $problems += 'the server''s rate differs from k6''s steady rate on this computer''s clock'
            }
        }
        $positionKnown = ($null -ne $clockTimeline) -and ($null -ne $anchorAmbiguityMs) -and ($anchorAmbiguityMs -le $marginMs / 2)
        if ($problems.Count -gt 0 -and -not $positionKnown) {
            $problems = @($problems | ForEach-Object { $_ + ' (the window may reach into the ramp or past the load)' })
        }
        if ($problems.Count -gt 0 -and $positionKnown) {
            $alignment.status = 'not steady'
            $alignment.reason = ('the window''s position is known to {0} s from the clock record, so the server''s rate ' +
                'changed inside the steady phase (queued requests building up or draining, dropped iterations): {1}') -f
                (Format-Number (($anchorAmbiguityMs + 50) / 1000.0) 1), ($problems -join '; ')
        } elseif ($problems.Count -gt 0) {
            $alignment.status = 'possibly shifted'
            $alignment.reason = $problems -join '; '
        } elseif ($null -eq $alignment.expected) {
            $alignment.status = 'flat, rate not compared'
            $alignment.reason = 'no clock rate or k6 rate to compare with'
        } else {
            $alignment.status = 'aligned'
        }
    }
}
if ($null -ne $anchorAmbiguityMs -and $anchorAmbiguityMs -gt $marginMs / 2 -and $alignment.status -ceq 'aligned') {
    $alignment.status = 'possibly shifted'
    $alignment.reason = 'a wall-clock step close to k6''s start makes the window''s position uncertain by more than half the margin'
}

$latencySplit = [ordered]@{}
foreach ($endpoint in $activeEndpoints) {
    $row = $k6Rows[$endpoint]
    $server = $timers["http.$endpoint"]
    $outside = $null
    if ($null -ne $row -and $null -ne $row.ttfbAvg -and $null -ne $server.steadyMeanMs) {
        $outside = [double] $row.ttfbAvg - $server.steadyMeanMs
    }
    $perVm = $null
    if ($null -ne $server.steadyPerSecond -and $null -ne $clockRate) { $perVm = $server.steadyPerSecond / $clockRate }
    $latencySplit[$endpoint] = [ordered]@{
        k6PerSecond = $(if ($null -ne $row) { $row.rps } else { $null })
        k6AvgMs = $(if ($null -ne $row) { $row.avg } else { $null })
        k6TtfbAvgMs = $(if ($null -ne $row) { $row.ttfbAvg } else { $null })
        serverPerSecond = $server.steadyPerSecond
        serverPerVmSecond = $perVm
        serverMeanMs = $server.steadyMeanMs
        outsideServerMs = $outside
    }
}

function Get-K6HostMs([double] $Second) {
    if ($null -ne $k6StartUptime) { return Convert-UptimeToHost $clockTimeline ($k6StartUptime + $Second) }
    if ($null -ne $k6StartHostMs -and $null -ne $clockRate) { return $k6StartHostMs + $Second * 1000.0 / $clockRate }
    return $null
}

function Get-SeriesPoints($Rows, [string] $Name) {
    return @($Rows | Where-Object { $_.name -ceq $Name } | ForEach-Object {
            [pscustomobject]@{ t = [double] $_.epoch_ms; v = (ConvertTo-Double $_.value) } } | Sort-Object t)
}

function Get-SeriesDelta($Points, $FromMs, $ToMs) {
    $Points = @($Points)
    if ($null -eq $FromMs -or $null -eq $ToMs -or $Points.Count -lt 2) { return $null }
    $a = Get-ValueAt $Points $FromMs
    $b = Get-ValueAt $Points $ToMs
    if ($null -eq $a -or $null -eq $b) { return $null }
    return $b - $a
}

$appLogFile = Join-Path $resultDir 'app.log'
$appLogLines = @()
if (Test-Path $appLogFile) { $appLogLines = @([IO.File]::ReadAllLines($appLogFile)) }
$pickedUpOptions = $null
$logCollector = $null
$gcLogPauses = New-Object Collections.Generic.List[object]
foreach ($logLine in $appLogLines) {
    if ($null -eq $pickedUpOptions -and $logLine -cmatch 'Picked up JAVA_TOOL_OPTIONS: (.+)$') { $pickedUpOptions = $Matches[1].Trim(); continue }
    if ($logLine -cmatch '\[([0-9]+\.[0-9]+)s\]\[info *\]\[gc *\] *(.*)$') {
        $uptime = ConvertTo-Double $Matches[1]
        $message = $Matches[2].Trim()
        if ($null -eq $logCollector -and $message -cmatch '^Using (.+)$') { $logCollector = $Matches[1]; continue }
        if ($message -cmatch '^GC\([0-9]+\) Pause (Young|Full)\b.* ([0-9]+(\.[0-9]+)?)ms$') {
            $gcLogPauses.Add([pscustomobject]@{ uptime = $uptime; full = ($Matches[1] -ceq 'Full'); ms = (ConvertTo-Double $Matches[2]) })
        }
    }
}
$gcLogged = $null -ne $logCollector
$jvmUptimeAtK6Start = $null
if ($gcLogged -and $null -ne $k6StartUptime -and $null -ne $appStartedVmMs) {
    $atJvm = Get-WallOffsetAt $clockTimeline ([double] $appStartedVmMs)
    $jvmUptimeAtK6Start = $k6StartUptime - ([double] $appStartedVmMs - $atJvm.offsetMs) / 1000.0
}

$statsAll = @()
$statsFile = Join-Path $resultDir 'docker-stats.csv'
if (Test-Path $statsFile) { $statsAll = @(Import-Csv $statsFile | Where-Object { $_.container -clike "$Project-app-*" }) }
$threadFile = Join-Path $resultDir 'thread-cpu.csv'
$threadRows = @()
if (Test-Path $threadFile) { $threadRows = @(Import-Csv $threadFile | Where-Object { $_.name -cne 'sample:FAILED' }) }
$cpuSeries = [ordered]@{}
foreach ($group in @('process', 'gc', 'jit')) { $cpuSeries[$group] = @(Get-SeriesPoints $threadRows "jvm.cpu.$group") }
$gcWorkers = $null
$workerValues = @($threadRows | Where-Object { $_.name -ceq 'jvm.threads.gc_workers' } | ForEach-Object { [int] $_.value })
if ($workerValues.Count -gt 0) { $gcWorkers = [int] ($workerValues | Measure-Object -Maximum).Maximum }
$timerFile = Join-Path $resultDir 'app-timers.csv'
$timerAll = @()
if (Test-Path $timerFile) { $timerAll = @(Import-Csv $timerFile) }
$pauseCountPoints = @(Get-SeriesPoints $timerAll 'jvm.gc.pause:COUNT')
$pauseTimePoints = @(Get-SeriesPoints $timerAll 'jvm.gc.pause:TOTAL_TIME')

function Measure-JvmInterval([double] $From, [double] $To, [bool] $RequireCoverage) {
    $result = [ordered]@{ fromSecond = $From; toSecond = $To; appCores = $null; processCores = $null; gcCores = $null
        jitCores = $null; pausesPerSecond = $null; pauseMeanMs = $null; pauseMaxMs = $null; fullPauses = $null
        pauseShare = $null; pauseSource = $null }
    $fromMs = Get-K6HostMs $From
    $toMs = Get-K6HostMs $To
    if ($null -eq $fromMs -or $null -eq $toMs) { return $result }
    $vmSeconds = $To - $From
    $readings = @($statsAll | Where-Object { [double] $_.epoch_ms -ge $fromMs -and [double] $_.epoch_ms -lt $toMs })
    if ($RequireCoverage) {
        $measured = Measure-Window $readings 'cpu_percent' ([int64] $fromMs) ([int64] $toMs)
        if ($null -ne $measured) { $result.appCores = $measured.avg / 100 }
    } elseif ($readings.Count -gt 0) {
        $result.appCores = ($readings | ForEach-Object { ConvertTo-Double $_.cpu_percent } | Measure-Object -Average).Average / 100
    }
    foreach ($group in @('process', 'gc', 'jit')) {
        $delta = Get-SeriesDelta $cpuSeries[$group] $fromMs $toMs
        if ($null -ne $delta) { $result["$($group)Cores"] = [Math]::Max(0.0, $delta) / $vmSeconds }
    }
    if ($null -ne $jvmUptimeAtK6Start) {
        $inside = @($gcLogPauses | Where-Object { $_.uptime - $jvmUptimeAtK6Start -ge $From -and $_.uptime - $jvmUptimeAtK6Start -lt $To })
        $result.pauseSource = 'GC log'
        $result.pausesPerSecond = $inside.Count / $vmSeconds
        $result.fullPauses = @($inside | Where-Object { $_.full }).Count
        if ($inside.Count -gt 0) {
            $m = $inside | ForEach-Object { $_.ms } | Measure-Object -Sum -Maximum
            $result.pauseMeanMs = $m.Sum / $inside.Count
            $result.pauseMaxMs = $m.Maximum
            $result.pauseShare = $m.Sum / ($vmSeconds * 1000.0)
        } else {
            $result.pauseShare = 0.0
        }
    } else {
        $count = Get-SeriesDelta $pauseCountPoints $fromMs $toMs
        $time = Get-SeriesDelta $pauseTimePoints $fromMs $toMs
        if ($null -ne $count -and $null -ne $time) {
            $result.pauseSource = 'jvm.gc.pause timer'
            $result.pausesPerSecond = $count / $vmSeconds
            if ($count -gt 0.5) { $result.pauseMeanMs = $time * 1000.0 / $count }
            $result.pauseShare = $time / $vmSeconds
        }
    }
    return $result
}

$marginSeconds = $marginMs / 1000.0
$jvmPhases = [ordered]@{
    'warm-up' = Measure-JvmInterval 0 $Warmup $true
    $windowName = Measure-JvmInterval ($Warmup + $marginSeconds) ($Warmup + $Duration - $marginSeconds) $true
}
$jvmBins = New-Object Collections.Generic.List[object]
for ($second = 0; $second -lt $Warmup + $Duration; $second += 10) {
    $jvmBins.Add((Measure-JvmInterval $second ([Math]::Min($second + 10, $Warmup + $Duration)) $false))
}
$jvmRuntime = [ordered]@{
    gcOption = $GcOption
    javaToolOptions = Get-JsonValue $raw.settings 'javaToolOptions'
    pickedUpOptions = $pickedUpOptions
    logCollector = $logCollector
    metricsCollectors = $jvm.gc
    gcWorkerThreads = $gcWorkers
    gcLogPauses = $gcLogPauses.Count
    jvmUptimeAtK6Start = $jvmUptimeAtK6Start
    phases = $jvmPhases
    bins = $jvmBins.ToArray()
}

$loadSeconds = ($loadEnd - $loadStart).TotalSeconds
$gaugeNames = @('threads_connected', 'threads_running', 'max_used_connections', 'innodb_row_lock_time_max',
    'innodb_buffer_pool_pages_dirty', 'innodb_redo_log_capacity_resized', 'innodb.checkpoint_age_bytes')
$isGauge = { param($key) ($gaugeNames -ccontains $key) -or $key.StartsWith('config.') -or $key.EndsWith('.timer_wrapped') }
$loadStartMs = [double] [DateTimeOffset]::new($loadStart).ToUnixTimeMilliseconds()
$loadEndMs = [double] [DateTimeOffset]::new($loadEnd).ToUnixTimeMilliseconds()
$stepsDuringLoad = Test-StepIn $clockTimeline $loadStartMs $loadEndMs
if ($null -eq $stepsDuringLoad) { $stepsDuringLoad = $wallStepped }
$mysql = [ordered]@{}
$statementTimes = [ordered]@{}
foreach ($key in $mysqlAfter.Keys) {
    $steady = $null
    $status = 'ok'
    if (& $isGauge $key) {
        $last = @($okMysql | Where-Object { $_.name -ceq $key } | Sort-Object { [int64] $_.epoch_ms } | Select-Object -Last 1)
        if ($mysqlCovered -and $last.Count -gt 0) { $steady = ConvertTo-Double $last[0].value } else { $status = 'not measured' }
        $mysql[$key] = [ordered]@{ gauge = $true; steady = $steady; steadyStatus = $status; wholeRun = [double] $mysqlAfter[$key]; wholeRunStatus = 'ok' }
        continue
    }
    $whole = $null
    $wholeStatus = 'ok'
    if ($mysqlBefore.Contains($key)) { $whole = [double] $mysqlAfter[$key] - [double] $mysqlBefore[$key] } else { $wholeStatus = 'not measured' }
    if ($key -clike 'statements.*.time_us') {
        $base = $key.Substring(0, $key.Length - '.time_us'.Length)
        $valid = Get-StatementDelta $okMysql $key "$base.count" "$base.timer_wrapped"
        $statementTimes[$base] = $valid
        $share = 0.0
        if ($windowSeconds -gt 0) { $share = $valid.seconds / $windowSeconds }
        if (-not $mysqlCovered) {
            $status = 'not measured'
        } elseif ($share -lt $MinStatementShare) {
            $status = 'invalid'
        } else {
            $steady = $valid.time / $valid.seconds
        }
        $valid['shareOfWindow'] = $share
        $valid['status'] = $status
        if ($stepsDuringLoad -or ($null -ne $whole -and $whole -lt 0)) { $wholeStatus = 'invalid'; $whole = $null }
    } elseif ($mysqlCovered) {
        $delta = Get-CounterDelta $okMysql $key
        if ($null -ne $delta -and $delta.seconds -gt 0) {
            if ($delta.value -lt 0) { $status = 'invalid' } else { $steady = $delta.value / $delta.seconds }
        } else {
            $status = 'not measured'
        }
        if ($null -ne $whole -and $whole -lt 0) { $wholeStatus = 'invalid'; $whole = $null }
    } else {
        $status = 'not measured'
    }
    $mysql[$key] = [ordered]@{ gauge = $false; steady = $steady; steadyStatus = $status; wholeRun = $whole; wholeRunStatus = $wholeStatus }
}

$commitMean = $null
$commitStatus = 'not measured'
if ($statementTimes.Contains('statements.commit')) {
    $commit = $statementTimes['statements.commit']
    $commitStatus = $commit.status
    if ($commit.status -ceq 'ok' -and $commit.count -gt 0) { $commitMean = $commit.time / $commit.count / 1000.0 }
}
$meanInWindow = {
    param([string] $timeKey, [string] $countKey, [double] $toMs)
    if (-not $mysqlCovered) { return $null }
    $time = Get-CounterDelta $okMysql $timeKey
    $count = Get-CounterDelta $okMysql $countKey
    if ($null -eq $time -or $null -eq $count -or $count.value -le 0 -or $time.value -lt 0) { return $null }
    return $time.value / $count.value * $toMs
}
$commitPath = [ordered]@{
    commitMeanMs = $commitMean
    commitStatus = $commitStatus
    redoLogSyncMeanMs = & $meanInWindow 'file_io.redo_log.sync_time_us' 'file_io.redo_log.sync_count' 0.001
    binlogSyncMeanMs = & $meanInWindow 'file_io.binlog.sync_time_us' 'file_io.binlog.sync_count' 0.001
}

$gaugeInWindow = {
    param([string] $Name)
    if (-not $mysqlCovered) { return $null }
    $rows = @($okMysql | Where-Object { $_.name -ceq $Name })
    if ($rows.Count -eq 0) { return $null }
    return Measure-Window $rows 'value' $windowStart $windowEnd
}
$perSecond = {
    param([string] $Key)
    if (-not $mysql.Contains($Key) -or $mysql[$Key].steadyStatus -cne 'ok') { return $null }
    return $mysql[$Key].steady
}
$redoCapacity = $null
if ($null -ne $mysqlAfter['config.innodb_redo_log_capacity']) { $redoCapacity = [double] $mysqlAfter['config.innodb_redo_log_capacity'] }
$checkpointAge = & $gaugeInWindow 'innodb.checkpoint_age_bytes'
$dirtyPages = & $gaugeInWindow 'innodb_buffer_pool_pages_dirty'
$innodbFlushing = [ordered]@{
    redoLogCapacityBytes = $redoCapacity
    ioCapacity = $(if ($null -ne $mysqlAfter['config.innodb_io_capacity']) { [double] $mysqlAfter['config.innodb_io_capacity'] } else { $null })
    doublewrite = $mysqlAfter['config.innodb_doublewrite']
    checkpointAgeBytes = $checkpointAge
    checkpointAgeMaxShare = $(if ($null -ne $checkpointAge -and $redoCapacity -gt 0) { $checkpointAge.max / $redoCapacity } else { $null })
    dirtyPages = $dirtyPages
    pagesFlushedPerSecond = & $perSecond 'innodb_buffer_pool_pages_flushed'
    doublewritePagesPerSecond = & $perSecond 'innodb_dblwr_pages_written'
    waitFreePerSecond = & $perSecond 'innodb_buffer_pool_wait_free'
    dataFileSyncsPerSecond = & $perSecond 'file_io.data_file.sync_count'
    dataFileSyncMeanMs = & $meanInWindow 'file_io.data_file.sync_time_us' 'file_io.data_file.sync_count' 0.001
    doublewriteSyncsPerSecond = & $perSecond 'file_io.dblwr.sync_count'
    doublewriteSyncMeanMs = & $meanInWindow 'file_io.dblwr.sync_time_us' 'file_io.dblwr.sync_count' 0.001
    innodbFsyncsPerSecond = & $perSecond 'innodb_data_fsyncs'
    redoBytesPerSecond = & $perSecond 'file_io.redo_log.write_bytes'
    binlogBytesPerSecond = & $perSecond 'file_io.binlog.write_bytes'
    dataFileBytesPerSecond = & $perSecond 'file_io.data_file.write_bytes'
    doublewriteBytesPerSecond = & $perSecond 'file_io.dblwr.write_bytes'
}

$typeAnomalies = @{}
$allMysqlRows = @()
if (Test-Path (Join-Path $resultDir 'mysql-status.csv')) {
    $allMysqlRows = @(Import-Csv (Join-Path $resultDir 'mysql-status.csv') | Where-Object { $_.name -clike 'statements.*.time_us' })
}
foreach ($group in ($allMysqlRows | Group-Object name)) {
    $type = $group.Name.Split('.')[1].ToUpperInvariant()
    $points = @($group.Group | Sort-Object { [int64] $_.epoch_ms })
    $anomalies = 0
    for ($i = 1; $i -lt $points.Count; $i++) {
        $delta = (ConvertTo-Double $points[$i].value) - (ConvertTo-Double $points[$i - 1].value)
        $seconds = ([int64] $points[$i].epoch_ms - [int64] $points[$i - 1].epoch_ms) / 1000.0
        if ($delta -lt 0 -or $delta -gt $seconds * $MaxConcurrentStatements * 1e6) { $anomalies++ }
    }
    $typeAnomalies[$type] = $anomalies
}
$statementRows = @()
foreach ($statement in $topStatements) {
    $parts = "$statement".Split("`t")
    if ($parts.Count -ge 9) {
        $statementRows += [ordered]@{ calls = $parts[0]; avgMs = $parts[1]; p95Ms = $parts[2]; totalMs = $parts[3]; rowsPerCall = $parts[4]
            noIndex = $parts[5]; maxMs = $parts[6]; timer = $(if ($parts[7] -ceq '1') { 'invalid' } else { 'ok' }); sql = $parts[8] }
    } elseif ($parts.Count -ge 7) {
        $statementRows += [ordered]@{ calls = $parts[0]; avgMs = $parts[1]; p95Ms = $parts[2]; totalMs = $parts[3]; rowsPerCall = $parts[4]
            noIndex = $parts[5]; maxMs = $null; timer = ''; sql = $parts[6] }
    }
}
$legacyStatements = @($statementRows | Where-Object { $null -eq $_.maxMs }).Count -gt 0
foreach ($row in @($statementRows | Where-Object { $null -eq $_.maxMs })) {
    $total = 0.0
    [void] [double]::TryParse($row.totalMs, [Globalization.NumberStyles]::Float, $Invariant, [ref] $total)
    $avg = 0.0
    [void] [double]::TryParse($row.avgMs, [Globalization.NumberStyles]::Float, $Invariant, [ref] $avg)
    $type = $row.sql.Split(' ')[0].ToUpperInvariant()
    $sameType = @($statementRows | Where-Object { $_.sql.Split(' ')[0].ToUpperInvariant() -ceq $type }).Count
    if ($total -ge $WrappedTimerMs -or $avg -gt $loadSeconds * 1000) {
        $row.timer = 'invalid'
    } elseif ($typeAnomalies.ContainsKey($type) -and $typeAnomalies[$type] -gt 0) {
        $row.timer = $(if ($sameType -eq 1 -and $type -ceq 'COMMIT') { 'invalid' } else { 'uncertain' })
    } elseif ($stepsDuringLoad) {
        $row.timer = 'uncertain'
    } else {
        $row.timer = 'ok'
    }
}

$redisDelta = {
    param([string] $name)
    if (-not $redisCovered) { return $null }
    $first = ($redisTimes | ForEach-Object { [int64] $_ } | Measure-Object -Minimum).Minimum
    $last = ($redisTimes | ForEach-Object { [int64] $_ } | Measure-Object -Maximum).Maximum
    $atFirst = @($redisRows | Where-Object { $_.name -ceq $name -and [int64] $_.epoch_ms -eq $first })
    $atLast = @($redisRows | Where-Object { $_.name -ceq $name -and [int64] $_.epoch_ms -eq $last })
    $before = 0.0; $after = 0.0
    if ($atFirst.Count -gt 0) { $before = ConvertTo-Double $atFirst[0].value }
    if ($atLast.Count -gt 0) { $after = ConvertTo-Double $atLast[0].value }
    return $after - $before
}
$cacheEstimate = [ordered]@{ basis = 'Redis command counts in the window (approximate)'; covered = $redisCovered }
if ($redisCovered) {
    $redisSeconds = (($redisTimes | ForEach-Object { [int64] $_ } | Measure-Object -Maximum).Maximum -
        ($redisTimes | ForEach-Object { [int64] $_ } | Measure-Object -Minimum).Minimum) / 1000.0
    $rateLimitScripts = & $redisDelta 'cmd.time.calls'
    $invalidations = & $redisDelta 'cmd.hget.calls'
    $scripts = (& $redisDelta 'cmd.evalsha.calls') - (& $redisDelta 'cmd.evalsha.failed') +
        (& $redisDelta 'cmd.eval.calls') - (& $redisDelta 'cmd.eval.failed')
    $fills = $scripts - $rateLimitScripts - $invalidations
    $teamLookups = (& $redisDelta 'cmd.hmget.calls') - $rateLimitScripts - $fills
    $membershipLookups = & $redisDelta 'cmd.get.calls'
    $membershipWrites = (& $redisDelta 'cmd.set.calls') + (& $redisDelta 'cmd.setex.calls') + (& $redisDelta 'cmd.psetex.calls')
    $teamHit = $null
    $teamNote = 'no team-cache lookups (cache off or no /get)'
    if ($teamLookups -gt 0) {
        if ($fills -ge 0 -and $fills -le $teamLookups) {
            $teamHit = 1.0 - $fills / $teamLookups
            $teamNote = ''
        } else {
            $teamNote = 'the command counts do not fit the expected pattern: not estimated'
        }
    }
    $membershipHit = $null
    $membershipNote = 'no membership lookups (cache off or no /get)'
    if ($membershipLookups -gt 0) {
        if ($membershipWrites -le $membershipLookups) {
            $membershipHit = 1.0 - $membershipWrites / $membershipLookups
            $membershipNote = ''
        } else {
            $membershipNote = 'more writes than lookups: not estimated'
        }
    }
    $cacheEstimate['seconds'] = $redisSeconds
    $cacheEstimate['rateLimitScriptsPerSecond'] = $rateLimitScripts / $redisSeconds
    $cacheEstimate['invalidationsPerSecond'] = $invalidations / $redisSeconds
    $cacheEstimate['teamLookupsPerSecond'] = $teamLookups / $redisSeconds
    $cacheEstimate['teamFillsPerSecond'] = $fills / $redisSeconds
    $cacheEstimate['teamHitRatioApprox'] = $teamHit
    $cacheEstimate['teamNote'] = $teamNote
    $cacheEstimate['membershipLookupsPerSecond'] = $membershipLookups / $redisSeconds
    $cacheEstimate['membershipWritesPerSecond'] = $membershipWrites / $redisSeconds
    $cacheEstimate['membershipHitRatioApprox'] = $membershipHit
    $cacheEstimate['membershipNote'] = $membershipNote
}

function Get-RedisCommandStats($Info) {
    $commands = @{}
    foreach ($key in $Info.Keys) {
        if ($key -clike 'cmdstat_*' -and $Info[$key] -cmatch 'calls=([0-9]+),usec=([0-9]+)') {
            $commands[$key.Substring(8)] = @{ calls = [double] $Matches[1]; usec = [double] $Matches[2] }
        }
    }
    return $commands
}
$commandsBefore = Get-RedisCommandStats $redisBefore
$commandsAfter = Get-RedisCommandStats $redisAfter
$redisCommands = [ordered]@{}
foreach ($name in ($commandsAfter.Keys | Sort-Object)) {
    $calls = $commandsAfter[$name].calls
    $usec = $commandsAfter[$name].usec
    if ($commandsBefore.ContainsKey($name)) {
        $calls -= $commandsBefore[$name].calls
        $usec -= $commandsBefore[$name].usec
    }
    if ($calls -gt 0) { $redisCommands[$name] = [ordered]@{ calls = $calls; usecPerCall = $usec / $calls } }
}
$redisWhole = {
    param($key)
    $after = 0.0; $before = 0.0
    if ($redisAfter.Contains($key)) { $after = ConvertTo-Double $redisAfter[$key] }
    if ($redisBefore.Contains($key)) { $before = ConvertTo-Double $redisBefore[$key] }
    return $after - $before
}
$redis = [ordered]@{
    commands = & $redisWhole 'total_commands_processed'
    keyspaceHits = & $redisWhole 'keyspace_hits'
    keyspaceMisses = & $redisWhole 'keyspace_misses'
    evictedKeys = & $redisWhole 'evicted_keys'
    cpuSeconds = (& $redisWhole 'used_cpu_sys') + (& $redisWhole 'used_cpu_user')
    usedMemory = $(if ($redisAfter.Contains('used_memory_human')) { $redisAfter['used_memory_human'] } else { '-' })
    commandStats = $redisCommands
}

$k6Warnings = @()
$k6Log = Join-Path $resultDir 'k6.log'
if (Test-Path $k6Log) {
    $k6Warnings = @(Get-Content $k6Log | Where-Object { $_ -cmatch 'level=(warning|error)' } |
        ForEach-Object { if ($_ -cmatch 'msg="((?:[^"\\]|\\.)*)"') { $Matches[1] } else { $_ } } |
        Group-Object | Sort-Object Count -Descending | ForEach-Object { '{0} x {1}' -f $_.Count, $_.Name })
}

$jfrSummary = $null
$jfrStatus = 'not recorded (run with -Jfr)'
$jfrJson = Join-Path $resultDir 'jfr-summary.json'
if ($Jfr) {
    $jfrStatus = 'no recording was copied out of the container'
    $jfrFile = Join-Path $resultDir 'app.jfr'
    if ((Test-Path $jfrFile) -and (Get-Item $jfrFile).Length -eq 0) {
        $jfrStatus = 'the recording is empty (0 bytes): the JVM did not finish writing it when it was stopped; nothing to summarise'
    } elseif (Test-Path $jfrFile) {
        $summarised = $false
        if (-not $SkipJfr -and (Get-Command docker -ErrorAction SilentlyContinue)) {
            $resultName = Split-Path $resultDir -Leaf
            $jfrArgs = @('run', '--rm', '-T', 'jfr-tools', "/results/$resultName/app.jfr", "/results/$resultName/jfr-summary.md", '--cpus', $AppCpus)
            if ($null -ne $jfrWindow) { $jfrArgs += @('--from-ms', "$($jfrWindow.start)", '--to-ms', "$($jfrWindow.end)") }
            if ($k6Rows.ContainsKey('all') -and $null -ne $k6Rows['all'].rps -and [double] $k6Rows['all'].rps -gt 0) {
                $jfrArgs += @('--rps', ([double] $k6Rows['all'].rps).ToString('R', $Invariant))
            }
            Write-Step 'Summarising the Java Flight Recording'
            Push-Location $RepoRoot
            try { Invoke-Compose -AllowFailure -Arguments $jfrArgs } finally { Pop-Location }
            $summarised = $true
        }
        if (Test-Path $jfrJson) {
            $jfrSummary = [IO.File]::ReadAllText($jfrJson) | ConvertFrom-Json
            $jfrStatus = $(if ($summarised) { 'summarised in jfr-summary.md' } else { 'jfr-summary.md from an earlier evaluation (not summarised again)' })
        } else {
            $jfrStatus = 'the summary failed; app.jfr can be opened with JDK Mission Control'
        }
    }
}

$violations = [ordered]@{}
foreach ($key in $verify.Keys) {
    if ($key -clike '*_violations' -and [int64] $verify[$key] -ne 0) { $violations[$key] = [int64] $verify[$key] }
}
if (-not $verify.Contains('over_capacity_violations')) { $violations['verify.sql'] = 'did not run' }

$k6Outcomes = Get-JsonValue $k6 'outcomes'
$k6Unexpected = Get-JsonValue (Get-JsonValue $k6 'unexpectedResponses') 'total'
$dbValue = { param($key) if ($verify.Contains($key)) { [int64] $verify[$key] } else { [int64] 0 } }
$k6Value = {
    param($outcome)
    $entry = Get-JsonValue $k6Outcomes $outcome
    if ($null -eq $entry) { return $null }
    return [int64] (Get-JsonValue $entry 'total')
}
$outcomeChecks = New-Object Collections.Generic.List[object]
if ($null -ne $k6Outcomes -and $verify.Contains('over_capacity_violations')) {
    $pairs = @(
        @('progress', '/progress applied', 'progress.applied', 'outcomes.progress.success', 'successful progress outcomes stored'),
        @('progress', 'teams completed by /progress', 'progress.team_completed', 'completed_during_run', 'teams completed during the run'),
        @('progress', '/progress TEAM_COMPLETED (expected rejections)', 'progress.rejected.TEAM_COMPLETED', 'outcomes.progress.team_completed', 'TEAM_COMPLETED outcomes stored'),
        @('progress', '/progress replays answered with 200', 'progress.replayed', $null, 'no record of their own (nothing applied again: member_points_violations)'),
        @('join', '/join new members', 'join.joined', 'joined_members', 'members added during the run'),
        @('claim', '/claim rewards given', 'claim.claimed', 'claimed_members', 'members with claimed_at'),
        @('claim', '/claim ALREADY_CLAIMED (expected rejections)', 'claim.rejected.ALREADY_CLAIMED', 'outcomes.claim.already_claimed', 'ALREADY_CLAIMED outcomes stored')
    )
    foreach ($pair in $pairs) {
        if ($sharePercent[$pair[0]] -le 0) { continue }
        $fromK6 = & $k6Value $pair[2]
        $fromDb = $null
        $status = 'k6 only'
        if ($null -ne $pair[3]) {
            $fromDb = & $dbValue $pair[3]
            if ($null -eq $fromK6) {
                $status = $NotMeasured
            } elseif ($fromK6 -eq $fromDb) {
                $status = 'match'
            } elseif ($null -ne $k6Unexpected -and [int64] $k6Unexpected -gt 0) {
                $status = "differs ($k6Unexpected unexpected responses in k6)"
            } else {
                $status = 'MISMATCH'
            }
        }
        $outcomeChecks.Add([ordered]@{ endpoint = $pair[0]; outcome = $pair[1]; k6 = $fromK6; database = $fromDb
            databaseMeaning = $pair[4]; status = $status })
    }
    $mismatches = @($outcomeChecks | Where-Object { $_.status -ceq 'MISMATCH' }).Count
    if ($mismatches -gt 0) { $violations['outcome_count_mismatch'] = $mismatches }
}
$k6Passed = ($k6ExitCode -eq 0) -and ($null -ne $k6) -and $k6.passed
$passed = $k6Passed -and ($violations.Count -eq 0)

$clockSummary = $null
if ($null -ne $clockTimeline) {
    $clockSummary = [ordered]@{}
    foreach ($key in $clockTimeline.Keys) { if ($key -cne 'points') { $clockSummary[$key] = $clockTimeline[$key] } }
}

$run = [ordered]@{
    runId = $runId
    passed = $passed
    settings = [ordered]@{ mix = $Mix; sharesPercent = $sharePercent; cache = $Cache; rate = $Rate; warmupSeconds = $Warmup
        durationSeconds = $Duration; appCpus = $AppCpus; appMemory = $AppMemory; jfr = $Jfr; gc = $GcOption
        dbPool = $DbPoolOption; dbPoolMax = $DbPoolMax; redoLogCapacityMiB = $RedoOption; ioCapacity = $IoOption }
    data = $raw.data
    k6 = [ordered]@{ exitCode = $k6ExitCode; report = $k6 }
    window = [ordered]@{ name = $windowName; basis = $windowBasis; startEpochMs = $windowStart; endEpochMs = $windowEnd
        seconds = $windowSeconds; marginMs = $marginMs; anchorAmbiguityMs = $anchorAmbiguityMs; alignment = $alignment
        vmWindow = $vmWindow; jfrWindow = $jfrWindow; jfrWindowBasis = $jfrWindowBasis }
    clock = [ordered]@{ source = $clockSource; monotonicRate = $clockRate; wallStepped = $wallStepped
        timeline = $clockSummary; twoReadings = $clock; readings = $raw.clockSamples }
    monotonicRate = $clockRate
    measurementQuality = [ordered]@{ minCoverage = $MinCoverage; sources = $quality; appMeters = $appCoverage
        appFailedReadings = $appFailures.Count; appMissingReadings = $appMissing.Count }
    k6Warnings = $k6Warnings
    jvm = $jvm
    jvmRuntime = $jvmRuntime
    tomcatPool = $tomcatPool
    commitPath = $commitPath
    innodbFlushing = $innodbFlushing
    containers = $containers
    appCpuMsPerRequest = $appCpuMsPerRequest
    serverPerSecond = $serverPerSecond
    serverPerVmSecond = $serverPerVmSecond
    appGauges = $gauges
    appTimers = $timers
    latencySplit = $latencySplit
    mysql = $mysql
    mysqlStatementTimes = $statementTimes
    topStatements = $statementRows
    redis = $redis
    cacheEstimate = $cacheEstimate
    jfr = [ordered]@{ status = $jfrStatus; summary = $jfrSummary }
    invariants = [ordered]@{ violations = $violations; values = $verify }
    outcomeChecks = $outcomeChecks.ToArray()
    environment = $environment
    notes = $rawNotes
}

$md = New-Object Collections.Generic.List[string]
$md.Add("# Load test $runId")
$md.Add('')
$md.Add("Result: **$(if ($passed) { 'PASS' } else { 'FAIL' })** (k6 exit code $k6ExitCode, invariant violations: $($violations.Count))")
$md.Add('')
$alignmentText = "Window check: **$($alignment.status)**"
if ($null -ne $alignment.firstHalf) {
    $alignmentText += (' (server {0} and {1} req/s in the two halves on this computer''s clock; expected {2} from k6''s rate x the clock rate)' -f
        (Format-Number $alignment.firstHalf 1), (Format-Number $alignment.secondHalf 1), (Format-Measured $alignment.expected 1))
}
if ($alignment.reason) { $alignmentText += ": $($alignment.reason)" }
$md.Add($alignmentText + '.')
$md.Add('')
foreach ($note in $rawNotes) { $md.Add("> $note"); $md.Add('') }
$shareText = ($activeEndpoints | ForEach-Object { '{0} {1} %' -f $_, $sharePercent[$_] }) -join ', '
$poolSize = $DbPoolMax
if ($null -eq $poolSize -and $gauges.Contains('hikaricp.connections.max') -and $null -ne $gauges['hikaricp.connections.max']) {
    $poolSize = $gauges['hikaricp.connections.max'].max
}
$poolText = 'DB pool ' + $(if ($null -eq $poolSize) { 'not recorded' } else { Format-Number $poolSize 0 }) +
    $(if ($null -ne $DbPoolOption -and [int] $DbPoolOption -gt 0) { ' (run.ps1 -DbPool)' } else { ' (application default)' })
$settingSource = {
    param($Value, $Option, [string] $Parameter)
    if ($null -eq $Value) { return 'not recorded' }
    if ($null -ne $Option -and [int] $Option -gt 0) { return "$Value (run.ps1 $Parameter)" }
    return "$Value (MySQL default)"
}
$innodbText = 'MySQL redo log capacity ' +
    (& $settingSource $(if ($null -eq $redoCapacity) { $null } else { (Format-Number ($redoCapacity / 1MB) 0) + ' MiB' }) $RedoOption '-RedoLogCapacityMiB') +
    ', innodb_io_capacity ' +
    (& $settingSource $(if ($null -eq $innodbFlushing.ioCapacity) { $null } else { Format-Number $innodbFlushing.ioCapacity 0 }) $IoOption '-IoCapacity')
$md.Add("Load: $Rate req/s, mix $Mix ($shareText), cache $Cache, $Warmup s warm-up + $Duration s steady. " +
    "Application: 1 instance, $AppCpus CPU, $AppMemory, GC option $(if ($null -eq $GcOption) { 'not recorded' } else { $GcOption }), $poolText$(if ($Jfr) { ', with a Java Flight Recording' }). " +
    "$innodbText. " +
    "Data: $teams teams ($completedTeams completed), $joinUsers join users.")
$md.Add('')
$k6Markdown = Join-Path $resultDir 'k6-report.md'
if (Test-Path $k6Markdown) {
    foreach ($line in ([IO.File]::ReadAllLines($k6Markdown) | Select-Object -Skip 2)) {
        $md.Add(($line -creplace '^## ', '### '))
    }
} else {
    $md.Add('k6 wrote no report; see k6.log.')
}
$md.Add('')
$md.Add('k6 warnings and errors (k6.log): ' + $(if ($k6Warnings.Count -eq 0) { 'none' } else { $k6Warnings -join '; ' }))
$md.Add('')

$md.Add('## Measurement quality')
$md.Add('')
$line = 'Window: {0}, {1} to {2} ({3} s on this computer''s clock; {4} s cut at both ends), {5}.' -f $windowName,
    (Format-EpochMs $windowStart), (Format-EpochMs $windowEnd), (Format-Number $windowSeconds 0), (Format-Number ($marginMs / 1000.0) 0), $windowBasis
if ($null -ne $anchorAmbiguityMs) { $line += (' Its position is certain to {0} s.' -f (Format-Number (($anchorAmbiguityMs + 50) / 1000.0) 1)) }
$md.Add($line)
$md.Add('')
if ($windowSeconds -lt 30) {
    $md.Add((('**The window is only {0} s long**: Redis is read every 5 s and MySQL every 2 s, so their readings may not cover it; ' +
        'use a longer -Duration.') -f (Format-Number $windowSeconds 0)))
    $md.Add('')
}
if ($null -ne $clockTimeline) {
    $line = ('Docker VM clocks ({0} readings, one per second over {1} s): the VM''s monotonic clock, which k6, the JVM and ' +
        'docker stats measure time with, ran {2} % {3} against this computer''s clock (+/- {4} %, fitted to all readings), ' +
        'so a VM second lasted {5} s here. Its wall clock stayed between {6} and {7} s of this computer''s: Docker Desktop ' +
        'stepped it {8} times against the monotonic clock (in total {9} s, largest step {10} s).') -f $clockTimeline.readings,
        (Format-Number (($clockTimeline.lastHostMs - $clockTimeline.firstHostMs) / 1000.0) 0),
        (Format-Number ([Math]::Abs($clockTimeline.monotonicRate - 1) * 100) 2), $(if ($clockTimeline.monotonicRate -ge 1) { 'fast' } else { 'slow' }),
        (Format-Number ($clockTimeline.rateUncertainty * 100) 3), (Format-Number (1 / $clockTimeline.monotonicRate) 4),
        (Format-Number ($clockTimeline.wallOffsetMinMs / 1000) 2), (Format-Number ($clockTimeline.wallOffsetMaxMs / 1000) 2),
        $clockTimeline.stepCount, (Format-Number ($clockTimeline.stepTotalMs / 1000) 2), (Format-Number ($clockTimeline.largestStepMs / 1000) 2)
    $md.Add($line)
    if ($null -ne $clock) {
        $md.Add('')
        $line = 'Cross-check with the two single readings before and after k6: {0} % {1} (+/- {2} %).' -f
            (Format-Number ([Math]::Abs($clock.monotonicRate - 1) * 100) 2), $(if ($clock.monotonicRate -ge 1) { 'fast' } else { 'slow' }),
            (Format-Number ($clock.rateUncertainty * 100) 2)
        $md.Add($line)
    }
} elseif ($null -ne $clock) {
    $line = ('Clock check (two readings, {0} s apart, no per-second record): the VM''s monotonic clock ran {1} % {2} ' +
        '(+/- {3} %), so a VM second lasted {4} s here. The VM''s wall clock was {5} s off this computer''s at the ' +
        'start and {6} s at the end, so it was corrected by {7} s against the monotonic clock during the run (when and in ' +
        'how many steps is not known).') -f (Format-Number $clock.hostSeconds 0),
        (Format-Number ([Math]::Abs($clock.monotonicRate - 1) * 100) 2), $(if ($clock.monotonicRate -ge 1) { 'fast' } else { 'slow' }),
        (Format-Number ($clock.rateUncertainty * 100) 2), (Format-Number (1 / $clock.monotonicRate) 4),
        (Format-Number ($clock.wallOffsetStartMs / 1000) 2), (Format-Number ($clock.wallOffsetEndMs / 1000) 2),
        (Format-Number ($clock.wallCorrectionMs / 1000) 2)
    $md.Add($line)
} else {
    $md.Add('Clock check: **failed** (the Docker VM''s clock could not be read), so the window is not converted.')
}
$md.Add('')
if ($null -ne $clockRate) {
    $line = (('Consequences: k6 counts requests per VM second and the samplers per second of this computer, so for the same ' +
        'requests the samplers show {0} % {1}. Durations measured inside the VM (k6 latencies, the application''s timers, ' +
        'CPU time, JFR) are in VM time. MySQL times statements with the VM''s wall clock: intervals with a wall-clock step ' +
        'are left out of the statement times below.') -f (Format-Number ([Math]::Abs($clockRate - 1) * 100) 1),
        $(if ($clockRate -ge 1) { 'more' } else { 'fewer' }))
    $md.Add($line)
    $md.Add('')
}
$md.Add('| source | readings in the window | failed readings | coverage | longest gap s | used |')
$md.Add('|---|---:|---:|---:|---:|---|')
foreach ($name in $quality.Keys) {
    $q = $quality[$name]
    $md.Add(('| {0} | {1} | {2} | {3} | {4} | {5} |' -f $name, $q.samples, $q.failures, (Format-Percent $q.coverage 0),
        (Format-Number $q.maxGapSeconds 1), $(if (Test-Covered $q) { 'yes' } else { "no: $NotMeasured" })))
}
$md.Add('')
$line = ('Coverage: share of the window between the first and the last good reading; below {0} the values of a source ' +
    'are "{1}", never 0. Application meters: {2} failed readings (no answer within 5 s) and {3} readings of meters that ' +
    'did not exist yet (not called yet); per meter in run.json (measurementQuality.appMeters).') -f (Format-Percent $MinCoverage 0),
    $NotMeasured, $appFailures.Count, $appMissing.Count
$md.Add($line)
$md.Add('')

$md.Add("## Resources ($windowName)")
$md.Add('')
$md.Add('docker stats every 2-3 s, application metrics every 2 s (management port), MySQL counters every 2 s, Redis counters every 5 s.')
$md.Add('')
$md.Add('| container | CPU avg (cores) | CPU max (cores) | memory avg MiB | memory max MiB |')
$md.Add('|---|---:|---:|---:|---:|')
foreach ($name in $containers.Keys) {
    $c = $containers[$name]
    if ($null -eq $c.cpuPercent) {
        $md.Add(('| {0} | {1} | | | |' -f $name, $NotMeasured))
        continue
    }
    $md.Add(('| {0} | {1} | {2} | {3} | {4} |' -f $name, (Format-Number ($c.cpuPercent.avg / 100) 2),
        (Format-Number ($c.cpuPercent.max / 100) 2), (Format-Number ($c.memoryBytes.avg / 1MB) 0),
        (Format-Number ($c.memoryBytes.max / 1MB) 0)))
}
$md.Add('')
$line = (('Application CPU per request (approximate): {0} ms = app container cores x 1000 / requests the server finished ' +
    'per VM second ({1}). It includes everything the container did (samplers, GC, JIT).') -f (Format-Measured $appCpuMsPerRequest 2),
    (Format-Measured $serverPerVmSecond 1))
$md.Add($line)
$md.Add('')
$md.Add('| application gauge | avg | max | readings |')
$md.Add('|---|---:|---:|---:|')
if ($gauges.Count -eq 0) { $md.Add("| every gauge | $NotMeasured | | 0 |") }
foreach ($name in $gauges.Keys) {
    $g = $gauges[$name]
    $scale = 1.0; $digits = 1; $label = $name
    if ($name -ceq 'jvm.memory.used') { $scale = 1MB; $digits = 0; $label = 'jvm.memory.used (heap, MiB)' }
    if ($name -ceq 'process.cpu.usage') { $digits = 3; $label = 'process.cpu.usage (share of the JVM''s CPUs)' }
    if ($name -clike 'tomcat.*') { $label = "$name ($tomcatPool)" }
    if ($null -eq $g) {
        $md.Add(('| {0} | {1} | | |' -f $label, $NotMeasured))
        continue
    }
    $md.Add(('| {0} | {1} | {2} | {3} |' -f $label, (Format-Number ($g.avg / $scale) $digits), (Format-Number ($g.max / $scale) $digits), $g.samples))
}
$md.Add('')
$md.Add('## Application timers')
$md.Add('')
$md.Add("| timer | $windowName calls/s | $windowName mean ms | whole run calls | whole run mean ms |")
$md.Add('|---|---:|---:|---:|---:|')
foreach ($name in $timers.Keys) {
    $t = $timers[$name]
    $steadyMeanText = Format-Measured $t.steadyMeanMs 3
    if ($null -ne $t.steadyCalls -and $t.steadyCalls -eq 0) { $steadyMeanText = 'no calls' }
    $wholeMeanText = Format-Measured $t.wholeRunMeanMs 3
    if ($null -ne $t.wholeRunCalls -and $t.wholeRunCalls -eq 0) { $wholeMeanText = 'no calls' }
    $md.Add(('| {0} | {1} | {2} | {3} | {4} |' -f $name, (Format-Measured $t.steadyPerSecond 1), $steadyMeanText,
        (Format-Measured $t.wholeRunCalls 0), $wholeMeanText))
}
$md.Add('')
$md.Add('`http.*`: server-side time per request, from Spring''s observation of the request (k6 adds the network and ' +
    'client side); calls/s per second of this computer. `db.pool.acquire`: wait for a database connection; ' +
    '`db.pool.usage`: how long a connection is held; `redis.command`: one Redis command as the application sees it. ' +
    '`jvm.gc.pause`: total pause = calls x mean. The whole run includes the warm-up, when the JVM is still compiling.')
$md.Add('')
$md.Add('`join.*` (group join): `join.queue` = one /join that went to the batcher, its wait ' +
    'until its group transaction started (in the instance, without a database connection); `join.batch` = one group ' +
    'transaction, lock retries and commit included. Per transaction attempt, groups and single joins alike: ' +
    '`join.lock.wait` = wait for the campaign''s cursor row lock (lock wait timeouts included), `join.lock.work` = reads ' +
    'and inserts under the lock, `join.commit` = flush and durable COMMIT, `join.lock.held` = lock held until the ' +
    'commit (work + commit) or the rollback.')
$md.Add('')
$joinQueue = $timers['join.queue']
$joinBatch = $timers['join.batch']
$joinsPerGroupText = $NotMeasured
if ($null -ne $joinQueue.steadyCalls -and $null -ne $joinBatch.steadyCalls) {
    if ($joinBatch.steadyCalls -gt 0) {
        $joinsPerGroupText = Format-Number ($joinQueue.steadyCalls / $joinBatch.steadyCalls) 2
    } else {
        $joinsPerGroupText = 'no group transactions'
    }
}
$md.Add("Joins per group transaction ($windowName, join.queue calls / join.batch calls): $joinsPerGroupText.")
$md.Add('')

$md.Add('## JVM: garbage collection and JIT compilation')
$md.Add('')
$gcOptionText = $(if ($null -eq $GcOption) { 'not recorded (a run from before run.ps1 -Gc; the JVM chose)' }
    elseif ($GcOption -ceq 'default') { '`default` (the JVM chose)' } else { "``$GcOption`` (run.ps1 -Gc)" })
$md.Add("GC option: $gcOptionText. Collector in the JVM's log: " +
    $(if ($gcLogged) { "**$logCollector** (" + '"Using ' + $logCollector + '" at start-up)' } else { 'no GC log in app.log (a run from before -Xlog:gc)' }) +
    '. Collectors in the application''s metrics (jvm.gc.pause): ' + $(if ($jvm.gc) { $jvm.gc } else { 'unknown' }) +
    '. GC worker threads ("GC Thread#n", most seen): ' + $(if ($null -ne $gcWorkers) { "$gcWorkers" } else { $NotMeasured }) + '.')
$md.Add('')
$md.Add('JVM options the JVM reported at start-up (app.log): ' + $(if ($pickedUpOptions) { "``$pickedUpOptions``" } else { 'not in the log' }) + '.')
$md.Add('')
$md.Add('| phase | app CPU (cores) | JVM CPU (cores) | GC threads CPU (cores) | JIT compiler CPU (cores) | GC pauses/s | pause mean ms | pause max ms | full GCs | time in GC pauses | pauses from |')
$md.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|')
foreach ($phaseName in $jvmPhases.Keys) {
    $p = $jvmPhases[$phaseName]
    $md.Add(('| {0} ({1}-{2} s) | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} |' -f $phaseName,
        (Format-Number $p.fromSecond 0), (Format-Number $p.toSecond 0), (Format-Measured $p.appCores 2), (Format-Measured $p.processCores 2),
        (Format-Measured $p.gcCores 3), (Format-Measured $p.jitCores 3), (Format-Measured $p.pausesPerSecond 1),
        (Format-Measured $p.pauseMeanMs 2), (Format-Measured $p.pauseMaxMs 2), (Format-Measured $p.fullPauses 0),
        (Format-Percent $p.pauseShare 2), $(if ($p.pauseSource) { $p.pauseSource } else { '-' })))
}
$md.Add('')
$md.Add('Every 10 s of the run, warm-up included (the seconds of k6''s latency table above):')
$md.Add('')
$md.Add('| seconds | app CPU (cores) | GC threads CPU | JIT compiler CPU | GC pauses/s | pause mean ms | pause max ms |')
$md.Add('|---|---:|---:|---:|---:|---:|---:|')
foreach ($b in $jvmBins) {
    $md.Add(('| {0}-{1} | {2} | {3} | {4} | {5} | {6} | {7} |' -f (Format-Number $b.fromSecond 0), (Format-Number $b.toSecond 0),
        (Format-Number $b.appCores 2), (Format-Number $b.gcCores 3), (Format-Number $b.jitCores 3),
        (Format-Number $b.pausesPerSecond 1), (Format-Number $b.pauseMeanMs 2), (Format-Number $b.pauseMaxMs 2)))
}
$md.Add('')
$md.Add('Cores: CPU seconds per second of the Docker VM''s clock, as docker stats counts them; GC pauses/s also per VM second. app CPU: the container (docker stats). ' +
    'JVM, GC threads and JIT compiler CPU: thread-cpu.csv, CPU time of the JVM''s threads read from /proc about every 5 s and ' +
    'interpolated to the interval''s ends (approximate). GC threads = the "GC Thread#n" workers and the "VM Thread", which runs ' +
    'Serial collections itself and coordinates parallel ones; for Serial, the CPU of a collection is about its pause. JIT compiler ' +
    '= the C1 and C2 compiler threads, the cost of warming up. Pauses: from the GC log (exact durations; positions mapped from the ' +
    'JVM''s uptime through the clock record, about +/- 1 s) or, without it, from the jvm.gc.pause timer (interpolated counts). ' +
    'Time in GC pauses: share of the interval in which the application''s threads were stopped. Phases need readings that cover ' +
    'them; a 10 s row shows "-" where there were none.')
$md.Add('')

$md.Add("## Latency: client vs. server ($windowName, approximate)")
$md.Add('')
$md.Add('| endpoint | k6 req/s (VM clock) | server calls/s (VM clock) | k6 avg ms | k6 time to first byte avg ms | server mean ms | difference ms |')
$md.Add('|---|---:|---:|---:|---:|---:|---:|')
foreach ($endpoint in $latencySplit.Keys) {
    $s = $latencySplit[$endpoint]
    $md.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f $endpoint, (Format-Measured $s.k6PerSecond 1),
        (Format-Measured $s.serverPerVmSecond 1), (Format-Measured $s.k6AvgMs 2), (Format-Measured $s.k6TtfbAvgMs 2),
        (Format-Measured $s.serverMeanMs 2), (Format-Measured $s.outsideServerMs 2)))
}
$md.Add('')
$md.Add('Server calls/s are converted to the VM clock to compare with k6. Difference = k6''s average time to first byte ' +
    'minus the server''s mean time: time a request spends outside the server''s own timer. That includes waiting for a ' +
    'Tomcat worker thread (connections queue in the accept backlog and the poller while all workers are busy), reading ' +
    'the request before the observation starts, the network between the k6 and application containers and k6''s own ' +
    'scheduling. It is a difference of two averages over slightly different requests (k6: requests that started in the ' +
    'steady phase; the server: requests that finished in the sampled window), so it is an estimate and not the Tomcat ' +
    'queue time by itself. Percentiles cannot be subtracted like this.')
$md.Add('')

$md.Add('## MySQL')
$md.Add('')
$commitText = $NotMeasured
if ($commitPath.commitStatus -ceq 'invalid') { $commitText = "$InvalidText (MySQL's statement timer, see below)" }
if ($null -ne $commitPath.commitMeanMs) { $commitText = (Format-Number $commitPath.commitMeanMs 2) + ' ms on average' }
$withMs = { param($value) if ($null -eq $value) { $NotMeasured } else { (Format-Number $value 2) + ' ms' } }
$durableCommits = ("Durable commits ({0}): COMMIT {1}; redo log sync {2}, binary log sync {3} on average " +
    '(sync = fsync of the log file; timed with the CPU cycle counter, not affected by wall-clock steps). Settings: ' +
    'log_bin={4}, sync_binlog={5}, innodb_flush_log_at_trx_commit={6}, innodb_buffer_pool_size={7} MiB, ' +
    'innodb_log_writer_threads={8}, max_connections={9}, innodb_doublewrite={10}, innodb_redo_log_capacity={11} MiB, innodb_io_capacity={12}.') -f $windowName, $commitText,
    (& $withMs $commitPath.redoLogSyncMeanMs), (& $withMs $commitPath.binlogSyncMeanMs),
    $mysqlAfter['config.log_bin'], $mysqlAfter['config.sync_binlog'], $mysqlAfter['config.innodb_flush_log_at_trx_commit'],
    (Format-Number ([double] $mysqlAfter['config.innodb_buffer_pool_size'] / 1MB) 0),
    $(if ($null -eq $mysqlAfter['config.innodb_log_writer_threads']) { $NotMeasured } else { $mysqlAfter['config.innodb_log_writer_threads'] }),
    $(if ($null -eq $mysqlAfter['config.max_connections']) { $NotMeasured } else { $mysqlAfter['config.max_connections'] }),
    $(if ($null -eq $mysqlAfter['config.innodb_doublewrite']) { $NotMeasured } elseif ("$($mysqlAfter['config.innodb_doublewrite'])" -ceq '1') { 'ON' } else { 'OFF' }),
    $(if ($null -eq $redoCapacity) { $NotMeasured } else { Format-Number ($redoCapacity / 1MB) 0 }),
    $(if ($null -eq $innodbFlushing.ioCapacity) { $NotMeasured } else { Format-Number $innodbFlushing.ioCapacity 0 })
$md.Add($durableCommits)
$md.Add('')
$f = $innodbFlushing
$mib = { param($bytes) if ($null -eq $bytes) { $NotMeasured } else { Format-Number ($bytes / 1MB) 1 } }
$stat = { param($measured, [string] $key) if ($null -eq $measured) { $null } else { $measured[$key] } }
$md.Add("Redo log and page flushing ($windowName):")
$md.Add('')
$md.Add('| measure | value |')
$md.Add('|---|---:|')
$md.Add(('| checkpoint age avg / max (MiB) | {0} / {1} |' -f (& $mib (& $stat $f.checkpointAgeBytes 'avg')), (& $mib (& $stat $f.checkpointAgeBytes 'max'))))
$md.Add(('| checkpoint age max / redo log capacity | {0} |' -f (Format-Percent $f.checkpointAgeMaxShare 1)))
$md.Add(('| dirty pages avg / max | {0} / {1} |' -f (Format-Measured (& $stat $f.dirtyPages 'avg') 0), (Format-Measured (& $stat $f.dirtyPages 'max') 0)))
$md.Add(('| pages flushed/s (doublewrite pages/s) | {0} ({1}) |' -f (Format-Measured $f.pagesFlushedPerSecond 1), (Format-Measured $f.doublewritePagesPerSecond 1)))
$md.Add(('| waits for a free page/s | {0} |' -f (Format-Measured $f.waitFreePerSecond 2)))
$md.Add(('| data file syncs/s, mean ms | {0}, {1} |' -f (Format-Measured $f.dataFileSyncsPerSecond 1), (Format-Measured $f.dataFileSyncMeanMs 2)))
$md.Add(('| doublewrite file syncs/s, mean ms | {0}, {1} |' -f (Format-Measured $f.doublewriteSyncsPerSecond 1), (Format-Measured $f.doublewriteSyncMeanMs 2)))
$md.Add(('| all InnoDB fsyncs/s (innodb_data_fsyncs, redo log included) | {0} |' -f (Format-Measured $f.innodbFsyncsPerSecond 1)))
$md.Add(('| MiB/s written: redo log, binary log, data files, doublewrite | {0}, {1}, {2}, {3} |' -f (& $mib $f.redoBytesPerSecond),
    (& $mib $f.binlogBytesPerSecond), (& $mib $f.dataFileBytesPerSecond), (& $mib $f.doublewriteBytesPerSecond)))
$md.Add('')
$md.Add('Checkpoint age = redo log written but not yet checkpointed (current LSN - checkpoint LSN, in bytes, no clock); ' +
    'when it approaches the redo log capacity, InnoDB flushes dirty pages harder and can stall writes. Checkpoint age ' +
    "and dirty pages: average and maximum of the samples (every ~2 s; $NotMeasured if the samples do not cover the window); " +
    'the other rows are counters per second. Sync times: performance_schema wait timer (cycle counter), like the log syncs.')
$md.Add('')
$md.Add("| counter | $windowName per second | whole run total |")
$md.Add('|---|---:|---:|')
foreach ($key in $mysql.Keys) {
    $m = $mysql[$key]
    $steadyText = $(if ($m.steadyStatus -ceq 'invalid') { $InvalidText } else { Format-Measured $m.steady $(if ($m.gauge) { 0 } else { 1 }) })
    $wholeText = $(if ($m.wholeRunStatus -ceq 'invalid') { $InvalidText } else { Format-Measured $m.wholeRun 0 })
    $label = $key
    if ($m.gauge) { $label = "$key (current value)" }
    $md.Add(('| {0} | {1} | {2} |' -f $label, $steadyText, $wholeText))
}
$md.Add('')
$md.Add('`statements.*.time_us`: server time in microseconds, so per second it is microseconds of statement time per second.')
$md.Add('')
$statementNotes = @()
foreach ($base in $statementTimes.Keys) {
    $s = $statementTimes[$base]
    $text = '{0}: {1} of {2} intervals ({3} of the window)' -f $base, $s.used, $s.intervals, (Format-Percent $s.shareOfWindow 0)
    if ($s.reasons.Count -gt 0) { $text += '; left out: ' + ($s.reasons -join ', ') }
    $statementNotes += $text
}
$line = (('Statement times use MySQL''s statement timer, which reads the VM''s wall clock: a statement running while ' +
    'Docker Desktop steps that clock back gets a negative duration, stored as an unsigned number close to 2^64 ' +
    'picoseconds. They are therefore summed only over sampling intervals without a wall-clock step (and without an ' +
    'impossible value), and are "{0}" if those cover less than {1} of the window. Intervals used: {2}.') -f $InvalidText,
    (Format-Percent $MinStatementShare 0), $(if ($statementNotes.Count -gt 0) { $statementNotes -join '; ' } else { 'none' }))
$md.Add($line)
$md.Add('')
$md.Add('Statements with the most server time over the whole k6 run, warm-up included (rows examined per call, calls ' +
    'without index; times "invalid" where a statement''s timer ran backwards):')
$md.Add('')
$md.Add('| calls | avg ms | p95 ms | total ms | max ms | timer | rows/call | no index | statement |')
$md.Add('|---:|---:|---:|---:|---:|---|---:|---:|---|')
foreach ($s in $statementRows) {
    $avgText = $s.avgMs; $totalText = $s.totalMs; $maxText = $(if ($null -ne $s.maxMs) { $s.maxMs } else { '-' })
    if ($s.timer -ceq 'invalid') { $avgText = $InvalidText; $totalText = $InvalidText; if ($null -ne $s.maxMs) { $maxText = $InvalidText } }
    $md.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | `{8}` |' -f $s.calls, $avgText, $s.p95Ms, $totalText, $maxText,
        $s.timer, $s.rowsPerCall, $s.noIndex, ($s.sql -creplace '\|', '\|')))
}
$md.Add('')
if ($legacyStatements) {
    $md.Add('This run''s statement list has no maximum per statement (recorded since a later version). Invalid: an ' +
        'impossibly large sum, or the only digest of a statement type whose time counter went backwards during the run. ' +
        'Uncertain: the VM wall clock was stepped during the run, so a wrapped statement may hide in the sum. The p95 ' +
        '(a histogram), the counts, rows and index use do not depend on the timer.')
} else {
    $md.Add('Timer "invalid": a statement of this digest ran while the VM wall clock was stepped back (its maximum is ' +
        '2^63 ps or more), so the sum and average are wrong; the p95 (a histogram), the counts, rows and index use are not ' +
        'affected. A statement longer than a step (about 0.7 s) that spans one is not recognised: it is shortened instead.')
}
$md.Add('')

$md.Add("## Cache hit ratios ($windowName, approximate)")
$md.Add('')
if (-not $redisCovered) {
    $md.Add("Not estimated: the Redis readings do not cover the window ($NotMeasured).")
} else {
    $md.Add('| cache | lookups/s | misses/s (estimated) | hit ratio (approximate) | note |')
    $md.Add('|---|---:|---:|---:|---|')
    $teamHitText = Format-Percent $cacheEstimate.teamHitRatioApprox 1
    if ($cacheEstimate.teamLookupsPerSecond -le 0) { $teamHitText = '-' }
    $membershipHitText = Format-Percent $cacheEstimate.membershipHitRatioApprox 1
    if ($cacheEstimate.membershipLookupsPerSecond -le 0) { $membershipHitText = '-' }
    $md.Add(('| team view (/get) | {0} | {1} | {2} | {3} |' -f (Format-Number $cacheEstimate.teamLookupsPerSecond 1),
        (Format-Number $cacheEstimate.teamFillsPerSecond 1), $teamHitText, $cacheEstimate.teamNote))
    $md.Add(('| membership (/get) | {0} | {1} | {2} | {3} |' -f (Format-Number $cacheEstimate.membershipLookupsPerSecond 1),
        (Format-Number $cacheEstimate.membershipWritesPerSecond 1), $membershipHitText, $cacheEstimate.membershipNote))
    $md.Add('')
    $line = ('Derived from Redis command counts, not counted by the application, so these are estimates (per second of ' +
        'this computer). Rate-limit scripts {0}/s (one per API request), cache invalidations {1}/s (one per committed ' +
        'write). Team view: lookups = HMGET calls minus those of the token-bucket and fill scripts, misses = fill scripts ' +
        '(one per miss, including cached views reloaded because they lacked the caller). Membership: lookups = GET, ' +
        'misses = SET/SETEX (a miss of a member writes the entry; a non-member''s miss writes nothing). Lookups/s should ' +
        'be close to the /get calls/s above.') -f (Format-Number $cacheEstimate.rateLimitScriptsPerSecond 1), (Format-Number $cacheEstimate.invalidationsPerSecond 1)
    $md.Add($line)
}
$md.Add('')

$md.Add('## Redis (whole k6 run)')
$md.Add('')
$line = ('Commands: {0} ({1}/s); keyspace hits {2}, misses {3} (all keys: cache, memberships and rate-limit buckets; ' +
    'not a cache hit ratio); evicted keys {4}; CPU {5} s ({6} cores); memory {7}.') -f (Format-Number $redis.commands 0),
    (Format-Number ($redis.commands / $loadSeconds) 0), (Format-Number $redis.keyspaceHits 0), (Format-Number $redis.keyspaceMisses 0),
    (Format-Number $redis.evictedKeys 0), (Format-Number $redis.cpuSeconds 1), (Format-Number ($redis.cpuSeconds / $loadSeconds) 2),
    $redis.usedMemory
$md.Add($line)
$md.Add('')
$md.Add('| command | calls | usec/call |')
$md.Add('|---|---:|---:|')
foreach ($name in $redisCommands.Keys) {
    $md.Add(('| {0} | {1} | {2} |' -f $name, (Format-Number $redisCommands[$name].calls 0), (Format-Number $redisCommands[$name].usecPerCall 2)))
}
$md.Add('')
$md.Add('## Outcomes: k6 vs. database (whole run)')
$md.Add('')
if ($outcomeChecks.Count -eq 0) {
    $md.Add('Not compared: ' + $(if ($null -eq $k6Outcomes) { 'k6 counted no outcomes (a run from before outcome counting)' } else { 'verify.sql did not run' }) + '.')
} else {
    $md.Add('| outcome | k6 (answers received) | database | database counts | check |')
    $md.Add('|---|---:|---:|---|---|')
    foreach ($check in $outcomeChecks) {
        $md.Add(('| {0} | {1} | {2} | {3} | {4} |' -f $check.outcome, (Format-Measured $check.k6 0),
            $(if ($null -eq $check.database) { '-' } else { "$($check.database)" }), $check.databaseMeaning, $check.status))
    }
    $md.Add('')
    $md.Add('k6 counts the answers it received, warm-up included; the database what was committed (perf/sql/verify.sql). ' +
        'A request can commit without its answer reaching k6 (timeout), so a difference fails the run only when k6 saw no ' +
        'unexpected response. Expected rejections are stored outcomes too (the same key gets the same answer).')
}
$md.Add('')
$md.Add('## Invariants after the run (perf/sql/verify.sql)')
$md.Add('')
if ($violations.Count -eq 0) { $md.Add('All checks passed.') } else { $md.Add("**Violations:** $(($violations.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', ')") }
$md.Add('')
$md.Add('| check | value |')
$md.Add('|---|---:|')
foreach ($key in $verify.Keys) { $md.Add("| $key | $($verify[$key]) |") }
$md.Add('')
$md.Add('## Environment')
$md.Add('')
$hostText = (@([string] $environment.hostCpu, [string] $environment.hostOs) | Where-Object { $_ -cne '' }) -join ', '
$md.Add("Docker: $($environment.dockerCpus) CPUs, $(Format-Number (([double] $environment.dockerMemoryBytes) / 1GB) 1) GiB, " +
    "engine $($environment.dockerVersion) on $($environment.dockerOs). Host: $hostText. " +
    "Commit: $(if ($environment.gitCommit) { $environment.gitCommit } else { 'unknown' })$(if ($environment.gitDirty) { ' (with uncommitted changes)' }). " +
    'k6, MySQL, Redis and the application share this machine. ' +
    ('JVM in the container: {0} CPUs, max heap {1} MiB, GC: {2}.' -f (Format-Number $jvm.cpus 0), (Format-Number $jvm.maxHeapMiB 0),
        $(if ($jvm.gc) { $jvm.gc } else { 'unknown' })))
$md.Add('')
$jfrMarkdown = Join-Path $resultDir 'jfr-summary.md'
if ($Jfr -and (Test-Path $jfrMarkdown)) {
    $md.Add("JFR window: $jfrWindowBasis$(if ($null -ne $jfrWindow -and $null -ne $jfrWindow.shiftMs) { ' (shifted by ' + (Format-Number ($jfrWindow.shiftMs / 1000) 2) + ' s)' })." )
    $md.Add('')
    foreach ($jfrLine in [IO.File]::ReadAllLines($jfrMarkdown)) { $md.Add($jfrLine) }
} else {
    $md.Add('## Java Flight Recording')
    $md.Add('')
    $md.Add("$jfrStatus.")
    $md.Add('')
}

foreach ($name in @('report', 'run')) {
    $extension = $(if ($name -ceq 'report') { 'md' } else { 'json' })
    $current = Join-Path $resultDir "$name.$extension"
    $original = Join-Path $resultDir "$name.original.$extension"
    if ((Test-Path $current) -and -not (Test-Path $original)) { Copy-Item $current $original }
}
Write-TextFile (Join-Path $resultDir 'run.json') ($run | ConvertTo-Json -Depth 12)
Write-TextFile (Join-Path $resultDir 'report.md') (($md -join "`n") + "`n")

Write-Step "Result: $(if ($passed) { 'PASS' } else { 'FAIL' })"
if ($k6ExitCode -ne 0) {
    Write-Host "    k6 exit code $k6ExitCode (99: a target was missed, 108: aborted, see k6.log)"
    if (Test-Path $k6Log) { Get-Content $k6Log -Tail 5 | ForEach-Object { Write-Host "    $_" } }
}
if ($violations.Count -gt 0) { Write-Host "    INVARIANT VIOLATIONS: $(($violations.Keys) -join ', ')" -ForegroundColor Red }
Write-Host "    window check: $($alignment.status)$(if ($alignment.reason) { ' (' + $alignment.reason + ')' })" -ForegroundColor $(if ($alignment.status -ceq 'aligned') { 'Gray' } else { 'Yellow' })
foreach ($name in $quality.Keys) {
    if (-not (Test-Covered $quality[$name])) { Write-Host "    measurement gap: $name covers $(Format-Percent $quality[$name].coverage 0) of the window" -ForegroundColor Yellow }
}
if ($clockSource -ceq 'none') { Write-Host '    clock check failed: the window is not converted from the Docker VM clock' -ForegroundColor Yellow }
$badStatementTimes = @($statementTimes.Keys | Where-Object { $statementTimes[$_].status -cne 'ok' })
if ($badStatementTimes.Count -gt 0) {
    Write-Host "    MySQL statement times not usable (VM wall-clock steps): $($badStatementTimes -join ', ')" -ForegroundColor Yellow
}
foreach ($s in $statementRows) {
    if ($s.timer -ceq 'invalid') { Write-Host "    MySQL statement timer ran backwards (times invalid): $($s.sql.Substring(0, [Math]::Min(70, $s.sql.Length)))..." -ForegroundColor Yellow }
}
Write-Host "    report: $(Join-Path $resultDir 'report.md')"
if ($passed) { exit 0 }
exit 1
