# Build whatsappfx.exe and whatsappcli.exe (jr launchers, one release, one build) and, only when asked, publish them as a GitHub release and write their update files.
# Run from this folder:
#
#     .\release.ps1 -Version 0.2.0-beta.1             build and checksum only; nothing leaves this machine
#     .\release.ps1 -Version 0.2.0-beta.1 -Publish    also publish the GitHub release and write the update files
#
# -Version X.Y.Z is a stable release; X.Y.Z-label (0.2.0-beta.1) is a pre-release on the beta channel, which the
# stable channel, followed by every installed X.Y.Z, never sees. While WhatsappFx is a preview, every version has a label.
#
# The build is made from a copy of HEAD (git archive) in a temporary folder, so it matches a public commit and never
# touches target\ here: a running app reads its jar from there. Nothing is installed anywhere (no -Djr.installDir).
# The connector (Go, connector\) is built in that copy first and goes INTO whatsappfx's jar (-DembedConnector): the exe
# fetches one jar and the app puts the connector in its data folder itself.
#
# The release is v<Version> in github.com/chatsgate/whatsappfx with: whatsappfx.exe, whatsappfx-<sha8>.jar,
# whatsappcli.exe, whatsappcli-<sha8>.jar, version.txt, SHA256SUMS. The update files whatsappfx.json and whatsappcli.json
# are written into update\ of THIS working copy only after the release exists and every asset url answers; the script
# never commits or pushes them: test the update path first, then commit and push (the exes read them from main).
# $env:GH names the GitHub CLI to use (default: gh).

param([Parameter(Mandatory)][string]$Version, [switch]$Publish)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$gh = if ($env:GH) { $env:GH } else { 'gh' }
$repo = 'chatsgate/whatsappfx'
$tag = "v$Version"
$pre = $Version -match '-'
$names = @('whatsappfx', 'whatsappcli')
$site = Join-Path $PSScriptRoot 'update'

# Only lines shaped like git's own output are trusted: a git wrapper may print a banner.
function Sha([string]$ref) { @(git rev-parse $ref) | Where-Object { $_ -match '^[0-9a-f]{40}$' } | Select-Object -Last 1 }

