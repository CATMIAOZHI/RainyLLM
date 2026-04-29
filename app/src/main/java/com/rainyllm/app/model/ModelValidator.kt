package com.rainyllm.app.model

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 模型文件 SHA256 校验器
 */
object ModelValidator {

    /**
     * 计算文件的 SHA256 哈希
     */
    fun computeSha256(file: File): String? {
        if (!file.exists() || !file.isFile) return null

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 校验模型文件 SHA256 是否匹配
     * @param file 模型文件
     * @param expectedSha256 期望的 SHA256 值（空字符串表示跳过校验）
     * @return 校验结果
     */
    fun validate(file: File, expectedSha256: String): ValidationResult {
        if (expectedSha256.isBlank()) {
            return ValidationResult.Skipped
        }

        val actual = computeSha256(file)
            ?: return ValidationResult.Error("无法计算文件哈希")

        return if (actual.equals(expectedSha256, ignoreCase = true)) {
            ValidationResult.Success
        } else {
            ValidationResult.Mismatch(expected = expectedSha256, actual = actual)
        }
    }
}

/**
 * 校验结果
 */
sealed class ValidationResult {
    data object Success : ValidationResult()
    data object Skipped : ValidationResult()
    data class Error(val message: String) : ValidationResult()
    data class Mismatch(val expected: String, val actual: String) : ValidationResult()
}