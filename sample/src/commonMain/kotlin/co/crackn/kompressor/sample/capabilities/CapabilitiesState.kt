/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.capabilities

import co.crackn.kompressor.CodecSupport
import co.crackn.kompressor.SourceMediaInfo
import co.crackn.kompressor.Supportability

data class CapabilitiesState(
    val deviceSummary: String = "",
    val videoDecoders: List<CodecSupport> = emptyList(),
    val videoEncoders: List<CodecSupport> = emptyList(),
    val audioDecoders: List<CodecSupport> = emptyList(),
    val audioEncoders: List<CodecSupport> = emptyList(),
    val probeFileName: String? = null,
    val probeInfo: SourceMediaInfo? = null,
    val probeVerdict: Supportability? = null,
    val probeError: String? = null,
    val isProbing: Boolean = false,
)
