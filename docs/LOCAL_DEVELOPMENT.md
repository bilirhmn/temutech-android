# Local development notes

For prerequisites, setup, build, launch, and complete shutdown, follow the [README](../README.md). Use a development AVD for manual work and a separate AVD for instrumentation; do not install an APK on the team's baseline AVD without authorization.

## Renderer and data checks

- The default debug build uses MapLibre Vulkan. To compare renderers on an approved AVD, add `-PcehMapRenderer=opengl` to `:app:assembleDebug`. The output APK path is shared; save a separate local copy before changing the renderer if you need both variants. The OpenGL build does not guarantee equivalent labels or pins on an emulator. API 26 intentionally bypasses the native map and offers a location list and return to the editor.
- Dicoding import uses one page (`active=-1&limit=20`) without a key. Explicit schedule offsets are preserved when interpreting timestamps; otherwise the input is treated as Asia/Jakarta. City and online filters use the cache. Only local, in-person events with valid coordinates qualify for map pins.
- To check offline behavior, cache events first, use airplane mode with Wi-Fi off on your development AVD, force-stop the app, reopen it and check **Tersimpan** or event details. Restore the AVD's previous network settings afterward. A cached catalog does not prove a new import succeeded.

## Instrumented repository tests

On an approved, dedicated test AVD only, build both APKs and install the pair from the same build and renderer. Confirm the target has no data you rely on and select its verified serial. Use a targeted runner command rather than a connected Gradle task that may uninstall the target APK.

```powershell
.\gradlew.bat --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
$testSerial = Read-Host 'Dedicated test emulator serial'
& $adb -s $testSerial emu avd name
# Continue only if this reports the intended dedicated test AVD.
& $adb -s $testSerial install --no-streaming -r .\app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'App APK install failed' }
& $adb -s $testSerial install --no-streaming -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed' }
& $adb -s $testSerial shell am instrument -w -e class id.ac.ceh.data.RepositoryTest id.ac.ceh.test/androidx.test.runner.AndroidJUnitRunner
```

Read the instrumentation summary for failures. An emulator shell command returning is not by itself evidence that tests passed. `CloudAccountLiveTest` and `tools/verify-ceh-cloud-live.mjs` are **not** part of the command above. Keep startup CSVs and runtime logs out of the public checkout unless you have separately approved, sanitized evidence.
