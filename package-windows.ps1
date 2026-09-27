param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$AppVersion = "1.6.0",
    [string]$WiXHome
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw "Set JAVA_HOME to a full JDK 17+ installation containing jpackage.exe."
}

$jpackage = Join-Path $JdkHome "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) {
    throw "JAVA_HOME must point to a full JDK containing jpackage.exe: $JdkHome"
}

$candle = $null
$light = $null
if (-not [string]::IsNullOrWhiteSpace($WiXHome)) {
    $candlePath = Join-Path $WiXHome "candle.exe"
    $lightPath = Join-Path $WiXHome "light.exe"
    if ((Test-Path $candlePath) -and (Test-Path $lightPath)) {
        $candle = Get-Item $candlePath
        $light = Get-Item $lightPath
    } else {
        throw "WiX tools not found in the specified directory: $WiXHome"
    }
} else {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if (-not $candle -or -not $light) {
        $toolCache = Join-Path $projectRoot "target\tool-cache"
        $wixPackage = Join-Path $toolCache "wix-nuget.nupkg"
        $wixDirectory = Join-Path $toolCache "wix3"
        New-Item -ItemType Directory -Force -Path $toolCache | Out-Null
        if (-not (Test-Path $wixPackage)) {
            Invoke-WebRequest `
                -Uri "https://api.nuget.org/v3-flatcontainer/wix/3.14.1/wix.3.14.1.nupkg" `
                -OutFile $wixPackage
        }
        $sha512 = [Security.Cryptography.SHA512]::Create()
        try {
            $actualHash = [Convert]::ToBase64String(
                $sha512.ComputeHash([IO.File]::ReadAllBytes($wixPackage)))
        } finally {
            $sha512.Dispose()
        }
        if ($actualHash -ne "UnChdB0hjeAgMK0v4C+QjEUhZB1OO1PLttenMhi4T3HV58TClIpk6s9nSMm2SLSqIsAaEmdBPD9APCBQ9nn70A==") {
            throw "The downloaded WiX package failed its SHA-512 integrity check."
        }
        if (-not (Test-Path (Join-Path $wixDirectory "tools\candle.exe"))) {
            if (Test-Path $wixDirectory) {
                throw "WiX extraction directory exists but is incomplete: $wixDirectory"
            }
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [IO.Compression.ZipFile]::ExtractToDirectory($wixPackage, $wixDirectory)
        }
        $WiXHome = Join-Path $wixDirectory "tools"
    }
}

Push-Location $projectRoot
try {
    $env:JAVA_HOME = (Resolve-Path $JdkHome).Path
    $env:PATH = "$WiXHome;$($env:JAVA_HOME)\bin;$env:PATH"

    $installerDirectory = Join-Path $projectRoot "target\installer"
    $packageInput = Join-Path $installerDirectory "input"
    New-Item -ItemType Directory -Force -Path $packageInput | Out-Null

    & .\mvnw.cmd package `
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies `
        "-DoutputDirectory=$packageInput" `
        "-DincludeScope=runtime"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven packaging failed with exit code $LASTEXITCODE."
    }

    Copy-Item (Join-Path $projectRoot "target\my-git-client-1.0-SNAPSHOT.jar") `
        (Join-Path $packageInput "gitpilot.jar") -Force
    $iconPath = Join-Path $installerDirectory "GitPilot.ico"
    $installerResources = Join-Path $projectRoot "installer-resources"
    & (Join-Path $env:JAVA_HOME "bin\java.exe") `
        (Join-Path $projectRoot "tools\CreateWindowsIcon.java") `
        (Join-Path $projectRoot "src\main\resources\com\git\client\gitdesk_icon.png") `
        $iconPath
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the Windows application icon (exit code $LASTEXITCODE)."
    }
    foreach ($installerType in @("exe", "msi")) {
        & $jpackage `
            --type $installerType `
            --name GitPilot `
            --app-version $AppVersion `
            --vendor GitPilot `
            --description "Desktop Git client" `
            --input $packageInput `
            --main-jar gitpilot.jar `
            --main-class com.git.client.Launcher `
            --icon $iconPath `
            --dest $installerDirectory `
            --resource-dir $installerResources `
            --win-upgrade-uuid "BDE280C1-590A-36B5-B5C6-B024658FFF53" `
            --win-menu `
            --win-shortcut `
            --win-dir-chooser `
            --win-per-user-install
        if ($LASTEXITCODE -ne 0) {
            throw "jpackage failed creating the $installerType installer (exit code $LASTEXITCODE)."
        }
    }

    Write-Host "EXE and MSI installers created in $installerDirectory"
}
finally {
    Pop-Location
}
