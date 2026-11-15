package com.tmplayer.cast

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.net.wifi.WifiInfo
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tmplayer.data.FormFactor
import java.io.IOException

/**
 * What this phone can transcode a cast with ([TranscodeAbility]), read from its MediaCodec list:
 * an H.264 encoder (the largest size and bitrate it takes, whether it is hardware), the decoders
 * Transformer can use, and what Media3's fragmented MP4 muxer carries as it is. Null on a TV
 * (receiver only, it never casts) and on a phone without an H.264 encoder; [noEncoder] says which.
 */
object AndroidTranscodeAbility {

    /** True when the last [probe] found no H.264 encoder at all (not a TV): the screen says so. */
    @Volatile
    var noEncoder = false
        private set

    @Volatile
    private var cached: TranscodeAbility? = null

    fun probe(context: Context): TranscodeAbility? {
        if (FormFactor.isTv(context)) return null
        cached?.let { return it }
        val found = runCatching { read() }.onFailure { Log.w(TAG, "Codec list could not be read", it) }.getOrNull()
        noEncoder = found == null
        cached = found
        Log.i(TAG, "Transcode for casts: ${found ?: "no H.264 encoder"}")
        return found
    }

    /** Reads the codec list again on the next [probe]; for the tests. */
    internal fun forget() {
        cached = null
        noEncoder = false
    }

    private fun read(): TranscodeAbility? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val encoders = infos.filter { it.isEncoder && MimeTypes.VIDEO_H264 in it.supportedTypes.map(String::lowercase) }
        // A hardware one first; a software one (OMX.google, c2.android) when that is all there is.
        val encoder = encoders.firstOrNull { hardware(it) } ?: encoders.firstOrNull() ?: return null
        val video = runCatching { encoder.getCapabilitiesForType(MimeTypes.VIDEO_H264).videoCapabilities }.getOrNull()
        val decoders = infos.filter { !it.isEncoder }.flatMap { it.supportedTypes.map(String::lowercase) }.toSet()
        fun decodable(map: Map<String, String>) = map.filterValues { it in decoders }.keys
        return TranscodeAbility(
            maxWidth = video?.supportedWidths?.upper?.coerceAtMost(1920) ?: 1920,
            maxHeight = video?.supportedHeights?.upper?.coerceAtMost(1080) ?: 1080,
            maxVideoKbps = video?.bitrateRange?.upper?.let { it / 1000 }?.coerceAtLeast(500) ?: 20_000,
            hardware = hardware(encoder),
            videoDecoders = decodable(VIDEO_MIMES),
            // Raw PCM needs no decoder: Media3 reads it itself.
            audioDecoders = decodable(AUDIO_MIMES) + Codecs.PCM,
            ac3 = false,
            containers = setOf(TranscodeContainer.Fmp4),
            passVideo = setOf(Codecs.H264, Codecs.HEVC, Codecs.AV1, Codecs.VP9),
            passAudio = setOf(Codecs.AAC, Codecs.OPUS),
        )
    }

    private fun hardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else info.name.lowercase().let { !it.startsWith("omx.google.") && !it.startsWith("c2.android.") }

    private val VIDEO_MIMES = mapOf(
        Codecs.H264 to MimeTypes.VIDEO_H264, Codecs.HEVC to MimeTypes.VIDEO_H265, Codecs.AV1 to MimeTypes.VIDEO_AV1,
        Codecs.VP8 to MimeTypes.VIDEO_VP8, Codecs.VP9 to MimeTypes.VIDEO_VP9, Codecs.MPEG4 to MimeTypes.VIDEO_MP4V,
        Codecs.MPEG2 to MimeTypes.VIDEO_MPEG2, Codecs.VC1 to MimeTypes.VIDEO_VC1,
    )
    private val AUDIO_MIMES = mapOf(
        Codecs.AAC to MimeTypes.AUDIO_AAC, Codecs.MP3 to MimeTypes.AUDIO_MPEG, Codecs.MP2 to MimeTypes.AUDIO_MPEG_L2,
        Codecs.OPUS to MimeTypes.AUDIO_OPUS, Codecs.VORBIS to MimeTypes.AUDIO_VORBIS, Codecs.FLAC to MimeTypes.AUDIO_FLAC,
        Codecs.AC3 to MimeTypes.AUDIO_AC3, Codecs.EAC3 to MimeTypes.AUDIO_E_AC3, Codecs.DTS to MimeTypes.AUDIO_DTS,
        Codecs.TRUEHD to MimeTypes.AUDIO_TRUEHD, Codecs.ALAC to MimeTypes.AUDIO_ALAC,
    )

    private const val TAG = "CastTranscode"
}

/** The battery and the heat, for the transcode's guard; what the platform cannot say stays unknown. */
class AndroidDeviceHealth(context: Context) {
    private val app = context.applicationContext

    fun read(): DeviceHealth {
        val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 }
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (runCatching { app.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()) {
                PowerManager.THERMAL_STATUS_NONE -> DeviceHealth.Thermal.None
                PowerManager.THERMAL_STATUS_LIGHT -> DeviceHealth.Thermal.Light
                PowerManager.THERMAL_STATUS_MODERATE -> DeviceHealth.Thermal.Moderate
                PowerManager.THERMAL_STATUS_SEVERE -> DeviceHealth.Thermal.Severe
                PowerManager.THERMAL_STATUS_CRITICAL -> DeviceHealth.Thermal.Critical
                PowerManager.THERMAL_STATUS_EMERGENCY -> DeviceHealth.Thermal.Emergency
                PowerManager.THERMAL_STATUS_SHUTDOWN -> DeviceHealth.Thermal.Shutdown
                else -> DeviceHealth.Thermal.Unknown
            }
        } else {
            DeviceHealth.Thermal.Unknown
        }
        return DeviceHealth(percent, plugged, thermal)
    }
}

/**
 * A [CastSource] as a Media3 [DataSource], for Transformer's extractors: the cast server's reads
 * (through [RecoveringCastSource], so a Telegram stall is waited out) at any byte the extractor
 * seeks to.
 */
@UnstableApi
class CastSourceDataSource(private val source: CastSource) : BaseDataSource(false) {

    private var reader: CastReader? = null
    private var uri: Uri? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val total = source.length
        if (dataSpec.position < 0 || (total > 0 && dataSpec.position > total)) throw IOException("Position ${dataSpec.position} past $total")
        uri = dataSpec.uri
        reader = source.open(dataSpec.position)
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total > 0 -> total - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("Not open")
        val want = if (remaining > 0) minOf(length.toLong(), remaining).toInt() else length
        val n = r.read(buffer, offset, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining > 0) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        val r = reader
        reader = null
        if (r != null) {
            runCatching { r.close() }
            transferEnded()
        }
    }

    class Factory(private val source: CastSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = CastSourceDataSource(source)
    }
}

/**
 * The local link's speed for the transcode's bitrate ([LinkEstimator]): the connected Wi-Fi's own
 * link rate (Android 10 and later, from the network's capabilities, which needs no extra
 * permission), scaled down to what a stream gets. Ethernet and mobile data say nothing here, and
 * before Android 10 nothing is read (the older call needs a permission the app does not ask for):
 * the plan then keeps 1080p 8 Mb/s until the cast server has measured the receiver.
 */
class AndroidLinkSpeed(context: Context) : LinkSpeedSource {
    private val app = context.applicationContext

    override fun effectiveKbps(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val info = caps.transportInfo as? WifiInfo ?: return null
            // -1 when Android does not know it.
            LinkSpeeds.wifiEffectiveKbps(info.linkSpeed.toDouble())
        }.getOrNull()
    }
}
