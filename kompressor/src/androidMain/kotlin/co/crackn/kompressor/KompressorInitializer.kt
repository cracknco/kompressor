package co.crackn.kompressor

import android.content.Context
import androidx.startup.Initializer

internal object KompressorContext {
    private lateinit var _appContext: Context

    val appContext: Context
        get() {
            check(::_appContext.isInitialized) {
                "Kompressor is not initialized. Ensure App Startup is not disabled for KompressorInitializer."
            }
            return _appContext
        }

    fun init(context: Context) {
        _appContext = context.applicationContext
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
