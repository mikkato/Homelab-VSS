package ru.mytolm.frgate

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import ru.mytolm.frgate.MediaSources.okHttpDataSourceFactory

/** media3 поверх нашего OkHttp: Basic+JWT заголовки на каждый сегмент (иначе 401). */
object MediaSources {
    fun okHttpDataSourceFactory(): DataSource.Factory {
        val factory = OkHttpDataSource.Factory(appContainer.client.streamClient)
        factory.setDefaultRequestProperties(appContainer.client.mediaHeaders())
        return factory
    }
}
