package com.nexumid.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexumid.iptv.BuildConfig
import com.nexumid.iptv.R
import com.nexumid.iptv.data.Activation
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private class Row(val title: String, val value: () -> String, val action: () -> Unit)

    private lateinit var adapter: SimpleAdapter<Row>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.applyOrientation(this)
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.tvVersion).text = "Versión ${BuildConfig.VERSION_NAME}"

        val rows = listOf(
            Row("Cuenta", { Prefs.current?.let { "${it.user} · ${it.name}" } ?: "-" }) { showAccount() },
            Row("Código de activación de este equipo", { Activation.display() }) {
                ActivationActivity.open(this)
            },
            Row("Tipo de equipo", { if (Device.isMobile) "Celular / Tablet" else "Android TV / TV Box" }) {
                ModeActivity.change(this)
            },
            Row("Video de inicio", { if (Prefs.showSplash) "Activado" else "Desactivado" }) {
                Prefs.showSplash = !Prefs.showSplash
            },
            Row("Cambiar de lista / cuenta", { "${Prefs.profiles.size} guardada(s)" }) { LoginActivity.manage(this) },
            Row("Formato de TV en vivo", { if (Prefs.liveFormat == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)" }) {
                choose("Formato de TV en vivo", listOf("MPEG-TS (.ts) — recomendado", "HLS (.m3u8)"), if (Prefs.liveFormat == "m3u8") 1 else 0) {
                    Prefs.liveFormat = if (it == 1) "m3u8" else "ts"
                }
            },
            Row("Buffer de reproducción", { listOf("Normal", "Alto", "Máximo")[Prefs.bufferMode.coerceIn(0, 2)] }) {
                choose("Buffer (más alto = menos cortes, más espera al iniciar)", listOf("Normal", "Alto", "Máximo"), Prefs.bufferMode) {
                    Prefs.bufferMode = it
                }
            },
            Row("Audio de los canales", { if (Prefs.audioMode == 0) "Máxima compatibilidad (FFmpeg)" else "Decodificador del equipo" }) {
                choose(
                    "Audio (si un canal no se escucha, usa «Máxima compatibilidad»)",
                    listOf("Máxima compatibilidad (FFmpeg) — recomendado", "Decodificador del equipo"),
                    Prefs.audioMode,
                ) { Prefs.audioMode = it }
            },
            Row("Reproducir el siguiente episodio automáticamente", { if (Prefs.autoplayNext) "Sí" else "No" }) {
                Prefs.autoplayNext = !Prefs.autoplayNext
            },
            Row("User-Agent", { if (Prefs.userAgent == Prefs.DEFAULT_UA) "Predeterminado" else "Personalizado" }) { editUserAgent() },
            Row("Control parental (ocultar contenido adulto)", { if (Prefs.hideAdult) "Activado" else "Desactivado" }) { toggleParental() },
            Row("Cambiar PIN", { "••••" }) { changePin() },
            Row("Actualizar canales y catálogo", { "" }) {
                Repo.clear()
                toast("Contenido actualizado. Se volverá a descargar al abrir cada sección.")
            },
            Row("Borrar historial \"Seguir viendo\"", { "${Prefs.resumes().size}" }) {
                confirm("¿Borrar el historial de reproducción?") { Prefs.clearResumes() }
            },
            Row("Buscar actualizaciones", { "v${BuildConfig.VERSION_NAME}" }) { Updater.check(this, true) },
            Row("Ver último reporte de error", { "" }) { com.nexumid.iptv.CrashReporter.showLast(this) },
            Row("Cerrar sesión", { "" }) {
                confirm("¿Cerrar la sesión de esta lista?") {
                    Prefs.activeKey = null
                    Repo.logout()
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                }
            },
        )

        adapter = SimpleAdapter(R.layout.item_setting) { v, r, _ ->
            v.findViewById<TextView>(R.id.tvName).text = r.title
            v.findViewById<TextView>(R.id.tvValue).text = r.value()
        }
        adapter.onClick = { r, _ -> r.action() }
        adapter.items = rows
        findViewById<RecyclerView>(R.id.rvSettings).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            this.adapter = this@SettingsActivity.adapter
            itemAnimator = null
            focusPosition(0)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = adapter.notifyItemRangeChanged(0, adapter.itemCount, "state")

    private fun choose(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { d, which ->
                onPick(which)
                d.dismiss()
                refresh()
                toast("Guardado. Se aplica al abrir el próximo canal o video.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sí") { _, _ -> onYes(); refresh() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun input(title: String, initial: String, type: Int, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(initial)
            inputType = type
            setSingleLine()
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text))
        }
        val box = FrameLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Aceptar") { _, _ -> onOk(et.text.toString()); refresh() }
            .setNegativeButton("Cancelar", null)
            .show()
        et.requestFocus()
    }

    private fun askPin(onOk: () -> Unit) {
        input("Ingresa el PIN", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            if (it == Prefs.pin) onOk() else toast("PIN incorrecto")
        }
    }

    private fun toggleParental() {
        if (Prefs.hideAdult) {
            askPin {
                Prefs.hideAdult = false
                refresh()
                toast("Control parental desactivado")
            }
        } else {
            Prefs.hideAdult = true
            refresh()
            toast("Control parental activado")
        }
    }

    private fun changePin() {
        askPin {
            input("Nuevo PIN (4 dígitos)", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                if (it.length in 4..8 && it.all(Char::isDigit)) {
                    Prefs.pin = it
                    toast("PIN cambiado")
                } else toast("El PIN debe tener entre 4 y 8 números")
            }
        }
    }

    private fun editUserAgent() {
        input("User-Agent (deja vacío para el predeterminado)", if (Prefs.userAgent == Prefs.DEFAULT_UA) "" else Prefs.userAgent, InputType.TYPE_CLASS_TEXT) {
            Prefs.userAgent = it.trim()
            toast("Guardado. Reinicia la app para aplicarlo en todas las conexiones.")
        }
    }

    private fun showAccount() {
        lifecycleScope.launch {
            val p = Prefs.current
            val acc = runCatching { Repo.requireApi().login() }.getOrNull()?.also { Repo.account = it }
            val msg = if (acc == null) "No se pudo consultar la cuenta en el servidor." else buildString {
                appendLine("Lista: ${p?.name ?: "-"}")
                appendLine("Servidor: ${p?.server ?: "-"}")
                appendLine("Usuario: ${acc.username}")
                append("Estado: ${if (acc.status.equals("Active", true)) "Activa" else acc.status}${if (acc.isTrial) " (prueba)" else ""}")
                append("")
            }
            if (!isFinishing) AlertDialog.Builder(this@SettingsActivity).setTitle("Cuenta").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }
}
