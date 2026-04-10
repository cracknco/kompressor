package co.crackn.kompressor.sample.di

import co.crackn.kompressor.Kompressor
import co.crackn.kompressor.createKompressor
import co.crackn.kompressor.sample.image.ImageCompressViewModel
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.KmpComponentCreate
import me.tatarka.inject.annotations.Provides

@AppScope
@Component
abstract class AppComponent {

    abstract val imageCompressViewModelFactory: () -> ImageCompressViewModel

    @AppScope
    @Provides
    protected fun provideKompressor(): Kompressor = createKompressor()
}

@KmpComponentCreate
expect fun createAppComponent(): AppComponent
