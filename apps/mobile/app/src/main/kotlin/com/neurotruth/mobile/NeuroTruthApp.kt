package com.neurotruth.mobile

import android.app.Application
import android.content.Context
import com.neurotruth.mobile.core.ProductNoticePolicy
import com.neurotruth.mobile.core.net.ApiEndpoints
import com.neurotruth.mobile.core.net.AuthenticatedApiClient
import com.neurotruth.mobile.data.DeviceNoticeVersionStore
import com.neurotruth.mobile.data.KeystoreRefreshTokenStore
import com.neurotruth.mobile.data.PendingChatStore
import com.neurotruth.mobile.data.UrlConnectionApiTransport
import java.util.Properties

/**
 * Base URL for the backend, read from `assets/server_config.properties`.
 *
 * The app talks only to the NeuroTruth backend; the DGX address is never present here.
 */
object ServerConfig {
    private const val ASSET = "server_config.properties"
    private const val KEY = "api_base_url"
    private const val FALLBACK = "http://127.0.0.1:8000"

    fun baseUrl(context: Context): String {
        val properties = runCatching {
            Properties().apply {
                context.assets.open(ASSET).use(::load)
            }
        }.getOrNull() ?: return FALLBACK
        return properties.getProperty(KEY)?.trim()?.takeIf { it.isNotBlank() } ?: FALLBACK
    }
}

class NeuroTruthApp : Application() {

    val apiClient: AuthenticatedApiClient by lazy {
        AuthenticatedApiClient(
            endpoints = ApiEndpoints(ServerConfig.baseUrl(this)),
            transport = UrlConnectionApiTransport(),
            sessionStore = KeystoreRefreshTokenStore(this),
        )
    }

    val endpoints: ApiEndpoints by lazy { ApiEndpoints(ServerConfig.baseUrl(this)) }

    val noticePolicy: ProductNoticePolicy by lazy {
        ProductNoticePolicy(DeviceNoticeVersionStore(this))
    }

    val pendingChatStore: PendingChatStore by lazy { PendingChatStore(this) }

    companion object {
        fun from(context: Context): NeuroTruthApp =
            context.applicationContext as NeuroTruthApp
    }
}
