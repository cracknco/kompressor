package co.crackn.kompressor

import android.content.Context
import androidx.startup.Initializer

internal object KompressorContext {
    // Nullable backing rather than `lateinit` so [resetForTest] can clear it between tests
    // exercising the uninitialized → access failure path. Production code never calls
    // [resetForTest]; App Startup invokes [init] exactly once per process.
    @Volatile
    private var _appContext: Context? = null

    val appContext: Context
        get() = checkNotNull(_appContext) {
            "Kompressor is not initialized. Ensure App Startup is not disabled for KompressorInitializer."
        }

    fun init(context: Context) {
        _appContext = context.applicationContext
    }

    /** Test-only hook to reset the singleton between tests. Do not call from production code. */
    internal fun resetForTest() {
        _appContext = null
    }
}

/**
 * AndroidX App Startup initializer that captures the application [Context]
 * so that [createKompressor] works without requiring an explicit Context parameter.
 *
 * This class is referenced in the library's `AndroidManifest.xml` and must remain public.
 */
class KompressorInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        KompressorContext.init(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
