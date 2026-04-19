/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample

import androidx.compose.ui.window.ComposeUIViewController
import co.crackn.kompressor.sample.di.createAppComponent

private val appComponent = createAppComponent()

fun MainViewController() = ComposeUIViewController { App(appComponent) }
