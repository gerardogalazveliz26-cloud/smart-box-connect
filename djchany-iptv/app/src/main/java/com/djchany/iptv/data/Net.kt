package com.djchany.iptv.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Net {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val out = if (req.header("User-Agent") == null) {
                    req.newBuilder().header("User-Agent", Prefs.userAgent).build()
                } else req
                chain.proceed(out)
            }
            .build()
    }
}
