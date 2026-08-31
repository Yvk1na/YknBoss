[CmdletBinding()]
param(
    [switch]$BuildOnly,
    [string]$ServerRoot = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Get-DefaultServerRoot {
    $serverFolder = ([char]0x670D).ToString() + [char]0x52A1 + [char]0x7AEF
    return "F:\mc$serverFolder\Paper-1.21.10-Inf-Skyblock"
}

function Get-FullPath([string]$Path) {
    return [IO.Path]::GetFullPath($Path)
}

function Assert-InDirectory([string]$Path, [string]$Directory) {
    $fullPath = (Get-FullPath $Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $fullDirectory = (Get-FullPath $Directory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $prefix = $fullDirectory + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the deployment directory: $fullPath"
    }
}

function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace("-", "")
    }
    finally {
        $algorithm.Dispose()
        $stream.Dispose()
    }
}

function Assert-FileUnlocked([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    $stream = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
    }
    catch {
        throw "A server file is still in use. Stop every server process before deploying: $Path"
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(500, $false)) { return $false }
        $client.EndConnect($pending)
        return $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Get-ZipEntryNames([string]$Path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    }
    finally {
        $archive.Dispose()
    }
}

function Read-ZipEntryText([string]$Path, [string]$EntryName) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry($EntryName)
        if ($null -eq $entry) { throw "Missing archive entry '$EntryName' in $Path" }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$ProjectRoot = Get-FullPath (Join-Path $PSScriptRoot "..")
$JdkHome = "C:\java\25"
$JavaExe = Join-Path $JdkHome "bin\java.exe"
$JarExe = Join-Path $JdkHome "bin\jar.exe"
$GradleWrapper = Join-Path $ProjectRoot "gradlew.bat"
$GradleHome = Join-Path $ProjectRoot ".gradle-user-home"
$BetterModelLibrary = Join-Path $ProjectRoot "libs\bettermodel-3.4.1-paper.jar"
$MultiverseLibrary = Join-Path $ProjectRoot "libs\Multiverse-Core-5.7.3.jar"

Write-Step "Checking the fixed build environment"
foreach ($requiredFile in @($JavaExe, $JarExe, $GradleWrapper, $BetterModelLibrary, $MultiverseLibrary)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}

$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome "bin") + ";" + $env:PATH
$env:GRADLE_USER_HOME = $GradleHome
[IO.Directory]::CreateDirectory($GradleHome) | Out-Null

& $JavaExe -version
if ($LASTEXITCODE -ne 0) { throw "JDK 25 failed to start." }

Write-Step "Building, obfuscating, and packaging plugin resources"
Push-Location $ProjectRoot
try {
    & $GradleWrapper "--no-daemon" "clean" "obfuscate" "betterModelPack" "craftEnginePack" "itemsAdderPack"
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
}
finally {
    Pop-Location
}

Write-Step "Validating release artifacts"
$libraryDirectory = Join-Path $ProjectRoot "build\libs"
$distributionDirectory = Join-Path $ProjectRoot "build\distributions"
$obfuscatedJars = @(Get-ChildItem -LiteralPath $libraryDirectory -Filter "YknBoss-*-obfuscated.jar" -File)
if ($obfuscatedJars.Count -ne 1) {
    throw "Expected exactly one obfuscated JAR, found $($obfuscatedJars.Count)."
}

$PluginJar = $obfuscatedJars[0]
if ($PluginJar.Name -notmatch '^YknBoss-(?<version>.+)-obfuscated\.jar$') {
    throw "Cannot determine the plugin version from $($PluginJar.Name)."
}
$Version = $Matches.version
$BetterModelPack = Join-Path $distributionDirectory "YknBoss-BetterModel-Pack-$Version.zip"
$CraftEnginePack = Join-Path $distributionDirectory "YknBoss-CraftEngine-Pack-$Version.zip"
$ItemsAdderPack = Join-Path $distributionDirectory "YknBoss-ItemsAdder-Pack-$Version.zip"

