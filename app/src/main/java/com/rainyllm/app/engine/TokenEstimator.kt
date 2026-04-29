package com.rainyllm.app.engine

import kotlin.math.roundToInt

/**
 * Token 计数估算器
 * 基于字符/词数的启发式估算（非精确分词，用于统计和配额控制）
 *
 * 估算方法：
 * - 中文：约 1.5 字符/token
 * - 英文：约 4 字符/token（约 0.75 词/token）
 * - 混合：按字符比例加权
 */
object TokenEstimator {

    /** 英文平均每 token 字符数 */
    private const val ENGLISH_CHARS_PER_TOKEN = 4.0

    /** 中文平均每 token 字符数 */
    private const val CHINESE_CHARS_PER_TOKEN = 1.5

    /** 正则：匹配中文字符和中文标点 */
    private val CHINESE_PATTERN = Regex("[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]")

    /**
     * 估算提示词的 token 数量
     * promptTokens = 输入文本的 token 估算值
     */
    fun estimatePromptTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return estimateTokens(text)
    }

    /**
     * 估算补全输出的 token 数量
     * completionTokens = 输出文本的 token 估算值
     */
    fun estimateCompletionTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return estimateTokens(text)
    }

    /**
     * 核心估算方法：混合中英文的 token 数
     */
    private fun estimateTokens(text: String): Int {
        val chineseChars = CHINESE_PATTERN.findAll(text).count()
        val totalChars = text.length
        val englishChars = totalChars - chineseChars

        val chineseTokens = chineseChars / CHINESE_CHARS_PER_TOKEN
        val englishTokens = englishChars / ENGLISH_CHARS_PER_TOKEN

        val estimated = (chineseTokens + englishTokens).roundToInt()
        return estimated.coerceAtLeast(1) // 至少 1 token
    }

    /**
     * 简单估算（不区分中英文，用平均比例）
     * @param charsPerToken 平均每个 token 的字符数（默认 3.0）
     */
    fun estimateSimple(text: String, charsPerToken: Double = 3.0): Int {
        if (text.isEmpty()) return 0
        return (text.length / charsPerToken).roundToInt().coerceAtLeast(1)
    }
}