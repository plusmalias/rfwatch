package com.rfwatch.app

import android.app.Application
import com.rfwatch.app.di.AppContainer
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/** Application entry point; owns the single [AppContainer] (manual dependency graph). */
class RfWatchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FLAVOR == "mapsOnline") {
            MapLibre.getInstance(this)
            val identifiedMapClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "RFWatch/0.2.0 (Android; com.rfwatch.app)")
                        .build()
                    try {
                        chain.proceed(request).also { response ->
                            android.util.Log.i("RFWatchMapHttp", "${request.url.host} response=${response.code}")
                        }
                    } catch (error: java.io.IOException) {
                        android.util.Log.e("RFWatchMapHttp", "${request.url.host} ${error.javaClass.simpleName}")
                        throw error
                    }
                }
                .build()
            HttpRequestUtil.setOkHttpClient(identifiedMapClient)
        }
        container = AppContainer(this)
    }
}
