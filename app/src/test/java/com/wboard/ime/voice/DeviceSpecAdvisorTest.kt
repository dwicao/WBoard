package com.wboard.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSpecAdvisorTest {

    @Test
    fun recommendsTinyOnLowRamOr32Bit() {
        val low = DeviceSpecAdvisor.evaluate(totalRamGb = 3.0f, cpuCores = 4, is64Bit = true)
        assertEquals(WhisperModelManager.ModelVariant.TINY_Q5_1, low.optimalModel)
        assertEquals(WhisperModelManager.ModelVariant.BASE_Q5_1, low.maxSupportedModel)

        val bit32 = DeviceSpecAdvisor.evaluate(totalRamGb = 6.0f, cpuCores = 8, is64Bit = false)
        assertEquals(WhisperModelManager.ModelVariant.TINY_Q5_1, bit32.optimalModel)
    }

    @Test
    fun recommendsBaseOn4Gb() {
        val mid = DeviceSpecAdvisor.evaluate(totalRamGb = 4.0f, cpuCores = 8, is64Bit = true)
        assertEquals(WhisperModelManager.ModelVariant.BASE_Q5_1, mid.optimalModel)
        assertEquals(WhisperModelManager.ModelVariant.SMALL_Q5_1, mid.maxSupportedModel)
    }

    @Test
    fun recommendsSmallAsOptimalOn6To12Gb() {
        val sixGb = DeviceSpecAdvisor.evaluate(totalRamGb = 6.0f, cpuCores = 8, is64Bit = true)
        assertEquals(WhisperModelManager.ModelVariant.SMALL_Q5_1, sixGb.optimalModel)
        assertEquals(WhisperModelManager.ModelVariant.LARGE_V3_TURBO_Q5_0, sixGb.maxSupportedModel)

        val twelveGb = DeviceSpecAdvisor.evaluate(totalRamGb = 12.0f, cpuCores = 8, is64Bit = true)
        assertEquals(WhisperModelManager.ModelVariant.SMALL_Q5_1, twelveGb.optimalModel)
        assertEquals(WhisperModelManager.ModelVariant.LARGE_V3_TURBO_Q8_0, twelveGb.maxSupportedModel)
    }
}
