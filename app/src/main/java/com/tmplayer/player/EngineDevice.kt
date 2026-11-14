package com.tmplayer.player

import android.os.Build
import androidx.media3.common.PlaybackException
import com.tmplayer.data.DeviceFacts

/** This device as [EngineSelector] and the known device list see it (PR1, PR2). */
object EngineDevice {
    fun facts(): DeviceFacts = DeviceFacts(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        hardware = Build.HARDWARE.orEmpty(),
        board = Build.BOARD.orEmpty(),
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else "",
        sdk = Build.VERSION.SDK_INT,
    )
}

/**
 * An ExoPlayer failure as the engine choice sees it ([EngineFailure]): only the decoder's own
 * failures (Media3's 4000 codes) are the engine's fault; a source that stopped (the 2000 codes,
 * the network and the file) would stop any engine, and the rest is neither.
 */
object EngineFailures {
    fun of(errorCode: Int): EngineFailure = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        -> EngineFailure.Decoder
        in 2000..2999 -> EngineFailure.Source
        else -> EngineFailure.Other
    }
}
