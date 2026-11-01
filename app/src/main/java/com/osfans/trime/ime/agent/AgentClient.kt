/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.agent

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming client for the local Agent IME backend.
 *
 * The backend (see `project/backend/app.py`) holds the upstream API key and
 * exposes `POST /v1/chat/stream` as text/event-stream. This client never talks
 * to the model provider directly and never logs question/answer text.
 *
 * Callbacks are always delivered on the main thread.
 */
class AgentClient {
    companion object {
        private const val TAG = "AgentClient"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000
    }

    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    private var worker: Thread? = null

    /**
     * Starts a streaming question. Any in-flight request is cancelled first.
     *
     * @param onDelta incremental answer text, delivered on the main thread
     * @param onDone  stream completed normally
     * @param onError human readable error, already localized
     */
    fun ask(
        question: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel()
        cancelled.set(false)
        worker =
            Thread {
                try {
                    runStream(question, onDelta, onDone, onError)
                } catch (t: Throwable) {
                    // Never leak stack traces with user text to the caller UI.
                    Log.w(TAG, "stream failed: ${t.javaClass.simpleName}")
                    if (!cancelled.get()) main.post { onError(describe(t)) }
                }
            }.also {
                it.isDaemon = true
                it.name = "agent-stream"
                it.start()
            }
    }

    /** Aborts the current request: closes the socket, so the backend stops the upstream call. */
    fun cancel() {
        cancelled.set(true)
        try {
            connection?.disconnect()
        } catch (_: Throwable) {
        }
        connection = null
        worker?.interrupt()
        worker = null
    }

    private fun runStream(
        question: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val endpoint = AgentConfig.endpoint()
        val token = AgentConfig.token()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $token")
        }
        connection = conn
        try {
            val payload =
                JSONObject()
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", question)
                            },
                        ),
                    ).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val detail = readErrorBody(conn)
                main.post { onError(errorMessageFor(code, detail)) }
                return
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            var finished = false
            reader.use {
                while (true) {
                    if (cancelled.get()) return
                    val line = it.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") {
                        finished = true
                        break
                    }
                    val obj =
                        try {
                            JSONObject(data)
                        } catch (_: Throwable) {
                            continue
                        }
                    if (obj.has("error")) {
                        val what = obj.optString("error", "unknown")
                        val status = if (obj.has("status")) obj.optInt("status") else 0
                        main.post { onError(errorMessageFor(status, what)) }
                        return
                    }
                    val piece =
                        obj.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    if (piece.isNotEmpty()) {
                        main.post { onDelta(piece) }
                    }
                }
            }
            if (cancelled.get()) return
            if (finished) main.post { onDone() } else main.post { onDone() }
        } finally {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
            if (connection === conn) connection = null
        }
    }

    private fun readErrorBody(conn: HttpURLConnection): String =
        try {
            conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
        } catch (_: Throwable) {
            ""
        }

    private fun describe(t: Throwable): String =
        when (t) {
            is java.net.UnknownHostException -> "连不上后端（地址或网络不通）"
            is java.net.ConnectException -> "后端没有启动，或地址端口不对"
            is java.net.SocketTimeoutException -> "请求超时，稍后再试"
            else -> "请求失败（${t.javaClass.simpleName}）"
        }

    /** Maps HTTP status codes to short, non-technical messages for the panel. */
    private fun errorMessageFor(status: Int, detail: String): String {
        val e = detail.take(120)
        return when (status) {
            401 -> "客户端令牌无效（CLIENT_TOKEN 不匹配）"
            429 -> "今天的额度已用完，明天再试"
            500 -> "后端未配置上游 Key"
            502 -> "上游模型报错，稍后再试"
            504 -> "上游超时，稍后再试"
            0 -> if (e.contains("upstream")) "上游模型报错，稍后再试" else "请求失败，稍后再试"
            else -> "请求失败（HTTP $status）"
        }
    }
}
