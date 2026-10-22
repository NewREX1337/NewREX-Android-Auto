// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation

data class Tile(
    val key: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val fallbackIcon: Int,
    val favorite: Boolean = false,
    val onClick: () -> Unit,
)

/** Kanal / istasyon kutucukları. Çalan öğe vurgulu çerçeveyle gösterilir. */
class TileAdapter(private val ctx: Context) : RecyclerView.Adapter<TileAdapter.VH>() {
    var items: List<Tile> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    var highlightKey: String? = null
        set(v) { field = v; notifyDataSetChanged() }

    class VH(
        val root: LinearLayout,
        val imageBox: FrameLayout,
        val image: ImageView,
        val star: ImageView,
        val title: TextView,
        val subtitle: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = with(ctx) {
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val star = icon(com.newrex.auto.R.drawable.ic_star, 16, Palette.ORANGE)
        val imageBox = FrameLayout(this).apply {
            addView(image, flp(MATCH, MATCH))
            addView(star, flp(dp(16), dp(16), Gravity.TOP or Gravity.END))
        }
        val title = label("", 14f, bold = true).apply { gravity = Gravity.CENTER }
        val subtitle = label("", 11f, Palette.TEXT_FAINT).apply { gravity = Gravity.CENTER }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(12), dp(10), dp(10))
            addView(imageBox, lp(MATCH, dp(58)))
            addView(title, lp(MATCH, WRAP).margins(t = dp(10)))
            addView(subtitle, lp(MATCH, WRAP).margins(t = dp(4)))
            layoutParams = RecyclerView.LayoutParams(MATCH, dp(132)).margins(dp(5), dp(5), dp(5), dp(5))
        }
        VH(root, imageBox, image, star, title, subtitle)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        val r = ctx.dpf(16)
        val active = t.key == highlightKey
        h.root.background = pressable(
            rounded(if (active) Palette.withAlpha(Palette.accent, 0x26) else Palette.SURFACE, r),
            r,
        )
        h.title.text = t.title
        h.subtitle.text = t.subtitle.orEmpty()
        h.subtitle.visibility = if (t.subtitle.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        h.star.visibility = if (t.favorite) android.view.View.VISIBLE else android.view.View.GONE
        h.root.neonGlow(active, 10)
        h.root.onTap(action = t.onClick)

        val fallback = tinted(t.fallbackIcon)
        if (t.image.isNullOrBlank()) {
            h.image.setImageDrawable(fallback)
        } else {
            h.image.load(t.image) {
                crossfade(true)
                placeholder(fallback)
                error(fallback)
                transformations(RoundedCornersTransformation(ctx.dpf(8)))
            }
        }
    }

    private fun tinted(res: Int): Drawable? =
        ContextCompat.getDrawable(ctx, res)?.mutate()?.apply { setTint(Palette.TEXT_FAINT) }
}

/** Ekran genişliğine göre sütun sayısını kendisi ayarlayan kutucuk ızgarası. */
fun Context.tileGrid(adapter: TileAdapter, minTileDp: Int = 150) = RecyclerView(this).apply {
    val glm = GridLayoutManager(context, 3)
    layoutManager = glm
    this.adapter = adapter
    clipToPadding = false
    setPadding(dp(4), dp(4), dp(4), dp(4))
    overScrollMode = RecyclerView.OVER_SCROLL_NEVER
    addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
        if (r - l != or - ol) {
            val span = ((r - l) / dpf(minTileDp)).toInt().coerceAtLeast(2)
            if (glm.spanCount != span) v.post { glm.spanCount = span }
        }
    }
}

/** Sol taraftaki kategori listesi (ör. TV grupları). */
class ListAdapter(private val ctx: Context, private val onPick: (String) -> Unit) :
    RecyclerView.Adapter<ListAdapter.VH>() {
    var items: List<Pair<String, String>> = emptyList()   // anahtar -> görünen ad
        set(v) { field = v; notifyDataSetChanged() }
    var selected: String? = null
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = with(ctx) {
        VH(label("", 15f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            layoutParams = RecyclerView.LayoutParams(MATCH, dp(46)).margins(b = dp(4))
        })
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val (key, name) = items[position]
        val r = ctx.dpf(12)
        val sel = key == selected
        h.text.text = name
        h.text.setTextColor(if (sel) Palette.TEXT else Palette.TEXT_DIM)
        h.text.background = pressable(
            rounded(if (sel) Palette.withAlpha(Palette.accent, 0x66) else 0, r), r,
        )
        h.text.neonGlow(sel, 5)
        h.text.onTap { onPick(key) }
    }
}

fun Context.verticalList(adapter: RecyclerView.Adapter<*>) = RecyclerView(this).apply {
    layoutManager = LinearLayoutManager(context)
    this.adapter = adapter
    overScrollMode = RecyclerView.OVER_SCROLL_NEVER
}
