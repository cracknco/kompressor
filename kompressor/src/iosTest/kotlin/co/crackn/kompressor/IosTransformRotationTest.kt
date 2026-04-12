package co.crackn.kompressor

import kotlin.test.Test
import kotlin.test.assertEquals

class IosTransformRotationTest {

    @Test
    fun identity_matrix_is_0_degrees() {
        assertEquals(0, computeRotationDegrees(a = 1.0, b = 0.0))
    }

    @Test
    fun `90 degree rotation matrix yields 90`() {
        // cos(90°) = 0, sin(90°) = 1
        assertEquals(90, computeRotationDegrees(a = 0.0, b = 1.0))
    }

    @Test
    fun `180 degree rotation matrix yields 180`() {
        assertEquals(180, computeRotationDegrees(a = -1.0, b = 0.0))
    }

    @Test
    fun `270 degree rotation matrix normalises from negative`() {
        // atan2(-1, 0) = -90° → normalised to 270°
        assertEquals(270, computeRotationDegrees(a = 0.0, b = -1.0))
    }
}
