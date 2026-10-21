# Measures fsync and MySQL commit latency on Docker storage.
[CmdletBinding()]
param(
    [ValidateRange(5, 600)]
    [int] $FioSeconds = 30,

    [ValidateRange(2, 32)]
    [int] $FioParallel = 4,

    [ValidateNotNullOrEmpty()]
    [string[]] $Concurrency = @('1', '4', '8', '16', '32'),

    [ValidateRange(1, 300)]
    [int] $CommitWarmup = 5,
    [ValidateRange(5, 600)]
    [int] $CommitSeconds = 30,

    [string[]] $CompareRuns = @(),

    [switch] $SkipFio,
    [switch] $SkipCommit,

    [switch] $SkipBuild,

    [switch] $KeepVolumes
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'common.ps1')

$BenchPrefix = 'team-chest-bench-'
$MysqlImage = 'mysql:8.4'
$MysqlOptions = @('--skip-name-resolve', '--max-connections=200', '--innodb-log-writer-threads=OFF')
$FioImage = 'team-chest-bench-fio:local'
$ClientImage = 'team-chest-bench-client:local'
$FioBlockSize = '16k'
$MysqlStartSeconds = 180
$WallStepNoteMs = 100

function New-Secret([int] $Length = 32) {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] $Length
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Get-Lines($Output) {
    return @($Output | ForEach-Object { "$_" } | Where-Object { $_.Trim() -cne '' })
}

function Get-VmClock([string] $Container) {
    $best = $null
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $before = Get-EpochMs
        $lines = @(Get-Lines (Invoke-Docker -Capture -AllowFailure -Arguments @('exec', $Container, 'cat', '/proc/uptime')))
        $after = Get-EpochMs
        if ($LASTEXITCODE -ne 0 -or $lines.Count -eq 0) { continue }
        [double] $uptime = 0
        if (-not [double]::TryParse($lines[0].Trim().Split(' ')[0], [Globalization.NumberStyles]::Float, $Invariant, [ref] $uptime)) { continue }
        $sample = [ordered]@{ hostMs = ($before + $after) / 2.0; uncertaintyMs = ($after - $before) / 2.0; vmSeconds = $uptime }
        if ($null -eq $best -or $sample.uncertaintyMs -lt $best.uncertaintyMs) { $best = $sample }
    }
    return $best
}

function Get-ClockRate($Start, $End) {
    if ($null -eq $Start -or $null -eq $End) { return $null }
    $hostMs = $End.hostMs - $Start.hostMs
    if ($hostMs -le 0) { return $null }
    return ($End.vmSeconds - $Start.vmSeconds) * 1000.0 / $hostMs
}

function Remove-BenchResource([string] $Kind, [string] $Name) {
    if (-not $Name.StartsWith($BenchPrefix)) { throw "Refusing to remove $Kind ${Name}: not a benchmark resource" }
    switch ($Kind) {
        'container' { Invoke-Docker -Capture -AllowFailure -Arguments @('rm', '--force', '--volumes', $Name) | Out-Null }
        'volume' { Invoke-Docker -Capture -AllowFailure -Arguments @('volume', 'rm', '--force', $Name) | Out-Null }
        'network' { Invoke-Docker -Capture -AllowFailure -Arguments @('network', 'rm', $Name) | Out-Null }
    }
}

function Remove-Created([string] $Kind, [string] $Name) {
    Remove-BenchResource $Kind $Name
    for ($i = $script:created.Count - 1; $i -ge 0; $i--) {
        if ($script:created[$i][0] -ceq $Kind -and $script:created[$i][1] -ceq $Name) { $script:created.RemoveAt($i) }
    }
}

function Test-DockerObject([string[]] $Arguments) {
    Invoke-Docker -Capture -AllowFailure -Arguments $Arguments | Out-Null
    return $LASTEXITCODE -eq 0
}

function Format-Ms($Value, [int] $Digits = 2) { return Format-Measured $Value $Digits }

function Get-Field($Object, [string[]] $Path) {
    $current = $Object
    foreach ($name in $Path) {
        if ($null -eq $current) { return $null }
        if ($current -is [System.Collections.IDictionary]) { $current = $current[$name]; continue }
        $current = Get-JsonValue $current $name
    }
    return $current
}

