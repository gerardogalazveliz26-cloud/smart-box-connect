package com.nexumid.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nexumid.iptv.R
import com.nexumid.iptv.data.Prefs

/** Video de presentación al abrir la app. OK, cualquier tecla o un toque lo saltan. */
@OptIn(UnstableApi::class)
class SplashActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var done = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.showSplash) {
            next(); return
        }
        setContentView(R.layout.activity_splash)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Device.immersive(this, true)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        // El video es cuadrado: se muestra completo y centrado sobre el mismo color de fondo.
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setShutterBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.splash_bg))

        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next()
            }

            override fun onPlayerError(error: PlaybackException) = next()
        })
        p.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.splash)))
        p.prepare()
        p.play()

        findViewById<View>(R.id.root).setOnClickListener { next() }
        handler.postDelayed({ findViewById<View>(R.id.tvSkip).visibility = View.VISIBLE }, 1200)
        handler.postDelayed({ next() }, 12_000) // por si el equipo no logra reproducir el video
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && event.keyCode != KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                done = true
                finishAffinity()
            } else next()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun next() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        startActivity(Intent(this, LoginActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onRestart() {
        super.onRestart()
        // Si el usuario salió durante el video, al volver va directo a la app.
        next()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
    }
}
