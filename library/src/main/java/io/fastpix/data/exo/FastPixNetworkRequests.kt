package io.fastpix.data.exo

import android.net.Uri
import android.os.AsyncTask
import android.util.Log
import io.fastpix.data.Interfaces.RequestHandler
import io.fastpix.data.Interfaces.RequestHandler.IFPNetworkRequestsCompletion
import io.fastpix.data.request.AnalyticsEventLogger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.math.pow

class FastPixNetworkRequests : RequestHandler {
    interface NetworkRequest {
        val url: URL
        val method: String
        val body: String?
        val headers: Map<String, String>
    }

    private class GetRequest(
        override val url: URL,
        override val headers: Map<String, String> = emptyMap()
    ) : NetworkRequest {
        override val method: String = "GET"
        override val body: String? = null
    }

    private class PostRequest(
        override val url: URL,
        override val body: String = "",
        override val headers: Map<String, String> = emptyMap()
    ) : NetworkRequest {
        override val method: String = "POST"
    }

    private class NetworkTaskRunner(private val callback: IFPNetworkRequestsCompletion?) :
        AsyncTask<NetworkRequest, Void, Boolean>() {
        private var failureCount = 0
        private fun getNextBeaconTime(): Long {
            return if (failureCount == 0) 0 else ((2.0.pow(failureCount - 1) * Math.random()).toLong() + 1) * BASE_TIME_BETWEEN_BEACONS
        }

        override fun doInBackground(vararg params: NetworkRequest): Boolean {
            val request = params[0]
            var successful = false
            while (!successful && failureCount < MAXIMUM_RETRY) {
                try {
                    Thread.sleep(getNextBeaconTime())
                } catch (e: InterruptedException) {
                    Log.e("YourTag", "Error message", e)
                }
                successful = executeHttp(request)
            }
            return successful
        }

        override fun onPostExecute(result: Boolean) {
            callback?.onComplete(result)
        }

        private fun executeHttp(request: NetworkRequest): Boolean {
            var conn: HttpURLConnection? = null
            var stream: InputStream? = null
            var successful = true
            try {
                conn = request.url.openConnection() as HttpURLConnection
                conn.readTimeout = READ_TIMEOUT_MS
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.requestMethod = request.method

                request.headers.forEach { (key, value) ->
                    conn.setRequestProperty(key, value)
                }

                if (request.method == "POST" && request.body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val outputStream: OutputStream = conn.outputStream
                    outputStream.write(request.body?.toByteArray())
                    outputStream.close()
                }
                conn.connect()
                stream = conn.inputStream
            } catch (e: IOException) { // Handles network, file, and stream issues
                AnalyticsEventLogger.d(TAG, "Network or file error: ${e.message}")
                successful = false
                failureCount++
            } finally {
                try {
                    stream?.close()
                    conn?.disconnect()
                } catch (e: IOException) {
                    AnalyticsEventLogger.d(TAG, "Error closing resources: ${e.message}")
                }
            }
            return successful
        }

        companion object {
            private const val READ_TIMEOUT_MS = 20000
            private const val CONNECT_TIMEOUT_MS = 30000
            private const val MAXIMUM_RETRY = 4
            private const val BASE_TIME_BETWEEN_BEACONS = 5000
        }
    }

    private fun getAuthority(propertyKey: String, domain: String): String {
        return if (Pattern.matches(
                "^[a-z0-9]+$",
                propertyKey
            )
        ) "$propertyKey$domain" else "img$domain"
    }

    override fun get(url: URL) {
        try {
            NetworkTaskRunner(null).execute(GetRequest(url))
        } catch (e: IOException) { // Network issues, file access errors
            AnalyticsEventLogger.d(TAG, "Network or file access error: ${e.message}")
        }
    }

    override fun post(url: URL, body: String, headers: Map<String, String>) {
        try {
            NetworkTaskRunner(null).execute(PostRequest(url, body, headers))
        } catch (e: IOException) { // Network issues, file access errors
            AnalyticsEventLogger.d(TAG, "Network or file access error: ${e.message}")
        }
    }

    override fun postWithCompletion(
        domain: String, propertyKey: String, body: String,
        headers: Map<String, String>,
        callback: IFPNetworkRequestsCompletion
    ) {
        try {
            val uriBuilder = Uri.Builder()
                .scheme("https")
                .authority(getAuthority(propertyKey, domain))

            NetworkTaskRunner(callback).execute(
                PostRequest(URL(uriBuilder.build().toString()), body, headers)
            )
        } catch (e: IOException) { // Network issues, file access errors
            AnalyticsEventLogger.d(TAG, "Network or file access error: ${e.message}")
            callback.onComplete(false)
        }
    }

    companion object {
        private const val TAG = "FPNetworkRequests"
    }
}
