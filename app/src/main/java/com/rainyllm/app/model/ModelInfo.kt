package com.rainyllm.app.model

/**
 * 模型元数据
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val url: String,
    val mirrorUrl: String = "",
    val sha256: String,
    val format: String = "litertlm",
    val description: String = ""
) {
    val sizeGb: String get() = "%.2f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))

    companion object {
        val Gemma4E2B = ModelInfo(
            id = "gemma4-e2b",
            name = "Gemma 4 E2B",
            sizeBytes = 2_769_000_000L, // ~2.58GB
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            mirrorUrl = "https://hf-mirror.com/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sha256 = "",
            description = "均衡性能，推荐大多数手机使用"
        )

        val Gemma4E4B = ModelInfo(
            id = "gemma4-e4b",
            name = "Gemma 4 E4B",
            sizeBytes = 3_919_000_000L, // ~3.65GB
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            mirrorUrl = "https://hf-mirror.com/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sha256 = "",
            description = "更高智能，需要更多内存"
        )

        /**
         * 预置模型列表
         */
        val PRESET_MODELS = listOf(
            Gemma4E2B,
            Gemma4E4B
        )
    }
}