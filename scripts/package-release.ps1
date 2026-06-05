param(
    [string]$Version,
    [int]$VersionCode,
    [switch]$SkipAndroid,
    [switch]$SkipWindows
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

if ($Version) {
    if (-not $VersionCode) {
        throw "Pass -VersionCode when using -Version."
    }

    & (Join-Path $PSScriptRoot "set-version.ps1") -Version $Version -VersionCode $VersionCode
}

$Version = (Get-Content -LiteralPath (Join-Path $repoRoot "VERSION") -Raw).Trim()
$VersionCode = [int](Get-Content -LiteralPath (Join-Path $repoRoot "VERSION_CODE") -Raw).Trim()

$distRoot = Join-Path $repoRoot "dist"
$releaseDir = Join-Path $distRoot "release\v$Version"
$windowsStageDir = Join-Path $distRoot "windows\ClipboardSync-windows-x64"
$repoDotnet = Join-Path $repoRoot ".dotnet\dotnet.exe"
$dotnet = if (Test-Path -LiteralPath $repoDotnet) { $repoDotnet } else { "dotnet" }

if (Test-Path -LiteralPath $releaseDir) {
    Remove-Item -LiteralPath $releaseDir -Recurse -Force
}

New-Item -ItemType Directory -Path $releaseDir | Out-Null

if (-not $SkipAndroid) {
    Push-Location (Join-Path $repoRoot "android-app")
    try {
        & .\gradlew.bat assembleDebug
    }
    finally {
        Pop-Location
    }

    $apkSource = Join-Path $repoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
    $apkDest = Join-Path $releaseDir "ClipboardSync-$Version-android-debug.apk"
    Copy-Item -LiteralPath $apkSource -Destination $apkDest -Force
}

if (-not $SkipWindows) {
    if (Test-Path -LiteralPath $windowsStageDir) {
        Remove-Item -LiteralPath $windowsStageDir -Recurse -Force
    }

    & $dotnet publish (Join-Path $repoRoot "windows-app\src\ClipboardSync.App\ClipboardSync.App.csproj") `
        -c Release `
        -r win-x64 `
        --self-contained true `
        -o $windowsStageDir `
        /p:PublishSingleFile=true `
        /p:IncludeNativeLibrariesForSelfExtract=true `
        /p:DebugType=None `
        /p:DebugSymbols=false

    $zipDest = Join-Path $releaseDir "ClipboardSync-$Version-windows-x64.zip"
    if (Test-Path -LiteralPath $zipDest) {
        Remove-Item -LiteralPath $zipDest -Force
    }

    Compress-Archive -Path $windowsStageDir -DestinationPath $zipDest
}

$checksumPath = Join-Path $releaseDir "SHA256SUMS.txt"
Get-ChildItem -LiteralPath $releaseDir -File |
    Where-Object { $_.Name -ne "SHA256SUMS.txt" } |
    Sort-Object Name |
    ForEach-Object {
        $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName
        "$($hash.Hash.ToLowerInvariant())  $($_.Name)"
    } |
    Set-Content -LiteralPath $checksumPath -Encoding utf8

Write-Host "Release artifacts written to $releaseDir"
