package com.rainyllm.app.engine

import kotlin.math.roundToInt

/**
 * Token 计数估算器
 *
 * 两种工作模式：
 * 1. **实际计数**（优先）：当引擎启用了 benchmark 模式，通过
 *    [Conversation.getBenchmarkInfo] 获取真实 tokenizer 计数。
 * 2. **启发式估算**（回退）：当 benchmark 不可用时使用本文本估算。
 *
 * 多模态 token 规则（Gemma 模型标准）：
 *   - 每张图片 ≈ 256 tokens（标准分辨率，高分辨率可到 1024+）
 *   - 每段音频 ≈ 1 token / 80ms（约 12.5 tokens/秒 @ 16kHz）
 *
 * 文本估算针对 Gemma (SentencePiece Unigram) 校准：
 *   - 中文/日韩文 ≈ 2.0 字符/token
 *   - 英文/ASCII ≈ 3.5 字符/token
 *   - 数字序列 ≈ 2.5 字符/token
 *   - 换行 ≈ 1 token/个
 */
object TokenEstimator {

    // ── 多模态常量 ────────────────────────────────

    /** 每张图片默认 token 数（Gemma std resolution） */
    const val TOKENS_PER_IMAGE = 256

    /** 每秒音频约对应 token 数（16kHz PCM） */
    const val TOKENS_PER_AUDIO_SECOND = 12.5

    /** 每字节音频约对应 token 数（16kHz 16-bit mono PCM = 32000 B/s） */
    const val TOKENS_PER_AUDIO_BYTE = TOKENS_PER_AUDIO_SECOND / 32_000.0

    // ── 文本估算常量 ──────────────────────────────

    private const val ASCII_CHARS_PER_TOKEN = 3.5
    private const val CJK_CHARS_PER_TOKEN = 2.0
    private const val DIGIT_CHARS_PER_TOKEN = 2.5

    private val CJK_PATTERN = Regex(
        "[\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff\\u2f800-\\u2fa1f" +
        "\\u3000-\\u303f\\uff00-\\uffef\\u2e80-\\u2eff\\u31c0-\\u31ef" +
        "\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af\\u1100-\\u11ff\\u3130-\\u318f]"
    )

    private val NEWLINE_PATTERN = Regex("\\n")
    private val DIGIT_PATTERN = Regex("\\d")

    // ── 类型与接口 ────────────────────────────────

    /** Token 使用量数据 */
    data class TokenUsage(
        val promptTokens: Int,
        val completionTokens: Int
    ) {
        val totalTokens: Int get() = promptTokens + completionTokens
        val isEstimated: Boolean get() = false
    }

    /** 带标记的 token 使用量 */
    data class EstimatedTokenUsage(
        val promptTokens: Int,
        val completionTokens: Int
    ) {
        val totalTokens: Int get() = promptTokens + completionTokens
        val isEstimated: Boolean get() = true
    }

    /** 多模态内容摘要（用于 token 估算） */
    data class MultimodalCounts(
        val imageCount: Int = 0,
        val audioBytes: Long = 0
    )

    // ── 估算方法 ──────────────────────────────────

    /**
     * 估算文本的 prompt token 数
     */
    fun estimatePromptTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return estimateTextTokens(text)
    }

    /**
     * 估算补全输出的 token 数
     */
    fun estimateCompletionTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return estimateTextTokens(text)
    }

    /**
     * 估算多模态 prompt token 数
     * @param textPrompt 纯文本部分
     * @param multimodal 多模态内容计数
     */
    fun estimateMultimodalPromptTokens(
        textPrompt: String,
        multimodal: MultimodalCounts
    ): Int {
        val textTokens = if (textPrompt.isNotEmpty()) estimateTextTokens(textPrompt) else 0
        val imageTokens = multimodal.imageCount * TOKENS_PER_IMAGE
        val audioTokens = (multimodal.audioBytes * TOKENS_PER_AUDIO_BYTE).roundToInt()
        return (textTokens + imageTokens + audioTokens).coerceAtLeast(1)
    }

    /**
     * 统计多模态内容
     */
    fun countMultimodal(contents: List<Any>): MultimodalCounts {
        var images = 0
        var audioBytes = 0L
        for (c in contents) {
            when (c) {
                is com.google.ai.edge.litertlm.Content.ImageBytes -> {
                    images++
                }
                is com.google.ai.edge.litertlm.Content.ImageFile -> {
                    images++
                }
                is com.google.ai.edge.litertlm.Content.AudioBytes -> {
                    audioBytes += c.bytes.size.toLong()
                }
                is com.google.ai.edge.litertlm.Content.AudioFile -> {
                    // 无法得知文件大小，用一个保守值
                    audioBytes += 320_000L // ~10 秒
                }
            }
        }
        return MultimodalCounts(images, audioBytes)
    }

    // ── 文本估算核心 ──────────────────────────────

    private fun estimateTextTokens(text: String): Int {
        val totalLen = text.length
        val cjkChars = CJK_PATTERN.findAll(text).count()
        val digitChars = DIGIT_PATTERN.findAll(text).count()
        val newlineCount = NEWLINE_PATTERN.findAll(text).count()
        // 修复：remaining 需排除已单独计数的换行符，避免被 ASCII 分支重复计数
        val remaining = (totalLen - cjkChars - digitChars - newlineCount).coerceAtLeast(0)

        val cjkTokens = cjkChars / CJK_CHARS_PER_TOKEN
        val digitTokens = digitChars / DIGIT_CHARS_PER_TOKEN
        val asciiTokens = remaining / ASCII_CHARS_PER_TOKEN
        val newlineTokens = newlineCount.toDouble()

        return (cjkTokens + digitTokens + asciiTokens + newlineTokens).roundToInt().coerceAtLeast(1)
    }

    /**
     * 简单估算（不区分语言，仅按平均字符/token比例）
     */
    fun estimateSimple(text: String, charsPerToken: Double = 3.0): Int {
        if (text.isEmpty()) return 0
        return (text.length / charsPerToken).roundToInt().coerceAtLeast(1)
    }
}