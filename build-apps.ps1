param (
    [ValidateSet("electron", "android", "all")]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $ScriptDir

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "     LibreChat Desktop & Android Build Tool       " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Project Directory: $ScriptDir"
Write-Host "Target: $Target"
Write-Host ""

function Build-Electron {
    Write-Host "[1/2] Building LibreChat Desktop (Electron Windows .exe)..." -ForegroundColor Green
    $ElectronDir = Join-Path $ScriptDir "electron"
    if (-not (Test-Path $ElectronDir)) {
        Write-Error "Electron directory not found at $ElectronDir"
        return
    }

    Push-Location $ElectronDir
    try {
        if (-not (Test-Path "node_modules")) {
            Write-Host "Installing Electron dependencies..." -ForegroundColor Yellow
            npm install
        }

        Write-Host "Packaging Windows NSIS Installer & Portable .exe..." -ForegroundColor Yellow
        npm run dist:win

        $DistDir = Join-Path $ElectronDir "dist"
        Write-Host ""
        Write-Host "[OK] Electron Build Completed Successfully!" -ForegroundColor Green
        Write-Host "Artifacts generated in: $DistDir" -ForegroundColor Cyan
        
        $exeFiles = Get-ChildItem -Path $DistDir -Filter "*.exe"
        foreach ($f in $exeFiles) {
            $mb = [math]::Round($f.Length / 1048576, 2)
            $msg = "  - " + $f.Name + " [" + $mb + " MB]"
            Write-Host $msg -ForegroundColor White
        }
        $manifests = Get-ChildItem -Path $DistDir -Filter "*.yml"
        foreach ($m in $manifests) {
            $msg = "  - " + $m.Name + " [Auto-update Manifest]"
            Write-Host $msg -ForegroundColor White
        }
    }
    catch {
        Write-Error "Electron build failed: $_"
    }
    finally {
        Pop-Location
    }
}

function Build-Android {
    Write-Host ""
    Write-Host "[2/2] Building LibreChat Android (.apk)..." -ForegroundColor Green
    $AndroidDir = Join-Path $ScriptDir "android"
    if (-not (Test-Path $AndroidDir)) {
        Write-Error "Android directory not found at $AndroidDir"
        return
    }

    $AndroidHome = $env:ANDROID_HOME
    if (-not $AndroidHome) {
        $PossibleSdkPaths = @(
            "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk",
            "C:\Android\Sdk",
            "D:\Android\Sdk"
        )
        foreach ($p in $PossibleSdkPaths) {
            if (Test-Path $p) {
                $AndroidHome = $p
                $env:ANDROID_HOME = $p
                break
            }
        }
    }

    if (-not $AndroidHome -or -not (Test-Path $AndroidHome)) {
        Write-Host "NOTE: Local Android SDK not detected on this machine." -ForegroundColor Yellow
        Write-Host "To build the APK locally:" -ForegroundColor White
        Write-Host "  1. Install Android Studio or Android Command-line Tools (cmdline-tools)." -ForegroundColor Gray
        Write-Host "  2. Set ANDROID_HOME environment variable to your SDK path." -ForegroundColor Gray
        Write-Host "  3. Re-run: .\build-apps.ps1 -Target android" -ForegroundColor Gray
        Write-Host ""
        Write-Host "Alternatively, push your code to GitHub with the included workflow:" -ForegroundColor Cyan
        Write-Host "  .github/workflows/build-and-release.yml" -ForegroundColor Cyan
        Write-Host "GitHub Actions will automatically compile the APK and EXE and create a release!" -ForegroundColor Cyan
        return
    }

    if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
        $Jdk17 = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
        if (Test-Path $Jdk17) {
            $env:JAVA_HOME = $Jdk17
        }
    }
    Write-Host "Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Gray

    $BuildGradle = Join-Path $AndroidDir "app\build.gradle"
    $VersionName = (Select-String -Path $BuildGradle -Pattern 'versionName\s+"([^"]+)"').Matches[0].Groups[1].Value
    $ApkName = "LibreChat-v$VersionName-release.apk"

    Push-Location $AndroidDir
    try {
        Write-Host "Running Gradle assembleRelease..." -ForegroundColor Yellow
        $Gradlew = Join-Path $AndroidDir "gradlew.bat"
        if (Test-Path $Gradlew) {
            & $Gradlew assembleRelease
        } else {
            gradle assembleRelease
        }

        $ApkDir = Join-Path $AndroidDir "app\build\outputs\apk\release"
        if (Test-Path $ApkDir) {
            Write-Host ""
            Write-Host "[OK] Android APK Build Completed Successfully!" -ForegroundColor Green
            $apks = Get-ChildItem -Path $ApkDir -Filter "*.apk"
            foreach ($a in $apks) {
                $mb = [math]::Round($a.Length / 1048576, 2)
                $msg = "  - " + $a.Name + " [" + $mb + " MB]"
                Write-Host $msg -ForegroundColor White
                Copy-Item -Path $a.FullName -Destination (Join-Path $ScriptDir $ApkName) -Force
                Write-Host "  -> Copied to $ApkName" -ForegroundColor Cyan
            }
        }
    }
    catch {
        Write-Error "Android build failed: $_"
    }
    finally {
        Pop-Location
    }
}

switch ($Target) {
    "electron" { Build-Electron }
    "android"  { Build-Android }
    "all"      {
        Build-Electron
        Build-Android
    }
}

Write-Host ""
Write-Host "Build script execution finished." -ForegroundColor Green
