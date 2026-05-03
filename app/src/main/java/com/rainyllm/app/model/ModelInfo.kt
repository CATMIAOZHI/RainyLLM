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
            sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
            description = "均衡性能，推荐大多数手机使用"
        )

        val Gemma4E4B = ModelInfo(
            id = "gemma4-e4b",
            name = "Gemma 4 E4B",
            sizeBytes = 3_919_000_000L, // ~3.65GB
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            mirrorUrl = "https://hf-mirror.com/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sha256 = "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
            description = "更高智能，需要更多内存"
        )

        // ⚡ 未审查/无限制版本（abliterated，已移除拒绝回答的限制）
        val Gemma4E2BUncensored = ModelInfo(
            id = "gemma4-e2b-uncensored",
            name = "Gemma 4 E2B (无限制)",
            sizeBytes = 2_769_000_000L, // ~2.58GB, INT8
            url = "https://huggingface.co/nqd145/Gemma-4-E2B-it-abliterated-litertlm/resolve/main/Gemma-4-E2B-it-abliterated.litertlm",
            mirrorUrl = "https://hf-mirror.com/nqd145/Gemma-4-E2B-it-abliterated-litertlm/resolve/main/Gemma-4-E2B-it-abliterated.litertlm",
            sha256 = "3bb979594d6fd1a958c7f9c5dbfbdf9d1312ee3eaae009d298b6f19194392953",
            description = "未审查版本，无内容限制（基于 huihui-ai ablirated）"
        )

        val Gemma4E4BUncensored = ModelInfo(
            id = "gemma4-e4b-uncensored",
            name = "Gemma 4 E4B (无限制)",
            sizeBytes = 3_919_000_000L, // ~3.65GB
            url = "https://huggingface.co/typomonster/supergemma4-e4b-abliterated-litert-lm/resolve/main/supergemma4-e4b-abliterated.litertlm",
            mirrorUrl = "https://hf-mirror.com/typomonster/supergemma4-e4b-abliterated-litert-lm/resolve/main/supergemma4-e4b-abliterated.litertlm",
            sha256 = "83399794f0ad10166c0034451e06b9cdb120590eab3665863ff20e443b3f9750",
            description = "未审查版本，无内容限制（基于 Jiunsong ablirated）"
        )

        /**
         * 预置模型列表
         */
        val PRESET_MODELS = listOf(
            Gemma4E2B,
            Gemma4E4B,
            Gemma4E2BUncensored,
            Gemma4E4BUncensored
        )
    }
}