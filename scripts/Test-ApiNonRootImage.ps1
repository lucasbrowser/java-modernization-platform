param(
    [string]$Image = "java-modernization-platform:t04"
)

$ErrorActionPreference = "Stop"

function Invoke-ImageCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Command,
        [string]$ExpectedOutput
    )

    $output = & docker run --rm --entrypoint /bin/sh $Image -c $Command
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Check '$Name' failed with exit code ${exitCode}: $($output -join ' ')"
    }
    if ($null -ne $ExpectedOutput -and (($output -join "`n").Trim() -ne $ExpectedOutput)) {
        throw "Check '$Name' returned '$($output -join ' ')'; expected '$ExpectedOutput'"
    }

    Write-Host "PASS: $Name"
    if ($output) {
        $output | ForEach-Object { Write-Host "  $_" }
    }
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = & docker @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Docker step '$Name' failed with exit code ${exitCode}: $($output -join ' ')"
    }
    return $output
}

docker build --tag $Image .
if ($LASTEXITCODE -ne 0) {
    throw "Docker image build failed with exit code $LASTEXITCODE"
}

$declaredUser = docker image inspect --format '{{.Config.User}}' $Image
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect the built image"
}
if ($declaredUser.Trim() -ne "10001:10001") {
    throw "Expected image user 10001:10001, found '$($declaredUser.Trim())'"
}
Write-Host "PASS: image declares UID/GID 10001:10001"

Invoke-ImageCheck -Name "effective UID" -Command 'id -u' -ExpectedOutput '10001'
Invoke-ImageCheck -Name "effective GID" -Command 'id -g' -ExpectedOutput '10001'
Invoke-ImageCheck -Name "JAR readable" -Command 'test -r /app/app.jar'
Invoke-ImageCheck -Name "application directory not writable" -Command 'test ! -w /app'
Invoke-ImageCheck -Name "root directory not writable" -Command 'test ! -w /'
Invoke-ImageCheck -Name "JRE directory not writable" -Command 'test ! -w /opt/java/openjdk'
Invoke-ImageCheck -Name "system certificates directory not writable" -Command 'test ! -w /etc/ssl/certs'
Invoke-ImageCheck -Name "temporary directory writable" -Command 'touch /tmp/t04-write-check'
Invoke-ImageCheck -Name "JVM truststore readable" -Command 'test -r "$JAVA_HOME/lib/security/cacerts"'
Invoke-ImageCheck -Name "Java runtime starts" -Command 'java -version'

$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$resourcePrefix = "t04-$suffix"
$network = "$resourcePrefix-net"
$postgres = "$resourcePrefix-postgres"
$api = "$resourcePrefix-api"
$migrationPassword = [Guid]::NewGuid().ToString("N")
$runtimePassword = [Guid]::NewGuid().ToString("N")
$bootstrapPassword = [Guid]::NewGuid().ToString("N")
$provisionerPassword = [Guid]::NewGuid().ToString("N")
$jwtSecret = [Guid]::NewGuid().ToString("N") + [Guid]::NewGuid().ToString("N")
$metricsPassword = [Guid]::NewGuid().ToString("N")
$composeEnvFile = Join-Path $env:TEMP "$resourcePrefix.env"

