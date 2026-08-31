param(
    [string]$SourcePath = "modle\endsun\bettermodel\endsun_anuhe.bbmodel",
    [string]$OutputPath = "packs\bettermodel\models\endsun_anuhe.bbmodel",
    [double]$BakeScale = 1.5
)

$ErrorActionPreference = "Stop"
$culture = [Globalization.CultureInfo]::InvariantCulture

function Scale-Vector($value) {
    if ($null -eq $value) { return $null }
    return [object[]]@($value | ForEach-Object {
        [Math]::Round(([double]$_) * $BakeScale, 5)
    })
}

function Scale-NumberString([string]$value) {
    $number = 0.0
    if (-not [double]::TryParse($value, [Globalization.NumberStyles]::Float,
            $culture, [ref]$number)) {
        throw "Non-numeric animation position cannot be baked: $value"
    }
    return ([Math]::Round($number * $BakeScale, 5)).ToString("0.#####", $culture)
}

$resolvedSource = [IO.Path]::GetFullPath($SourcePath)
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$model = Get-Content -LiteralPath $resolvedSource -Raw -Encoding UTF8 | ConvertFrom-Json

if (@($model.outliner).Count -eq 1 -and $model.outliner[0].uuid -eq "7c58f8ac-2c0d-4abe-b64d-1bb08e0fe3aa") {
    throw "The source model already contains the baked root; refusing to scale twice."
}

foreach ($element in @($model.elements)) {
    if ($null -ne $element.from) { $element.from = Scale-Vector $element.from }
    if ($null -ne $element.to) { $element.to = Scale-Vector $element.to }
    if ($null -ne $element.origin) { $element.origin = Scale-Vector $element.origin }
}

foreach ($group in @($model.groups)) {
    if ($null -ne $group.origin) { $group.origin = Scale-Vector $group.origin }
}

foreach ($animation in @($model.animations)) {
    foreach ($animatorProperty in $animation.animators.PSObject.Properties) {
        foreach ($keyframe in @($animatorProperty.Value.keyframes)) {
            if ($keyframe.channel -ne "position") { continue }
            foreach ($point in @($keyframe.data_points)) {
                $point.x = Scale-NumberString ([string]$point.x)
                $point.y = Scale-NumberString ([string]$point.y)
                $point.z = Scale-NumberString ([string]$point.z)
            }
        }
    }
}

if ($null -ne $model.visible_box) {
    $model.visible_box = Scale-Vector $model.visible_box
}

$oldOutliner = [object[]]@($model.outliner)
$rootUuid = "7c58f8ac-2c0d-4abe-b64d-1bb08e0fe3aa"
$rootGroup = [pscustomobject][ordered]@{
    name = "ykn_root"
    uuid = $rootUuid
    export = $true
    locked = $true
    scope = 0
    selected = $false
    _static = [pscustomobject]@{ properties = [pscustomobject]@{}; temp_data = [pscustomobject]@{} }
    origin = [object[]]@(0, 0, 0)
    rotation = [object[]]@(0, 0, 0)
    color = 0
    children = [object[]]@()
    reset = $false
    shade = $true
    mirror_uv = $false
    visibility = $true
    autouv = 0
    isOpen = $true
    primary_selected = $false
}
$rootOutliner = [pscustomobject][ordered]@{
    uuid = $rootUuid
    isOpen = $true
    children = $oldOutliner
}
$model.groups = [object[]]@($rootGroup) + [object[]]@($model.groups)
$model.outliner = [object[]]@($rootOutliner)

if ($null -eq $model.unhandled_root_fields) {
    $model | Add-Member -NotePropertyName unhandled_root_fields -NotePropertyValue ([pscustomobject]@{})
}
$model.unhandled_root_fields | Add-Member -Force -NotePropertyName yknboss_baked_scale -NotePropertyValue $BakeScale

$outputDirectory = [IO.Path]::GetDirectoryName($resolvedOutput)
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$json = $model | ConvertTo-Json -Depth 100 -Compress
[IO.File]::WriteAllText($resolvedOutput, $json, [Text.UTF8Encoding]::new($false))

Write-Output "Generated fixed-scale Endsun model: $resolvedOutput (baked scale=$BakeScale)"
