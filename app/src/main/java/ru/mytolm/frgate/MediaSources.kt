package ru.mytolm.frgate

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import ru.mytolm.frgate.MediaSources.okHttpDataSourceFactory
import ru.mytolm.frgate.util.Diag

/** media3 поверх нашего OkHttp: Basic+JWT заголовки на каждый сегмент (иначе 401). */
object MediaSources {
    fun okHttpDataSourceFactory(): DataSource.Factory {
        Diag.log("media.sourceFactory.build")
        return try {
            val factory = OkHttpDataSource.Factory(appContainer.client.streamClient)
            factory.setDefaultRequestProperties(appContainer.client.mediaHeaders())
            factory
        } catch (e: Exception) {
            // заголовки не логируем — там токены
            Diag.error("media.sourceFactory.build", e)
            // Фолбэк вместо rethrow: без заголовков плеер даст воспроизводимую
            // ошибку 401 в состоянии плеера, а не упадёт в фазе recomposition.
            OkHttpDataSource.Factory(appContainer.client.streamClient)
        }
    }
}
