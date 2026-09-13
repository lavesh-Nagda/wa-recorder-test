package com.uplers.watest

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WaCallUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WaCallUpload"
        private const val KEY_M4A_PATH   = "m4a_path"
        private const val KEY_WA_NUMBER  = "wa_number"
        private const val KEY_STARTED_AT = "started_at_ms"
        private const val KEY_ENDED_AT   = "ended_at_ms"
        private const val KEY_DURATION   = "duration_sec"

        fun enqueue(ctx: Context, m4aPath: String, waNumber: String, startedAt: Long, endedAt: Long, durationSec: Int) {
            val data = workDataOf(KEY_M4A_PATH to m4aPath, KEY_WA_NUMBER to waNumber,
                KEY_STARTED_AT to startedAt, KEY_ENDED_AT to endedAt, KEY_DURATION to durationSec)
            val req = OneTimeWorkRequestBuilder<WaCallUploadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("wa_upload_$startedAt", ExistingWorkPolicy.KEEP, req)
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun getForegroundInfo() =
        RecordingUploadNotificationHelper.buildForegroundInfo(applicationContext, 0, "Uploading WA call…")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val m4aPath    = inputData.getString(KEY_M4A_PATH) ?: return@withContext Result.failure()
        val waNumber   = inputData.getString(KEY_WA_NUMBER) ?: "unknown"
        val startedAt  = inputData.getLong(KEY_STARTED_AT, 0L)
        val endedAt    = inputData.getLong(KEY_ENDED_AT, 0L)
        val durationSec = inputData.getInt(KEY_DURATION, 0)
        val m4a = File(m4aPath)
        if (!m4a.exists() || m4a.length() == 0L) return@withContext Result.failure()

        val ts = startedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val recordingKey  = "wa-calls/recordings/$ts.m4a"
        val transcriptKey = "wa-calls/transcripts/$ts.json"

        return@withContext try {
            setForeground(getForegroundInfo())
            Log.i(TAG, "Uploading M4A to S3: $recordingKey (${m4a.length()} bytes)")
            putFileToS3(m4a, recordingKey, "audio/mp4")
            Log.i(TAG, "M4A uploaded. Transcribing…")
            val transcriptJson = transcribeWithElevenLabs(m4a)
            val meta = JSONObject().apply {
                put("wa_number", waNumber); put("started_at_ms", startedAt)
                put("ended_at_ms", endedAt); put("duration_sec", durationSec)
                put("recording_s3", recordingKey); put("transcript", transcriptJson)
                put("saved_at_ms", System.currentTimeMillis())
            }
            putBytesToS3(meta.toString(2).toByteArray(), transcriptKey, "application/json")
            Log.i(TAG, "Transcript saved to S3: $transcriptKey")
            WaCallPrefs.setLastRecordingInfo(applicationContext,
                "✅ Uploaded! Caller: $waNumber | ${durationSec}s | s3://${BuildConfig.WA_S3_BUCKET}/$recordingKey")
            m4a.delete()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed (attempt ${runAttemptCount + 1})", e)
            WaCallPrefs.setLastRecordingInfo(applicationContext, "❌ Upload failed: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            RecordingUploadNotificationHelper.dismiss(applicationContext)
        }
    }

    private fun putFileToS3(file: File, key: String, contentType: String) {
        val (host, encodedPath, url) = s3Parts(key)
        val now = Date(); val dt = awsDateTime(now); val d = awsDate(now)
        val auth = buildS3Auth("PUT", encodedPath, host, contentType, dt, d)
        val req = Request.Builder().url(url).put(file.asRequestBody(contentType.toMediaType()))
            .header("Content-Type", contentType).header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
            .header("x-amz-date", dt).header("Authorization", auth).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("S3 PUT failed ${resp.code}: ${resp.body?.string()?.take(200)}")
        }
    }

    private fun putBytesToS3(bytes: ByteArray, key: String, contentType: String) {
        val (host, encodedPath, url) = s3Parts(key)
        val now = Date(); val dt = awsDateTime(now); val d = awsDate(now)
        val auth = buildS3Auth("PUT", encodedPath, host, contentType, dt, d)
        val req = Request.Builder().url(url).put(bytes.toRequestBody(contentType.toMediaType()))
            .header("Content-Type", contentType).header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
            .header("x-amz-date", dt).header("Authorization", auth).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("S3 PUT json failed ${resp.code}: ${resp.body?.string()?.take(200)}")
        }
    }

    private data class S3Parts(val host: String, val encodedPath: String, val url: String)
    private fun s3Parts(key: String): S3Parts {
        val bucket = BuildConfig.WA_S3_BUCKET; val region = BuildConfig.WA_AWS_REGION
        val host = "$bucket.s3.$region.amazonaws.com"
        val encodedPath = "/" + key.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return S3Parts(host, encodedPath, "https://$host$encodedPath")
    }

    private fun buildS3Auth(method: String, encodedPath: String, host: String, contentType: String, dateTime: String, date: String): String {
        val region = BuildConfig.WA_AWS_REGION; val awsKey = BuildConfig.WA_AWS_KEY; val awsSecret = BuildConfig.WA_AWS_SECRET
        val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-amz-content-sha256:UNSIGNED-PAYLOAD\nx-amz-date:$dateTime\n"
        val signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "$method\n$encodedPath\n\n$canonicalHeaders\n$signedHeaders\nUNSIGNED-PAYLOAD"
        val scope = "$date/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$dateTime\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"
        val signingKey = hmacSha256(hmacSha256(hmacSha256(hmacSha256("AWS4$awsSecret".toByteArray(), date), region), "s3"), "aws4_request")
        return "AWS4-HMAC-SHA256 Credential=$awsKey/$scope, SignedHeaders=$signedHeaders, Signature=${hmacSha256Hex(signingKey, stringToSign)}"
    }

    private fun sha256Hex(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun hmacSha256(key: ByteArray, data: String): ByteArray { val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data.toByteArray()) }
    private fun hmacSha256Hex(key: ByteArray, data: String) = hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    private fun awsDateTime(date: Date) = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(date)
    private fun awsDate(date: Date) = SimpleDateFormat("yyyyMMdd", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    private fun transcribeWithElevenLabs(file: File): JSONObject {
        val elKey = BuildConfig.WA_ELEVENLABS_KEY
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model_id", "scribe_v2").addFormDataPart("diarize", "true").build()
        val req = Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text").post(body)
            .header("xi-api-key", elKey).header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw Exception("ElevenLabs failed ${resp.code}: ${raw.take(200)}")
            return JSONObject(raw)
        }
    }
}
