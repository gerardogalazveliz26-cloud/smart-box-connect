package com.nexumid.iptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexumid.iptv.R
import com.nexumid.iptv.data.Activation
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Muestra el código del equipo y espera a que el proveedor cargue la lista desde su panel. */
class ActivationActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ActivationActivity::class.java))
    }

    private var job: Job? = null
    private lateinit var tvStatus: TextView
    private val started = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.applyOrientation(this)
        setContentView(R.layout.activity_activation)
        tvStatus = findViewById(R.id.tvStatus)
        val code = Activation.display()
        findViewById<TextView>(R.id.tvCode).text = code

        val btnShare = findViewById<Button>(R.id.btnShare)
        val btnManual = findViewById<Button>(R.id.btnManual)
        btnShare.visibility = if (Device.isMobile) View.VISIBLE else View.GONE
        btnShare.setOnClickListener {
            val text = "Hola, este es el código de mi equipo para NexumID IPTV: $code"
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            try {
                startActivity(Intent(send).setPackage("com.whatsapp"))
            } catch (e: Exception) {
                startActivity(Intent.createChooser(send, "Enviar código"))
            }
        }
        btnManual.setOnClickListener { finish() }
        listOf(btnShare, btnManual).forEach { it.focusZoom(1.04f) }
        btnManual.requestFocus()
        TrialQr.bind(this, window.decorView)
    }

    override fun onStart() {
        super.onStart()
        job = lifecycleScope.launch {
            while (isActive) {
                when (val r = Activation.fetch()) {
                    is Activation.Result.Found -> {
                        Activation.apply(r.payload)
                        if (Prefs.profiles.any { it.remote } && Repo.api != null) {
                            tvStatus.text = "¡Equipo activado!"
                            findViewById<ProgressBar>(R.id.pbWait).visibility = View.GONE
                            toast("¡Listo! Tu lista fue cargada")
                            delay(800)
                            startActivity(
                                Intent(this@ActivationActivity, HomeActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            finish()
                            return@launch
                        }
                        tvStatus.text = "La activación no tiene una lista válida. Avísale a tu proveedor."
                    }
                    is Activation.Result.NotFound -> tvStatus.text = "Esperando la activación…"
                    is Activation.Result.Error -> tvStatus.text = "Sin conexión, reintentando…"
                }
                // Los primeros 10 minutos consulta seguido; después, cada minuto.
                delay(if (System.currentTimeMillis() - started < 10 * 60_000) 15_000 else 60_000)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        job?.cancel()
    }
}
