# TemuTech

TemuTech is a community event hub for Android for students and beginners in Indonesia to discover technology and learning events. Browse as a guest, import public events from Dicoding, filter by city or **Daring** (online), and open event details. Room keeps previously loaded data available offline. Bookmark, RSVP, and event creation/editing/deletion are local app actions; an RSVP does not register you with Dicoding.

The app uses Kotlin, Jetpack Compose inside Fragments, Navigation Component, Room, Retrofit/OkHttp and MapLibre Native. The map uses OpenFreeMap Liberty without a key. Vulkan is the default renderer; API 26 deliberately displays a location list without an interactive map. The debug demo session is separate from Firebase Authentication. Firebase can enable email/password sign-in and private profiles, but there is no cloud event sync.

## Requirements

- Windows x64 and PowerShell; Zed is the project editor. Android Studio is not required for this workflow.
- JDK 21 (used for project verification). Java/Kotlin bytecode targets 17. The checked-in Gradle Wrapper uses Gradle 8.9; do not install separate Gradle.
- Android SDK Command-line Tools, Platform-Tools (`adb`), Android platform 35, Android Build Tools, and the standalone Android Emulator. The app compiles/targets API 35 and supports API 26 and higher.
- A hardware-accelerated x86_64 emulator (CPU virtualization and Windows Hypervisor Platform) and the Google APIs API 35 system image. Network access is required for the first dependency download, a live Dicoding import, and live map tiles. Cached Room data works offline.
- Optional: Node.js 20+ for test tools; Git for cloning. No API key, Firebase configuration or billing is needed for the guest/debug demo.

## Download and configure

Use the **actual** HTTPS or SSH clone URL from the team repository, or download the repository ZIP and extract it. For Git:

```powershell
$repositoryUrl = Read-Host 'Team repository clone URL'
git clone -- $repositoryUrl temutech-android
if ($LASTEXITCODE -ne 0) { throw 'Clone failed' }
Set-Location temutech-android
```

For a ZIP, open the extracted folder containing `gradlew.bat`. Set `JAVA_HOME` to your JDK installation and `ANDROID_HOME` to your Android SDK installation if they are not already set. Example paths for a custom local install (replace if different):

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\AndroidDev\jdk-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\AndroidDev\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
$avdManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\avdmanager.bat'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
java -version
```

These assignments are for the current shell only. The command-line tools must already be installed under `cmdline-tools\latest`. On an existing setup, skip the downloads and AVD creation if the required packages and a suitable development AVD already exist. If installing or changing tools on a shared machine, obtain approval first. The legacy `setup-android-dev.ps1` is **not required**: it replaces existing installations at its chosen paths, changes user environment variables and does not install an emulator image.

If the needed SDK packages or development AVD are missing, install them once and review the SDK license prompts:

```powershell
& $sdkManager --licenses
& $sdkManager 'platform-tools' 'platforms;android-35' 'build-tools;34.0.0' 'build-tools;35.0.0' 'emulator' 'system-images;android-35;google_apis;x86_64'
'no' | & $avdManager create avd --name CEH_DEV_API_35 --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
& $emulator -list-avds
& $emulator -accel-check
```

Gradle can use `ANDROID_HOME` directly. If local SDK discovery needs an override, copy `local.properties.example` to `local.properties` and set `sdk.dir` to the SDK path using forward slashes. Do not commit `local.properties`.

### Optional Firebase setup

For authorized cloud development, register an Android app with application ID `id.ac.ceh` in your own approved Firebase project and put its downloaded configuration at `app/google-services.json`. Use a dedicated project, enable Email/Password and Firestore as approved, and ensure the intended [Firestore rules](firestore.rules) are published before any live test. A normal build does **not** deploy rules. Keep the configuration, service accounts and test credentials out of the repository. Without the configuration, use guest mode or the debug demo; Dicoding and the map do not depend on Firebase.

## Build and run

Use one PowerShell session for the variables and commands. The following chooses a standalone development AVD on port `5556`. If that port is in use, choose another free even emulator port by changing `$port` before launch. Do not use the team's baseline or instrumented-test AVD for day-to-day development.

```powershell
.\gradlew.bat --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Debug build failed' }

