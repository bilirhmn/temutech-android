$ErrorActionPreference = 'Stop'

$installRoot = Join-Path $env:LOCALAPPDATA 'AndroidDev'
$jdkDir = Join-Path $installRoot 'jdk-21'
$sdkDir = Join-Path $installRoot 'Sdk'
$downloadDir = Join-Path $installRoot 'downloads'
New-Item -ItemType Directory -Force -Path $installRoot, $downloadDir | Out-Null

function Get-Zip($url, $file) {
    if (Test-Path -LiteralPath $file) {
        Write-Host "Using cached $([IO.Path]::GetFileName($file))..."
        return
    }
    Write-Host "Downloading $([IO.Path]::GetFileName($file))..."
    $partFile = "$file.part"
    curl.exe -fL --progress-bar $url -o $partFile
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    Move-Item -LiteralPath $partFile -Destination $file -Force
}

$jdkZip = Join-Path $downloadDir 'microsoft-jdk-21.zip'
Get-Zip 'https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip' $jdkZip
Write-Host 'Extracting JDK 21...'
$jdkExtract = Join-Path $installRoot 'jdk-extract'
Remove-Item -LiteralPath $jdkExtract -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkExtract -Force
$jdkCandidate = Get-ChildItem -LiteralPath $jdkExtract -Directory | Select-Object -First 1
if (-not $jdkCandidate) { throw 'JDK archive did not contain an installation folder.' }
Remove-Item -LiteralPath $jdkDir -Recurse -Force -ErrorAction SilentlyContinue
Move-Item -LiteralPath $jdkCandidate.FullName -Destination $jdkDir
Remove-Item -LiteralPath $jdkExtract -Recurse -Force

$cmdZip = Join-Path $downloadDir 'android-commandlinetools.zip'
Get-Zip 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $cmdZip
Write-Host 'Extracting Android Command Line Tools...'
$sdkCmdDir = Join-Path $sdkDir 'cmdline-tools'
$latestDir = Join-Path $sdkCmdDir 'latest'
New-Item -ItemType Directory -Force -Path $sdkCmdDir | Out-Null
$cmdExtract = Join-Path $installRoot 'cmdline-extract'
Remove-Item -LiteralPath $cmdExtract -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive -LiteralPath $cmdZip -DestinationPath $cmdExtract -Force
Remove-Item -LiteralPath $latestDir -Recurse -Force -ErrorAction SilentlyContinue
Move-Item -LiteralPath (Join-Path $cmdExtract 'cmdline-tools') -Destination $latestDir
Remove-Item -LiteralPath $cmdExtract -Recurse -Force

$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$env:Path = "$jdkDir\bin;$latestDir\bin;$sdkDir\platform-tools;$env:Path"

$sdkManager = Join-Path $latestDir 'bin\sdkmanager.bat'
Write-Host 'Accepting Android SDK licenses and installing packages...'
$packages = @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0')
1..10 | ForEach-Object { 'y' } | & $sdkManager --sdk_root=$sdkDir --licenses | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed.' }
& $sdkManager --sdk_root=$sdkDir $packages
if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed.' }

[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkDir, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdkDir, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $sdkDir, 'User')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$newEntries = @("$jdkDir\bin", "$latestDir\bin", "$sdkDir\platform-tools")
foreach ($entry in $newEntries) {
    if (($userPath -split ';') -notcontains $entry) { $userPath = "$userPath;$entry" }
}
[Environment]::SetEnvironmentVariable('Path', $userPath.Trim(';'), 'User')
Remove-Item -LiteralPath $downloadDir -Recurse -Force

Write-Host "`nInstalled. Open a new terminal or restart Android Studio, then verify with:"
Write-Host '  java -version'
Write-Host '  adb version'
Write-Host "`nInstall location: $installRoot"
