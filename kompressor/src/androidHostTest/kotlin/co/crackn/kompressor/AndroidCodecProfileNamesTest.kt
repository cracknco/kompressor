package co.crackn.kompressor

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AndroidCodecProfileNamesTest {

    @Test
    fun avcProfileNameMapsKnownConstants() {
        avcProfileName(CodecProfileLevel.AVCProfileBaseline) shouldBe "Baseline"
        avcProfileName(CodecProfileLevel.AVCProfileMain) shouldBe "Main"
        avcProfileName(CodecProfileLevel.AVCProfileHigh) shouldBe "High"
        avcProfileName(CodecProfileLevel.AVCProfileHigh10) shouldBe "High 10"
        avcProfileName(CodecProfileLevel.AVCProfileHigh422) shouldBe "High 4:2:2"
        avcProfileName(CodecProfileLevel.AVCProfileHigh444) shouldBe "High 4:4:4"
        avcProfileName(CodecProfileLevel.AVCProfileConstrainedBaseline) shouldBe "Constrained Baseline"
        avcProfileName(CodecProfileLevel.AVCProfileConstrainedHigh) shouldBe "Constrained High"
        avcProfileName(CodecProfileLevel.AVCProfileExtended) shouldBe "Extended"
    }

    @Test
    fun avcProfileNameReturnsEmptyForUnknown() {
        avcProfileName(Int.MAX_VALUE) shouldBe ""
    }

    @Test
    fun hevcProfileNameMapsKnownConstants() {
        hevcProfileName(CodecProfileLevel.HEVCProfileMain) shouldBe "Main"
        hevcProfileName(CodecProfileLevel.HEVCProfileMain10) shouldBe "Main 10"
        hevcProfileName(CodecProfileLevel.HEVCProfileMainStill) shouldBe "Main Still"
        hevcProfileName(CodecProfileLevel.HEVCProfileMain10HDR10) shouldBe "Main 10 HDR10"
        hevcProfileName(CodecProfileLevel.HEVCProfileMain10HDR10Plus) shouldBe "Main 10 HDR10+"
    }

    @Test
    fun hevcProfileNameReturnsEmptyForUnknown() {
        hevcProfileName(-1) shouldBe ""
    }

    @Test
    fun vp9ProfileNameCoversAllHDRAndPlainProfiles() {
        vp9ProfileName(CodecProfileLevel.VP9Profile0) shouldBe "Profile 0"
        vp9ProfileName(CodecProfileLevel.VP9Profile1) shouldBe "Profile 1"
        vp9ProfileName(CodecProfileLevel.VP9Profile2) shouldBe "Profile 2 (10-bit)"
        vp9ProfileName(CodecProfileLevel.VP9Profile3) shouldBe "Profile 3 (10-bit 4:4:4)"
        vp9ProfileName(CodecProfileLevel.VP9Profile2HDR) shouldBe "Profile 2 HDR"
        vp9ProfileName(CodecProfileLevel.VP9Profile3HDR) shouldBe "Profile 3 HDR"
        vp9ProfileName(CodecProfileLevel.VP9Profile2HDR10Plus) shouldBe "Profile 2 HDR10+"
        vp9ProfileName(CodecProfileLevel.VP9Profile3HDR10Plus) shouldBe "Profile 3 HDR10+"
        vp9ProfileName(-99) shouldBe ""
    }

    @Test
    fun av1ProfileNameCoversAllFourProfiles() {
        av1ProfileName(CodecProfileLevel.AV1ProfileMain8) shouldBe "Main 8-bit"
        av1ProfileName(CodecProfileLevel.AV1ProfileMain10) shouldBe "Main 10-bit"
        av1ProfileName(CodecProfileLevel.AV1ProfileMain10HDR10) shouldBe "Main 10 HDR10"
        av1ProfileName(CodecProfileLevel.AV1ProfileMain10HDR10Plus) shouldBe "Main 10 HDR10+"
        av1ProfileName(0) shouldBe ""
    }

    @Test
    fun humanProfileNameDispatchesByMime() {
        humanProfileName(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh) shouldBe "High"
        humanProfileName(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10) shouldBe "Main 10"
        humanProfileName(MediaFormat.MIMETYPE_VIDEO_VP9, CodecProfileLevel.VP9Profile0) shouldBe "Profile 0"
        humanProfileName(MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain10) shouldBe "Main 10-bit"
        humanProfileName("audio/mp4a-latm", 1) shouldBe ""
    }

    @Test
    fun isTenBitProfileTrueForThe10bitAndHigherbitcapableProfilesOfEachMime() {
        // HEVC
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10HDR10) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10HDR10Plus) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain) shouldBe false
        // AVC — High 10 + High 4:2:2 + High 4:4:4 all support 10-bit
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh10) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh422) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh444) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh) shouldBe false
        // VP9 — all HDR-capable profiles (including HDR10Plus variants)
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_VP9, CodecProfileLevel.VP9Profile2) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_VP9, CodecProfileLevel.VP9Profile3HDR) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_VP9, CodecProfileLevel.VP9Profile2HDR10Plus) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_VP9, CodecProfileLevel.VP9Profile3HDR10Plus) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_VP9, CodecProfileLevel.VP9Profile0) shouldBe false
        // AV1
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain10) shouldBe true
        isTenBitProfile(MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain8) shouldBe false
        // Unknown mime
        isTenBitProfile("audio/mp4a-latm", 1) shouldBe false
    }
}