if ($PluginJar.Length -lt 100KB) { throw "The obfuscated JAR is unexpectedly small." }
if (-not (Test-Path -LiteralPath $BetterModelPack -PathType Leaf)) {
    throw "BetterModel pack is missing: $BetterModelPack"
}
if ((Get-Item -LiteralPath $BetterModelPack).Length -lt 100KB) {
    throw "The BetterModel pack is unexpectedly small."
}
foreach ($resourcePack in @($CraftEnginePack, $ItemsAdderPack)) {
    if (-not (Test-Path -LiteralPath $resourcePack -PathType Leaf)) {
        throw "Custom-item resource pack is missing: $resourcePack"
    }
    if ((Get-Item -LiteralPath $resourcePack).Length -lt 1KB) {
        throw "Custom-item resource pack is unexpectedly small: $resourcePack"
    }
}

$jarEntries = @(& $JarExe "tf" $PluginJar.FullName)
if ($LASTEXITCODE -ne 0 -or $jarEntries -notcontains "plugin.yml" -or
        $jarEntries -notcontains "cn/yknboss/YknBossPlugin.class") {
    throw "The obfuscated JAR is unreadable or is missing plugin.yml/the plugin main class."
}

$pluginYaml = Read-ZipEntryText $PluginJar.FullName "plugin.yml"
$versionLine = @($pluginYaml -split "`r?`n" | Where-Object { $_ -match '^version\s*:' } | Select-Object -First 1)
if ($versionLine.Count -ne 1) { throw "plugin.yml does not contain a version." }
$declaredVersion = (($versionLine[0] -split ':', 2)[1]).Trim().Trim([char[]]@([char]39, [char]34))
if ($declaredVersion -ne $Version) {
    throw "Artifact version mismatch: file=$Version, plugin.yml=$declaredVersion"
}

$packEntries = Get-ZipEntryNames $BetterModelPack
foreach ($requiredEntry in @(
    "models/endsun_anuhe.bbmodel",
    "models/skeleton_dragon.bbmodel",
    "models/end_witch.bbmodel",
    "licenses/flying_magic_book_LICENSE.txt"
)) {
    if ($packEntries -notcontains $requiredEntry) {
        throw "BetterModel pack is missing $requiredEntry."
    }
}

$craftEngineEntries = Get-ZipEntryNames $CraftEnginePack
foreach ($requiredEntry in @(
    "yknboss/configuration/items.yml",
    "yknboss/resourcepack/assets/yknboss/items/infernal_grimoire.json",
    "yknboss/resourcepack/assets/yknboss/models/item/infernal_grimoire.json",
    "yknboss/resourcepack/assets/yknboss/textures/item/infernal_grimoire.png",
    "yknboss/resourcepack/assets/yknboss/items/ancient_codex.json",
    "yknboss/resourcepack/assets/yknboss/models/item/ancient_codex.json",
    "yknboss/resourcepack/assets/yknboss/textures/item/ancient_codex_page_left.png",
    "yknboss/resourcepack/assets/yknboss/textures/item/ancient_codex_page_right.png",
    "yknboss/licenses/flying_magic_book_LICENSE.txt"
)) {
    if ($craftEngineEntries -notcontains $requiredEntry) {
        throw "CraftEngine pack is missing $requiredEntry."
    }
}

$itemsAdderEntries = Get-ZipEntryNames $ItemsAdderPack
foreach ($requiredEntry in @(
    "contents/yknboss/configs/items.yml",
    "contents/yknboss/items/infernal_grimoire.json",
    "contents/yknboss/models/item/infernal_grimoire.json",
    "contents/yknboss/textures/item/infernal_grimoire.png",
    "contents/yknboss/items/ancient_codex.json",
    "contents/yknboss/models/item/ancient_codex.json",
    "contents/yknboss/textures/item/ancient_codex_page_left.png",
    "contents/yknboss/textures/item/ancient_codex_page_right.png",
    "contents/yknboss/licenses/flying_magic_book_LICENSE.txt"
)) {
    if ($itemsAdderEntries -notcontains $requiredEntry) {
        throw "ItemsAdder pack is missing $requiredEntry."
    }
}

