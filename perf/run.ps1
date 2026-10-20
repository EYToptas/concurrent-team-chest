# Runs one load test and collects the results.
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string] $Mix = 'mixed',

    [ValidateSet('on', 'off')]
    [string] $Cache = 'on',

    [ValidateRange(1, 20000)]
    [int] $Rate = 1000,

    [ValidateRange(1, 3600)]
    [int] $Warmup = 60,

    [ValidateRange(1, 7200)]
    [int] $Duration = 180,

    [ValidateRange(1, 30000)]
    [int] $MinTeams = 1000,

    [ValidatePattern('^[0-9]+(\.[0-9]+)?$')]
    [string] $AppCpus = '2',
    [ValidatePattern('^[0-9]+[kmgKMG]?$')]
    [string] $AppMemory = '1g',

    [switch] $Smoke,

    [switch] $Jfr,

    [ValidateSet('default', 'parallel')]
    [string] $Gc = 'default',

    [switch] $SkipBuild,

    [switch] $KeepRunning
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'common.ps1')

$TeamSize = 30
$AppUrl = 'http://localhost:8080'
$ManagementUrl = 'http://localhost:8081'
$ProgressRequestsPerUser = 5
$JoinTokenBlock = 5000
$JfrFile = '/tmp/team-chest.jfr'
$JfrJavaOptions = '-XX:FlightRecorderOptions=stackdepth=128,maxchunksize=1g ' +
    "-XX:StartFlightRecording=filename=$JfrFile,settings=default,dumponexit=true," +
    'jdk.ThreadPark#threshold=50ms,jdk.JavaMonitorEnter#threshold=50ms,jdk.SocketRead#threshold=50ms,' +
    'jdk.ContainerCPUUsage#period=5s,jdk.ContainerCPUThrottling#period=5s'
$GcLogOption = '-Xlog:gc:stdout:uptime,level,tags'
$GcJavaOptions = @{ 'default' = ''; 'parallel' = '-XX:+UseParallelGC' }
$JfrStopSeconds = 120
$DefaultJwtIssuer = 'https://auth.team-chest.local'
$DefaultJwtAudience = 'team-chest-api'
$RequiredEnvVariables = @('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'JWT_ISSUER')
$RedisInfoPattern = '^(cmdstat_.*|total_commands_processed|keyspace_hits|keyspace_misses|evicted_keys|used_cpu_sys|used_cpu_user|used_memory_human)$'

function Invoke-Sql([string] $File, [string[]] $Variables = @()) {
    $lines = @(Invoke-Compose -Capture -Arguments (@('run', '--rm', '-T', 'mysql-tools', $File) + $Variables))
    $values = [ordered]@{}
    foreach ($line in $lines) {
        $parts = "$line".Split("`t")
        if ($parts.Count -ge 2) { $values[$parts[0]] = $parts[1] }
    }
    return $values
}

function Get-DotEnvValue([string] $Raw) {
    $value = $Raw.Trim()
    if ($value.Length -ge 2 -and $value[0] -eq $value[$value.Length - 1] -and ($value[0] -ceq '"' -or $value[0] -ceq "'")) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Read-DotEnv([string] $Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -cmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { $values[$Matches[1]] = Get-DotEnvValue $Matches[2] }
    }
    return $values
}

function Initialize-DotEnv([string] $Path) {
    $lines = New-Object Collections.Generic.List[string]
    $newline = "`n"
    $rewrite = $false
    if (Test-Path $Path) {
        $bytes = [IO.File]::ReadAllBytes($Path)
        $utf8Bom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
        $utf16 = $bytes.Length -ge 2 -and (($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) -or ($bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF))
        $rewrite = $utf8Bom -or $utf16
        if ($rewrite) { Write-Host '    .env had a byte order mark or UTF-16; rewriting it as UTF-8 (values unchanged)' }
        $text = [IO.File]::ReadAllText($Path)
        if ($text.Contains("`r`n")) { $newline = "`r`n" }
        if ($text.Length -gt 0) { $lines.AddRange([string[]] ($text -split "`r?`n")) }
        if ($lines.Count -gt 0 -and $lines[$lines.Count - 1] -ceq '') { $lines.RemoveAt($lines.Count - 1) }
    } else {
        $lines.Add('# Local values for compose.yaml, created by perf/run.ps1. Never commit this file.')
        $rewrite = $true
    }
    $filled = New-Object Collections.Generic.List[string]
    foreach ($name in $RequiredEnvVariables) {
        $index = -1
        $current = ''
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -cmatch "^\s*$name\s*=(.*)$") { $index = $i; $current = Get-DotEnvValue $Matches[1] }
        }
        if ($current -cne '') { continue }
        if ($name -ceq 'JWT_ISSUER') { $value = $DefaultJwtIssuer } else { $value = New-Secret }
        if ($index -ge 0) { $lines[$index] = "$name=$value" } else { $lines.Add("$name=$value") }
        $filled.Add($name)
        $rewrite = $true
    }
    if ($rewrite) { Write-TextFile $Path (($lines -join $newline) + $newline) }
    return $filled.ToArray()
}

function Get-ResponseStatus($ErrorRecord) {
    $exception = $ErrorRecord.Exception
    if ($exception.PSObject.Properties['Response'] -and $null -ne $exception.Response) { return [int] $exception.Response.StatusCode }
    return 0
}

