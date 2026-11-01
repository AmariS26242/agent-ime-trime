/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * Agent backend connection settings, editable from the panel ("设置").
 *
 * Defaults point at the PC backend on the local network; users whose PC IP
 * changes can re-point it from the panel without rebuilding the APK.
 */
object AgentConfig {
    private const val PREF = "agentime_config"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_TOKEN = "token"

    /** PC 局域网地址（2026-11-01 本机 WLAN 为 172.24.217.222）。IP 变了在面板「设置」里改。 */
    const val DEFAULT_ENDPOINT = "http://172.24.217.222:8787/v1/chat/stream"

    /** 与后端 CLIENT_TOKEN 一致（backend/run-backend.ps1 默认值）。 */
    const val DEFAULT_TOKEN = "dev-agentime-token"

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(lock) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun endpoint(): String =
        prefs?.getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

    fun token(): String =
        prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN

    fun save(endpoint: String, token: String) {
        prefs?.edit()
            ?.putString(KEY_ENDPOINT, endpoint.trim())
            ?.putString(KEY_TOKEN, token.trim())
            ?.apply()
    }

    fun reset() {
        prefs?.edit()?.remove(KEY_ENDPOINT)?.remove(KEY_TOKEN)?.apply()
    }
}
