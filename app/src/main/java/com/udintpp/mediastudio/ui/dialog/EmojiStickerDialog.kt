package com.udintpp.mediastudio.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.udintpp.mediastudio.R
import com.udintpp.mediastudio.data.model.EmojiCatalog
import com.udintpp.mediastudio.data.model.EmojiCategory
import com.udintpp.mediastudio.data.model.EmojiItem
import com.udintpp.mediastudio.data.model.OverlayType
import com.udintpp.mediastudio.data.model.StickerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class EmojiStickerDialog(
    context: Context,
    private val onStickerSelected: (StickerItem) -> Unit
) : Dialog(context) {

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var etSearch: EditText
    private lateinit var tabLayout: TabLayout
    private lateinit var rvGrid: RecyclerView
    private lateinit var tvStatus: TextView

    private var currentMode = 0 // 0 = Emoji, 1 = Web Sticker PNG/GIF
    private var emojiList = EmojiCatalog.items

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_emoji_sticker)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        etSearch = findViewById(R.id.et_search)
        tabLayout = findViewById(R.id.tab_layout)
        rvGrid = findViewById(R.id.rv_grid)
        tvStatus = findViewById(R.id.tv_status)

        rvGrid.layoutManager = GridLayoutManager(context, 4)

        tabLayout.addTab(tabLayout.newTab().setText("Emotikon"))
        tabLayout.addTab(tabLayout.newTab().setText("Pencarian Google & Web"))

        setupEmojiGrid(emojiList)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentMode = tab?.position ?: 0
                if (currentMode == 0) {
                    etSearch.hint = "Cari emotikon (senyum, api, cinta, dll)..."
                    filterEmojis(etSearch.text.toString())
                } else {
                    etSearch.hint = "Cari stiker PNG / GIF di Google / Web..."
                    searchWebStickers(etSearch.text.toString().ifEmpty { "fire effect" })
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                if (currentMode == 0) {
                    filterEmojis(query)
                } else {
                    searchWebStickers(query)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterEmojis(query: String) {
        val filtered = EmojiCatalog.search(query)
        setupEmojiGrid(filtered)
        tvStatus.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvStatus.text = "Tidak ada emotikon yang cocok"
    }

    private fun setupEmojiGrid(items: List<EmojiItem>) {
        rvGrid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_emoji, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = items[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.tv_emoji_char)
                val tvName = holder.itemView.findViewById<TextView>(R.id.tv_emoji_name)
                tv.text = item.unicode
                tvName.text = item.name

                holder.itemView.setOnClickListener {
                    val bmp = renderEmojiToBitmap(item.unicode, 180)
                    val sticker = StickerItem(type = OverlayType.EMOJI, bitmap = bmp, text = item.unicode)
                    onStickerSelected(sticker)
                    dismiss()
                }
            }

            override fun getItemCount(): Int = items.size
        }
    }

    private fun searchWebStickers(query: String) {
        if (query.trim().isEmpty()) return
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Mencari stiker di web..."

        scope.launch {
            val results = withContext(Dispatchers.IO) {
                fetchOnlineStickers(query)
            }
            tvStatus.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            tvStatus.text = if (results.isEmpty()) "Tidak ada hasil stiker web ditemukan" else ""
            setupWebStickersGrid(results)
        }
    }

    private fun setupWebStickersGrid(urls: List<String>) {
        rvGrid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_web_sticker, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val url = urls[position]
                val iv = holder.itemView.findViewById<ImageView>(R.id.iv_sticker_thumb)
                Glide.with(context)
                    .load(url)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .into(iv)

                holder.itemView.setOnClickListener {
                    scope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            try {
                                Glide.with(context).asBitmap().load(url).submit().get()
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (bmp != null) {
                            val isGif = url.endsWith(".gif", ignoreCase = true)
                            val type = if (isGif) OverlayType.STICKER_GIF else OverlayType.STICKER_PNG
                            val sticker = StickerItem(type = type, bitmap = bmp)
                            onStickerSelected(sticker)
                            dismiss()
                        }
                    }
                }
            }

            override fun getItemCount(): Int = urls.size
        }
    }

    private fun fetchOnlineStickers(query: String): List<String> {
        val list = mutableListOf<String>()
        try {
            // Fetch stickers/PNGs from public OpenEmoji / SVG / Tenor sticker endpoints
            val url = "https://tenor.googleapis.com/v2/search?q=${query.replace(" ", "+")}&key=LIVDSRZULELA&limit=24&media_filter=tinygif,png"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val jsonObj = JSONObject(jsonStr)
                val resultsArr = jsonObj.optJSONArray("results") ?: JSONArray()
                for (i in 0 until resultsArr.length()) {
                    val item = resultsArr.getJSONObject(i)
                    val mediaFormats = item.optJSONObject("media_formats")
                    val gif = mediaFormats?.optJSONObject("tinygif")?.optString("url")
                    val png = mediaFormats?.optJSONObject("tinymp4")?.optString("url")
                    val chosen = gif ?: png
                    if (!chosen.isNullOrEmpty()) {
                        list.add(chosen)
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback default sample sticker overlays if offline
        if (list.isEmpty()) {
            list.addAll(
                listOf(
                    "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?w=300",
                    "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=300"
                )
            )
        }
        return list
    }

    private fun renderEmojiToBitmap(emoji: String, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx * 0.72f
            textAlign = Paint.Align.CENTER
        }
        val y = (sizePx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(emoji, sizePx / 2f, y, paint)
        return bmp
    }
}