# Record this before the first ADB command if you plan a full shutdown.
$startedAdbHere = -not (Get-Process adb -ErrorAction SilentlyContinue)
$avd = 'CEH_DEV_API_35'
$port = 5556
$serial = "emulator-$port"
& $adb devices -l
# Continue only after checking that the port/serial is free.
$emulatorProcess = Start-Process -FilePath $emulator -ArgumentList @('-avd', $avd, '-port', $port, '-gpu', 'swiftshader', '-no-snapshot') -PassThru
```

After the emulator reaches the Android home screen, run:

```powershell
$identity = & $adb -s $serial emu avd name
if ($LASTEXITCODE -ne 0 -or ([string]($identity | Select-Object -First 1)).Trim() -ne $avd) {
    throw 'Not the intended development emulator'
}
$boot = & $adb -s $serial shell getprop sys.boot_completed
if ($LASTEXITCODE -ne 0 -or ([string]$boot).Trim() -ne '1') {
    throw 'Wait for Android to finish booting, then retry'
}
& $adb -s $serial install --no-streaming -r .\app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; do not clear data to bypass a signing mismatch' }
& $adb -s $serial shell am start -W -n id.ac.ceh/.MainActivity
```

On **Beranda**, select **Impor Dicoding**, then try the city and **Daring** filters. To exercise local member actions without Firebase, open **Profil**, select **Masuk** if needed, and use the debug demo action. Offline mode uses existing Room data; live import and map rendering depend on provider and network availability. See [Local development](docs/LOCAL_DEVELOPMENT.md) for renderer and test notes.

## Stop the app and shut down the development session

Pressing Home or closing a terminal does not reliably stop the app or emulator. To stop **only the app**, verify the AVD and force-stop its package. An empty `pidof` result is expected:

```powershell
$identity = & $adb -s $serial emu avd name
if ($LASTEXITCODE -ne 0 -or ([string]($identity | Select-Object -First 1)).Trim() -ne $avd) {
    throw 'Not the intended development emulator'
}
& $adb -s $serial shell am force-stop id.ac.ceh
if ($LASTEXITCODE -ne 0) { throw 'App force-stop failed' }
& $adb -s $serial shell pidof id.ac.ceh
```

To close **the entire session**, continue from that verified AVD. Do not uninstall the app, wipe the AVD, or kill all Java processes:

```powershell
& $adb -s $serial emu kill
if ($LASTEXITCODE -ne 0) { throw 'Emulator shutdown failed' }
# If started with Start-Process above, wait for the captured process to exit.
if ($emulatorProcess -and -not $emulatorProcess.HasExited) {
    Wait-Process -Id $emulatorProcess.Id -Timeout 60
}
& $adb devices -l
```

Confirm that your serial is no longer listed before continuing. The build commands here use `--no-daemon` and in-process Kotlin; there should be no persistent build daemon from those commands. If **you** previously ran a daemon-enabled build and no other project is using that Gradle 8.9 daemon, run `.\gradlew.bat --stop`. Stop any Firebase emulator you started separately in its own terminal (Ctrl+C); none is started by the run instructions.

ADB is shared. Stop it only if this session started it, the device list is empty, and no other tools are using it:

```powershell
if ($startedAdbHere) {
    # Run only after checking the device list and other tools above.
    & $adb kill-server
}
# Read-only check. Do not run `adb devices` after kill-server; it restarts ADB.
Get-Process adb, emulator, qemu-system-x86_64, netsimd -ErrorAction SilentlyContinue
```

In an exclusive session, the last command should show no processes from this run after shutdown. If it lists another session's processes, leave them running. If a process that belongs to your AVD remains, check its PID and the emulator terminal before stopping it; do not terminate processes by name on a shared machine.

## Tests and contributing

```powershell
.\gradlew.bat --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :app:lintDebug --console=plain
```

Use a dedicated AVD for instrumented tests. Never run live Firebase tests as part of routine local validation; they require explicit approval and create disposable cloud records. See [contribution and staging commands](docs/CONTRIBUTING.md). Internal coursework and test evidence are kept out of the source-only checkout; do not treat their absence as a requirement to generate new evidence.