$EndsunModel = Join-Path $ProjectRoot "packs\bettermodel\models\endsun_anuhe.bbmodel"
$SkeletonDragonModel = Join-Path $ProjectRoot "packs\bettermodel\models\skeleton_dragon.bbmodel"
$EndWitchModel = Join-Path $ProjectRoot "modle\end_witch\bettermodel\end_witch.bbmodel"
$model = Get-Content -LiteralPath $EndsunModel -Raw -Encoding UTF8 | ConvertFrom-Json
if (@($model.outliner).Count -ne 1) { throw "Endsun model must have exactly one root outliner." }
if ($model.outliner[0].uuid -ne "7c58f8ac-2c0d-4abe-b64d-1bb08e0fe3aa") {
    throw "Endsun model does not contain the fixed baked root."
}
$markerProperty = $model.unhandled_root_fields.PSObject.Properties["yknboss_baked_scale"]
if ($null -eq $markerProperty -or [Math]::Abs(([double]$markerProperty.Value) - 1.5) -gt 0.00001) {
    throw "Endsun model does not contain the expected 1.5 baked-scale marker."
}
$witchModel = Get-Content -LiteralPath $EndWitchModel -Raw -Encoding UTF8 | ConvertFrom-Json
$witchAnimationNames = @($witchModel.animations | ForEach-Object { $_.name })
foreach ($requiredAnimation in @("animation.idle", "animation.walk", "animation.cast",
        "animation.float_cast", "animation.blink_slash", "animation.phase_transition",
        "animation.silenced", "animation.world_end")) {
    if ($witchAnimationNames -notcontains $requiredAnimation) {
        throw "End Witch model is missing animation $requiredAnimation."
    }
}
$modelSources = @($EndsunModel, $SkeletonDragonModel, $EndWitchModel)

$PluginHash = Get-Sha256 $PluginJar.FullName
$PackHash = Get-Sha256 $BetterModelPack
$CraftEnginePackHash = Get-Sha256 $CraftEnginePack
$ItemsAdderPackHash = Get-Sha256 $ItemsAdderPack
Write-Host "Plugin: $($PluginJar.FullName)" -ForegroundColor Green
Write-Host "Plugin SHA256: $PluginHash"
Write-Host "BetterModel pack: $BetterModelPack" -ForegroundColor Green
Write-Host "Pack SHA256: $PackHash"
Write-Host "CraftEngine pack: $CraftEnginePack" -ForegroundColor Green
Write-Host "CraftEngine pack SHA256: $CraftEnginePackHash"
Write-Host "ItemsAdder pack: $ItemsAdderPack" -ForegroundColor Green
Write-Host "ItemsAdder pack SHA256: $ItemsAdderPackHash"

if ($BuildOnly) {
    Write-Host "`nBuild-only validation completed. No server files were changed." -ForegroundColor Green
    return
}

if ([string]::IsNullOrWhiteSpace($ServerRoot)) { $ServerRoot = Get-DefaultServerRoot }
$ServerRoot = Get-FullPath $ServerRoot
$PluginsDirectory = Join-Path $ServerRoot "plugins"
$ModelsDirectory = Join-Path $PluginsDirectory "BetterModel\models"

Write-Step "Checking the deployment target"
if (-not (Test-Path -LiteralPath $ServerRoot -PathType Container)) {
    throw "Server directory does not exist: $ServerRoot"
}
if (-not (Test-Path -LiteralPath $PluginsDirectory -PathType Container)) {
    throw "Server plugins directory does not exist: $PluginsDirectory"
}

$serverLeaf = [IO.Path]::GetFileName($ServerRoot.TrimEnd([IO.Path]::DirectorySeparatorChar))
try {
    $matchingProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop |
        Where-Object {
            $null -ne $_.CommandLine -and
            ($_.CommandLine.IndexOf($ServerRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
             $_.CommandLine.IndexOf($serverLeaf, [StringComparison]::OrdinalIgnoreCase) -ge 0)
        })
    if ($matchingProcesses.Count -gt 0) {
        $processIds = ($matchingProcesses | ForEach-Object { $_.ProcessId }) -join ", "
        throw "The target server appears to be running (PID: $processIds). Stop it before deploying."
    }
}
catch {
    if ($_.Exception.Message -like "The target server appears*") { throw }
    Write-Warning "Could not inspect Java process command lines; file-lock and port checks will still run."
}

