package com.rainyllm.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用偏好设置（DataStore 持久化）
 */
class AppPreferences(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("rainyllm_settings")

        val KEY_PORT = intPreferencesKey("server_port")
        val KEY_BACKEND = stringPreferencesKey("inference_backend")
        val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        val KEY_TOP_K = intPreferencesKey("top_k")
        val KEY_TOP_P = floatPreferencesKey("top_p")
        val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        val KEY_IDLE_TIMEOUT_MIN = intPreferencesKey("idle_timeout_min")
        val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive")
    }

    val serverPort: Flow<Int> = context.dataStore.data.map { it[KEY_PORT] ?: 8080 }
    val backend: Flow<String> = context.dataStore.data.map { it[KEY_BACKEND] ?: "cpu" }
    val temperature: Flow<Float> = context.dataStore.data.map { it[KEY_TEMPERATURE] ?: 0.7f }
    val topK: Flow<Int> = context.dataStore.data.map { it[KEY_TOP_K] ?: 40 }
    val topP: Flow<Float> = context.dataStore.data.map { it[KEY_TOP_P] ?: 0.95f }
    val maxTokens: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_TOKENS] ?: 4096 }
    val idleTimeoutMin: Flow<Int> = context.dataStore.data.map { it[KEY_IDLE_TIMEOUT_MIN] ?: 5 }
    val selectedModel: Flow<String> = context.dataStore.data.map { it[KEY_SELECTED_MODEL] ?: "gemma4-e2b" }
    val systemPrompt: Flow<String> = context.dataStore.data.map {
        it[KEY_SYSTEM_PROMPT] ?: "你是雨晴喵，是一个喜欢卖萌的小猫ai助手"
    }
    val keepAlive: Flow<Boolean> = context.dataStore.data.map { it[KEY_KEEP_ALIVE] ?: true }

    suspend fun setServerPort(port: Int) { context.dataStore.edit { it[KEY_PORT] = port } }
    suspend fun setBackend(backend: String) { context.dataStore.edit { it[KEY_BACKEND] = backend } }
    suspend fun setTemperature(temp: Float) { context.dataStore.edit { it[KEY_TEMPERATURE] = temp } }
    suspend fun setTopK(k: Int) { context.dataStore.edit { it[KEY_TOP_K] = k } }
    suspend fun setTopP(p: Float) { context.dataStore.edit { it[KEY_TOP_P] = p } }
    suspend fun setMaxTokens(tokens: Int) { context.dataStore.edit { it[KEY_MAX_TOKENS] = tokens } }
    suspend fun setIdleTimeoutMin(min: Int) { context.dataStore.edit { it[KEY_IDLE_TIMEOUT_MIN] = min } }
    suspend fun setSelectedModel(model: String) { context.dataStore.edit { it[KEY_SELECTED_MODEL] = model } }
    suspend fun setSystemPrompt(prompt: String) { context.dataStore.edit { it[KEY_SYSTEM_PROMPT] = prompt } }
    suspend fun setKeepAlive(on: Boolean) { context.dataStore.edit { it[KEY_KEEP_ALIVE] = on } }
}