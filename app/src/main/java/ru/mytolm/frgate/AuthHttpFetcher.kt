package ru.mytolm.frgate

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import okhttp3.OkHttpClient
import okio.Buffer
import okio.FileSystem
import okio.buffer
import okio.source

/** HTTPS-загрузки картинок через наш OkHttp (Basic+JWT заголовки обязательны, §4). */
class AuthHttpFetcher(private val client: OkHttpClient, private val target: String) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val req = okhttp3.Request.Builder().url(target).build()
        val resp = client.newCall(req).execute()
        android.util.Log.d("FRGateImg", "img ${resp.code} $target")
        if (!resp.isSuccessful) {
            resp.close()
            return SourceFetchResult(ImageSource(Buffer(), FileSystem.SYSTEM), null, DataSource.NETWORK)
        }
        val src = resp.body!!.byteStream().source().buffer()
        return SourceFetchResult(ImageSource(src, FileSystem.SYSTEM), null, DataSource.NETWORK)
    }

    companion object {
        val Factory = Fetcher.Factory<Any> { data, _, _ ->
            val s = data.toString()
            if (s.startsWith("http")) AuthHttpFetcher(HttpClientHolder.client, s) else null
        }
    }
}

object HttpClientHolder { lateinit var client: OkHttpClient }