$serverPort = 25565
$serverProperties = Join-Path $ServerRoot "server.properties"
if (Test-Path -LiteralPath $serverProperties -PathType Leaf) {
    $portLine = Get-Content -LiteralPath $serverProperties | Where-Object { $_ -match '^server-port=(\d+)$' } | Select-Object -First 1
    if ($null -ne $portLine -and $portLine -match '^server-port=(\d+)$') {
        $serverPort = [int]$Matches[1]
    }
}
if (Test-TcpPort "127.0.0.1" $serverPort) {
    throw "TCP port $serverPort is accepting connections. Stop the target server before deploying."
}

$sessionLock = Join-Path $ServerRoot "world\session.lock"
if (Test-Path -LiteralPath $sessionLock -PathType Leaf) { Assert-FileUnlocked $sessionLock }

$OldPluginJars = @(Get-ChildItem -LiteralPath $PluginsDirectory -Filter "YknBoss*.jar" -File)
foreach ($oldJar in $OldPluginJars) { Assert-FileUnlocked $oldJar.FullName }
$remappedDirectory = Join-Path $PluginsDirectory ".paper-remapped"
if (Test-Path -LiteralPath $remappedDirectory -PathType Container) {
    foreach ($remappedJar in @(Get-ChildItem -LiteralPath $remappedDirectory -Filter "YknBoss*.jar" -File -Recurse)) {
        Assert-FileUnlocked $remappedJar.FullName
    }
}

[IO.Directory]::CreateDirectory($ModelsDirectory) | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupDirectory = Join-Path $ServerRoot "backups\yknboss-deploy\$stamp-$Version"
$FinalPluginJar = Join-Path $PluginsDirectory $PluginJar.Name
$tempToken = [Guid]::NewGuid().ToString("N")
$TempPluginJar = Join-Path $PluginsDirectory ".yknboss-$tempToken.tmp"
$retiredModelRecords = @(
    foreach ($retiredName in @("ancient_codex.bbmodel", "ancient_codex_rings.bbmodel")) {
        $retiredTarget = Join-Path $ModelsDirectory $retiredName
        [pscustomobject]@{
            Target = $retiredTarget
            HadOriginal = (Test-Path -LiteralPath $retiredTarget -PathType Leaf)
            Backup = $null
        }
    }
)
foreach ($retiredRecord in $retiredModelRecords) {
    if ($retiredRecord.HadOriginal) { Assert-FileUnlocked $retiredRecord.Target }
}
$modelRecords = @()
$movedJars = @()
$newJarInstalled = $false

Assert-InDirectory $BackupDirectory $ServerRoot
Assert-InDirectory $FinalPluginJar $PluginsDirectory
Assert-InDirectory $TempPluginJar $PluginsDirectory