function Invoke-FioTest($Test) {
    $fioArguments = @('run', '--rm', '-v', "$($script:fioVolume):/data", $FioImage,
        "--name=$($Test.name)", '--directory=/data', '--ioengine=sync', '--rw=write', "--bs=$FioBlockSize",
        '--fdatasync=1', '--direct=0', '--time_based', "--runtime=$FioSeconds", "--numjobs=$($Test.jobs)",
        '--group_reporting', '--clocksource=clock_gettime', '--output-format=json') + $Test.extra
    Write-Host ("    {0}: {1} job(s), {2} s" -f $Test.name, $Test.jobs, $FioSeconds)
    $text = (Get-Lines (Invoke-Docker -Capture -Arguments $fioArguments)) -join "`n"
    $start = $text.IndexOf('{')
    if ($start -lt 0) { throw "fio $($Test.name) printed no JSON result" }
    $jsonText = $text.Substring($start)
    Write-TextFile (Join-Path $script:resultDir "fio-$($Test.name).json") $jsonText
    $job = @((ConvertFrom-Json $jsonText).jobs)[0]
    $sync = Get-Field $job @('sync', 'lat_ns')
    $percentiles = Get-JsonValue $sync 'percentile'
    $toMs = { param($ns) if ($null -eq $ns) { $null } else { [double] $ns / 1e6 } }
    $result = [ordered]@{
        test = $Test.name; jobs = $Test.jobs; files = $Test.files
        durableWritesPerSecond = [double] (Get-Field $job @('write', 'iops'))
        syncs = Get-JsonValue $sync 'N'
        syncMeanMs = & $toMs (Get-JsonValue $sync 'mean')
        syncP50Ms = & $toMs (Get-JsonValue $percentiles '50.000000')
        syncP95Ms = & $toMs (Get-JsonValue $percentiles '95.000000')
        syncP99Ms = & $toMs (Get-JsonValue $percentiles '99.000000')
        syncMaxMs = & $toMs (Get-JsonValue $sync 'max')
        writeMeanMs = & $toMs (Get-Field $job @('write', 'clat_ns', 'mean'))
        error = Get-JsonValue $job 'error'
    }
    Write-Host ("      {0} durable writes/s, fdatasync p50 {1} ms, p99 {2} ms" -f (Format-Number $result.durableWritesPerSecond 0),
        (Format-Ms $result.syncP50Ms), (Format-Ms $result.syncP99Ms))
    return $result
}

