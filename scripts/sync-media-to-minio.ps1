param(
    [string]$MediaRoot = "D:\MediaCrawler\MediaCrawler\data\douyin",
    [string]$Bucket = "flower-show-media"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $MediaRoot -PathType Container)) {
    throw "Media root not found: $MediaRoot"
}

docker compose up -d minio
docker compose run --rm minio-init

$sourceVolume = "${MediaRoot}:/source:ro"
$command = @"
mc alias set local http://minio:9000 flower_show_minio flower_show_minio_123 &&
mc mirror --overwrite --remove --retry --max-workers 1 --disable-multipart --summary /source/videos local/$Bucket/videos &&
mc mirror --overwrite --remove --retry --max-workers 1 --disable-multipart --summary /source/images local/$Bucket/images
"@

docker compose --profile tools run --rm --volume $sourceVolume minio-client -c $command
