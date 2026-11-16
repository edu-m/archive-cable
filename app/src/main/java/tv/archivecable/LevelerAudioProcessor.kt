package tv.archivecable

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs [Leveler] inside ExoPlayer's audio pipeline, on the decoded 16-bit PCM. */
@OptIn(UnstableApi::class)
class LevelerAudioProcessor : BaseAudioProcessor() {

    /** Toggled from the SETUP menu; takes effect smoothly within a few milliseconds. */
    @Volatile var enabled: Boolean = true

    private var leveler: Leveler? = null

    /** Gain being applied right now (1 = unchanged), for diagnostics. */
    val currentGain: Float get() = leveler?.currentGain ?: 1f
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        leveler = Leveler(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val lv = leveler
        val bytes = inputBuffer.remaining()
        if (lv == null || bytes == 0) return
        val channels = inputAudioFormat.channelCount
        val samples = bytes / 2 / channels * channels
        if (scratch.size < samples) scratch = ShortArray(samples)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, samples)
        inputBuffer.position(inputBuffer.position() + samples * 2)
        lv.enabled = enabled
        lv.process(scratch, samples / channels)
        val out = replaceOutputBuffer(samples * 2)
        out.asShortBuffer().put(scratch, 0, samples)
        out.position(samples * 2)
        out.flip()
    }

    override fun onFlush() {
        leveler?.restart() // new clip or seek: re-measure quickly
    }

    override fun onReset() {
        leveler = null
    }
}
