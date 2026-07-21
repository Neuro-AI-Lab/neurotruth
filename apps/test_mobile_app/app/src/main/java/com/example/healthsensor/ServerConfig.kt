package com.example.healthsensor

import android.content.Context
import java.util.Properties

data class ServerConfig(
    val sensorPostUrl: String,
    val predictionSseUrl: String
) {
    fun apiBaseUrl(): String {
        val source = sensorPostUrl.ifBlank { predictionSseUrl }
        val parsed = java.net.URL(source)
        return "${parsed.protocol}://${parsed.authority}"
    }

    companion object {
        private const val FILE_NAME = "server_config.properties"

        fun load(context: Context): ServerConfig {
            val properties = Properties()
            runCatching {
                context.assets.open(FILE_NAME).use { input ->
                    properties.load(input)
                }
            }

            val configuredBase = properties.getProperty("api_base_url", "").trim()
            val configuredSensor = properties.getProperty("sensor_post_url", "").trim()
            val configuredPrediction = properties.getProperty("prediction_sse_url", "").trim()
            val base = configuredBase.ifBlank {
                val source = configuredSensor.ifBlank { configuredPrediction }
                val parsed = java.net.URL(source)
                "${parsed.protocol}://${parsed.authority}"
            }.trimEnd('/')
            val endpoints = ApiEndpoints(base)
            return ServerConfig(
                sensorPostUrl = endpoints.sensorWindows,
                predictionSseUrl = endpoints.predictionStream
            )
        }
    }
}
