[CmdletBinding()]
param(
    [string]$GatewayBaseUrl = "",
    [string]$AppProjectPath = "D:\android-studio\flowershow",
    [switch]$SkipServerBuild,
    [switch]$SkipAppSync,
    [switch]$EnableSemanticSearch,
    [switch]$BuildApp,
    [switch]$InstallApp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$serverRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runtimeDir = Join-Path $serverRoot "target\runtime"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$embeddingEnabledValue = "$env:EMBEDDING_ENABLED".Trim().ToLowerInvariant()
$semanticSearchEnabled = $EnableSemanticSearch -or
    $embeddingEnabledValue -in @("1", "true", "yes", "on")
if ($semanticSearchEnabled) {
    $env:EMBEDDING_ENABLED = "true"
    if ([string]::IsNullOrWhiteSpace($env:OLLAMA_MODEL) -and
        -not [string]::IsNullOrWhiteSpace($env:EMBEDDING_MODEL)) {
        $env:OLLAMA_MODEL = $env:EMBEDDING_MODEL.Trim()
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $FilePath @Arguments 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }

    if ($exitCode -ne 0) {
        throw "$FilePath $($Arguments -join ' ') failed with exit code $exitCode.`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-DotEnvValue {
    param([string]$Name)

    $envPath = Join-Path $serverRoot ".env"
    if (-not (Test-Path $envPath)) {
        return ""
    }

    foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed.Split(@("="), 2, [System.StringSplitOptions]::None)
        if ($parts.Count -eq 2 -and $parts[0].Trim() -eq $Name) {
            return $parts[1].Trim().Trim('"').Trim("'")
        }
    }
    return ""
}

function Test-HttpEndpoint {
    param([string]$Uri)

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
    } catch {
        return $false
    }
}

function Wait-HttpEndpoint {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpEndpoint $Uri) {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Uri"
}

function Get-ServiceLogTail {
    param(
        [string]$ServiceName,
        [string]$StdoutPath,
        [string]$StderrPath,
        [int]$Tail = 80
    )

    $sections = @()
    foreach ($entry in @(
        @{ Name = "stdout"; Path = $StdoutPath },
        @{ Name = "stderr"; Path = $StderrPath }
    )) {
        $content = if (Test-Path $entry.Path) {
            (Get-Content $entry.Path -Tail $Tail -ErrorAction SilentlyContinue) -join [Environment]::NewLine
        } else {
            "No log file was created."
        }
        $sections += "$ServiceName $($entry.Name) ($($entry.Path)):`n$content"
    }
    return $sections -join [Environment]::NewLine
}

function Wait-ContainerReady {
    param(
        [string]$ContainerName,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $status = (Invoke-NativeCapture $docker @(
                "inspect", "--format",
                "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}",
                $ContainerName
            ) | Select-Object -Last 1).Trim()
            if ($status -eq "healthy" -or $status -eq "running") {
                return
            }
        } catch {
            # The container may not have been created yet.
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for container $ContainerName"
}

function Resolve-JavaHome {
    $candidates = @(
        $env:JAVA_HOME,
        "C:\Users\$env:USERNAME\.jdks\corretto-17.0.14"
    )
    $installedJdks = Get-ChildItem "C:\Users\$env:USERNAME\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "17" } |
        Select-Object -ExpandProperty FullName
    $candidates += $installedJdks

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and
            (Test-Path (Join-Path $candidate "bin\java.exe"))) {
            $resolved = (Resolve-Path $candidate).Path
            try {
                $version = Invoke-NativeCapture (Join-Path $resolved "bin\java.exe") @("-version")
                if (($version -join [Environment]::NewLine) -match 'version "17(?:[.\-+]|")') {
                    return $resolved
                }
            } catch {
                # Ignore unusable candidates and continue looking for JDK 17.
            }
        }
    }
    throw "JDK 17 was not found. Configure JAVA_HOME to a JDK 17 installation before running this script."
}

function Resolve-Maven {
    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $knownPath = "D:\IDEA\IntelliJ IDEA 2024.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd"
    if (Test-Path $knownPath) {
        return $knownPath
    }

    $discovered = Get-ChildItem "D:\IDEA" -Filter mvn.cmd -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($discovered) {
        return $discovered
    }
    throw "Maven was not found. Configure Maven in PATH before running this script."
}

