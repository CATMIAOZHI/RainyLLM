package com.rainyllm.app.engine

import kotlin.math.roundToInt

/**
 * Token 计数估算器
 * 基于字符/词数的启发式估算（非精确分词，用于统计和配额控制）
 *
 * 针对 Gemma (SentencePiece Unigram) 校准：
 *   - 中文 ≈ 2.0 字符/token
 *   - 日韩文 ≈ 2.0 字符/token
 *   - 英文 ≈ 3.5 字符/token
 *   - 数字序列 ≈ 2.5 字符/token
 *   - 换行 ≈ 1 token/个
 */
object TokenEstimator {

    /** 英文/ASCII 平均每 token 字符数 */
    private const val ASCII_CHARS_PER_TOKEN = 3.5

    /** CJK 字符平均每 token 字符数 */
    private const val CJK_CHARS_PER_TOKEN = 2.0

    /** 数字序列平均每 token 字符数 */
    private const val DIGIT_CHARS_PER_TOKEN = 2.5

    /** 匹配 CJK 统一汉字 + 扩展区 + 兼容区 */
    private val CJK_PATTERN = Regex(
        "[\\u4e00-\\u9fff" +   // CJK 统一汉字
        "\\u3400-\\u4dbf" +    // CJK 扩展 A
        "\\uf900-\\ufaff" +    // CJK 兼容汉字
        "\\u2f800-\\u2fa1f" +  // CJK 兼容汉字补充
        "\\u3000-\\u303f" +    // CJK 标点
        "\\uff00-\\uffef" +    // 全角字符
        "\\u2e80-\\u2eff" +    // CJK 部首补充
        "\\u31c0-\\u31ef" +    // CJK 笔画
        "\\u3040-\\u309f" +    // 日文平假名
        "\\u30a0-\\u30ff" +    // 日文片假名
        "\\uac00-\\ud7af" +    // 韩文音节
        "\\u1100-\\u11ff" +    // 韩文辅音
        "\\u3130-\\u318f]"     // 韩文兼容字母
    )

    /** 匹配换行符 */
    private val NEWLINE_PATTERN = Regex("\\n")

    /** 匹配数字字符 */
    private val DIGIT_PATTERN = Regex("\\d")

    fun estimatePromptTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return estimateTokens(text)
    }

    fun estimateCompletionTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return estimateTokens(text)
    }

    private fun estimateTokens(text: String): Int {
        val totalLen = text.length

        // 1. 统计 CJK 字符数
        val cjkChars = CJK_PATTERN.findAll(text).count()

        // 2. 统计数字字符数
        val digitChars = DIGIT_PATTERN.findAll(text).count()

        // 3. 统计换行符数（每个 ≈ 1 token）
        val newlineCount = NEWLINE_PATTERN.findAll(text).count()

        // 4. 剩余字符 = ASCII 字母/标点/空格等
        val remaining = totalLen - cjkChars - digitChars

        // 5. 分别计算 token 数
        val cjkTokens = cjkChars / CJK_CHARS_PER_TOKEN
        val digitTokens = digitChars / DIGIT_CHARS_PER_TOKEN
        val asciiTokens = remaining / ASCII_CHARS_PER_TOKEN
        val newlineTokens = newlineCount.toDouble()  // 每个换行 ≈ 1 token

        val estimated = (cjkTokens + digitTokens + asciiTokens + newlineTokens).roundToInt()
        return estimated.coerceAtLeast(1)
    }

    fun estimateSimple(text: String, charsPerToken: Double = 3.0): Int {
        if (text.isEmpty()) return 0
        return (text.length / charsPerToken).roundToInt().coerceAtLeast(1)
    }
}