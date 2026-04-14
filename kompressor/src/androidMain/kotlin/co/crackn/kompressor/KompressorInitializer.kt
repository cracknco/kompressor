package co.crackn.kompressor

import android.content.Context
import androidx.startup.Initializer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object KompressorContext {
    private val _appContext = AtomicReference<Context?>(null)
    private val _initCount = AtomicInteger(0)

    val appContext: Context
        get() = checkNotNull(_appContext.get()) {
            "Kompressor is not initialized. Ensure App Startup is not disabled for KompressorInitializer."
        }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        if (_appContext.compareAndSet(null, appCtx)) {
            _initCount.incrementAndGet()
        }
    }

    internal val initCount: Int get() = _initCount.get()

    internal fun resetForTest() {
        _appContext.set(null)
        _initCount.set(0)
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