Write-Step "Backing up and deploying release $Version"
try {
    Copy-Item -LiteralPath $PluginJar.FullName -Destination $TempPluginJar
    if ((Get-Sha256 $TempPluginJar) -ne $PluginHash) { throw "Temporary plugin copy failed SHA256 validation." }

    foreach ($sourceModel in $modelSources) {
        $targetModel = Join-Path $ModelsDirectory ([IO.Path]::GetFileName($sourceModel))
        $tempModel = "$targetModel.$tempToken.tmp"
        Assert-InDirectory $targetModel $ModelsDirectory
        Assert-InDirectory $tempModel $ModelsDirectory
        Copy-Item -LiteralPath $sourceModel -Destination $tempModel
        if ((Get-Sha256 $tempModel) -ne (Get-Sha256 $sourceModel)) {
            throw "Temporary model copy failed SHA256 validation: $sourceModel"
        }
        $modelRecords += [pscustomobject]@{
            Source = $sourceModel
            Target = $targetModel
            Temp = $tempModel
            HadOriginal = (Test-Path -LiteralPath $targetModel -PathType Leaf)
            Backup = $null
        }
    }

    [IO.Directory]::CreateDirectory($BackupDirectory) | Out-Null
    foreach ($oldJar in $OldPluginJars) {
        $backupJar = Join-Path $BackupDirectory $oldJar.Name
        Move-Item -LiteralPath $oldJar.FullName -Destination $backupJar
        $movedJars += [pscustomobject]@{ Original = $oldJar.FullName; Backup = $backupJar }
    }

    $modelBackupDirectory = Join-Path $BackupDirectory "BetterModel-models"
    [IO.Directory]::CreateDirectory($modelBackupDirectory) | Out-Null
    foreach ($record in $modelRecords) {
        if ($record.HadOriginal) {
            $record.Backup = Join-Path $modelBackupDirectory ([IO.Path]::GetFileName($record.Target))
            Copy-Item -LiteralPath $record.Target -Destination $record.Backup
        }
    }
    foreach ($retiredRecord in $retiredModelRecords) {
        if ($retiredRecord.HadOriginal) {
            $retiredRecord.Backup = Join-Path $modelBackupDirectory ([IO.Path]::GetFileName($retiredRecord.Target))
            Copy-Item -LiteralPath $retiredRecord.Target -Destination $retiredRecord.Backup
        }
    }

    Move-Item -LiteralPath $TempPluginJar -Destination $FinalPluginJar
    $newJarInstalled = $true
    foreach ($record in $modelRecords) {
        Move-Item -LiteralPath $record.Temp -Destination $record.Target -Force
    }
    foreach ($retiredRecord in $retiredModelRecords) {
        if ($retiredRecord.HadOriginal -and
                (Test-Path -LiteralPath $retiredRecord.Target -PathType Leaf)) {
            Remove-Item -LiteralPath $retiredRecord.Target -Force
        }
    }

    if ((Get-Sha256 $FinalPluginJar) -ne $PluginHash) { throw "Deployed plugin failed SHA256 validation." }
    foreach ($record in $modelRecords) {
        if ((Get-Sha256 $record.Target) -ne (Get-Sha256 $record.Source)) {
            throw "Deployed model failed SHA256 validation: $($record.Target)"
        }
    }
}
catch {
    $deploymentError = $_
    Write-Warning "Deployment failed; attempting rollback."

    if ($newJarInstalled -and (Test-Path -LiteralPath $FinalPluginJar -PathType Leaf)) {
        Assert-InDirectory $FinalPluginJar $PluginsDirectory
        Remove-Item -LiteralPath $FinalPluginJar -Force
    }
    foreach ($moved in $movedJars) {
        if (Test-Path -LiteralPath $moved.Backup -PathType Leaf) {
            Move-Item -LiteralPath $moved.Backup -Destination $moved.Original -Force
        }
    }
    foreach ($record in $modelRecords) {
        if ($record.HadOriginal -and $null -ne $record.Backup -and (Test-Path -LiteralPath $record.Backup -PathType Leaf)) {
            Copy-Item -LiteralPath $record.Backup -Destination $record.Target -Force
        }
        elseif (-not $record.HadOriginal -and (Test-Path -LiteralPath $record.Target -PathType Leaf)) {
            Remove-Item -LiteralPath $record.Target -Force
        }
    }
    foreach ($retiredRecord in $retiredModelRecords) {
        if ($retiredRecord.HadOriginal -and
                $null -ne $retiredRecord.Backup -and
                (Test-Path -LiteralPath $retiredRecord.Backup -PathType Leaf)) {
            Copy-Item -LiteralPath $retiredRecord.Backup -Destination $retiredRecord.Target -Force
        }
    }
    throw $deploymentError
}
finally {
    if (Test-Path -LiteralPath $TempPluginJar -PathType Leaf) { Remove-Item -LiteralPath $TempPluginJar -Force }
    foreach ($record in $modelRecords) {
        if (Test-Path -LiteralPath $record.Temp -PathType Leaf) { Remove-Item -LiteralPath $record.Temp -Force }
    }
}

Write-Host "`nDeployment completed successfully." -ForegroundColor Green
Write-Host "Plugin: $FinalPluginJar"
Write-Host "Models: $ModelsDirectory"
Write-Host "Backup: $BackupDirectory"
$betterModelConfig = Join-Path $PluginsDirectory "BetterModel\config.yml"
if (Test-Path -LiteralPath $betterModelConfig -PathType Leaf) {
    $minimumSight = Get-Content -LiteralPath $betterModelConfig | Where-Object { $_ -match '^\s*min-sight:\s*(\d+)\s*$' } | Select-Object -First 1
    if ($null -ne $minimumSight -and $minimumSight -match '^\s*min-sight:\s*(\d+)\s*$' -and [int]$Matches[1] -ne 0) {
        Write-Warning "BetterModel min-sight is $($Matches[1]); set it to 0 if close-range model updates are skipped."
    }
}
Write-Host "Start the server and complete the runtime checks listed in AGENTS.md."
