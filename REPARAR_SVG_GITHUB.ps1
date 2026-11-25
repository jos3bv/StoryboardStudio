$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot
$assetRel = "app/src/main/assets/icons_svg"
$assetPath = Join-Path $repo $assetRel
$backupPath = Join-Path $repo "__RECOVERY_ICONS_SVG"

git -C $repo rev-parse --show-toplevel | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Esta carpeta no es la raíz de un repositorio Git." }
if (!(Test-Path $backupPath)) { throw "Falta __RECOVERY_ICONS_SVG. Extrae el ZIP completo en la raíz del repositorio." }

# Keep all 30 SVG assets safe while removing old indexed paths, including the malformed filename.
$temp = Join-Path $env:TEMP ("BVStoryboarder_svg_" + [guid]::NewGuid().ToString("N"))
Copy-Item -LiteralPath $backupPath -Destination $temp -Recurse

git -C $repo rm -r --cached --force --ignore-unmatch -- $assetRel
if ($LASTEXITCODE -ne 0) { throw "Git no pudo retirar las rutas antiguas de SVG del índice." }

# Recreate the folder with the complete, restored SVG set.
if (Test-Path $assetPath) { Remove-Item -LiteralPath $assetPath -Recurse -Force }
New-Item -ItemType Directory -Force -Path $assetPath | Out-Null
Copy-Item -Path (Join-Path $temp "*") -Destination $assetPath -Recurse -Force
Remove-Item -LiteralPath $temp -Recurse -Force
Remove-Item -LiteralPath $backupPath -Recurse -Force

git -C $repo add -- $assetRel
if ($LASTEXITCODE -ne 0) { throw "No se pudieron agregar los SVG restaurados al índice Git." }

Write-Host "Listo: se restauraron los 30 SVG, incluido 'anadir plano.svg'."
Write-Host "Abre GitHub Desktop y confirma que aparezca el archivo antiguo con nombre ilegible como eliminado y el nuevo 'anadir plano.svg' como agregado."
Write-Host "No hagas commit si no aparecen ambas operaciones."
