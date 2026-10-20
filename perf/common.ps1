# Shared helpers for the load-test scripts.
$Project = 'team-chest'
$Invariant = [Globalization.CultureInfo]::InvariantCulture
$Utf8 = New-Object Text.UTF8Encoding $false
$NotMeasured = 'not measured'
$InvalidText = 'invalid'
$Endpoints = @('get', 'progress', 'join', 'claim')

$AppTimers = @(
    @{ key = 'http.get'; name = 'http.server.requests'; tag = 'uri:/get' },
    @{ key = 'http.progress'; name = 'http.server.requests'; tag = 'uri:/progress' },
    @{ key = 'http.join'; name = 'http.server.requests'; tag = 'uri:/join' },
    @{ key = 'http.claim'; name = 'http.server.requests'; tag = 'uri:/claim' },
    @{ key = 'db.pool.acquire'; name = 'hikaricp.connections.acquire'; tag = '' },
    @{ key = 'db.pool.usage'; name = 'hikaricp.connections.usage'; tag = '' },
    @{ key = 'jvm.gc.pause'; name = 'jvm.gc.pause'; tag = '' },
    @{ key = 'redis.command'; name = 'lettuce'; tag = '' }
)

function Write-Step([string] $Text) {
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Write-TextFile([string] $Path, [string] $Text) {
    [IO.File]::WriteAllText($Path, $Text, $Utf8)
}

function Format-Number($Value, [int] $Digits = 1) {
    if ($null -eq $Value) { return '-' }
    return ([double] $Value).ToString("F$Digits", $Invariant)
}

function Format-Measured($Value, [int] $Digits = 1) {
    if ($null -eq $Value) { return $NotMeasured }
    return Format-Number $Value $Digits
}

function Format-Percent($Ratio, [int] $Digits = 1) {
    if ($null -eq $Ratio) { return $NotMeasured }
    return (Format-Number (100.0 * $Ratio) $Digits) + ' %'
}

function ConvertTo-Double([string] $Text) {
    return [double]::Parse($Text, [Globalization.NumberStyles]::Float, $Invariant)
}

function Get-EpochMs {
    return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

function Format-EpochMs([double] $EpochMs) {
    return [DateTimeOffset]::FromUnixTimeMilliseconds([int64] $EpochMs).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", $Invariant)
}

function Get-JsonValue($Object, [string] $Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function ConvertTo-Map($Object) {
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary]) { return $Object }
    $map = [ordered]@{}
    foreach ($property in $Object.PSObject.Properties) { $map[$property.Name] = $property.Value }
    return $map
}

function Invoke-Docker {
    param([Parameter(Mandatory = $true)] [string[]] $Arguments, [switch] $Capture, [switch] $AllowFailure)
    if ($Capture) {
        $output = & docker @Arguments
    } else {
        & docker @Arguments | Out-Host
    }
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "docker $($Arguments -join ' ') failed with exit code $code"
    }
    if ($Capture -and $null -ne $output) { return $output }
}

function Invoke-Compose {
    param([Parameter(Mandatory = $true)] [string[]] $Arguments, [switch] $Capture, [switch] $AllowFailure)
    $all = @('compose', '-f', 'compose.yaml', '-f', 'perf/compose.perf.yaml') + $Arguments
    return Invoke-Docker -Arguments $all -Capture:$Capture -AllowFailure:$AllowFailure
}
