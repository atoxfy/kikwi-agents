# Publica o kikwi-agents no Maven Central (perfil "release": GPG + central-publishing), sem o agents-app (demo).
# Rode no PowerShell (o gpg do Windows tem a chave; o do Git Bash não):
#   powershell -ExecutionPolicy Bypass -File .\release.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& mvn --batch-mode '-Prelease' clean deploy '-DskipTests' '-pl' '!agents-app'
if ($LASTEXITCODE -ne 0) { throw "Publicação falhou (mvn saiu com $LASTEXITCODE)" }