function Get-HttpStatus([string] $Uri, [string] $Token) {
    try {
        Invoke-RestMethod -Uri $Uri -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 10 | Out-Null
        return 200
    } catch {
        return Get-ResponseStatus $_
    }
}

function New-Secret([int] $Length = 32) {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] $Length
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Get-MetricUri([string] $Name, [string] $Tag) {
    $uri = "$ManagementUrl/actuator/metrics/$Name"
    if ($Tag) { $uri = "$uri`?tag=$([Uri]::EscapeDataString($Tag))" }
    return $uri
}

function Get-AppMetric([string] $Name, [string] $Token, [string] $Tag = '') {
    try {
        $response = Invoke-RestMethod -Uri (Get-MetricUri $Name $Tag) -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 10
    } catch {
        $status = 'failed'
        if ((Get-ResponseStatus $_) -eq 404) { $status = 'missing' }
        return [pscustomobject]@{ Status = $status; Values = $null; Response = $null }
    }
    $values = @{}
    foreach ($m in $response.measurements) { $values[[string] $m.statistic] = [double] $m.value }
    return [pscustomobject]@{ Status = 'ok'; Values = $values; Response = $response }
}

function Get-AppTimers([string] $Token) {
    $snapshot = [ordered]@{}
    foreach ($t in $AppTimers) {
        $metric = Get-AppMetric -Name $t.name -Tag $t.tag -Token $Token
        if ($metric.Status -ceq 'ok') {
            $snapshot[$t.key] = $metric.Values
        } elseif ($metric.Status -ceq 'missing') {
            $snapshot[$t.key] = @{ COUNT = 0.0; TOTAL_TIME = 0.0; MAX = 0.0 }
        } else {
            $snapshot[$t.key] = $null
        }
    }
    return $snapshot
}

function Get-JvmInfo([string] $Token) {
    $info = [ordered]@{ cpus = $null; maxHeapMiB = $null; gc = '' }
    $cpus = Get-AppMetric -Name 'system.cpu.count' -Token $Token
    if ($cpus.Status -ceq 'ok') { $info.cpus = $cpus.Values['VALUE'] }
    $heap = Get-AppMetric -Name 'jvm.memory.max' -Tag 'area:heap' -Token $Token
    if ($heap.Status -ceq 'ok') { $info.maxHeapMiB = $heap.Values['VALUE'] / 1MB }
    $pause = Get-AppMetric -Name 'jvm.gc.pause' -Token $Token
    if ($pause.Status -ceq 'ok') {
        $names = @($pause.Response.availableTags | Where-Object { $_.tag -ceq 'gc' } | ForEach-Object { $_.values })
        $info.gc = ($names | Sort-Object -Unique) -join ', '
    }
    return $info
}

function Get-TomcatPoolName([string] $Token) {
    $metric = Get-AppMetric -Name 'tomcat.threads.busy' -Token $Token
    if ($metric.Status -cne 'ok') { return $null }
    $names = @($metric.Response.availableTags | Where-Object { $_.tag -ceq 'name' } | ForEach-Object { $_.values } |
        Where-Object { "$_" -clike '*8080*' })
    if ($names.Count -eq 1) { return [string] $names[0] }
    return $null
}

function Get-RedisInfo {
    $lines = @(Invoke-Compose -Capture -Arguments @('exec', '-T', 'redis', 'redis-cli', 'INFO', 'all'))
    $info = @{}
    foreach ($line in $lines) {
        $text = "$line".Trim()
        if ($text -cmatch '^([a-z_0-9|]+):(.*)$') { $info[$Matches[1]] = $Matches[2] }
    }
    return $info
}

function Get-ClockSample {
    $best = $null
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $before = Get-EpochMs
        $lines = @(Invoke-Compose -Capture -AllowFailure -Arguments @('exec', '-T', 'redis', 'sh', '-c', 'cat /proc/uptime; redis-cli TIME'))
        $after = Get-EpochMs
        if ($LASTEXITCODE -ne 0) { continue }
        $text = @($lines | ForEach-Object { "$_".Trim() } | Where-Object { $_ -cne '' })
        if ($text.Count -lt 3) { continue }
        [double] $uptime = 0
        [int64] $seconds = 0
        [int64] $micros = 0
        $float = [Globalization.NumberStyles]::Float
        $integer = [Globalization.NumberStyles]::None
        if (-not [double]::TryParse($text[0].Split(' ')[0], $float, $Invariant, [ref] $uptime)) { continue }
        if (-not [int64]::TryParse($text[1], $integer, $Invariant, [ref] $seconds)) { continue }
        if (-not [int64]::TryParse($text[2], $integer, $Invariant, [ref] $micros)) { continue }
        $sample = [ordered]@{ hostMs = ($before + $after) / 2.0; uncertaintyMs = ($after - $before) / 2.0
            vmWallMs = $seconds * 1000.0 + $micros / 1000.0; vmUptimeSeconds = $uptime }
        if ($null -eq $best -or $sample.uncertaintyMs -lt $best.uncertaintyMs) { $best = $sample }
    }
    return $best
}