if ($Publish) {
    $dirty = @(git status --porcelain) | Where-Object { $_ -match '^[ MADRCU?!]{2} ' }
    if ($dirty) { throw "the working tree is not clean - commit first, so the release matches a public commit:`n$($dirty -join "`n")" }
    git fetch -q origin
    if ((Sha HEAD) -ne (Sha '@{u}')) { throw 'HEAD is not pushed - push first' }
}

$work = Join-Path ([IO.Path]::GetTempPath()) "whatsappfx-release-$Version-$PID"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null
$tar = Join-Path $work 'src.tar'
# A dry run may build uncommitted changes to tracked files (git stash create makes a commit object for them
# without touching the tree); a publish is refused above unless the tree is clean, so it builds HEAD exactly.
$ref = 'HEAD'
if (-not $Publish) {
    # git prints a line-ending warning on stderr, which Windows PowerShell 5.1 turns into an error under 'Stop'
    $ErrorActionPreference = 'Continue'
    $wip = Sha (@(git stash create) | Where-Object { $_ -match '^[0-9a-f]{40}$' } | Select-Object -Last 1)
    $ErrorActionPreference = 'Stop'
    if ($wip) { $ref = $wip; Write-Host 'dry run: building the uncommitted working tree' }
}
$ErrorActionPreference = 'Continue'
git archive --format=tar -o $tar $ref
$archiveExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($archiveExit -ne 0) { throw 'git archive failed' }
$src = Join-Path $work 'src'
New-Item -ItemType Directory -Path $src | Out-Null
tar -xf $tar -C $src
if ($LASTEXITCODE -ne 0) { throw 'tar failed' }

Write-Host "building $tag from $(Sha HEAD)"

# 0. The connector, from the source of this same commit. -trimpath keeps this machine's folders out of the binary.
Push-Location (Join-Path $src 'connector')
try {
    $env:CGO_ENABLED = '0'
    & go build -trimpath -o wa-connector.exe .
    if ($LASTEXITCODE -ne 0) { throw 'go build of the connector failed' }
} finally { Pop-Location }

# -Djrexe.version names the exe's own version (and the jars' Implementation-Version); -Djrexe.channel keeps a beta off
# the stable channel; -Djr.updateMergeFrom keeps the releases and channels already in the update file.
$mvnArgs = @('-B', '-q', '-DskipTests', '-Djrexe', '-DembedConnector', '-Djr.source=url', "-Djrexe.version=$Version")
if ($pre) { $mvnArgs += '-Djrexe.channel=beta' }
function Build([string[]]$more) {
    Push-Location $src
    try {
        # The network here drops connections mid-download often enough that one retry is not enough.
        for ($i = 1; $i -le 4; $i++) {
            & mvn @mvnArgs @more clean package
            if ($LASTEXITCODE -eq 0) { return }
            Write-Host "build attempt $i failed, retrying"
        }
        throw "mvn $more failed"
    } finally { Pop-Location }
}
$all = Join-Path $work 'release'
New-Item -ItemType Directory -Path $all | Out-Null
# One run per exe, because -Djr.updateMergeFrom is one file and each exe has its own update file.
foreach ($m in @(@('whatsappfx', 'ui'), @('whatsappcli', 'cli'))) {
    $u = Join-Path $site "$($m[0]).json"
    $merge = @(if (Test-Path $u) { "-Djr.updateMergeFrom=$((Resolve-Path $u).Path)" })
    Build ($merge + @('-pl', $m[1], '-am'))
    Copy-Item (Join-Path $src "$($m[1])\target\jr\release\*") $all -Force
}
$out = $all
# SHA256SUMS over everything gathered (each run wrote one for its own files only); the update files are not assets.
$updates = $names | ForEach-Object { "$_.update.json" }
$sums = Get-ChildItem $out -File | Where-Object { $_.Name -ne 'SHA256SUMS' -and $_.Name -notin $updates } | Sort-Object Name |
    ForEach-Object { "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)" }
[IO.File]::WriteAllText((Join-Path $out 'SHA256SUMS'), (($sums -join "`n") + "`n"))
foreach ($f in @('whatsappfx.exe', 'whatsappcli.exe', 'version.txt', 'SHA256SUMS') + $updates) {
    if (-not (Test-Path (Join-Path $out $f))) { throw "the release folder has no $f - see the jr:exe output above" }
}
foreach ($n in $names) {
    if (@(Get-ChildItem $out -Filter "$n-????????.jar").Count -ne 1) { throw "expected exactly one $n jar" }
}
# the app's jar must carry the connector, or the exe cannot start on a machine without the zip
$appJar = (Get-ChildItem $out -Filter 'whatsappfx-????????.jar').FullName
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($appJar)
try { if (-not ($zip.Entries | Where-Object FullName -eq 'connector/wa-connector.exe')) { throw 'whatsappfx jar does not carry the connector' } } finally { $zip.Dispose() }
Get-ChildItem $out | ForEach-Object { Write-Host ('  {0,-34} {1,7:N1} MB' -f $_.Name, ($_.Length / 1MB)) }
if (-not $Publish) { Write-Host "dry run - nothing published. Assets are in $out"; return }

$assets = (Get-ChildItem $out -File | Where-Object { $_.Name -notin $updates }).FullName
$base = "https://github.com/$repo/releases/download/$tag"
$notes = "WhatsappFx $Version, for Windows. Both programs are one file each; the first run of each fetches its jar from this release, checked against the sha256 it carries (and a Java 25 if there is none)." +
         "`n`nInstall, in PowerShell (curl.exe ships with Windows 10 and 11):" +
         "`n`n    mkdir `$env:LOCALAPPDATA\Programs\whatsappfx -Force; cd `$env:LOCALAPPDATA\Programs\whatsappfx" +
         "`n    curl.exe -fLO $base/whatsappfx.exe" +
         "`n    curl.exe -fLO $base/whatsappcli.exe" +
         "`n    .\whatsappfx.exe" +
         "`n`nwhatsappcli.exe is the command line and only works while whatsappfx is running: start whatsappfx first. Put the folder on your PATH to type both from anywhere. They are one build; update both: whatsappfx -Xjr:update, whatsappcli -Xjr:update."
if ($pre) { $notes += "`n`nThis is a pre-release: installed copies of other versions on the stable channel are not offered it. Mac: still the whatsappfx-mac.tar.gz of v0.1.0." }
$notesFile = Join-Path $work 'release-notes.md'
[IO.File]::WriteAllText($notesFile, $notes + "`n")
# No --latest=true for a stable release: GitHub makes the newest non-pre-release the latest by itself.
$latest = if ($pre) { @('--prerelease', '--latest=false') } else { @() }
# gh prints an ACCOUNT notice on stderr, which Windows PowerShell 5.1 turns into an error under 'Stop'
$ErrorActionPreference = 'Continue'
& $gh release create $tag $assets --repo $repo --target (Sha HEAD) --title "WhatsappFx $Version" --notes-file $notesFile @latest
$ghExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($ghExit -ne 0) { throw 'gh release create failed' }
Write-Host "published $tag"

# GET every asset url BEFORE the update file goes anywhere: GitHub has served an asset with HTTP 500 on its
# public url while the API had it (cfucli 0.5.1).
foreach ($a in $assets) {
    $n = Split-Path $a -Leaf
    $tmp = Join-Path $work "check-$n"
    & curl.exe -fsSL -o $tmp "$base/$n"
    if ($LASTEXITCODE -ne 0) { throw "GET $base/$n failed - do not publish the update files" }
    if ((Get-Item $tmp).Length -ne (Get-Item $a).Length) { throw "$n differs in size from what was uploaded" }
    if ((Get-FileHash $tmp -Algorithm SHA256).Hash -ne (Get-FileHash $a -Algorithm SHA256).Hash) { throw "$n differs in content from what was uploaded" }
    Remove-Item $tmp
}
Write-Host 'every asset url answers, with the bytes that were uploaded'

New-Item -ItemType Directory -Force -Path $site | Out-Null
foreach ($n in $names) { Copy-Item (Join-Path $out "$n.update.json") (Join-Path $site "$n.json") -Force }
Write-Host "update files written to $site - test the update path, then commit and push them (the exes read them from main) to offer $Version to -Xjr:update"
