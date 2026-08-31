param(
    [Parameter(Mandatory = $true)]
    [string]$ModelPath
)

$ErrorActionPreference = 'Stop'
$resolvedModel = (Resolve-Path -LiteralPath $ModelPath).Path
$model = Get-Content -LiteralPath $resolvedModel -Raw -Encoding UTF8 | ConvertFrom-Json

if ($model.meta.model_format -ne 'free') {
    throw "Ancient Codex model format must be 'free', got '$($model.meta.model_format)'."
}

$meshElements = @($model.elements | Where-Object { $_.type -eq 'mesh' })
if ($meshElements.Count -eq 0) {
    throw 'Ancient Codex model contains no mesh elements.'
}

$meshNames = @($meshElements | ForEach-Object { $_.name })
foreach ($requiredMesh in @('circle_1_circle_1_0', 'circle_2_circle_2_0')) {
    if ($requiredMesh -notin $meshNames) {
        throw "Ancient Codex model is missing the original rune-ring mesh '$requiredMesh'."
    }
}

$textures = @($model.textures)
if ($textures.Count -ne 6) {
    throw "Ancient Codex model must contain six original base-color textures; found $($textures.Count)."
}
$textureNames = @($textures | ForEach-Object { $_.name } | Select-Object -Unique)
$textureSources = @($textures | ForEach-Object { $_.source } | Select-Object -Unique)
if ($textureNames.Count -ne 6 -or $textureSources.Count -ne 6) {
    throw 'Ancient Codex textures must have six unique names and six unique embedded images.'
}
foreach ($texture in $textures) {
    if ([int]$texture.width -le 0 -or [int]$texture.height -le 0) {
        throw "Texture '$($texture.name)' has invalid dimensions $($texture.width)x$($texture.height)."
    }
    if ([int]$texture.uv_width -ne 16 -or [int]$texture.uv_height -ne 16) {
        throw "Texture '$($texture.name)' must use the model's 16x16 UV space."
    }
    if (-not ([string]$texture.source).StartsWith('data:image/')) {
        throw "Texture '$($texture.name)' is not embedded in the bbmodel."
    }
}

$faceCount = 0
foreach ($element in $meshElements) {
    foreach ($faceProperty in $element.faces.PSObject.Properties) {
        $faceCount++
        $face = $faceProperty.Value
        $vertices = @($face.vertices | Select-Object -Unique)
        if ($vertices.Count -lt 3 -or $vertices.Count -gt 4) {
            throw "Mesh '$($element.name)' face '$($faceProperty.Name)' has $($vertices.Count) unique vertices; BetterModel supports triangles or quads."
        }
        foreach ($vertex in $vertices) {
            if ($null -eq $face.uv -or $null -eq $face.uv.PSObject.Properties[$vertex]) {
                throw "Mesh '$($element.name)' face '$($faceProperty.Name)' references vertex '$vertex' without UV coordinates."
            }
        }
    }
}

if ($faceCount -eq 0) {
    throw 'Ancient Codex model contains no renderable mesh faces.'
}

$hoverAnimation = @($model.animations | Where-Object { $_.name -eq 'animation.hover' })
if ($hoverAnimation.Count -ne 1) {
    throw "Ancient Codex model must contain exactly one animation.hover; found $($hoverAnimation.Count)."
}
if ([Math]::Abs([double]$hoverAnimation[0].length - 5.0) -gt 0.0001) {
    throw "animation.hover must be 5 seconds long; got $($hoverAnimation[0].length)."
}

Write-Host "Ancient Codex model validation passed: $($meshElements.Count) meshes, $faceCount valid faces, six embedded textures, both original rune rings, and the 5-second rotation animation."