function Set-AppGateway {
    param(
        [string]$ProjectPath,
        [string]$PublicGateway
    )

    $propertiesPath = Join-Path $ProjectPath "gradle.properties"
    if (-not (Test-Path $propertiesPath)) {
        throw "Android project gradle.properties was not found: $propertiesPath"
    }

    $content = [System.IO.File]::ReadAllText($propertiesPath)
    $property = "FLOWER_SHOW_PUBLIC_GATEWAY_BASE_URL=$PublicGateway"
    if ($content -match "(?m)^FLOWER_SHOW_PUBLIC_GATEWAY_BASE_URL=.*$") {
        $updated = [regex]::Replace(
            $content,
            "(?m)^FLOWER_SHOW_PUBLIC_GATEWAY_BASE_URL=.*$",
            $property
        )
    } else {
        $updated = $content.TrimEnd() + [Environment]::NewLine + $property + [Environment]::NewLine
    }

    if ($updated -ne $content) {
        [System.IO.File]::WriteAllText($propertiesPath, $updated, $utf8NoBom)
        Write-Host "Updated Android gateway: $PublicGateway"
    } else {
        Write-Host "Android gateway is already current: $PublicGateway"
    }
}

function Get-QuickTunnelUrl {
    param([bool]$WasRunning)

    $startedAt = (Invoke-NativeCapture $docker @(
        "inspect", "--format", "{{.State.StartedAt}}", "flower-show-cloudflared"
    ) | Select-Object -Last 1).Trim()
    $deadline = (Get-Date).AddSeconds(90)

    do {
        $arguments = @("logs")
        if (-not $WasRunning) {
            $arguments += @("--since", $startedAt)
        }
        $arguments += "flower-show-cloudflared"
        $logs = Invoke-NativeCapture $docker $arguments
        $matches = [regex]::Matches(
            ($logs -join [Environment]::NewLine),
            "https://[a-z0-9-]+\.trycloudflare\.com"
        )
        if ($matches.Count -gt 0) {
            $candidate = $matches[$matches.Count - 1].Value.TrimEnd('/')
            if (Test-HttpEndpoint "$candidate/health") {
                return $candidate
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "The Quick Tunnel did not publish a reachable URL within 90 seconds."
}

function Stop-ManagedBackend {
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $listener) {
        return
    }

    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($process.CommandLine -notmatch "flower-show-server|FlowerShowServerApplication") {
        throw "Port 8080 is occupied by an unrelated process: $($process.CommandLine)"
    }

    Write-Host "Stopping previous Flower Show backend process $($listener.OwningProcess)..."
    Stop-Process -Id $listener.OwningProcess -Force
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        $stillListening = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    } while ($stillListening -and (Get-Date) -lt $deadline)
    if ($stillListening) {
        throw "Port 8080 did not become available."
    }
}

Push-Location $serverRoot
try {
    $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
    if (-not $dockerCommand) {
        throw "Docker CLI was not found. Install or start Docker Desktop first."
    }
    $docker = $dockerCommand.Source

    try {
        Invoke-NativeCapture $docker @("info", "--format", "{{.ServerVersion}}") | Out-Null
    } catch {
        $dockerDesktop = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
        if (-not (Test-Path $dockerDesktop)) {
            throw "Docker Desktop is not running."
        }
        Write-Host "Starting Docker Desktop..."
        Start-Process -FilePath $dockerDesktop -WindowStyle Hidden | Out-Null
        $deadline = (Get-Date).AddMinutes(3)
        do {
            Start-Sleep -Seconds 3
            try {
                Invoke-NativeCapture $docker @("info", "--format", "{{.ServerVersion}}") | Out-Null
                $dockerReady = $true
            } catch {
                $dockerReady = $false
            }
        } while (-not $dockerReady -and (Get-Date) -lt $deadline)
        if (-not $dockerReady) {
            throw "Docker Desktop did not become ready within three minutes."
        }
    }

    Write-Host "Starting PostgreSQL, Kafka, MinIO, and Nginx..."
    Invoke-NativeCapture $docker @(
        "compose", "up", "-d", "kafka", "postgres", "minio", "minio-init", "nginx"
    ) | ForEach-Object { Write-Host $_ }
    Wait-ContainerReady "flower-show-postgres"
    Wait-ContainerReady "flower-show-kafka"
    Wait-ContainerReady "flower-show-minio"
    Wait-ContainerReady "flower-show-nginx"
    if ($semanticSearchEnabled) {
        Write-Host "Starting Ollama semantic-search service..."
        Invoke-NativeCapture $docker @(
            "compose", "--profile", "semantic", "up", "-d", "ollama"
        ) | ForEach-Object { Write-Host $_ }
        Wait-ContainerReady "flower-show-ollama"
        Write-Host "Ensuring the configured embedding model is available..."
        Invoke-NativeCapture $docker @(
            "compose", "--profile", "semantic", "run", "--rm", "--no-deps", "ollama-model"
        ) | ForEach-Object { Write-Host $_ }
    }
    Write-Host "Validating and reloading Nginx gateway configuration..."
    Invoke-NativeCapture $docker @(
        "exec", "flower-show-nginx", "nginx", "-t"
    ) | ForEach-Object { Write-Host $_ }
    Invoke-NativeCapture $docker @(
        "exec", "flower-show-nginx", "nginx", "-s", "reload"
    ) | ForEach-Object { Write-Host $_ }
    Wait-HttpEndpoint "http://localhost:8088/health" 60

    $configuredGateway = $GatewayBaseUrl.Trim()
    if ([string]::IsNullOrWhiteSpace($configuredGateway)) {
        $configuredGateway = $env:FLOWER_SHOW_PUBLIC_GATEWAY_BASE_URL
    }
    if ([string]::IsNullOrWhiteSpace($configuredGateway)) {
        $configuredGateway = Get-DotEnvValue "FLOWER_SHOW_PUBLIC_GATEWAY_BASE_URL"
    }

    $tunnelToken = $env:CLOUDFLARE_TUNNEL_TOKEN
    if ([string]::IsNullOrWhiteSpace($tunnelToken)) {
        $tunnelToken = Get-DotEnvValue "CLOUDFLARE_TUNNEL_TOKEN"
    }

    if (-not [string]::IsNullOrWhiteSpace($tunnelToken) -and
        [string]::IsNullOrWhiteSpace($configuredGateway)) {
        throw "A named tunnel token is configured, but FLOWER_SHOW_PUBLIC_GATEWAY_BASE_URL is missing."
    }

    if (-not [string]::IsNullOrWhiteSpace($configuredGateway)) {
        $publicGateway = $configuredGateway.TrimEnd('/')
        if (-not [string]::IsNullOrWhiteSpace($tunnelToken)) {
            Write-Host "Starting named Cloudflare Tunnel..."
            $env:CLOUDFLARE_TUNNEL_TOKEN = $tunnelToken
            Invoke-NativeCapture $docker @(
                "compose", "--profile", "tunnel", "stop", "cloudflared"
            ) | Out-Null
            Invoke-NativeCapture $docker @(
                "compose", "--profile", "named-tunnel", "up", "-d", "cloudflared-named"
            ) | ForEach-Object { Write-Host $_ }
            Wait-ContainerReady "flower-show-cloudflared-named"
        } else {
            Write-Host "Using configured public gateway managed outside this Compose stack."
        }
        Wait-HttpEndpoint "$publicGateway/health" 90
    } else {
        Write-Host "Starting a temporary Cloudflare Quick Tunnel..."
        $wasRunning = $false
        try {
            $wasRunning = ((Invoke-NativeCapture $docker @(
                "inspect", "--format", "{{.State.Running}}", "flower-show-cloudflared"
            ) | Select-Object -Last 1).Trim() -eq "true")
        } catch {
            $wasRunning = $false
        }
        Invoke-NativeCapture $docker @(
            "compose", "--profile", "named-tunnel", "stop", "cloudflared-named"
        ) | Out-Null
        Invoke-NativeCapture $docker @(
            "compose", "--profile", "tunnel", "up", "-d", "cloudflared"
        ) | ForEach-Object { Write-Host $_ }
        $publicGateway = Get-QuickTunnelUrl $wasRunning
    }

    Write-Host "Public gateway: $publicGateway"

    if (-not $SkipAppSync) {
        Set-AppGateway $AppProjectPath $publicGateway
    }

    $javaHome = Resolve-JavaHome
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"

    $gradle = Join-Path $AppProjectPath "gradlew.bat"
    if (-not (Test-Path $gradle)) {
        throw "Android Gradle wrapper was not found: $gradle"
    }

    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

    Stop-ManagedBackend
    if (-not $SkipServerBuild) {
        $maven = Resolve-Maven
        Write-Host "Building backend..."
        Invoke-NativeCapture $maven @("-q", "-DskipTests", "package") |
            ForEach-Object { Write-Host $_ }
    }

    $jar = Get-ChildItem (Join-Path $serverRoot "target") -Filter "flower-show-server-*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "Backend JAR was not found. Run without -SkipServerBuild once."
    }

    $env:DB_URL = "jdbc:postgresql://localhost:5432/flower_show"
    $env:DB_USERNAME = "flower_show"
    $env:DB_PASSWORD = "flower_show"
    $env:KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
    $env:KAFKA_ENABLED = "true"
    $env:LOCAL_EVENT_DISPATCHER_ENABLED = "false"
    $env:MEDIA_PUBLIC_BASE_URL = "$publicGateway/media"

    $stdoutLog = Join-Path $runtimeDir "backend-$timestamp.out.log"
    $stderrLog = Join-Path $runtimeDir "backend-$timestamp.err.log"
    Write-Host "Starting backend..."
    $backendStartArguments = @{
        FilePath = Join-Path $javaHome "bin\java.exe"
        ArgumentList = @("-jar", $jar.FullName)
        WorkingDirectory = $serverRoot
        RedirectStandardOutput = $stdoutLog
        RedirectStandardError = $stderrLog
        WindowStyle = "Hidden"
        PassThru = $true
    }
    try {
        $backendProcess = Start-Process @backendStartArguments
    } catch {
        $tail = Get-ServiceLogTail "Spring backend" $stdoutLog $stderrLog
        throw "Failed to start Spring backend: $($_.Exception.Message)`n$tail"
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $runtimeDir "backend.pid"),
        "$($backendProcess.Id)",
        $utf8NoBom
    )

    $backendHealthTimeoutSeconds = if ($semanticSearchEnabled) { 360 } else { 120 }
    try {
        Wait-HttpEndpoint "http://localhost:8080/api/v1/health" $backendHealthTimeoutSeconds
    } catch {
        $tail = Get-ServiceLogTail "Spring backend" $stdoutLog $stderrLog
        throw "$($_.Exception.Message)`n$tail"
    }
    try {
        Wait-HttpEndpoint "$publicGateway/api/v1/health" 60
    } catch {
        $backendTail = Get-ServiceLogTail "Spring backend" $stdoutLog $stderrLog
        throw "$($_.Exception.Message)`n$backendTail"
    }

    if ($BuildApp -or $InstallApp) {
        if ($SkipAppSync) {
            Write-Warning "The App build was requested with -SkipAppSync. Its compiled gateway may be stale."
        }
        $gradleTask = if ($InstallApp) { ":app:installDebug" } else { ":app:assembleDebug" }
        Write-Host "Running Android task $gradleTask..."
        Push-Location $AppProjectPath
        try {
            Invoke-NativeCapture $gradle @("--no-daemon", $gradleTask) |
                ForEach-Object { Write-Host $_ }
        } finally {
            Pop-Location
        }
    }

    Write-Host ""
    Write-Host "Flower Show is ready."
    Write-Host "Spring API:          http://localhost:8080/api/v1"
    Write-Host "Public gateway:       $publicGateway"
    Write-Host "Media base:           $publicGateway/media"
    Write-Host "Spring PID:           $($backendProcess.Id)"
    Write-Host "Spring logs:          $stdoutLog, $stderrLog"
} finally {
    Pop-Location
}
