package com.nexumid.iptv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nexumid.iptv.R
import com.nexumid.iptv.data.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Código QR para pedir una prueba por WhatsApp (pantallas previas a activar una cuenta). */
object TrialQr {

    /** Genera el QR (azul oscuro sobre blanco) con el ícono de NexumID al centro. */
    fun bitmap(text: String, size: Int, logo: Bitmap?): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m[x, y]) 0xFF06142A.toInt() else 0xFFFFFFFF.toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        if (logo != null) {
            val c = Canvas(bmp)
            val s = w * 0.24f
            val left = (w - s) / 2f
            val top = (h - s) / 2f
            val pad = s * 0.09f
            c.drawRoundRect(RectF(left - pad, top - pad, left + s + pad, top + s + pad), s * 0.22f, s * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            c.drawBitmap(logo, null, RectF(left, top, left + s, top + s), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return bmp
    }

    /** Completa la tarjeta incluida con layout/view_trial.xml y la actualiza si cambia el número. */
    fun bind(activity: AppCompatActivity, root: View) {
        val card = root.findViewById<View>(R.id.trialCard) ?: return
        fun render() {
            if (!RemoteConfig.hasWhatsapp) {
                card.visibility = View.GONE
                return
            }
            card.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvTrialTitle).text = RemoteConfig.trialTitle
            root.findViewById<TextView>(R.id.tvTrialText).text = RemoteConfig.trialText
            root.findViewById<TextView>(R.id.tvTrialNumber).text = "WhatsApp ${RemoteConfig.whatsappDisplay()}"
            val iv = root.findViewById<ImageView>(R.id.ivQr)
            val url = RemoteConfig.whatsappUrl()
            activity.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    val logo = BitmapFactory.decodeResource(activity.resources, R.drawable.brand_icon)
                    bitmap(url, 512, logo)
                }
                iv.setImageBitmap(bmp)
            }
            root.findViewById<View>(R.id.btnTrial)?.apply {
                // En el celular no se puede escanear la propia pantalla: botón directo al chat.
                visibility = if (Device.isMobile) View.VISIBLE else View.GONE
                setOnClickListener {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        activity.toast("No se pudo abrir WhatsApp")
                    }
                }
            }
        }
        render()
        activity.lifecycleScope.launch { if (RemoteConfig.refresh()) render() }
    }
}
