package com.rainyllm.app.model

import org.junit.Test
import org.junit.Assert.*

/**
 * ModelInfo 单元测试
 *
 * 覆盖范围：
 * - sizeGb 格式化
 * - 预置模型列表完整性
 * - 模型 ID 唯一性
 * - URL 格式有效性
 * - SHA256 长度合法性
 */
class ModelInfoTest {

    @Test
    fun sizeGb_formatsCorrectly() {
        val model = ModelInfo(
            id = "test",
            name = "Test",
            sizeBytes = 1_073_741_824L, // exactly 1 GB
            url = "https://example.com/model.litertlm",
            sha256 = "a".repeat(64)
        )
        assertEquals("1.00 GB", model.sizeGb)
    }

    @Test
    fun sizeGb_formatsLargeSize() {
        val model = ModelInfo(
            id = "test",
            name = "Test",
            sizeBytes = 2_769_000_000L, // ~2.58 GB
            url = "https://example.com/model.litertlm",
            sha256 = "a".repeat(64)
        )
        assertTrue("sizeGb 应包含 GB", model.sizeGb.contains("GB"))
        assertTrue("sizeGb 应约为 2.58", model.sizeGb.startsWith("2.5"))
    }

    @Test
    fun presetModels_notEmpty() {
        assertTrue("预置模型列表不应为空", ModelInfo.PRESET_MODELS.isNotEmpty())
        assertEquals(4, ModelInfo.PRESET_MODELS.size)
    }

    @Test
    fun presetModelIds_areUnique() {
        val ids = ModelInfo.PRESET_MODELS.map { it.id }
        assertEquals("预置模型 ID 应唯一", ids.size, ids.toSet().size)
    }

    @Test
    fun presetModelNames_areUnique() {
        val names = ModelInfo.PRESET_MODELS.map { it.name }
        assertEquals("预置模型名称应唯一", names.size, names.toSet().size)
    }

    @Test
    fun presetModelUrls_areValidHuggingFaceUrls() {
        for (model in ModelInfo.PRESET_MODELS) {
            assertTrue(
                "${model.id} URL 应以 huggingface.co 开头",
                model.url.startsWith("https://huggingface.co/")
            )
            assertTrue(
                "${model.id} URL 应以 .litertlm 结尾",
                model.url.endsWith(".litertlm")
            )
        }
    }

    @Test
    fun presetModelMirrorUrls_areValid() {
        for (model in ModelInfo.PRESET_MODELS) {
            assertTrue(
                "${model.id} mirrorUrl 应以 hf-mirror.com 开头",
                model.mirrorUrl.startsWith("https://hf-mirror.com/")
            )
        }
    }

    @Test
    fun presetModelSha256_areValidHex() {
        for (model in ModelInfo.PRESET_MODELS) {
            assertEquals(
                "${model.id} SHA256 应为 64 位十六进制",
                64,
                model.sha256.length
            )
            assertTrue(
                "${model.id} SHA256 应只含十六进制字符",
                model.sha256.all { it in "0123456789abcdef" }
            )
        }
    }

    @Test
    fun presetModelIds_matchExpectedPattern() {
        val expectedIds = setOf("gemma4-e2b", "gemma4-e4b", "gemma4-e2b-uncensored", "gemma4-e4b-uncensored")
        val actualIds = ModelInfo.PRESET_MODELS.map { it.id }.toSet()
        assertEquals(expectedIds, actualIds)
    }

    @Test
    fun presetModelSizes_areReasonable() {
        for (model in ModelInfo.PRESET_MODELS) {
            assertTrue(
                "${model.id} 模型大小应 > 1GB",
                model.sizeBytes > 1_000_000_000L
            )
            assertTrue(
                "${model.id} 模型大小应 < 10GB",
                model.sizeBytes < 10_000_000_000L
            )
        }
    }
}