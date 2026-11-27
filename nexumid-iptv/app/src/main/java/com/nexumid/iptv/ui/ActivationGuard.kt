package com.nexumid.iptv.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexumid.iptv.data.Activation
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo
import kotlinx.coroutines.launch

/**
 * Revisa cada pocos minutos si el proveedor dio de baja (o cambió) la activación de este equipo,
 * también mientras se está mirando un canal o una película. Si fue dado de baja, se corta y vuelve
 * al inicio de sesión.
 */
object ActivationGuard {
    const val INTERVAL_MS = 3 * 60_000L
    private var lastCheck = 0L

    fun check(activity: AppCompatActivity, force: Boolean = false) {
        if (!Prefs.activated) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < INTERVAL_MS) return
        lastCheck = now
        activity.lifecycleScope.launch {
            when (val r = Activation.fetch()) {
                is Activation.Result.NotFound -> {
                    Activation.revoke()
                    if (Repo.api == null && !activity.isFinishing) {
                        activity.toast("Este equipo fue desactivado por tu proveedor")
                        activity.startActivity(
                            Intent(activity, LoginActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        activity.finish()
                    }
                }
                is Activation.Result.Found ->
                    if (r.payload.updated != Prefs.activationStamp) Activation.apply(r.payload)
                is Activation.Result.Error -> Unit
            }
        }
    }
}
