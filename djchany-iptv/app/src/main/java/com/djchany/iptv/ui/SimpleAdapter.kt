package com.djchany.iptv.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

/** Adaptador genérico pensado para control remoto (foco, OK y mantener OK). */
class SimpleAdapter<T>(
    @LayoutRes private val layout: Int,
    private val zoom: Float = 1f,
    private val bind: (View, T, Int) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter.VH>() {

    var onClick: ((T, Int) -> Unit)? = null
    var onLongClick: ((T, Int) -> Unit)? = null
    var onFocus: ((T, Int) -> Unit)? = null

    var items: List<T> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v)

    private fun VH.pos(): Int? =
        bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION && it < items.size }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        val vh = VH(v)
        v.isFocusable = true
        v.setOnClickListener { vh.pos()?.let { p -> onClick?.invoke(items[p], p) } }
        v.setOnLongClickListener {
            val p = vh.pos()
            val cb = onLongClick
            if (p == null || cb == null) false else {
                cb(items[p], p)
                true
            }
        }
        v.setOnFocusChangeListener { view, has ->
            if (zoom != 1f) view.zoom(has, zoom)
            if (has) vh.pos()?.let { p -> onFocus?.invoke(items[p], p) }
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) = bind(holder.itemView, items[position], position)

    override fun getItemCount() = items.size

    fun refresh(position: Int) {
        if (position in items.indices) notifyItemChanged(position, "state")
    }
}

/** Da foco al elemento [pos] de la lista, desplazándola si hace falta. */
fun RecyclerView.focusPosition(pos: Int) {
    if (pos < 0) return
    scrollToPosition(pos)
    post {
        val vh = findViewHolderForAdapterPosition(pos)
        if (vh != null) vh.itemView.requestFocus() else postDelayed({ findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }, 80)
    }
}
