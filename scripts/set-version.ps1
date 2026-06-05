param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [int]$VersionCode
)

$ErrorActionPreference = "Stop"

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must use MAJOR.MINOR.PATCH, for example 1.2.3."
}

if ($VersionCode -lt 1) {
    throw "VersionCode must be a positive integer."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$versionPath = Join-Path $repoRoot "VERSION"
$versionCodePath = Join-Path $repoRoot "VERSION_CODE"
$iosProjectPath = Join-Path $repoRoot "ios-app\ClipboardSyncIOS.xcodeproj\project.pbxproj"
$iosAppInfoPath = Join-Path $repoRoot "ios-app\ClipboardSync\Info.plist"
$iosShareInfoPath = Join-Path $repoRoot "ios-app\ClipboardSyncShareExtension\Info.plist"

Set-Content -LiteralPath $versionPath -Value $Version -NoNewline -Encoding utf8
Set-Content -LiteralPath $versionCodePath -Value $VersionCode -NoNewline -Encoding utf8

$project = Get-Content -LiteralPath $iosProjectPath -Raw
$project = $project -replace 'MARKETING_VERSION = [^;]+;', "MARKETING_VERSION = $Version;"
$project = $project -replace 'CURRENT_PROJECT_VERSION = [^;]+;', "CURRENT_PROJECT_VERSION = $VersionCode;"
Set-Content -LiteralPath $iosProjectPath -Value $project -NoNewline -Encoding utf8

foreach ($plistPath in @($iosAppInfoPath, $iosShareInfoPath)) {
    $plist = Get-Content -LiteralPath $plistPath -Raw
    $plist = $plist -replace '(<key>CFBundleShortVersionString</key>\s*<string>)[^<]+(</string>)', '${1}$(MARKETING_VERSION)${2}'
    $plist = $plist -replace '(<key>CFBundleVersion</key>\s*<string>)[^<]+(</string>)', '${1}$(CURRENT_PROJECT_VERSION)${2}'
    Set-Content -LiteralPath $plistPath -Value $plist -NoNewline -Encoding utf8
}

Write-Host "Set Clipboard Sync version to $Version ($VersionCode)."
