package com.lagdaemon.creationremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val API_BASE = "https://lagdaemon.com/djehuti"

data class PairedDevice(
    val hostSessionId: String,
    val grantToken: String,
    val deviceName: String?,
    val productSlug: String,
    val presenceState: String
)

data class RemoteProject(val projectId: String, val displayName: String)

/** Phone-side counterpart to the receiver's RemoteClient.cpp -- same endpoints, same contract. */
class RemoteApiClient(private val bearerToken: String) {
    private val client = OkHttpClient()

    private fun authorizedRequest(path: String) = Request.Builder()
        .url("$API_BASE$path")
        .addHeader("Authorization", "Bearer $bearerToken")

    /** Submits a scanned/typed pairing code; returns the issued grant token on success. */
    suspend fun approvePairing(code: String, remoteDeviceName: String, remoteDeviceType: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("remoteDeviceName", remoteDeviceName)
                    put("remoteDeviceType", remoteDeviceType)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = authorizedRequest("/api/remote/pairings/$code/approve")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Pairing failed (HTTP ${response.code}): $text")
                    JSONObject(text).getString("grantToken")
                }
            }
        }

    suspend fun listDevices(): Result<List<PairedDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authorizedRequest("/api/remote/devices").get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Could not list devices (HTTP ${response.code}): $text")
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    PairedDevice(
                        hostSessionId = obj.getString("hostSessionId"),
                        grantToken = obj.getString("grantToken"),
                        deviceName = obj.optString("deviceName", null),
                        productSlug = obj.getString("productSlug"),
                        presenceState = obj.getString("presenceState")
                    )
                }
            }
        }
    }

    /** The project picker for a specific paired host session -- fails with a 404-shaped error if this account has no active grant for it. */
    suspend fun listProjects(hostSessionId: String): Result<List<RemoteProject>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authorizedRequest("/api/remote/host-sessions/$hostSessionId/projects").get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Could not load projects (HTTP ${response.code}): $text")
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    RemoteProject(projectId = obj.getString("projectId"), displayName = obj.getString("displayName"))
                }
            }
        }
    }

    suspend fun revokeDevice(grantToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authorizedRequest("/api/remote/devices/$grantToken").delete().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) error("Could not revoke device (HTTP ${response.code})")
            }
        }
    }
}

/** Extracts the pairing code from a scanned "creationremote://pair?code=XXXX" QR payload. */
fun parsePairingCode(qrPayload: String): String? =
    android.net.Uri.parse(qrPayload).takeIf { it.scheme == "creationremote" }?.getQueryParameter("code")
