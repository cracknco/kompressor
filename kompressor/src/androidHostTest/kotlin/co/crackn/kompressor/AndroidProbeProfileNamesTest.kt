package co.crackn.kompressor

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AndroidProbeProfileNamesTest {

    @Test
    fun `hevcProbeProfileName maps known raw ints`() {
        hevcProbeProfileName(HEVC_MAIN) shouldBe "Main"
        hevcProbeProfileName(HEVC_MAIN10) shouldBe "Main 10"
        hevcProbeProfileName(HEVC_MAIN10_HDR10) shouldBe "Main 10 HDR10"
        hevcProbeProfileName(HEVC_MAIN10_HDR10_PLUS) shouldBe "Main 10 HDR10+"
        hevcProbeProfileName(9999) shouldBe null
    }

    @Test
    fun `avcProbeProfileName maps known raw ints`() {
        avcProbeProfileName(AVC_BASELINE) shouldBe "Baseline"
        avcProbeProfileName(AVC_MAIN) shouldBe "Main"
        avcProbeProfileName(AVC_HIGH) shouldBe "High"
        avcProbeProfileName(AVC_HIGH10) shouldBe "High 10"
        avcProbeProfileName(42) shouldBe null
    }

    @Test
    fun `readProbeProfileName falls back to raw int when mime unknown to this table`() {
        readProbeProfileName("video/hevc", HEVC_MAIN10) shouldBe "Main 10"
        readProbeProfileName("video/avc", AVC_HIGH) shouldBe "High"
        readProbeProfileName("video/hevc", 42) shouldBe "Profile 42"
        readProbeProfileName("video/av1", 1) shouldBe "Profile 1"
        readProbeProfileName(null, 1) shouldBe null
        readProbeProfileName("video/avc", null) shouldBe null
    }

    @Test
    fun `isHevc10BitProfile matches the 10-bit family only`() {
        isHevc10BitProfile(HEVC_MAIN10) shouldBe true
        isHevc10BitProfile(HEVC_MAIN10_HDR10) shouldBe true
        isHevc10BitProfile(HEVC_MAIN10_HDR10_PLUS) shouldBe true
        isHevc10BitProfile(HEVC_MAIN) shouldBe false
        isHevc10BitProfile(null) shouldBe false
    }

    @Test
    fun `readProbeBitDepth honours explicit KEY_BIT_DEPTH first`() {
        readProbeBitDepth(mime = "video/hevc", profile = HEVC_MAIN, colorFormat = null, explicitBitDepth = 10) shouldBe 10
    }

    @Test
    fun `readProbeBitDepth picks TEN_BIT from YUVP010 color format`() {
        readProbeBitDepth(mime = "video/avc", profile = null, colorFormat = 54, explicitBitDepth = null) shouldBe 10
    }

    @Test
    fun `readProbeBitDepth picks TEN_BIT from HEVC 10-bit profile family`() {
        readProbeBitDepth("video/hevc", HEVC_MAIN10, null, null) shouldBe 10
        readProbeBitDepth("video/hevc", HEVC_MAIN10_HDR10, null, null) shouldBe 10
    }

    @Test
    fun `readProbeBitDepth picks TEN_BIT from AVC High 10 profile`() {
        readProbeBitDepth("video/avc", AVC_HIGH10, null, null) shouldBe 10
    }

    @Test
    fun `readProbeBitDepth returns EIGHT_BIT for HEVC Main`() {
        readProbeBitDepth("video/hevc", HEVC_MAIN, null, null) shouldBe 8
    }

    @Test
    fun `readProbeBitDepth returns EIGHT_BIT for any known AVC profile that is not High 10`() {
        readProbeBitDepth("video/avc", AVC_MAIN, null, null) shouldBe 8
        readProbeBitDepth("video/avc", AVC_HIGH, null, null) shouldBe 8
    }

    @Test
    fun `readProbeBitDepth returns null when nothing is known`() {
        readProbeBitDepth(null, null, null, null) shouldBe null
        readProbeBitDepth("video/vp9", 1, null, null) shouldBe null
    }

    @Test
    fun `isProbeHdrFormat detects ST2084 and HLG transfers`() {
        isProbeHdrFormat(colorTransfer = 6, colorStandard = null, bitDepth = null) shouldBe true
        isProbeHdrFormat(colorTransfer = 7, colorStandard = null, bitDepth = null) shouldBe true
        isProbeHdrFormat(colorTransfer = 3, colorStandard = null, bitDepth = null) shouldBe false
    }

    @Test
    fun `isProbeHdrFormat uses BT2020 plus 10-bit as a proxy when transfer is missing`() {
        isProbeHdrFormat(colorTransfer = null, colorStandard = 6, bitDepth = 10) shouldBe true
        isProbeHdrFormat(colorTransfer = null, colorStandard = 6, bitDepth = 8) shouldBe false
        isProbeHdrFormat(colorTransfer = null, colorStandard = 1, bitDepth = 10) shouldBe false
    }

    private companion object {
        const val HEVC_MAIN = 1
        const val HEVC_MAIN10 = 2
        const val HEVC_MAIN10_HDR10 = 4096
        const val HEVC_MAIN10_HDR10_PLUS = 8192
        const val AVC_BASELINE = 1
        const val AVC_MAIN = 2
        const val AVC_HIGH = 8
        const val AVC_HIGH10 = 16
    }
}
