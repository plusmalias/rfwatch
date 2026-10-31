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
            // The OSM tile usage policy requires an identifying User-Agent.
            val identifiedMapClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", "RFWatch/${BuildConfig.VERSION_NAME} (Android; com.rfwatch.app)")
                            .build()
                    )
                }
                .build()
            HttpRequestUtil.setOkHttpClient(identifiedMapClient)
        }
        container = AppContainer(this)
    }
}
