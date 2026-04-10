package co.crackn.kompressor.sample

import androidx.compose.ui.window.ComposeUIViewController
import co.crackn.kompressor.sample.di.createAppComponent

private val appComponent = createAppComponent()

fun MainViewController() = ComposeUIViewController { App(appComponent) }
