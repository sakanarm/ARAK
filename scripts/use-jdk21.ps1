# Point JAVA_HOME at a project-local JDK 21 for the current shell only.
#
#   . .\scripts\use-jdk21.ps1     (note the leading dot — it must be sourced)
#
# This exists because the build needs Java 21 and a machine may have an older
# JDK on PATH. It changes nothing outside the shell you run it in: no registry,
# no system PATH, no installer. Deleting .tools/ undoes it entirely.
#
# If .tools/ has no JDK, the script fetches Temurin 21 (~196 MB) from Adoptium.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root '.tools'
$jdk = Get-ChildItem -Path $tools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1

if (-not $jdk) {
    Write-Host 'No JDK 21 under .tools — downloading Temurin 21 from Adoptium...'
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $zip = Join-Path $tools 'temurin21.zip'
    $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse'
    Invoke-WebRequest -Uri $url -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $tools -Force
    Remove-Item $zip
    $jdk = Get-ChildItem -Path $tools -Directory -Filter 'jdk-21*' |
        Sort-Object Name -Descending | Select-Object -First 1
}

$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$($jdk.FullName)\bin;$env:PATH"

& "$env:JAVA_HOME\bin\java.exe" -version
Write-Host "JAVA_HOME = $env:JAVA_HOME  (this shell only)"
