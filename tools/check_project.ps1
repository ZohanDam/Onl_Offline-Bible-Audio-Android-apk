$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$gitRoot = git -C $root rev-parse --show-toplevel
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($gitRoot) -ne [IO.Path]::GetFullPath($root)) {
    throw 'This project must be its own Git checkout'
}
$tracked = @(git -C $root ls-files)
if ($LASTEXITCODE -ne 0) { throw 'Run from a Git checkout of this project' }
$forbidden = @($tracked | Where-Object {
    $_ -match '\.(apk|idsig|keystore|jks|p12|pfx|log|part)$' -or
    $_ -match '(^|/)(\.gradle[^/]*|\.kotlin|\.idea|build[^/]*|install|archive)/' -or
    $_ -eq 'local.properties'
})
if ($forbidden.Count) { throw "Generated/private files tracked in Git: $($forbidden -join ', ')" }
$guides = @($tracked | Where-Object { $_ -match '\.md$' })
if ($guides.Count -ne 1 -or $guides[0] -ne 'README.md') { throw 'Keep README.md as the single project guide' }
$extraGuides = @(git -C $root ls-files --others --exclude-standard -- '*.md')
if ($extraGuides.Count) { throw "Extra project guides found: $($extraGuides -join ', ')" }
$readme = Get-Content -LiteralPath (Join-Path $root 'README.md') -Raw
if (!$readme.Contains('https://github.com/ZohanDam/Onl_Offline-Bible-Audio-Android-apk/releases/latest') -or
    $readme -match 'blob/main/[^\s)]+\.apk|releases/download/v[0-9]') {
    throw 'README must use the permanent latest-release link, not a pinned/old APK'
}
foreach ($script in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count) { throw "PowerShell syntax errors in $($script.Name): $errors" }
}
foreach ($file in @('app/src/main/assets/bible.db', 'app/src/main/assets/VIE1925_SOURCE.txt',
    'app/src/main/assets/TEXT_VERSIONS_SOURCE.txt', 'app/src/main/assets/NIV_AUDIO_SOURCE.txt',
    'app/src/main/assets/NIVUK_AUDIO_SOURCE.txt')) {
    if (!(Test-Path -LiteralPath (Join-Path $root $file) -PathType Leaf)) { throw "Required app data missing: $file" }
}
Write-Output 'Project layout, guide, release links, script syntax, and required data: OK'
