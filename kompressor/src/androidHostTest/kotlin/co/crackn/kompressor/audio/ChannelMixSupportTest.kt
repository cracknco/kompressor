package co.crackn.kompressor.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * Host-side coverage for [checkChannelMixSupported] — the upfront rejection of (input, target)
 * channel pairs the mixer can't satisfy (primarily upmix attempts and 7-channel inputs into
 * outputs that have no matrix). Pure function so the regression gate runs on every PR host CI
 * job without a multichannel device fixture.
 */
class ChannelMixSupportTest {

    @Test
    fun monoToStereo_isAccepted() {
        checkChannelMixSupported(inputChannels = 1, outputChannels = 2)
    }

    @Test
    fun stereoToMono_isAccepted() {
        checkChannelMixSupported(inputChannels = 2, outputChannels = 1)
    }

    @Test
    fun fiveOneToStereo_isAccepted() {
        checkChannelMixSupported(inputChannels = 6, outputChannels = 2)
    }

    @Test
    fun sevenOneToStereo_isAccepted() {
        checkChannelMixSupported(inputChannels = 8, outputChannels = 2)
    }

    @Test
    fun sevenOneToFiveOne_isAccepted() {
        checkChannelMixSupported(inputChannels = 8, outputChannels = 6)
    }

    @Test
    fun nullInput_isAccepted() {
        checkChannelMixSupported(inputChannels = null, outputChannels = 2)
    }

    @Test
    fun stereoToFiveOne_rejectedAsUpmix() {
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkChannelMixSupported(inputChannels = 2, outputChannels = 6)
        }
        err.details shouldContain "Cannot mix 2-channel input into 6-channel output"
    }

    @Test
    fun monoToFiveOne_rejectedAsUpmix() {
        shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkChannelMixSupported(inputChannels = 1, outputChannels = 6)
        }
    }

    @Test
    fun sevenChannelToStereo_rejectedAsUnsupportedInput() {
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkChannelMixSupported(inputChannels = 7, outputChannels = 2)
        }
        err.details shouldContain "Cannot mix 7-channel input into 2-channel output"
    }

    @Test
    fun fiveOneToFiveOne_isAccepted() {
        // Identity passthrough: 6→6 is always supported via the identity matrix even though
        // it's neither a downmix nor an explicit surround matrix.
        checkChannelMixSupported(inputChannels = 6, outputChannels = 6)
    }
}