$DockerSampler = {
    param([string] $File, [string] $ContainerPrefix)
    $ErrorActionPreference = 'Continue'
    $invariant = [Globalization.CultureInfo]::InvariantCulture
    Set-Content -Path $File -Value 'epoch_ms,container,cpu_percent,mem_bytes' -Encoding ASCII
    $units = @{ 'B' = 1.0; 'KIB' = 1024.0; 'MIB' = 1048576.0; 'GIB' = 1073741824.0; 'TIB' = 1099511627776.0;
                'KB' = 1000.0; 'MB' = 1000000.0; 'GB' = 1000000000.0; 'TB' = 1000000000000.0 }
    while ($true) {
        $sent = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $lines = @(& docker stats --no-stream --format '{{json .}}' 2>$null)
        $now = [int64] (($sent + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 2)
        $rows = @()
        foreach ($line in $lines) {
            try { $s = $line | ConvertFrom-Json } catch { continue }
            if (-not ([string] $s.Name).StartsWith($ContainerPrefix)) { continue }
            try { $cpu = [double]::Parse(([string] $s.CPUPerc).TrimEnd('%'), $invariant) } catch { continue }
            $memory = 0.0
            if (([string] $s.MemUsage) -cmatch '^\s*([0-9.]+)\s*([A-Za-z]+)') {
                $unit = $Matches[2].ToUpperInvariant()
                if ($units.ContainsKey($unit)) { $memory = [double]::Parse($Matches[1], $invariant) * $units[$unit] }
            }
            $rows += '{0},{1},{2},{3}' -f $now, $s.Name, $cpu.ToString('F2', $invariant), [int64] $memory
        }
        if ($rows.Count -eq 0) { $rows = @('{0},sample:FAILED,0,0' -f $sent) }
        Add-Content -Path $File -Value $rows -Encoding ASCII
        Start-Sleep -Milliseconds 500
    }
}

$AppSampler = {
    param([string] $File, [string] $Token, [string] $Url, [string[]] $Meters)
    $ErrorActionPreference = 'Continue'
    $ProgressPreference = 'SilentlyContinue'
    $invariant = [Globalization.CultureInfo]::InvariantCulture
    Set-Content -Path $File -Value 'epoch_ms,name,value' -Encoding ASCII
    while ($true) {
        foreach ($meter in $Meters) {
            $rows = @()
            $key, $name, $tag = $meter.Split('|')
            $uri = "$Url/actuator/metrics/$name"
            if ($tag) { $uri = "$uri`?tag=$([Uri]::EscapeDataString($tag))" }
            $sent = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            try {
                $response = Invoke-RestMethod -Uri $uri -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 5
                $now = [int64] (($sent + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 2)
                foreach ($m in $response.measurements) {
                    if (@('VALUE', 'COUNT', 'TOTAL_TIME') -ccontains [string] $m.statistic) {
                        $rows += '{0},{1}:{2},{3}' -f $now, $key, $m.statistic, ([double] $m.value).ToString('R', $invariant)
                    }
                }
            } catch {
                $status = 'FAILED'
                $exception = $_.Exception
                if ($exception.PSObject.Properties['Response'] -and $null -ne $exception.Response -and
                        [int] $exception.Response.StatusCode -eq 404) { $status = 'MISSING' }
                $rows += '{0},{1}:{2},{3}' -f $sent, $key, $status, ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $sent)
            }
            if ($rows.Count -gt 0) { Add-Content -Path $File -Value $rows -Encoding ASCII }
        }
        Start-Sleep -Milliseconds 1500
    }
}

$ThreadSampler = {
    param([string] $File, [string] $RepoRoot)
    $ErrorActionPreference = 'Continue'
    Set-Location $RepoRoot
    $invariant = [Globalization.CultureInfo]::InvariantCulture
    Set-Content -Path $File -Value 'epoch_ms,name,value' -Encoding ASCII
    $last = @{}
    $sums = @{ gc = 0.0; jit = 0.0 }
    $first = $true
    while ($true) {
        $sent = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $lines = @(& docker compose -f compose.yaml -f perf/compose.perf.yaml exec -T app sh -c 'cat /proc/1/stat; echo --; cat /proc/1/task/*/stat' 2>$null)
        $now = [int64] (($sent + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 2)
        $process = $null
        $workers = 0
        $inTasks = $false
        $current = @{}
        foreach ($line in $lines) {
            $text = "$line"
            if ($text.Trim() -ceq '--') { $inTasks = $true; continue }
            $open = $text.IndexOf('(')
            $close = $text.LastIndexOf(')')
            if ($open -lt 1 -or $close -lt $open -or $close + 2 -gt $text.Length) { continue }
            $fields = $text.Substring($close + 2).Split(' ')
            if ($fields.Count -lt 13) { continue }
            [double] $user = 0
            [double] $system = 0
            if (-not [double]::TryParse($fields[11], [Globalization.NumberStyles]::None, $invariant, [ref] $user) -or
                    -not [double]::TryParse($fields[12], [Globalization.NumberStyles]::None, $invariant, [ref] $system)) { continue }
            $seconds = ($user + $system) / 100.0
            if (-not $inTasks) { $process = $seconds; continue }
            $name = $text.Substring($open + 1, $close - $open - 1)
            $group = ''
            if ($name.StartsWith('GC Thread#', [StringComparison]::Ordinal) -or $name -ceq 'VM Thread') { $group = 'gc' }
            elseif ($name.StartsWith('C1 CompilerThre', [StringComparison]::Ordinal) -or $name.StartsWith('C2 CompilerThre', [StringComparison]::Ordinal)) { $group = 'jit' }
            if ($name.StartsWith('GC Thread#', [StringComparison]::Ordinal)) { $workers++ }
            if ($group -ceq '') { continue }
            $id = $text.Substring(0, $open).Trim()
            $current[$id] = $seconds
            if (-not $first) {
                $previous = 0.0
                if ($last.ContainsKey($id)) { $previous = $last[$id] }
                if ($seconds -gt $previous) { $sums[$group] += $seconds - $previous }
            }
        }
        $rows = @()
        if ($null -ne $process) {
            $last = $current
            $first = $false
            $rows += '{0},jvm.cpu.process,{1}' -f $now, $process.ToString('F2', $invariant)
            $rows += '{0},jvm.cpu.gc,{1}' -f $now, $sums['gc'].ToString('F2', $invariant)
            $rows += '{0},jvm.cpu.jit,{1}' -f $now, $sums['jit'].ToString('F2', $invariant)
            $rows += '{0},jvm.threads.gc_workers,{1}' -f $now, $workers
        } else {
            $rows = @('{0},sample:FAILED,0' -f $sent)
        }
        Add-Content -Path $File -Value $rows -Encoding ASCII
        Start-Sleep -Seconds 4
    }
}

$MysqlSampler = {
    param([string] $File, [string] $RepoRoot)
    $ErrorActionPreference = 'Continue'
    Set-Location $RepoRoot
    Set-Content -Path $File -Value 'epoch_ms,name,value' -Encoding ASCII
    while ($true) {
        $sent = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $lines = @(& docker compose -f compose.yaml -f perf/compose.perf.yaml exec -T mysql sh /sql/run-sql.sh status.sql 2>$null)
        $now = [int64] (($sent + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 2)
        $rows = @()
        foreach ($line in $lines) {
            $parts = "$line".Split("`t")
            if ($parts.Count -ge 2) { $rows += '{0},{1},{2}' -f $now, $parts[0], $parts[1] }
        }
        if ($rows.Count -eq 0) { $rows = @('{0},sample:FAILED,0' -f $sent) }
        Add-Content -Path $File -Value $rows -Encoding ASCII
        Start-Sleep -Seconds 1
    }
}

$RedisSampler = {
    param([string] $File, [string] $RepoRoot)
    $ErrorActionPreference = 'Continue'
    Set-Location $RepoRoot
    Set-Content -Path $File -Value 'epoch_ms,name,value' -Encoding ASCII
    while ($true) {
        $sent = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $lines = @(& docker compose -f compose.yaml -f perf/compose.perf.yaml exec -T redis redis-cli INFO stats commandstats 2>$null)
        $now = [int64] (($sent + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 2)
        $rows = @()
        foreach ($line in $lines) {
            $text = "$line".Trim()
            if ($text -cmatch '^cmdstat_([a-z_|-]+):calls=([0-9]+),.*failed_calls=([0-9]+)') {
                $rows += '{0},cmd.{1}.calls,{2}' -f $now, $Matches[1], $Matches[2]
                $rows += '{0},cmd.{1}.failed,{2}' -f $now, $Matches[1], $Matches[3]
            } elseif ($text -cmatch '^(total_commands_processed|keyspace_hits|keyspace_misses):([0-9]+)$') {
                $rows += '{0},{1},{2}' -f $now, $Matches[1], $Matches[2]
            }
        }
        if ($rows.Count -eq 0) { $rows = @('{0},sample:FAILED,0' -f $sent) }
        Add-Content -Path $File -Value $rows -Encoding ASCII
        Start-Sleep -Seconds 4
    }
}

$ClockSampler = {
    param([string] $File, [string] $RepoRoot, [int] $Seconds)
    $ErrorActionPreference = 'Continue'
    Set-Location $RepoRoot
    $invariant = [Globalization.CultureInfo]::InvariantCulture
    Set-Content -Path $File -Value 'epoch_ms,vm_uptime_s,vm_wall_ms' -Encoding ASCII
    $loop = ('end=$(( $(cut -d. -f1 /proc/uptime) + LIMIT )); while [ $(cut -d. -f1 /proc/uptime) -lt $end ]; do ' +
        'u=$(cut -d'' '' -f1 /proc/uptime); set -- $(redis-cli TIME); echo $u $1 $2; sleep 1; done').Replace('LIMIT', "$Seconds")
    & docker compose -f compose.yaml -f perf/compose.perf.yaml exec -T redis sh -c $loop 2>$null | ForEach-Object {
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $parts = "$_".Trim().Split(' ')
        [double] $uptime = 0
        [int64] $seconds = 0
        [int64] $micros = 0
        if ($parts.Count -eq 3 -and [double]::TryParse($parts[0], [Globalization.NumberStyles]::Float, $invariant, [ref] $uptime) -and
                [int64]::TryParse($parts[1], [Globalization.NumberStyles]::None, $invariant, [ref] $seconds) -and
                [int64]::TryParse($parts[2], [Globalization.NumberStyles]::None, $invariant, [ref] $micros)) {
            $line = '{0},{1},{2}' -f $now, $uptime.ToString('R', $invariant), ($seconds * 1000.0 + $micros / 1000.0).ToString('R', $invariant)
            Add-Content -Path $File -Value $line -Encoding ASCII
        }
    }
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $RepoRoot

if ($Smoke) {
    $Rate = 50; $Warmup = 60; $Duration = 60
}
$Mix = $Mix.ToLowerInvariant()
$Cache = $Cache.ToLowerInvariant()

$mixes = ([IO.File]::ReadAllText((Join-Path $RepoRoot 'perf\k6\mixes.json')) | ConvertFrom-Json).mixes
$mixNames = @($mixes.PSObject.Properties | ForEach-Object { $_.Name })
if ($mixNames -cnotcontains $Mix) {
    Pop-Location
    throw "Unknown -Mix '$Mix'. perf/k6/mixes.json has: $($mixNames -join ', ')"
}
$share = @{}
$sharePercent = @{}
$shareSum = 0
foreach ($endpoint in $Endpoints) {
    $property = $mixes.$Mix.shares.PSObject.Properties[$endpoint]
    $percent = 0
    if ($null -ne $property) { $percent = [int] $property.Value }
    $sharePercent[$endpoint] = $percent
    $share[$endpoint] = [decimal] $percent / 100
    $shareSum += $percent
}
if ($shareSum -ne 100) {
    Pop-Location
    throw "The shares of mix $Mix in perf/k6/mixes.json add up to $shareSum, not 100"
}
$activeEndpoints = @($Endpoints | Where-Object { $share[$_] -gt 0 })

$runId = '{0}-{1}-cache-{2}-{3}rps{4}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Mix, $Cache, $Rate,
    $(if ($Gc -cne 'default') { "-gc-$Gc" } else { '' })
$resultDir = Join-Path (Join-Path $RepoRoot 'perf\results') $runId
$tokensDir = Join-Path $RepoRoot 'perf\.tokens'
$secretsDir = Join-Path $RepoRoot '.secrets'
$k6RunId = $runId.Substring(0, 15)

$savedEnvironment = @{}
foreach ($name in @('PERF_APP_CPUS', 'PERF_APP_MEMORY', 'PERF_CACHE_ENABLED', 'PERF_JAVA_TOOL_OPTIONS',
        'JWT_AUDIENCE', 'JWT_PUBLIC_KEY_FILE') + $RequiredEnvVariables) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$samplers = @()
$exitCode = 1
try {
    Write-Step 'Checking Docker'
    Invoke-Docker -Capture -Arguments @('version', '--format', '{{.Server.Version}}') | Out-Null
    Invoke-Docker -Capture -Arguments @('compose', 'version') | Out-Null

    Write-Step 'Local settings (.env, git-ignored)'
    $envFile = Join-Path $RepoRoot '.env'
    $filled = @(Initialize-DotEnv $envFile)
    if ($filled.Count -gt 0) {
        Write-Host "    added to .env: $($filled -join ', ') (values that were already set are unchanged)"
    } else {
        Write-Host '    .env has every required variable'
    }
    $dotEnv = Read-DotEnv $envFile
    foreach ($required in $RequiredEnvVariables) {
        if (-not $dotEnv.ContainsKey($required) -or -not $dotEnv[$required]) { throw ".env has no value for $required" }
    }
    if ($dotEnv.ContainsKey('JWT_PUBLIC_KEY_FILE') -and $dotEnv['JWT_PUBLIC_KEY_FILE']) {
        throw '.env sets JWT_PUBLIC_KEY_FILE: load tests need the local token issuer (remove that line for the test)'
    }
    $jwtIssuer = $dotEnv['JWT_ISSUER']
    $jwtAudience = $DefaultJwtAudience
    if ($dotEnv.ContainsKey('JWT_AUDIENCE') -and $dotEnv['JWT_AUDIENCE']) { $jwtAudience = $dotEnv['JWT_AUDIENCE'] }
    if ($jwtIssuer -cmatch '\s' -or $jwtAudience -cmatch '\s') { throw 'JWT_ISSUER and JWT_AUDIENCE in .env must not contain spaces' }
    Write-Host "    JWT issuer: $jwtIssuer, audience: $jwtAudience"

    foreach ($name in $RequiredEnvVariables) { [Environment]::SetEnvironmentVariable($name, $dotEnv[$name], 'Process') }
    $env:JWT_AUDIENCE = $jwtAudience
    [Environment]::SetEnvironmentVariable('JWT_PUBLIC_KEY_FILE', $null, 'Process')

    $requestSeconds = ($Warmup / 2.0d + $Duration) * 1.1d
    $claimUsers = [Math]::Ceiling($Rate * $share.claim * $requestSeconds)
    $completedTeams = [int] [Math]::Ceiling($claimUsers / $TeamSize)
    $progressTeams = [int] [Math]::Ceiling($Rate * $share.progress * $requestSeconds / ($ProgressRequestsPerUser * $TeamSize))
    $teams = [Math]::Max($MinTeams, $completedTeams + [Math]::Max(1, $progressTeams))
    $seededUsers = $teams * $TeamSize
    $joinUsers = [int] [Math]::Max(100, [Math]::Ceiling($Rate * $share.join * $requestSeconds))
    $joinTokens = [int] ([Math]::Ceiling($joinUsers / [double] $JoinTokenBlock) * $JoinTokenBlock)
    if ($seededUsers + $joinTokens -gt 400000) {
        throw "This load needs $seededUsers seeded and $joinUsers join users; lower -Rate or -Duration (at most 400000 users)"
    }

    Write-Step "Run $runId"
    Write-Host ("    load: {0} req/s, mix {1} ({2}), cache {3}, {4} s warm-up + {5} s steady; app limited to {6} CPU / {7}, GC {8}" -f `
            $Rate, $Mix, (($activeEndpoints | ForEach-Object { '{0} {1}%' -f $_, $sharePercent[$_] }) -join ', '),
            $Cache, $Warmup, $Duration, $AppCpus, $AppMemory, $Gc)
    Write-Host ("    data: {0} teams of {1} ({2} completed), {3} seeded users, {4} join users needed" -f `
            $teams, $TeamSize, $completedTeams, $seededUsers, $joinUsers)

    New-Item -ItemType Directory -Force -Path $resultDir, $tokensDir, $secretsDir | Out-Null

    Write-Step 'JWT key pair and load-test tokens'
    $metaFile = Join-Path $tokensDir 'tokens-meta.json'
    $publicKeyFile = Join-Path $secretsDir 'jwt-public.pem'
    $reuse = $false
    if ((Test-Path $metaFile) -and (Test-Path $publicKeyFile)) {
        try {
            $meta = [IO.File]::ReadAllText($metaFile) | ConvertFrom-Json
            $fingerprint = (Get-FileHash -Algorithm SHA256 -Path $publicKeyFile).Hash
            $neededUntil = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + $Warmup + $Duration + 3600
            $reuse = ($meta.seededUsers -eq $seededUsers) -and ($meta.joinUsers -ge $joinUsers) -and
                ($meta.issuer -ceq $jwtIssuer) -and ($meta.audience -ceq $jwtAudience) -and
                ($meta.publicKeySha256.ToUpperInvariant() -ceq $fingerprint.ToUpperInvariant()) -and
                ([int64] $meta.expiresAtEpochSeconds -gt $neededUntil)
        } catch {
            $reuse = $false
        }
    }
    if ($reuse) {
        Write-Host '    reusing the tokens in perf/.tokens (same users, key pair, issuer and audience; still valid)'
    } else {
        Invoke-Compose -Arguments @('run', '--rm', '-T', 'token-mint', 'perf', "$seededUsers", "$joinTokens", '/tokens')
    }
    $meta = [IO.File]::ReadAllText($metaFile) | ConvertFrom-Json
    if ($meta.issuer -cne $jwtIssuer -or $meta.audience -cne $jwtAudience) {
        throw ("The tokens in perf/.tokens were signed for issuer '{0}' and audience '{1}', but .env says '{2}' and '{3}'" -f `
                $meta.issuer, $meta.audience, $jwtIssuer, $jwtAudience)
    }
    $opsToken = ([IO.File]::ReadAllText((Join-Path $tokensDir 'ops-token.txt'))).Trim()

    Write-Step 'Starting MySQL, Redis and the application with an empty database'
    $env:PERF_APP_CPUS = $AppCpus
    $env:PERF_APP_MEMORY = $AppMemory
    $env:PERF_CACHE_ENABLED = if ($Cache -ceq 'on') { 'true' } else { 'false' }
    $javaOptions = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError $GcLogOption"
    if ($GcJavaOptions[$Gc]) { $javaOptions += " $($GcJavaOptions[$Gc])" }
    if ($Jfr) { $javaOptions += " $JfrJavaOptions" }
    $env:PERF_JAVA_TOOL_OPTIONS = $javaOptions

    Invoke-Compose -Arguments @('down', '--volumes', '--remove-orphans')
    Invoke-Compose -Arguments @('up', '--detach', '--wait', '--wait-timeout', '300', 'mysql', 'redis')
    $samplers += Start-Job -ScriptBlock $ClockSampler -ArgumentList (Join-Path $resultDir 'clock.csv'), $RepoRoot, ($Warmup + $Duration + 1800)
    $up = @('up', '--detach', '--wait', '--wait-timeout', '300')
    if (-not $SkipBuild) { $up += '--build' }
    Invoke-Compose -Arguments ($up + @('app'))
    $appContainer = "$(Invoke-Compose -Capture -Arguments @('ps', '--all', '--quiet', 'app') | Select-Object -First 1)".Trim()
    $appStartedVmMs = $null
    $startedText = "$(Invoke-Docker -Capture -AllowFailure -Arguments @('inspect', '--format', '{{.State.StartedAt}}', $appContainer) | Select-Object -First 1)".Trim()
    if ($startedText -cmatch '^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(\.([0-9]+))?Z$') {
        $fraction = $(if ($Matches[3]) { ($Matches[3] + '000').Substring(0, 3) } else { '000' })
        $startedSeconds = [DateTimeOffset]::ParseExact($Matches[1], "yyyy-MM-dd'T'HH:mm:ss", $Invariant,
            [Globalization.DateTimeStyles]::AssumeUniversal).ToUnixTimeMilliseconds()
        $appStartedVmMs = $startedSeconds + [int] $fraction
    }

    Write-Step 'Seeding the load-test data'
    $seed = Invoke-Sql 'seed.sql' @("teams=$teams", "completed_teams=$completedTeams")
    $seed.GetEnumerator() | ForEach-Object { Write-Host ("    {0} = {1}" -f $_.Key, $_.Value) }
    if ([int] $seed['teams'] -ne $teams -or [int] $seed['completed_teams'] -ne $completedTeams -or
            [int] $seed['members'] -ne $seededUsers) {
        throw 'The seeded data does not match the requested sizes'
    }

    $tokenStatus = Get-HttpStatus "$ManagementUrl/actuator/metrics/process.cpu.usage" $opsToken
    if ($tokenStatus -eq 401) {
        throw ('The application rejected a load-test token (HTTP 401): its key pair, issuer or audience differs ' +
            'from the tokens in perf/.tokens. Delete perf/.tokens and run again.')
    }
    if ($tokenStatus -ne 200) {
        throw "Token check: GET $ManagementUrl/actuator/metrics returned HTTP $tokenStatus (0 = no response); see 'docker compose logs app'"
    }
    Write-Host '    the application accepts the load-test tokens'

    Write-Step 'Counters before the load'
    Invoke-Sql 'reset-statements.sql' | Out-Null
    $mysqlBefore = Invoke-Sql 'status.sql'
    $redisBefore = Get-RedisInfo
    $timersBefore = Get-AppTimers $opsToken
    $tomcatPool = Get-TomcatPoolName $opsToken
    if ($null -eq $tomcatPool) { Write-Host '    Tomcat thread pool of port 8080 not found in the metrics: no Tomcat gauges' }

    $gaugeMeters = @(
        'process.cpu.usage|process.cpu.usage|', 'jvm.memory.used|jvm.memory.used|area:heap',
        'jvm.threads.live|jvm.threads.live|',
        'hikaricp.connections.active|hikaricp.connections.active|',
        'hikaricp.connections.idle|hikaricp.connections.idle|',
        'hikaricp.connections.pending|hikaricp.connections.pending|',
        'hikaricp.connections.max|hikaricp.connections.max|'
    )
    if ($null -ne $tomcatPool) {
        foreach ($gauge in @('tomcat.threads.busy', 'tomcat.threads.current', 'tomcat.threads.config.max', 'tomcat.connections.current')) {
            $gaugeMeters += '{0}|{0}|name:{1}' -f $gauge, $tomcatPool
        }
    }
    $timerMeters = @($AppTimers | ForEach-Object { '{0}|{1}|{2}' -f $_.key, $_.name, $_.tag })
    $samplers += @(
        (Start-Job -ScriptBlock $DockerSampler -ArgumentList (Join-Path $resultDir 'docker-stats.csv'), "$Project-"),
        (Start-Job -ScriptBlock $AppSampler -ArgumentList (Join-Path $resultDir 'app-gauges.csv'), $opsToken, $ManagementUrl, $gaugeMeters),
        (Start-Job -ScriptBlock $AppSampler -ArgumentList (Join-Path $resultDir 'app-timers.csv'), $opsToken, $ManagementUrl, $timerMeters),
        (Start-Job -ScriptBlock $ThreadSampler -ArgumentList (Join-Path $resultDir 'thread-cpu.csv'), $RepoRoot),
        (Start-Job -ScriptBlock $MysqlSampler -ArgumentList (Join-Path $resultDir 'mysql-status.csv'), $RepoRoot),
        (Start-Job -ScriptBlock $RedisSampler -ArgumentList (Join-Path $resultDir 'redis-status.csv'), $RepoRoot)
    )

    $clockStart = Get-ClockSample
    $loadStart = Get-Date
    Write-Step ("k6: {0} s, expected to finish at about {1:HH:mm:ss}" -f ($Warmup + $Duration), $loadStart.AddSeconds($Warmup + $Duration + 15))
    $k6Args = @('run', '--rm', '-T', 'k6', 'run', '--quiet', "--log-output=file=/results/$runId/k6.log",
        '-e', "MIX=$Mix", '-e', "RATE=$Rate", '-e', "WARMUP=$Warmup", '-e', "DURATION=$Duration",
        '-e', "COMPLETED_TEAMS=$completedTeams", '-e', "CACHE=$Cache", '-e', "RUN_ID=$k6RunId",
        '-e', "RESULT_DIR=/results/$runId", '/scripts/load.js')
    Invoke-Compose -AllowFailure -Arguments $k6Args
    $k6ExitCode = $LASTEXITCODE
    $loadEnd = Get-Date
    $clockEnd = Get-ClockSample

    $samplers | Stop-Job
    $samplers | Remove-Job
    $samplers = @()

    Write-Step 'Counters after the load, invariant checks'
    $timersAfter = Get-AppTimers $opsToken
    $jvm = Get-JvmInfo $opsToken
    $redisAfter = Get-RedisInfo
    $mysqlAfter = Invoke-Sql 'status.sql'
    $topStatements = @(Invoke-Compose -Capture -Arguments @('run', '--rm', '-T', 'mysql-tools', 'top-statements.sql'))
    $verify = Invoke-Sql 'verify.sql' @("teams=$teams", "completed_teams=$completedTeams")

    if ($Jfr) {
        Write-Step 'Stopping the application so it writes the Java Flight Recording'
        Invoke-Compose -Arguments @('stop', '--timeout', "$JfrStopSeconds", 'app')
        $exitState = "$(Invoke-Docker -Capture -AllowFailure -Arguments @('inspect', '--format', 'exit code {{.State.ExitCode}}, OOM killed {{.State.OOMKilled}}', $appContainer) | Select-Object -First 1)"
        Write-Host "    application container: $exitState"
        $jfrTarget = Join-Path $resultDir 'app.jfr'
        Invoke-Docker -AllowFailure -Arguments @('cp', "$($appContainer):$JfrFile", $jfrTarget)
        $jfrBytes = 0
        if (Test-Path $jfrTarget) { $jfrBytes = (Get-Item $jfrTarget).Length }
        if ($jfrBytes -eq 0) {
            Write-Host '    the recording is empty: the JVM did not finish writing it (see app.log)' -ForegroundColor Yellow
        } else {
            Write-Host ("    recording: {0} MB" -f (Format-Number ($jfrBytes / 1MB) 1))
        }
    }
    $appLog = @(Invoke-Compose -Capture -AllowFailure -Arguments @('logs', '--no-color', 'app'))
    Write-TextFile (Join-Path $resultDir 'app.log') (($appLog -join "`n") + "`n")

    $dockerInfo = Invoke-Docker -Capture -AllowFailure -Arguments @('info', '--format', '{{.NCPU}}|{{.MemTotal}}|{{.ServerVersion}}|{{.OperatingSystem}}')
    $dockerParts = "$($dockerInfo | Select-Object -First 1)".Split('|')
    $gitCommit = ''
    $gitDirty = $null
    if (Get-Command git -ErrorAction SilentlyContinue) {
        $gitCommit = "$(& git rev-parse --short HEAD 2>$null)"
        $gitDirty = [bool] (& git status --porcelain --untracked-files=no 2>$null)
    }
    $cpuName = ''
    try { $cpuName = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name } catch { }
    $redisInfo = {
        param($Info)
        $kept = [ordered]@{}
        foreach ($key in ($Info.Keys | Sort-Object)) { if ($key -cmatch $RedisInfoPattern) { $kept[$key] = $Info[$key] } }
        return $kept
    }
    $raw = [ordered]@{
        format = 2
        runId = $runId
        settings = [ordered]@{ mix = $Mix; sharesPercent = $sharePercent; cache = $Cache; rate = $Rate; warmupSeconds = $Warmup
            durationSeconds = $Duration; minTeams = $MinTeams; appCpus = $AppCpus; appMemory = $AppMemory; jfr = [bool] $Jfr
            smoke = [bool] $Smoke; gc = $Gc; javaToolOptions = $javaOptions }
        data = [ordered]@{ teams = $teams; completedTeams = $completedTeams; seededUsers = $seededUsers
            joinUsersNeeded = $joinUsers; joinTokens = $meta.joinUsers }
        load = [ordered]@{ startUtc = $loadStart.ToUniversalTime().ToString('o'); endUtc = $loadEnd.ToUniversalTime().ToString('o')
            k6ExitCode = $k6ExitCode }
        clockSamples = [ordered]@{ start = $clockStart; end = $clockEnd }
        appStartedVmMs = $appStartedVmMs
        meters = [ordered]@{ gauges = $gaugeMeters; timers = $timerMeters }
        tomcatPool = $tomcatPool
        mysqlBefore = $mysqlBefore
        mysqlAfter = $mysqlAfter
        redisBefore = & $redisInfo $redisBefore
        redisAfter = & $redisInfo $redisAfter
        timersBefore = $timersBefore
        timersAfter = $timersAfter
        topStatements = $topStatements
        verify = $verify
        jvm = $jvm
        environment = [ordered]@{ dockerCpus = $dockerParts[0]; dockerMemoryBytes = $(if ($dockerParts.Count -gt 1) { $dockerParts[1] } else { '' })
            dockerVersion = $(if ($dockerParts.Count -gt 2) { $dockerParts[2] } else { '' })
            dockerOs = $(if ($dockerParts.Count -gt 3) { $dockerParts[3] } else { '' })
            hostCpu = $cpuName; hostOs = [Environment]::OSVersion.VersionString
            gitCommit = $gitCommit; gitDirty = $gitDirty }
        notes = @()
    }
    Write-TextFile (Join-Path $resultDir 'raw.json') ($raw | ConvertTo-Json -Depth 8)

    & (Join-Path $PSScriptRoot 'evaluate.ps1') -ResultDir $resultDir
    $exitCode = $LASTEXITCODE
} finally {
    foreach ($job in $samplers) {
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
    if (-not $KeepRunning -and (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Step 'Removing the stack (use -KeepRunning to keep it)'
        & docker compose -f compose.yaml -f perf/compose.perf.yaml down --volumes --remove-orphans | Out-Host
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    Pop-Location
}
exit $exitCode
