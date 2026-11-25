$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot
$assetRel = "app/src/main/assets/icons_svg"
$assetPath = Join-Path $repo $assetRel
$backupPath = Join-Path $repo "__RECOVERY_ICONS_SVG"

Write-Host "BV'Storyboarder 0.9.39 - limpiando el archivo SVG problemático del índice Git..."
git -C $repo rev-parse --show-toplevel | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Esta carpeta no parece ser la raíz de un repositorio Git." }
if (!(Test-Path $backupPath)) { throw "No se encuentra __RECOVERY_ICONS_SVG. Extrae el ZIP completo antes de ejecutar este script." }

# Preserve all valid SVGs outside the tracked directory.
$temp = Join-Path $env:TEMP ("BVStoryboarder_icons_" + [guid]::NewGuid().ToString("N"))
Copy-Item -LiteralPath $backupPath -Destination $temp -Recurse

# Remove the entire old tracked SVG directory from the Git index and worktree.
git -C $repo rm -r --force --ignore-unmatch -- $assetRel
if ($LASTEXITCODE -ne 0) { throw "Git no pudo retirar la carpeta antigua de iconos." }

# Restore only the clean SVG set supplied in this release.
New-Item -ItemType Directory -Force -Path $assetPath | Out-Null
Copy-Item -Path (Join-Path $temp "*") -Destination $assetPath -Recurse -Force
Remove-Item -LiteralPath $temp -Recurse -Force
Remove-Item -LiteralPath $backupPath -Recurse -Force

# Stage the clean folder again. The malformed tracked filename is now deleted.
git -C $repo add -- $assetRel
if ($LASTEXITCODE -ne 0) { throw "No se pudieron agregar los SVG limpios al índice." }

Write-Host ""
Write-Host "Limpieza terminada. Abre GitHub Desktop y revisa Changes."
Write-Host "Debe aparecer la eliminación del archivo SVG con nombre ilegible."
Write-Host "No hagas commit si no aparece esa eliminación."