try {
    @(
        "DB_PROVISIONER_USERNAME=provisioner",
        "DB_PROVISIONER_PASSWORD=$provisionerPassword",
        "MIGRATION_DB_USERNAME=app_migrator",
        "MIGRATION_DB_PASSWORD=$migrationPassword",
        "RUNTIME_DB_USERNAME=app_runtime",
        "RUNTIME_DB_PASSWORD=$runtimePassword",
        "BOOTSTRAP_DB_PASSWORD=$bootstrapPassword",
        "JWT_SECRET=$jwtSecret",
        "PROMETHEUS_PASSWORD=$metricsPassword",
        "GRAFANA_ADMIN_PASSWORD=$([Guid]::NewGuid().ToString('N'))"
    ) | Set-Content -Path $composeEnvFile -Encoding ascii
    Invoke-Docker -Name "validate Docker Compose service configuration" -Arguments @(
        "compose", "--project-name", $resourcePrefix, "--env-file", $composeEnvFile,
        "--file", "docker-compose.yml", "config", "--quiet"
    ) | Out-Null
    Write-Host "PASS: Compose configuration validates with isolated credentials for postgres, migrate, API, and bootstrap"

    Invoke-Docker -Name "create isolated test network" -Arguments @("network", "create", $network) | Out-Null

    Invoke-Docker -Name "start disposable PostgreSQL" -Arguments @(
        "run", "--detach", "--network", $network, "--network-alias", "postgres", "--name", $postgres,
        "--env", "POSTGRES_DB=order_platform", "--env", "POSTGRES_USER=provisioner",
        "--env", "POSTGRES_PASSWORD=$provisionerPassword", "postgres:16-alpine"
    ) | Out-Null

    $postgresReady = $false
    $lastPostgresReadinessError = $null
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            $readinessResult = Invoke-Docker -Name "query disposable PostgreSQL readiness" -Arguments @(
                "exec", $postgres, "psql", "-qAt", "-U", "provisioner", "-d", "order_platform", "-c", "SELECT 1"
            )
            if (($readinessResult -join "").Trim() -eq "1") {
                $postgresReady = $true
                break
            }
            $lastPostgresReadinessError = "SELECT 1 did not return 1"
        }
        catch {
            $lastPostgresReadinessError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    if (-not $postgresReady) {
        throw "Disposable PostgreSQL did not become ready within 60 seconds. Last check: $lastPostgresReadinessError"
    }
    Write-Host "PASS: disposable PostgreSQL ready"

    Invoke-Docker -Name "copy role provisioning SQL" -Arguments @(
        "cp", "scripts/provision-postgres-roles.sql", "${postgres}:/tmp/provision-postgres-roles.sql"
    ) | Out-Null
    Invoke-Docker -Name "provision isolated database roles" -Arguments @(
        "exec", "--env", "MIGRATION_DB_PASSWORD=$migrationPassword",
        "--env", "RUNTIME_DB_PASSWORD=$runtimePassword", "--env", "BOOTSTRAP_DB_PASSWORD=$bootstrapPassword",
        $postgres, "psql", "-v", "ON_ERROR_STOP=1", "-U", "provisioner", "-d", "order_platform",
        "-f", "/tmp/provision-postgres-roles.sql"
    ) | Out-Null
    Write-Host "PASS: disposable PostgreSQL roles provisioned"

    Invoke-Docker -Name "run isolated Flyway migration job" -Arguments @(
        "run", "--rm", "--network", $network, "--name", "$resourcePrefix-migrate",
        "--env", "MIGRATION_DB_URL=jdbc:postgresql://postgres:5432/order_platform",
        "--env", "MIGRATION_DB_USERNAME=app_migrator", "--env", "MIGRATION_DB_PASSWORD=$migrationPassword",
        $Image, "--migrate-only"
    ) | Out-Null
    Write-Host "PASS: Flyway migration job completed with app_migrator"

    Invoke-Docker -Name "start API with runtime credentials only" -Arguments @(
        "run", "--detach", "--network", $network, "--name", $api,
        "--publish", "127.0.0.1::8080", "--publish", "127.0.0.1::8081",
        "--env", "DB_URL=jdbc:postgresql://postgres:5432/order_platform",
        "--env", "DB_USERNAME=app_runtime", "--env", "DB_PASSWORD=$runtimePassword",
        "--env", "JWT_SECRET=$jwtSecret", "--env", "PROMETHEUS_USERNAME=prometheus",
        "--env", "PROMETHEUS_PASSWORD=$metricsPassword", "--env", "MANAGEMENT_SERVER_PORT=8081", $Image
    ) | Out-Null

    $apiEnvironment = Invoke-Docker -Name "inspect API credential names" -Arguments @(
        "inspect", "--format", "{{range .Config.Env}}{{println .}}{{end}}", $api
    )
    if (($apiEnvironment -join "`n") -match "(?m)^(MIGRATION_DB_|BOOTSTRAP_DB_)") {
        throw "API container received a migration or bootstrap credential variable"
    }
    Write-Host "PASS: API container has no migration/bootstrap credential variables"

    $processStatus = Invoke-Docker -Name "inspect Java process identity" -Arguments @("exec", $api, "cat", "/proc/1/status")
    if (($processStatus -join "`n") -notmatch "(?m)^Name:\s+java\s*$") {
        throw "PID 1 in API container is not the Java process"
    }
    if (($processStatus -join "`n") -notmatch "(?m)^Uid:\s+10001\s+10001\s+10001\s+10001\s*$") {
        throw "Java process effective UID is not 10001"
    }
    if (($processStatus -join "`n") -notmatch "(?m)^Gid:\s+10001\s+10001\s+10001\s+10001\s*$") {
        throw "Java process effective GID is not 10001"
    }
    Write-Host "PASS: Java PID 1 effective UID/GID are 10001:10001"

    $publishedPort = Invoke-Docker -Name "read API health port" -Arguments @("port", $api, "8081/tcp")
    $portMatch = [regex]::Match(($publishedPort -join "`n"), "127\.0\.0\.1:(\d+)")
    if (-not $portMatch.Success) {
        throw "Could not determine the published API management port: $($publishedPort -join ' ')"
    }
    $healthUri = "http://127.0.0.1:$($portMatch.Groups[1].Value)/actuator/health"
    $healthy = $false
    $lastHealthError = $null
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri $healthUri -TimeoutSec 3
            if ($health.status -eq "UP") {
                $healthy = $true
                break
            }
            $lastHealthError = "Health status was '$($health.status)'"
        }
        catch {
            $lastHealthError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    if (-not $healthy) {
        throw "API health endpoint did not become UP within 120 seconds: $lastHealthError"
    }
    Write-Host "PASS: API health endpoint reports UP; datasource health confirms PostgreSQL connectivity"

    $apiPort = Invoke-Docker -Name "read API HTTP port" -Arguments @("port", $api, "8080/tcp")
    $apiPortMatch = [regex]::Match(($apiPort -join "`n"), "127\.0\.0\.1:(\d+)")
    if (-not $apiPortMatch.Success) {
        throw "Could not determine the published API HTTP port: $($apiPort -join ' ')"
    }
    $runtimeEmail = "t04-$suffix@example.test"
    $registration = @{
        name = "T04 Runtime Check"
        email = $runtimeEmail
        password = [Guid]::NewGuid().ToString("N")
    } | ConvertTo-Json -Compress
    $registrationResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$($apiPortMatch.Groups[1].Value)/api/auth/register" `
        -Method Post -ContentType "application/json" -Body $registration -TimeoutSec 20 -UseBasicParsing
    if ($registrationResponse.StatusCode -ne 201) {
        throw "Runtime registration returned HTTP $($registrationResponse.StatusCode); expected 201"
    }
    Write-Host "PASS: API runtime user registration returned HTTP 201"

    $persistedRole = Invoke-Docker -Name "verify runtime user persisted in PostgreSQL" -Arguments @(
        "exec", $postgres, "psql", "-qAt", "-U", "provisioner", "-d", "order_platform", "-c",
        "SELECT role FROM users WHERE email = '$runtimeEmail'"
    )
    if (($persistedRole -join "").Trim() -ne "USER") {
        throw "Runtime registration did not persist a USER row in PostgreSQL"
    }
    Write-Host "PASS: PostgreSQL contains the registered USER row"
}
catch {
    $apiContainers = & docker ps --all --filter "name=^/$api$" --format "{{.Names}}" 2>&1
    if ($LASTEXITCODE -eq 0 -and $apiContainers -contains $api) {
        Write-Host "API container logs (last 100 lines):"
        docker logs --tail 100 $api
    }
    throw
}
finally {
    $apiContainers = & docker ps --all --filter "name=^/$api$" --format "{{.Names}}" 2>&1
    if ($LASTEXITCODE -eq 0 -and $apiContainers -contains $api) {
        docker rm --force $api | Out-Null
    }
    $postgresContainers = & docker ps --all --filter "name=^/$postgres$" --format "{{.Names}}" 2>&1
    if ($LASTEXITCODE -eq 0 -and $postgresContainers -contains $postgres) {
        docker rm --force --volumes $postgres | Out-Null
    }
    $networks = & docker network ls --filter "name=^$network$" --format "{{.Name}}" 2>&1
    if ($LASTEXITCODE -eq 0 -and $networks -contains $network) {
        docker network rm $network | Out-Null
    }
    if (Test-Path -LiteralPath $composeEnvFile) {
        Remove-Item -LiteralPath $composeEnvFile -Force
    }
}
