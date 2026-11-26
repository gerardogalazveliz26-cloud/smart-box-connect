package com.djchany.iptv.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.djchany.iptv.R
import com.djchany.iptv.data.Prefs

/** Pregunta, la primera vez, si la app se usará en celular o en Android TV. */
class ModeActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHANGE = "change"
        fun change(ctx: Context) = ctx.startActivity(Intent(ctx, ModeActivity::class.java).putExtra(EXTRA_CHANGE, true))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode)
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        findViewById<LinearLayout>(R.id.options).orientation =
            if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val tv = Device.looksLikeTv(this)
        val optTv = findViewById<View>(R.id.optTv)
        val optMobile = findViewById<View>(R.id.optMobile)
        findViewById<View>(R.id.tvRecTv).visibility = if (tv) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvRecMobile).visibility = if (tv) View.GONE else View.VISIBLE
        optTv.focusZoom(1.06f)
        optMobile.focusZoom(1.06f)
        optTv.setOnClickListener { pick("tv") }
        optMobile.setOnClickListener { pick("mobile") }
        val current = Prefs.deviceMode
        val preferTv = if (current != null) current == "tv" else tv
        (if (preferTv) optTv else optMobile).requestFocus()
    }

    private fun pick(mode: String) {
        Prefs.deviceMode = mode
        val change = intent.getBooleanExtra(EXTRA_CHANGE, false)
        val next = if (change) Intent(this, HomeActivity::class.java) else Intent(this, LoginActivity::class.java)
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
