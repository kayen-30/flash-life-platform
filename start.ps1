$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function New-LocalSecret {
    param(
        [int]$ByteCount = 32,
        [switch]$Base64
    )

    $bytes = New-Object byte[] $ByteCount
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }

    if ($Base64) {
        return [Convert]::ToBase64String($bytes)
    }
    return ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

function Ensure-LocalEnvValue {
    param(
        [string]$Name,
        [string]$Value
    )

    $lines = @(Get-Content -Path ".env")
    $pattern = "^\s*" + [regex]::Escape($Name) + "\s*=\s*(.*)$"
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match $pattern) {
            if (-not [string]::IsNullOrWhiteSpace($matches[1])) {
                return
            }
            $lines[$index] = "$Name=$Value"
            Set-Content -Path ".env" -Value $lines -Encoding utf8
            return
        }
    }
    Add-Content -Path ".env" -Value "$Name=$Value" -Encoding utf8
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker Desktop is not installed or docker is not available in PATH."
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is not running."
}

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
}

# 服务间凭证和 Nacos 服务端认证材料仅写入被忽略的本地文件，避免提交可用密钥。
Ensure-LocalEnvValue "HMDP_INTERNAL_TOKEN" (New-LocalSecret)
Ensure-LocalEnvValue "NACOS_AUTH_TOKEN" (New-LocalSecret -ByteCount 48 -Base64)
Ensure-LocalEnvValue "NACOS_AUTH_IDENTITY_KEY" (New-LocalSecret)
Ensure-LocalEnvValue "NACOS_AUTH_IDENTITY_VALUE" (New-LocalSecret)
# Nacos 官方镜像首次启动的本机账号可通过 .env 覆盖，生产环境应预先配置独立账号。
Ensure-LocalEnvValue "NACOS_USERNAME" "nacos"
Ensure-LocalEnvValue "NACOS_PASSWORD" "nacos"

docker compose up -d --build
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed to start the project."
}

docker compose ps
Write-Host ""
Write-Host "Flash Life is starting: http://localhost:8080"
