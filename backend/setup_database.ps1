$ErrorActionPreference = "Stop"

$backendDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$environmentFile = Join-Path $backendDirectory ".env"
if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Missing backend/.env. Copy .env.example and set the local MySQL values first."
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $environmentFile) {
    if ($line -match "^\s*([^#][^=]*)=(.*)$") {
        $settings[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$mysqlClient = Get-Command mysql -ErrorAction SilentlyContinue
if ($mysqlClient) {
    $mysqlExecutable = $mysqlClient.Source
} else {
    $mysqlExecutable = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
}
if (-not (Test-Path -LiteralPath $mysqlExecutable)) {
    throw "mysql.exe was not found. Install MySQL Server client tools or add mysql.exe to PATH."
}

$required = "MYSQL_HOST", "MYSQL_PORT", "MYSQL_USER", "MYSQL_PASSWORD"
foreach ($key in $required) {
    if (-not $settings.ContainsKey($key)) {
        throw "Missing $key in backend/.env"
    }
}

$env:MYSQL_PWD = $settings["MYSQL_PASSWORD"]
try {
    $mysqlArguments = @(
        "--host=$($settings['MYSQL_HOST'])",
        "--port=$($settings['MYSQL_PORT'])",
        "--user=$($settings['MYSQL_USER'])"
    )
    Get-Content -Raw -LiteralPath (Join-Path $backendDirectory "schema.sql") |
        & $mysqlExecutable @mysqlArguments
    if ($LASTEXITCODE -ne 0) {
        throw "schema.sql failed with exit code $LASTEXITCODE"
    }

    # Older dated files in migrations/ are historical one-time migrations whose
    # changes are already included in schema.sql. Only current idempotent upgrades
    # belong in this list.
    $currentMigrations = @(
        "001_add_experiment_control_to_sessions.sql"
    )
    foreach ($migrationName in $currentMigrations) {
        $migrationPath = Join-Path $backendDirectory "migrations\$migrationName"
        Write-Host "Applying $migrationName"
        Get-Content -Raw -LiteralPath $migrationPath | & $mysqlExecutable @mysqlArguments
        if ($LASTEXITCODE -ne 0) {
            throw "$migrationName failed with exit code $LASTEXITCODE"
        }
    }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

Write-Host "Database schema is ready."