function Get-AppRun([string] $Name) {
    $file = Join-Path (Join-Path $RepoRoot 'perf\results') (Join-Path $Name 'run.json')
    if (-not (Test-Path $file)) { return $null }
    $run = [IO.File]::ReadAllText($file) | ConvertFrom-Json
    $rate = {
        param([string] $Counter)
        $entry = Get-Field $run @('mysql', $Counter)
        if ($null -eq $entry -or (Get-JsonValue $entry 'steadyStatus') -cne 'ok') { return $null }
        return [double] (Get-JsonValue $entry 'steady')
    }
    $all = $null
    foreach ($row in @(Get-Field $run @('k6', 'report', 'steady'))) {
        if ($null -ne $row -and (Get-JsonValue $row 'endpoint') -ceq 'all') { $all = $row }
    }
    $pool = Get-Field $run @('settings', 'dbPoolMax')
    if ($null -eq $pool) { $pool = Get-Field $run @('appGauges', 'hikaricp.connections.max', 'max') }
    $commits = & $rate 'statements.commit.count'
    $redoSyncs = & $rate 'file_io.redo_log.sync_count'
    $binlogSyncs = & $rate 'file_io.binlog.sync_count'
    $redoBusy = & $rate 'file_io.redo_log.sync_time_us'
    $binlogBusy = & $rate 'file_io.binlog.sync_time_us'
    return [ordered]@{
        run = $Name; pool = $pool; p95 = Get-JsonValue $all 'p95'; commitsPerSecond = $commits
        commitMeanMs = Get-Field $run @('commitPath', 'commitMeanMs')
        redoSyncMeanMs = Get-Field $run @('commitPath', 'redoLogSyncMeanMs')
        binlogSyncMeanMs = Get-Field $run @('commitPath', 'binlogSyncMeanMs')
        redoSyncsPerSecond = $redoSyncs; binlogSyncsPerSecond = $binlogSyncs
        redoBusy = $(if ($null -ne $redoBusy) { $redoBusy / 1e6 } else { $null })
        binlogBusy = $(if ($null -ne $binlogBusy) { $binlogBusy / 1e6 } else { $null })
        commitsPerRedoSync = $(if ($null -ne $commits -and $redoSyncs -gt 0) { $commits / $redoSyncs } else { $null })
        commitsPerBinlogSync = $(if ($null -ne $commits -and $binlogSyncs -gt 0) { $commits / $binlogSyncs } else { $null })
    }
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $RepoRoot

$levels = New-Object Collections.Generic.List[int]
foreach ($part in @($Concurrency | ForEach-Object { "$_".Split(',') })) {
    $text = $part.Trim()
    if ($text -ceq '') { continue }
    [int] $value = 0
    if (-not [int]::TryParse($text, [Globalization.NumberStyles]::None, $Invariant, [ref] $value) -or $value -lt 1 -or $value -gt 150) {
        Pop-Location
        throw "-Concurrency: '$text' is not a number of connections between 1 and 150"
    }
    $levels.Add($value)
}
if ($levels.Count -eq 0 -and -not $SkipCommit) { Pop-Location; throw '-Concurrency: no level given' }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$tag = "$BenchPrefix$stamp"
$fioVolume = "$tag-fio"
$mysqlVolume = "$tag-mysql"
$network = "$tag-net"
$mysqlContainer = "$tag-mysql"
$clockContainer = "$tag-clock"
$resultDir = Join-Path (Join-Path $RepoRoot 'perf\results') "storage-$stamp"
$created = New-Object Collections.Generic.List[object]
$savedEnvironment = @{}
foreach ($name in @('MYSQL_ROOT_PASSWORD', 'MYSQL_PWD', 'BENCH_MYSQL_PASSWORD')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$fioResults = @()
$fioClock = $null
$commit = $null
$commitClock = $null
$exitCode = 1
try {
    Write-Step 'Checking Docker'
    Invoke-Docker -Capture -Arguments @('version', '--format', '{{.Server.Version}}') | Out-Null
    $running = @(Get-Lines (Invoke-Docker -Capture -Arguments @('ps', '--filter', "label=com.docker.compose.project=$Project", '--format', '{{.Names}}')))
    if ($running.Count -gt 0) {
        throw ("The load-test stack is running ({0}); stop it first so the benchmark has the disk to itself " +
            "(this keeps its volumes): docker compose -f compose.yaml -f perf/compose.perf.yaml stop") -f ($running -join ', ')
    }
    foreach ($name in @($fioVolume, $mysqlVolume)) {
        if (Test-DockerObject @('volume', 'inspect', $name)) { throw "Volume $name exists already" }
    }
    New-Item -ItemType Directory -Force -Path $resultDir | Out-Null

    Write-Step 'Benchmark images'
    if ($SkipBuild) {
        foreach ($image in @($FioImage, $ClientImage)) {
            if (-not (Test-DockerObject @('image', 'inspect', $image))) { throw "Image $image not found: run without -SkipBuild" }
        }
        Write-Host '    using the existing images'
    } else {
        Invoke-Docker -Arguments @('build', '--tag', $FioImage, '--file', 'perf/storage/fio.Dockerfile', 'perf/storage')
        Invoke-Docker -Arguments @('build', '--tag', $ClientImage, '--file', 'perf/storage/client.Dockerfile', 'perf/storage')
    }
    if (-not $SkipCommit) { Invoke-Docker -Arguments @('pull', '--quiet', $MysqlImage) }

    $created.Add(@('container', $clockContainer))
    Invoke-Docker -Capture -Arguments @('run', '--detach', '--name', $clockContainer, '--entrypoint', 'sleep', $FioImage, '86400') | Out-Null

    if (-not $SkipFio) {
        Write-Step "fio on the new named volume $fioVolume"
        $created.Add(@('volume', $fioVolume))
        Invoke-Docker -Capture -Arguments @('volume', 'create', $fioVolume) | Out-Null
        $fioTests = @(
            [ordered]@{ name = 'redo-like-1'; jobs = 1; files = 'preallocated, overwritten (like the redo log)'
                extra = @('--size=256m', '--overwrite=1') },
            [ordered]@{ name = 'binlog-like-1'; jobs = 1; files = 'growing, appended (like the binary log)'
                extra = @('--size=1g', '--file_append=1', '--fallocate=none') },
            [ordered]@{ name = "redo-like-$FioParallel"; jobs = $FioParallel; files = 'preallocated, overwritten (like the redo log)'
                extra = @('--size=256m', '--overwrite=1') },
            [ordered]@{ name = "binlog-like-$FioParallel"; jobs = $FioParallel; files = 'growing, appended (like the binary log)'
                extra = @('--size=1g', '--file_append=1', '--fallocate=none') }
        )
        $clockStart = Get-VmClock $clockContainer
        foreach ($test in $fioTests) { $fioResults += Invoke-FioTest $test }
        $clockEnd = Get-VmClock $clockContainer
        $fioClock = [ordered]@{ start = $clockStart; end = $clockEnd; rate = Get-ClockRate $clockStart $clockEnd }
        if (-not $KeepVolumes) { Remove-Created 'volume' $fioVolume }
    }

    if (-not $SkipCommit) {
        Write-Step "MySQL 8.4 alone on the new named volume $mysqlVolume"
        $password = New-Secret
        $env:MYSQL_ROOT_PASSWORD = $password
        $env:MYSQL_PWD = $password
        $env:BENCH_MYSQL_PASSWORD = $password
        $created.Add(@('network', $network))
        Invoke-Docker -Capture -Arguments @('network', 'create', $network) | Out-Null
        $created.Add(@('volume', $mysqlVolume))
        Invoke-Docker -Capture -Arguments @('volume', 'create', $mysqlVolume) | Out-Null
        $created.Add(@('container', $mysqlContainer))
        Invoke-Docker -Capture -Arguments (@('run', '--detach', '--name', $mysqlContainer, '--network', $network,
                '-v', "$($mysqlVolume):/var/lib/mysql", '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_DATABASE=bench',
                $MysqlImage) + $MysqlOptions) | Out-Null
        $deadline = (Get-Date).AddSeconds($MysqlStartSeconds)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            Invoke-Docker -Capture -AllowFailure -Arguments @('exec', '-e', 'MYSQL_PWD', $mysqlContainer, 'mysqladmin', 'ping',
                '-h', '127.0.0.1', '-uroot', '--silent') | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            $state = "$(Get-Lines (Invoke-Docker -Capture -AllowFailure -Arguments @('inspect', '--format', '{{.State.Running}}', $mysqlContainer)) | Select-Object -First 1)"
            if ($state.Trim() -cne 'true') { throw "The benchmark MySQL stopped during start-up; see: docker logs $mysqlContainer" }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) { throw "The benchmark MySQL did not start within $MysqlStartSeconds s" }
        Write-Host '    MySQL is ready'

        $clientArguments = @('run', '--rm', '--network', $network, '-e', 'BENCH_MYSQL_PASSWORD', $ClientImage,
            '--host', $mysqlContainer, '--levels', ($levels -join ','), '--warmup', "$CommitWarmup", '--duration', "$CommitSeconds")
        $clockStart = Get-VmClock $clockContainer
        $output = (Get-Lines (Invoke-Docker -Capture -Arguments $clientArguments)) -join "`n"
        $clockEnd = Get-VmClock $clockContainer
        $commitClock = [ordered]@{ start = $clockStart; end = $clockEnd; rate = Get-ClockRate $clockStart $clockEnd }
        $start = $output.IndexOf('{')
        if ($start -lt 0) { throw 'The commit benchmark printed no JSON result' }
        Write-TextFile (Join-Path $resultDir 'commit-bench.json') $output.Substring($start)
        $commit = ConvertFrom-Json $output.Substring($start)
        Remove-Created 'container' $mysqlContainer
        if (-not $KeepVolumes) { Remove-Created 'volume' $mysqlVolume }
        Remove-Created 'network' $network
    }

    Write-Step 'Report'
    $runsDir = Join-Path $RepoRoot 'perf\results'
    $compareNames = @($CompareRuns | Where-Object { $_ })
    if ($compareNames.Count -eq 0 -and (Test-Path $runsDir)) {
        $compareNames = @(Get-ChildItem -Path $runsDir -Directory | Where-Object {
                $_.Name -clike '*-mixed-cache-on-500rps*' -and (Test-Path (Join-Path $_.FullName 'run.json')) } |
            Sort-Object Name | Select-Object -Last 2 | ForEach-Object { $_.Name })
    }
    $appRuns = @($compareNames | ForEach-Object { Get-AppRun $_ } | Where-Object { $null -ne $_ })

    $dockerInfo = "$(Get-Lines (Invoke-Docker -Capture -AllowFailure -Arguments @('info', '--format', '{{.ServerVersion}}|{{.OperatingSystem}}|{{.NCPU}}|{{.MemTotal}}')) | Select-Object -First 1)"
    $cpuName = ''
    try { $cpuName = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name } catch { }

    $md = New-Object Collections.Generic.List[string]
    $md.Add("# Storage benchmark $stamp")
    $md.Add('')
    $md.Add("Docker: $dockerInfo (version | OS | CPUs | memory bytes). Host CPU: $cpuName. The load-test stack was not running.")
    $md.Add('')

    $md.Add('## Clocks')
    $md.Add('')
    $clockLine = {
        param([string] $Part, $Clock)
        if ($null -eq $Clock -or $null -eq $Clock.rate) { return "- ${Part}: VM clock rate $NotMeasured." }
        return ("- {0}: the VM's monotonic clock ran {1} % {2} against this computer's (readings +/- {3} ms)." -f
            $Part, (Format-Number ([Math]::Abs($Clock.rate - 1) * 100) 2), $(if ($Clock.rate -ge 1) { 'fast' } else { 'slow' }),
            (Format-Number ([Math]::Max($Clock.start.uncertaintyMs, $Clock.end.uncertaintyMs)) 0))
    }
    if (-not $SkipFio) { $md.Add((& $clockLine 'fio' $fioClock)) }
    if (-not $SkipCommit) { $md.Add((& $clockLine 'commit benchmark' $commitClock)) }
    $md.Add('')
    $md.Add('Every duration below is measured inside the Docker VM, in VM time, like the load tests'' latencies and ' +
        'timers, so they compare directly. On this computer''s clock a duration is the VM value divided by the rate ' +
        '(1 + the share above). fio and the client use the monotonic clock; MySQL''s log syncs its cycle counter. ' +
        'MySQL''s statement timer (wall clock, stepped by Docker Desktop) is not used here.')
    if ($null -ne $fioClock -and $null -ne $commitClock -and $null -ne $fioClock.rate -and $null -ne $commitClock.rate -and
            [Math]::Abs($fioClock.rate - $commitClock.rate) -gt 0.01) {
        $md.Add('')
        $md.Add('**Warning:** the VM clock rate differed by more than 1 % between the two parts; repeat before comparing them.')
    }
    $md.Add('')

    if (-not $SkipFio) {
        $md.Add("## fio: $FioBlockSize writes + fdatasync on a Docker named volume")
        $md.Add('')
        $md.Add('| test | jobs | files | durable writes/s | fdatasync mean ms | p50 ms | p95 ms | p99 ms | max ms | write (before the sync) mean ms |')
        $md.Add('|---|---:|---|---:|---:|---:|---:|---:|---:|---:|')
        foreach ($r in $fioResults) {
            $md.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} |' -f $r.test, $r.jobs, $r.files,
                (Format-Number $r.durableWritesPerSecond 0), (Format-Ms $r.syncMeanMs), (Format-Ms $r.syncP50Ms),
                (Format-Ms $r.syncP95Ms), (Format-Ms $r.syncP99Ms), (Format-Ms $r.syncMaxMs), (Format-Ms $r.writeMeanMs 3)))
        }
        $md.Add('')
        $md.Add("Each job writes $FioBlockSize at a time with one outstanding write (ioengine=sync) and calls fdatasync after " +
            'every write, like a commit that makes its log durable. Durable writes/s: all jobs together. ' +
            'Raw results: fio-*.json.')
        $md.Add('')
    }

    if (-not $SkipCommit -and $null -ne $commit) {
        $s = $commit.settings
        $md.Add('## MySQL alone: INSERT + COMMIT')
        $md.Add('')
        $md.Add((('MySQL {0}: log_bin={1}, sync_binlog={2}, innodb_flush_log_at_trx_commit={3}, innodb_log_writer_threads={4}, ' +
            'innodb_use_fdatasync={5}, innodb_flush_method={6}, innodb_doublewrite={7}, buffer pool {8} MiB, redo log capacity {9} MiB, ' +
            'innodb_io_capacity={10}, binlog_group_commit_sync_delay={11}. Server options as in compose.yaml: {12}. ' +
            'Client round trip (SELECT 1) p50 {13} ms, included in every COMMIT latency below.') -f
            $s.version, $s.log_bin, $s.sync_binlog, $s.innodb_flush_log_at_trx_commit, $s.innodb_log_writer_threads,
            $s.innodb_use_fdatasync, $s.innodb_flush_method, $s.innodb_doublewrite,
            (Format-Number ([double] $s.innodb_buffer_pool_size / 1MB) 0), (Format-Number ([double] $s.innodb_redo_log_capacity / 1MB) 0),
            $s.innodb_io_capacity, $s.binlog_group_commit_sync_delay, ($MysqlOptions -join ' '),
            (Format-Ms (Get-Field $commit @('roundTripMs', 'p50')) 3)))
        $md.Add('')
        $md.Add('| connections | commits/s | COMMIT mean ms | p50 ms | p95 ms | p99 ms | max ms | redo syncs/s | redo sync mean ms | redo sync busy | binlog syncs/s | binlog sync mean ms | binlog sync busy | commits per redo sync | commits per binlog sync | valid |')
        $md.Add('|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|')
        $stepNotes = @()
        foreach ($level in @($commit.levels)) {
            $c = Get-JsonValue $level 'commitMs'
            $redo = Get-Field $level @('server', 'redo')
            $binlog = Get-Field $level @('server', 'binlog')
            $valid = [bool] (Get-JsonValue $level 'valid')
            $md.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} | {14} | {15} |' -f
                $level.concurrency, (Format-Number (Get-JsonValue $level 'commitsPerSecond') 1),
                (Format-Ms (Get-JsonValue $c 'mean')), (Format-Ms (Get-JsonValue $c 'p50')), (Format-Ms (Get-JsonValue $c 'p95')),
                (Format-Ms (Get-JsonValue $c 'p99')), (Format-Ms (Get-JsonValue $c 'max')),
                (Format-Number (Get-JsonValue $redo 'syncsPerSecond') 1), (Format-Ms (Get-JsonValue $redo 'syncMeanMs')),
                (Format-Percent (Get-JsonValue $redo 'busy') 0),
                (Format-Number (Get-JsonValue $binlog 'syncsPerSecond') 1), (Format-Ms (Get-JsonValue $binlog 'syncMeanMs')),
                (Format-Percent (Get-JsonValue $binlog 'busy') 0),
                (Format-Number (Get-Field $level @('server', 'commitsPerRedoSync')) 2),
                (Format-Number (Get-Field $level @('server', 'commitsPerBinlogSync')) 2),
                $(if ($valid) { 'yes' } else { 'no: ' + (@($level.errors) -join '; ') })))
            $step = Get-JsonValue $level 'wallStepMs'
            if ($null -ne $step -and [Math]::Abs([double] $step) -ge $WallStepNoteMs) {
                $stepNotes += ('{0} connections: the VM wall clock was stepped by {1} ms' -f $level.concurrency, (Format-Number $step 0))
            }
        }
        $md.Add('')
        $md.Add("Per level: $CommitWarmup s warm-up, then $CommitSeconds s measured. COMMIT latency: client side, from sending " +
            'COMMIT until its answer (one round trip included). Syncs: performance_schema (cycle counter); busy = share of the ' +
            'window in which a sync of that log was running; commits per sync = COMMIT statements / syncs of that log ' +
            '(MySQL''s group commit). Raw results: commit-bench.json.')
        if ($stepNotes.Count -gt 0) {
            $md.Add('')
            $md.Add('Wall-clock steps during a level (they do not affect these durations): ' + ($stepNotes -join '; ') + '.')
        }
        $md.Add('')
    }

    $md.Add('## Compared with the load tests')
    $md.Add('')
    if ($appRuns.Count -eq 0) {
        $md.Add('No load-test run to compare with (-CompareRuns, or a mixed 500 req/s run under perf/results).')
    } else {
        $md.Add('| source | DB pool | commits/s | COMMIT mean ms | redo syncs/s | redo sync mean ms | redo sync busy | binlog syncs/s | binlog sync mean ms | binlog sync busy | commits per redo sync | commits per binlog sync |')
        $md.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
        foreach ($app in $appRuns) {
            $md.Add(('| {0} (p95 {1} ms) | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} |' -f
                $app.run, (Format-Number $app.p95 2), (Format-Number $app.pool 0), (Format-Number $app.commitsPerSecond 1),
                (Format-Ms $app.commitMeanMs), (Format-Number $app.redoSyncsPerSecond 1), (Format-Ms $app.redoSyncMeanMs),
                (Format-Percent $app.redoBusy 0), (Format-Number $app.binlogSyncsPerSecond 1), (Format-Ms $app.binlogSyncMeanMs),
                (Format-Percent $app.binlogBusy 0), (Format-Number $app.commitsPerRedoSync 2), (Format-Number $app.commitsPerBinlogSync 2)))
            $closest = $null
            if ($null -ne $commit -and $null -ne $app.commitsPerSecond) {
                foreach ($level in @($commit.levels)) {
                    $cps = Get-JsonValue $level 'commitsPerSecond'
                    if (-not [bool] (Get-JsonValue $level 'valid') -or $null -eq $cps) { continue }
                    if ($null -eq $closest -or [Math]::Abs($cps - $app.commitsPerSecond) -lt [Math]::Abs((Get-JsonValue $closest 'commitsPerSecond') - $app.commitsPerSecond)) {
                        $closest = $level
                    }
                }
            }
            if ($null -ne $closest) {
                $redo = Get-Field $closest @('server', 'redo')
                $binlog = Get-Field $closest @('server', 'binlog')
                $md.Add(('| MySQL alone, {0} connections (closest commit rate) | - | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} |' -f
                    $closest.concurrency, (Format-Number (Get-JsonValue $closest 'commitsPerSecond') 1),
                    (Format-Ms (Get-Field $closest @('commitMs', 'mean'))), (Format-Number (Get-JsonValue $redo 'syncsPerSecond') 1),
                    (Format-Ms (Get-JsonValue $redo 'syncMeanMs')), (Format-Percent (Get-JsonValue $redo 'busy') 0),
                    (Format-Number (Get-JsonValue $binlog 'syncsPerSecond') 1), (Format-Ms (Get-JsonValue $binlog 'syncMeanMs')),
                    (Format-Percent (Get-JsonValue $binlog 'busy') 0),
                    (Format-Number (Get-Field $closest @('server', 'commitsPerRedoSync')) 2),
                    (Format-Number (Get-Field $closest @('server', 'commitsPerBinlogSync')) 2)))
            }
        }
        $md.Add('')
        $md.Add('Load tests: steady phase of their report (COMMIT from MySQL''s statement timer, only from intervals without a ' +
            'wall-clock step; log syncs from the cycle counter). The application''s transactions do more than one INSERT ' +
            '(several statements, row locks), so the same commit rate is not the same work; the log syncs are the common part.')
        $md.Add('')
        $md.Add('How to read it: fdatasync on the volume vs. the log sync times of MySQL alone shows what MySQL adds to a ' +
            'flush; MySQL alone vs. the load test at a similar commit rate shows what the application adds to a commit. ' +
            'These are measurements of this machine at this time; repeat the benchmark before drawing conclusions.')
    }
    $md.Add('')

    $summary = [ordered]@{ format = 1; stamp = $stamp; fio = $fioResults; fioClock = $fioClock; commit = $commit
        commitClock = $commitClock; compared = $appRuns; settings = [ordered]@{ fioSeconds = $FioSeconds; fioParallel = $FioParallel
            levels = @($levels); commitWarmup = $CommitWarmup; commitSeconds = $CommitSeconds } }
    Write-TextFile (Join-Path $resultDir 'storage.json') ($summary | ConvertTo-Json -Depth 10)
    $reportFile = Join-Path $resultDir 'report.md'
    Write-TextFile $reportFile (($md -join "`n") + "`n")
    Write-Host ''
    Write-Host ($md -join "`n")
    Write-Host ''
    Write-Host "Report: $reportFile"
    $exitCode = 0
} finally {
    for ($i = $created.Count - 1; $i -ge 0; $i--) {
        $kind = $created[$i][0]
        $name = $created[$i][1]
        if ($kind -ceq 'volume' -and $KeepVolumes) { Write-Host "    kept volume $name"; continue }
        Remove-BenchResource $kind $name
    }
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    Pop-Location
}
exit $exitCode
