$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$wrapperDir = Join-Path $projectDir 'gradle/wrapper'
New-Item -ItemType Directory -Force -Path $wrapperDir | Out-Null
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $tempDir | Out-Null
try {
    Write-Host 'Downloading the official Gradle 8.9 wrapper (first run only)...'
    $checksumFile = Join-Path $tempDir 'wrapper.sha256'
    $jarFile = Join-Path $tempDir 'gradle-wrapper.jar'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256' -OutFile $checksumFile
    Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $jarFile
    $expected = (Get-Content -Raw $checksumFile).Trim()
    $actual = (Get-FileHash -Algorithm SHA256 $jarFile).Hash
    if ($expected -ine $actual) { throw 'Gradle wrapper checksum mismatch; the download was rejected.' }
    Move-Item -Force $jarFile (Join-Path $wrapperDir 'gradle-wrapper.jar')
    Write-Host 'Official Gradle wrapper checksum verified.'
} finally {
    Remove-Item -Recurse -Force $tempDir
}
