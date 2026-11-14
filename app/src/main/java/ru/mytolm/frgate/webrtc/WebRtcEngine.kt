package ru.mytolm.frgate.webrtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Фактория PeerConnectionFactory и EglBase на весь процесс (top10-8):
 * тяжёлую инициализацию libwebrtc делаем один раз, а не на каждый вход в камеру.
 * Факторию не dispose при уходе с экрана — dispose живой фактории ломает активные
 * PeerConnection; это штатная практика (LiveKit/WebRTC-демо держат её до конца процесса).
 */
object WebRtcEngine {
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var egl: EglBase? = null
    private val lock = Any()

    fun eglBase(): EglBase {
        egl?.let { return it }
        synchronized(lock) {
            if (egl == null) egl = EglBase.create()
        }
        return egl!!
    }

    fun getFactory(ctx: Context): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(lock) {
            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(ctx.applicationContext)
                        .createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase().eglBaseContext))
                    .createPeerConnectionFactory()
            }
        }
        return factory!!
    }
}
