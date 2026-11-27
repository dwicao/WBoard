package com.wboard.ime.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.wboard.ime.voice.WhisperModelManager.ModelVariant

/**
 * Evaluates local device RAM, CPU cores, and ABI (64-bit vs 32-bit) to recommend
 * the optimal Whisper model tier without collecting or sending any device telemetry.
 */
object DeviceSpecAdvisor {

    data class SpecReport(
        val totalRamGb: Float,
        val cpuCores: Int,
        val is64Bit: Boolean,
        val optimalModel: ModelVariant,
        val maxSupportedModel: ModelVariant
    )

    fun inspect(context: Context): SpecReport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val ramGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        return evaluate(ramGb, cores, is64Bit)
    }

    fun evaluate(totalRamGb: Float, cpuCores: Int, is64Bit: Boolean): SpecReport {
        if (!is64Bit || totalRamGb < 3.5f) {
            return SpecReport(
                totalRamGb = totalRamGb,
                cpuCores = cpuCores,
                is64Bit = is64Bit,
                optimalModel = ModelVariant.TINY_Q5_1,
                maxSupportedModel = ModelVariant.BASE_Q5_1
            )
        }
        if (totalRamGb < 5.5f) {
            return SpecReport(
                totalRamGb = totalRamGb,
                cpuCores = cpuCores,
                is64Bit = true,
                optimalModel = ModelVariant.BASE_Q5_1,
                maxSupportedModel = ModelVariant.SMALL_Q5_1
            )
        }
        if (totalRamGb < 10.5f) {
            return SpecReport(
                totalRamGb = totalRamGb,
                cpuCores = cpuCores,
                is64Bit = true,
                optimalModel = ModelVariant.SMALL_Q5_1,
                maxSupportedModel = ModelVariant.LARGE_V3_TURBO_Q5_0
            )
        }
        // Flagship 11-12+ GB RAM: Small is still optimal for instant daily keyboard latency,
        // while Large-v3 Turbo Q8_0 is supported for maximum Indonesian accuracy.
        return SpecReport(
            totalRamGb = totalRamGb,
            cpuCores = cpuCores,
            is64Bit = true,
            optimalModel = ModelVariant.SMALL_Q5_1,
            maxSupportedModel = ModelVariant.LARGE_V3_TURBO_Q8_0
        )
    }
}
