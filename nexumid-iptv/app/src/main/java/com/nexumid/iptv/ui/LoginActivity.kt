package com.nexumid.iptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexumid.iptv.BuildConfig
import com.nexumid.iptv.R
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo
import com.nexumid.iptv.data.XtreamApi
import com.nexumid.iptv.data.XtreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MANAGE = "manage"
        fun manage(ctx: Context) = ctx.startActivity(Intent(ctx, LoginActivity::class.java).putExtra(EXTRA_MANAGE, true))
    }

    private lateinit var etName: EditText
    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvError: TextView
    private lateinit var btnLogin: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvSaved: TextView
    private lateinit var profilesAdapter: SimpleAdapter<Prefs.Profile>
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.deviceMode == null) {
            startActivity(Intent(this, ModeActivity::class.java))
            finish()
            return
        }
        Device.applyOrientation(this)
        val manage = intent.getBooleanExtra(EXTRA_MANAGE, false)
        if (!manage && Prefs.current != null && Repo.api != null) {
            goHome()
            return
        }
        setContentView(R.layout.activity_login)
        etName = findViewById(R.id.etName)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        tvError = findViewById(R.id.tvError)
        btnLogin = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)
        tvSaved = findViewById(R.id.tvSaved)

        etPass.typeface = android.graphics.Typeface.DEFAULT
        etServer.setText(Prefs.current?.server ?: BuildConfig.DEFAULT_SERVER)

        btnLogin.setOnClickListener {
            XtreamApi.parseM3uLink(etServer.text.toString())?.let { (server, u, p) ->
                etServer.setText(server)
                etUser.setText(u)
                etPass.setText(p)
                toast("Datos tomados del enlace M3U")
            }
            val user = etUser.text.toString().trim()
            login(
                Prefs.Profile(
                    name = etName.text.toString().trim().ifBlank { user },
                    server = etServer.text.toString().trim(),
                    user = user,
                    pass = etPass.text.toString().trim(),
                )
            )
        }
        etPass.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO) {
                btnLogin.performClick(); true
            } else false
        }

        profilesAdapter = SimpleAdapter(R.layout.item_profile, 1.05f) { v, p, _ ->
            v.findViewById<TextView>(R.id.tvName).text = (if (p.key == Prefs.activeKey) "● " else "") + p.name
            v.findViewById<TextView>(R.id.tvSub).text = "${p.user} · ${p.server.removePrefix("http://").removePrefix("https://")}"
        }
        profilesAdapter.onClick = { p, _ -> login(p) }
        profilesAdapter.onLongClick = { p, _ ->
            AlertDialog.Builder(this)
                .setTitle("¿Borrar la lista \"${p.name}\"?")
                .setPositiveButton("Borrar") { _, _ ->
                    Prefs.deleteProfile(p)
                    if (Prefs.current == null) Repo.logout()
                    refreshProfiles()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        findViewById<RecyclerView>(R.id.rvProfiles).apply {
            layoutManager = LinearLayoutManager(this@LoginActivity, RecyclerView.HORIZONTAL, false)
            adapter = profilesAdapter
            itemAnimator = null
        }
        refreshProfiles()
        TrialQr.bind(this, window.decorView)
        com.nexumid.iptv.CrashReporter.offer(this)
        btnLogin.focusZoom(1.03f)
        findViewById<Button>(R.id.btnActivate).apply {
            focusZoom(1.03f)
            setOnClickListener { ActivationActivity.open(this@LoginActivity) }
        }
        if (Prefs.profiles.isEmpty()) (if (etServer.text.isBlank()) etServer else etUser).requestFocus()
        else findViewById<RecyclerView>(R.id.rvProfiles).post {
            findViewById<RecyclerView>(R.id.rvProfiles).getChildAt(0)?.requestFocus()
        }
    }

    private fun refreshProfiles() {
        val list = Prefs.profiles
        profilesAdapter.items = list
        tvSaved.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun login(p: Prefs.Profile) {
        if (busy) return
        if (p.server.isBlank() || p.user.isBlank() || p.pass.isBlank()) {
            showError("Completa el servidor, el usuario y la contraseña")
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val api = XtreamApi(p.server, p.user, p.pass)
                val acc = api.login()
                if (!acc.status.equals("Active", ignoreCase = true)) {
                    throw XtreamException("La cuenta no está activa (estado: ${acc.status})")
                }
                val saved = p.copy(server = api.baseString)
                Prefs.saveProfile(saved)
                Repo.use(saved, acc)
                goHome()
            } catch (e: CancellationException) {
                throw e
            } catch (e: XtreamException) {
                showError(e.message ?: "No se pudo iniciar sesión")
            } catch (e: JSONException) {
                showError("El servidor no respondió como un panel Xtream Codes")
            } catch (e: IOException) {
                showError("No se pudo conectar con el servidor: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: Exception) {
                showError("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnLogin.text = if (b) "" else "Entrar"
        if (b) tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
