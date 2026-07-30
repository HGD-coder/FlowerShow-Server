[CmdletBinding(DefaultParameterSetName = "Ids")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Ids")]
    [string[]]$ContentId,

    [Parameter(Mandatory = $true, ParameterSetName = "All")]
    [switch]$All,

    [string]$MediaRoot = "D:\MediaCrawler\MediaCrawler\data\douyin",
    [string]$Bucket = "flower-show-media",
    [ValidateSet("nvenc", "x264")]
    [string]$Encoder = "nvenc",
    [int]$MaxVideos = 0,
    [switch]$Force,
    [switch]$SkipUpload,
    [switch]$SkipDatabaseRegistration
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$serverRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$ffmpeg = (Get-Command ffmpeg.exe -ErrorAction Stop).Source
$ffprobe = (Get-Command ffprobe.exe -ErrorAction Stop).Source

function Get-VideoInfo {
    param([string]$Path)

    $json = & $ffprobe -v error -select_streams v:0 `
        -show_entries stream=width,height,bit_rate `
        -show_entries format=duration,bit_rate `
        -of json $Path
    if ($LASTEXITCODE -ne 0) {
        throw "ffprobe failed for $Path"
    }
    $result = $json | ConvertFrom-Json
    $stream = $result.streams | Select-Object -First 1
    if (-not $stream) {
        throw "No video stream found in $Path"
    }
    return [pscustomobject]@{
        Width = [int]$stream.width
        Height = [int]$stream.height
        ShortEdge = [Math]::Min([int]$stream.width, [int]$stream.height)
        DurationSeconds = [double]$result.format.duration
        Bitrate = [long]$(if ($stream.bit_rate) { $stream.bit_rate } else { $result.format.bit_rate })
    }
}

function Test-HasAudio {
    param([string]$Path)

    $output = & $ffprobe -v error -select_streams a:0 -show_entries stream=index -of csv=p=0 $Path
    if ($LASTEXITCODE -ne 0) {
        throw "ffprobe audio check failed for $Path"
    }
    return -not [string]::IsNullOrWhiteSpace(($output -join ""))
}

function Get-TargetBitrateKbps {
    param([int]$Height)

    if ($Height -le 360) { return 500 }
    if ($Height -le 480) { return 850 }
    if ($Height -le 576) { return 1100 }
    return 1600
}

function Get-Renditions {
    param([string]$VideoDirectory)

    $items = New-Object System.Collections.Generic.List[object]
    foreach ($definition in @(
        @{ Name = "360p"; File = "video_360p.mp4" },
        @{ Name = "480p"; File = "video_480p.mp4" },
        @{ Name = "720p"; File = "video_720p.mp4" }
    )) {
        $path = Join-Path $VideoDirectory $definition.File
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $info = Get-VideoInfo $path
            $items.Add([pscustomobject]@{
                Name = $definition.Name
                Path = $path
                Info = $info
                BitrateKbps = Get-TargetBitrateKbps $info.ShortEdge
                HasAudio = Test-HasAudio $path
            })
        }
    }

    $sourcePath = Join-Path $VideoDirectory "video.mp4"
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Source video not found: $sourcePath"
    }
    $sourceInfo = Get-VideoInfo $sourcePath
    $highestShortEdge = ($items | ForEach-Object { $_.Info.ShortEdge } | Measure-Object -Maximum).Maximum
    if ($items.Count -eq 0 -or ($sourceInfo.ShortEdge -le 720 -and $sourceInfo.ShortEdge -gt $highestShortEdge)) {
        $items.Add([pscustomobject]@{
            Name = "$($sourceInfo.ShortEdge)p"
            Path = $sourcePath
            Info = $sourceInfo
            BitrateKbps = Get-TargetBitrateKbps $sourceInfo.ShortEdge
            HasAudio = Test-HasAudio $sourcePath
        })
    }

    return @($items |
        Sort-Object { $_.Info.ShortEdge } |
        Group-Object Name |
        ForEach-Object { $_.Group | Select-Object -First 1 })
}

function Reset-HlsDirectory {
    param(
        [string]$VideoDirectory,
        [string]$HlsDirectory
    )

    if (-not (Test-Path -LiteralPath $HlsDirectory)) {
        return
    }
    $videoRoot = [System.IO.Path]::GetFullPath($VideoDirectory).TrimEnd('\') + '\'
    $hlsRoot = [System.IO.Path]::GetFullPath($HlsDirectory)
    if (-not $hlsRoot.StartsWith($videoRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetFileName($hlsRoot) -ne "hls") {
        throw "Refusing to remove unexpected HLS path: $hlsRoot"
    }
    Remove-Item -LiteralPath $hlsRoot -Recurse -Force
}

function Convert-Rendition {
    param(
        [object]$Rendition,
        [string]$OutputDirectory
    )

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $playlistPath = Join-Path $OutputDirectory "index.m3u8"
    $segmentPath = Join-Path $OutputDirectory "segment_%05d.ts"
    $bitrate = [int]$Rendition.BitrateKbps
    $maxRate = [int][Math]::Ceiling($bitrate * 1.20)
    $bufferSize = $bitrate * 2

    $arguments = @(
        "-hide_banner", "-loglevel", "warning", "-y",
        "-i", $Rendition.Path,
        "-map", "0:v:0"
    )
    if ($Rendition.HasAudio) {
        $arguments += @("-map", "0:a:0?")
    }

    if ($Encoder -eq "nvenc") {
        $arguments += @(
            "-c:v", "h264_nvenc",
            "-preset", "p4",
            "-tune", "hq",
            "-rc", "vbr",
            "-cq", "26"
        )
    } else {
        $arguments += @(
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "23"
        )
    }

    $arguments += @(
        "-b:v", "${bitrate}k",
        "-maxrate", "${maxRate}k",
        "-bufsize", "${bufferSize}k",
        "-profile:v", "main",
        "-pix_fmt", "yuv420p",
        "-r", "30",
        "-g", "60",
        "-keyint_min", "60",
        "-force_key_frames", "expr:gte(t,n_forced*2)"
    )
    if ($Rendition.HasAudio) {
        $arguments += @(
            "-c:a", "aac",
            "-b:a", "96k",
            "-ac", "2",
            "-ar", "48000"
        )
    }
    $arguments += @(
        "-f", "hls",
        "-hls_time", "2",
        "-hls_list_size", "0",
        "-hls_playlist_type", "vod",
        "-hls_flags", "independent_segments+temp_file",
        "-hls_segment_filename", $segmentPath,
        $playlistPath
    )

    Write-Host "  Encoding $($Rendition.Name) at ${bitrate} kbps..."
    & $ffmpeg @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ffmpeg failed for $($Rendition.Path)"
    }
    if (-not (Test-Path -LiteralPath $playlistPath -PathType Leaf)) {
        throw "HLS playlist was not created: $playlistPath"
    }
}

function Write-MasterPlaylist {
    param(
        [object[]]$Renditions,
        [string]$HlsDirectory
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("#EXTM3U")
    $lines.Add("#EXT-X-VERSION:3")
    $lines.Add("#EXT-X-INDEPENDENT-SEGMENTS")
    foreach ($rendition in $Renditions) {
        $bandwidth = [int](($rendition.BitrateKbps + $(if ($rendition.HasAudio) { 96 } else { 0 })) * 1100)
        $codecs = if ($rendition.HasAudio) { 'avc1.4d401f,mp4a.40.2' } else { 'avc1.4d401f' }
        $lines.Add(
            "#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,AVERAGE-BANDWIDTH=$bandwidth," +
            "RESOLUTION=$($rendition.Info.Width)x$($rendition.Info.Height),FRAME-RATE=30.000,CODECS=`"$codecs`""
        )
        $lines.Add("$($rendition.Name)/index.m3u8")
    }
    $masterPath = Join-Path $HlsDirectory "master.m3u8"
    [System.IO.File]::WriteAllText($masterPath, ($lines -join "`n") + "`n", $utf8NoBom)
    return $masterPath
}

