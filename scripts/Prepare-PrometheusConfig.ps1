
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot ".env"
$templatePath = Join-Path $projectRoot "monitoring/prometheus.yml"
$generatedConfigPath = Join-Path $projectRoot "monitoring/prometheus.generated.yml"
$secretDirectory = Join-Path $projectRoot ".secrets"
$passwordPath = Join-Path $secretDirectory "prometheus_password"

if (-not (Test-Path $envPath)) {
    throw "Arquivo .env nao encontrado."
}

if (-not (Test-Path $templatePath)) {
    throw "Arquivo monitoring/prometheus.yml nao encontrado."
}

$values = @{}

foreach ($line in Get-Content $envPath) {
    if ($line -match '^\s*#' -or $line -match '^\s*$') {
        continue
    }

    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        $key = $Matches[1]
        $value = $Matches[2].Trim()

        if ($value.Length -ge 2) {
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        $values[$key] = $value
    }
}

$username = $values["PROMETHEUS_USERNAME"]
if ([string]::IsNullOrWhiteSpace($username)) {
    $username = "prometheus"
}

$password = $values["PROMETHEUS_PASSWORD"]
if ([string]::IsNullOrEmpty($password)) {
    throw "PROMETHEUS_PASSWORD nao esta configurada no .env."
}

New-Item -ItemType Directory -Path $secretDirectory -Force |
    Out-Null

# Grava a senha sem quebra de linha e sem BOM UTF-8.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($passwordPath, $password, $utf8NoBom)

# Escapa o username como uma string YAML entre aspas.
$usernameYaml = ConvertTo-Json -InputObject $username -Compress
$config = Get-Content $templatePath -Raw
$config = $config.Replace('${PROMETHEUS_USERNAME}', $usernameYaml)

if ($config.Contains('${PROMETHEUS_USERNAME}') -or
    $config.Contains('${PROMETHEUS_PASSWORD}')) {
    throw "A configuracao ainda contem variaveis nao substituidas."
}

[System.IO.File]::WriteAllText(
    $generatedConfigPath,
    $config,
    $utf8NoBom
)

Write-Host "Configuracao local do Prometheus preparada."
Write-Host "A senha nao foi exibida."
