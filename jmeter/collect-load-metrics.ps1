param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [int]$Samples = 12,
    [int]$IntervalSeconds = 3
)

$ErrorActionPreference = 'Stop'

function Get-MetricValue {
    param(
        [string]$Metrics,
        [string]$Name
    )

    $values = @($Metrics -split "`n" |
        Where-Object { $_ -match ('^' + [regex]::Escape($Name) + '(\{| )') } |
        ForEach-Object { [double](($_ -split ' ')[-1]) })
    if ($values.Count -eq 0) {
        return $null
    }
    return ($values | Measure-Object -Sum).Sum
}

'timestamp,service,cpu_percent,memory,network,trade_cpu,trade_gc_pause_seconds,hikari_active,hikari_pending,gateway_cpu,gateway_gc_pause_seconds,netty_active,netty_pending,netty_total,gateway_request_count' |
    Set-Content -LiteralPath $OutputPath -Encoding utf8

for ($sample = 0; $sample -lt $Samples; $sample++) {
    $tradeMetrics = (Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:18083/actuator/prometheus').Content
    $gatewayMetrics = (Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:19010/actuator/prometheus').Content
    $timestamp = (Get-Date).ToString('o')
    $tradeCpu = Get-MetricValue $tradeMetrics 'process_cpu_usage'
    $tradeGc = Get-MetricValue $tradeMetrics 'jvm_gc_pause_seconds_sum'
    $hikariActive = Get-MetricValue $tradeMetrics 'hikaricp_connections_active'
    $hikariPending = Get-MetricValue $tradeMetrics 'hikaricp_connections_pending'
    $gatewayCpu = Get-MetricValue $gatewayMetrics 'process_cpu_usage'
    $gatewayGc = Get-MetricValue $gatewayMetrics 'jvm_gc_pause_seconds_sum'
    $nettyActive = Get-MetricValue $gatewayMetrics 'reactor_netty_connection_provider_active_connections'
    $nettyPending = Get-MetricValue $gatewayMetrics 'reactor_netty_connection_provider_pending_connections'
    $nettyTotal = Get-MetricValue $gatewayMetrics 'reactor_netty_connection_provider_total_connections'
    $gatewayRequests = Get-MetricValue $gatewayMetrics 'spring_cloud_gateway_requests_seconds_count'

    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}' `
        hm-dianping-trade-service-1 hm-dianping-gateway-1 hmdp-mysql |
        ForEach-Object {
            $parts = $_ -split '\|', 4
            "$timestamp,$($parts[0]),$($parts[1]),`"$($parts[2])`",`"$($parts[3])`",$tradeCpu,$tradeGc,$hikariActive,$hikariPending,$gatewayCpu,$gatewayGc,$nettyActive,$nettyPending,$nettyTotal,$gatewayRequests" |
                Add-Content -LiteralPath $OutputPath -Encoding utf8
        }

    if ($sample -lt ($Samples - 1)) {
        Start-Sleep -Seconds $IntervalSeconds
    }
}