function Upload-Hls {
    param([string]$Id)

    $sourceVolume = "${MediaRoot}:/source:ro"
    $command = @"
mc alias set local http://minio:9000 flower_show_minio flower_show_minio_123 &&
mc mirror --overwrite --remove --retry --max-workers 4 --summary /source/videos/$Id/hls local/$Bucket/videos/$Id/hls
"@
    & docker compose --profile tools run --rm --volume $sourceVolume minio-client -c $command
    if ($LASTEXITCODE -ne 0) {
        throw "MinIO upload failed for $Id"
    }
}

function Register-Hls {
    param(
        [string]$Id,
        [string]$HlsDirectory,
        [object[]]$Renditions
    )

    $storageKey = "videos/$Id/hls/master.m3u8"
    $fallbackUrl = "http://localhost:8088/media/$storageKey"
    $totalBytes = (Get-ChildItem $HlsDirectory -File -Recurse | Measure-Object Length -Sum).Sum
    $durationMs = [long][Math]::Round(($Renditions | Select-Object -First 1).Info.DurationSeconds * 1000)
    $highest = $Renditions | Sort-Object { $_.Info.ShortEdge } -Descending | Select-Object -First 1
    $sql = @"
begin;
delete from media_assets
where content_id = '$Id' and kind = 'video' and delivery_type = 'hls';
insert into media_assets
    (content_id, kind, url, storage_key, mime_type, file_size, width, height,
     duration_ms, quality, sort_order, delivery_type, container_format, codec)
select
    '$Id', 'video', '$fallbackUrl', '$storageKey', 'application/vnd.apple.mpegurl',
    $totalBytes, $($highest.Info.Width), $($highest.Info.Height), $durationMs,
    'auto', 1000, 'hls', 'hls', 'h264,aac'
where exists (select 1 from content_items where id = '$Id');
commit;
"@
    & docker exec flower-show-postgres psql -v ON_ERROR_STOP=1 -U flower_show -d flower_show -c $sql
    if ($LASTEXITCODE -ne 0) {
        throw "Database registration failed for $Id"
    }
    $registered = & docker exec flower-show-postgres psql -U flower_show -d flower_show -Atc `
        "select count(*) from media_assets where content_id='$Id' and delivery_type='hls';"
    if (($registered | Select-Object -Last 1).Trim() -ne "1") {
        throw "Content $Id does not exist in PostgreSQL or the HLS row was not registered."
    }
}

if (-not (Test-Path -LiteralPath $MediaRoot -PathType Container)) {
    throw "Media root not found: $MediaRoot"
}

$videoRoot = Join-Path $MediaRoot "videos"
if ($All) {
    $ids = @(Get-ChildItem $videoRoot -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName "video.mp4") } |
        Select-Object -ExpandProperty Name |
        Sort-Object)
} else {
    $ids = @($ContentId |
        ForEach-Object { $_ -split ',' } |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ } |
        Select-Object -Unique)
}
if ($MaxVideos -gt 0) {
    $ids = @($ids | Select-Object -First $MaxVideos)
}
if ($ids.Count -eq 0) {
    throw "No videos selected."
}

foreach ($id in $ids) {
    if ($id -notmatch '^[A-Za-z0-9_-]{1,64}$') {
        throw "Invalid content id: $id"
    }
}

Push-Location $serverRoot
try {
    if (-not $SkipUpload) {
        & docker compose up -d minio
        if ($LASTEXITCODE -ne 0) { throw "Unable to start MinIO." }
        & docker compose run --rm minio-init
        if ($LASTEXITCODE -ne 0) { throw "Unable to initialize MinIO." }
    }
    if (-not $SkipDatabaseRegistration) {
        & docker compose up -d postgres
        if ($LASTEXITCODE -ne 0) { throw "Unable to start PostgreSQL." }
    }

    $completed = 0
    foreach ($id in $ids) {
        $videoDirectory = Join-Path $videoRoot $id
        if (-not (Test-Path -LiteralPath $videoDirectory -PathType Container)) {
            throw "Video directory not found: $videoDirectory"
        }
        $hlsDirectory = Join-Path $videoDirectory "hls"
        $masterPath = Join-Path $hlsDirectory "master.m3u8"
        $renditions = Get-Renditions $videoDirectory
        Write-Host "[$($completed + 1)/$($ids.Count)] $id ($($renditions.Name -join ', '))"

        if ($Force -or -not (Test-Path -LiteralPath $masterPath -PathType Leaf)) {
            Reset-HlsDirectory $videoDirectory $hlsDirectory
            New-Item -ItemType Directory -Path $hlsDirectory -Force | Out-Null
            foreach ($rendition in $renditions) {
                Convert-Rendition $rendition (Join-Path $hlsDirectory $rendition.Name)
            }
            $masterPath = Write-MasterPlaylist $renditions $hlsDirectory
        } else {
            Write-Host "  Existing HLS output retained. Use -Force to rebuild."
        }

        & $ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1 $masterPath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Generated HLS validation failed for $id"
        }
        if (-not $SkipUpload) {
            Write-Host "  Uploading HLS to MinIO..."
            Upload-Hls $id
        }
        if (-not $SkipDatabaseRegistration) {
            Write-Host "  Registering HLS in PostgreSQL..."
            Register-Hls $id $hlsDirectory $renditions
        }
        $completed += 1
    }

    Write-Host "HLS conversion complete: $completed video(s)."
} finally {
    Pop-Location
}
