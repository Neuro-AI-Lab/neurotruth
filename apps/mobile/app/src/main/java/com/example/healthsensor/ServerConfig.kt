package com.example.healthsensor

import android.content.Context
import java.util.Properties

data class ServerConfig(
    val sensorPostUrl: String,
    val predictionSseUrl: String
) {
    companion object {
        private const val FILE_NAME = "server_config.properties"

        fun load(context: Context): ServerConfig {
            val properties = Properties()
            runCatching {
                context.assets.open(FILE_NAME).use { input ->
                    properties.load(input)
                }
            }

            return ServerConfig(
                sensorPostUrl = properties.getProperty("sensor_post_url", "").trim(),
                predictionSseUrl = properties.getProperty("prediction_sse_url", "").trim()
            )
        }
    }
}
