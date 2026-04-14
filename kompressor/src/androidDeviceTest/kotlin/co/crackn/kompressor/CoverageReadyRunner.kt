package co.crackn.kompressor

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * [AndroidJUnitRunner] that pre-creates the app's external files directory before any test
 * runs so JaCoCo coverage dump can actually write the `.ec` file.
 *
 * Why this is needed: the test APK targets SDK 36. Under scoped storage, bare writes to
 * `/sdcard/coverage.ec` fail `EACCES` (no `WRITE_EXTERNAL_STORAGE` permission). `/data/local/tmp`
 * is shell-user only and also `EACCES` for app processes. The only path that is
 * (a) writable by the app without permissions and (b) pullable by FTL via `--directories-to-pull`
 * is the app-scoped external files directory (`/sdcard/Android/data/<pkg>/files/`).
 *
 * The catch: for an instrumentation-only APK install the system *does not* auto-create that
 * directory, so `FileOutputStream` in `InstrumentationCoverageReporter` blows up with ENOENT
 * (parent missing) before any coverage bytes are written. Calling `getExternalFilesDir(null)`
 * once — here in `onCreate` before any tests execute — has the side effect of creating the
 * directory so the later coverage dump has somewhere to land.
 *
 * Wired via `--test-runner-class=co.crackn.kompressor.CoverageReadyRunner` in the FTL
 * invocation (see `.github/workflows/pr.yml`); local `connectedAndroidDeviceTest` continues to
 * use the default `AndroidJUnitRunner` because it doesn't collect coverage the same way.
 */
class CoverageReadyRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // `getExternalFilesDir(null)` returns /sdcard/Android/data/<pkg>/files/ and creates
        // the directory path on first access. Both the test APK context and the target (for
        // library self-tests they're the same process) are touched so neither surprises us.
        context?.getExternalFilesDir(null)
        targetContext?.getExternalFilesDir(null)
    }
}