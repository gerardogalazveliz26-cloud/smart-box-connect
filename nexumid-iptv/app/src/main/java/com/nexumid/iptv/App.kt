package com.nexumid.iptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nexumid.iptv.data.Net
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo

class App : Application(), ImageLoaderFactory {
    private val lowRam: Boolean by lazy {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice || am.memoryClass <= 128
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Prefs.init(this)
        Repo.restore()
        Repo.lowRam = lowRam
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android avisa que falta memoria: se vacía lo que no se usa en vez de dejar que cierre la app.
        val c = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        val critical = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        val moderate = android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
        // Solo con falta real de memoria (no cada vez que la app pasa a segundo plano).
        if (level == c || level == critical || level >= moderate) {
            Repo.trim()
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Repo.trim()
        runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Net.client }
        .crossfade(true)
        .respectCacheHeaders(false)
        // En equipos con poca memoria se usa menos caché de imágenes y colores de 16 bits.
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (lowRam) 0.10 else 0.2).build() }
        .allowRgb565(lowRam)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .build()
}
