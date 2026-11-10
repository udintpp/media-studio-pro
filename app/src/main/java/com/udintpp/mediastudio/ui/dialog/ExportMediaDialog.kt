package com.udintpp.mediastudio.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.udintpp.mediastudio.R
import com.udintpp.mediastudio.data.model.ExportResolution
import com.udintpp.mediastudio.data.model.PhotoExportFormat
import com.udintpp.mediastudio.data.model.VideoExportFormat

class ExportMediaDialog private constructor(
    context: Context,
    private val isVideoMode: Boolean,
    private val onPhotoExportConfirmed: ((ExportResolution, PhotoExportFormat, Int) -> Unit)? = null,
    private val onVideoExportConfirmed: ((ExportResolution, VideoExportFormat) -> Unit)? = null
) : Dialog(context) {

    companion object {
        fun forPhoto(
            context: Context,
            onConfirmed: (ExportResolution, PhotoExportFormat, Int) -> Unit
        ): ExportMediaDialog {
            return ExportMediaDialog(context, isVideoMode = false, onPhotoExportConfirmed = onConfirmed)
        }

        fun forVideo(
            context: Context,
            onConfirmed: (ExportResolution, VideoExportFormat) -> Unit
        ): ExportMediaDialog {
            return ExportMediaDialog(context, isVideoMode = true, onVideoExportConfirmed = onConfirmed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_export_media)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTitle = findViewById<TextView>(R.id.tv_dialog_title)
        val tvSubtitle = findViewById<TextView>(R.id.tv_dialog_subtitle)
        val spinnerResolutions = findViewById<Spinner>(R.id.spinner_resolutions)
        val spinnerFormats = findViewById<Spinner>(R.id.spinner_formats)
        val tvTransparencyIcon = findViewById<TextView>(R.id.tv_transparency_icon)
        val tvTransparencyStatus = findViewById<TextView>(R.id.tv_transparency_status)
        val tvFormatDescription = findViewById<TextView>(R.id.tv_format_description)
        val layoutQuality = findViewById<View>(R.id.layout_quality_container)
        val tvQualityValue = findViewById<TextView>(R.id.tv_quality_value)
        val sliderQuality = findViewById<Slider>(R.id.slider_quality)
        val btnConfirm = findViewById<Button>(R.id.btn_confirm_export)

        // Setup Resolutions
        val resolutions = ExportResolution.values()
        val resAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            resolutions.map { it.label }
        )
        spinnerResolutions.adapter = resAdapter
        // Default to 4K UHD
        spinnerResolutions.setSelection(ExportResolution.UHD_4K.ordinal)

        if (isVideoMode) {
            tvTitle.text = "Export Video Studio Pro"
            tvSubtitle.text = "Pilih resolusi hingga 4K UHD dan format kontainer video terlengkap."
            layoutQuality.visibility = View.GONE

            val formats = VideoExportFormat.values()
            val formatAdapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                formats.map { it.displayName }
            )
            spinnerFormats.adapter = formatAdapter
            spinnerFormats.setSelection(VideoExportFormat.MP4.ordinal)

            spinnerFormats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = formats[position]
                    tvFormatDescription.text = selected.description
                    if (selected.supportsTransparency) {
                        tvTransparencyIcon.text = "✨"
                        tvTransparencyStatus.text = "Mendukung Video Transparan (Alpha Channel)"
                        tvTransparencyStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_emerald))
                    } else {
                        tvTransparencyIcon.text = "ℹ️"
                        tvTransparencyStatus.text = "Format Standar Video (Tanpa Alpha Channel)"
                        tvTransparencyStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            btnConfirm.setOnClickListener {
                val chosenRes = resolutions[spinnerResolutions.selectedItemPosition]
                val chosenFormat = formats[spinnerFormats.selectedItemPosition]
                onVideoExportConfirmed?.invoke(chosenRes, chosenFormat)
                dismiss()
            }
        } else {
            tvTitle.text = "Export Foto Studio Pro"
            tvSubtitle.text = "Pilih resolusi hingga 4K UHD dan format file foto terlengkap."
            layoutQuality.visibility = View.VISIBLE

            var currentQuality = 100
            sliderQuality.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    currentQuality = value.toInt()
                    tvQualityValue.text = "$currentQuality%"
                }
            }

            val formats = PhotoExportFormat.values()
            val formatAdapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                formats.map { it.displayName }
            )
            spinnerFormats.adapter = formatAdapter
            // Default to PNG (lossless + transparency)
            spinnerFormats.setSelection(PhotoExportFormat.PNG.ordinal)

            spinnerFormats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = formats[position]
                    tvFormatDescription.text = selected.description
                    if (selected.supportsTransparency) {
                        tvTransparencyIcon.text = "✨"
                        tvTransparencyStatus.text = "Mendukung Transparansi Penuh (Alpha Channel)"
                        tvTransparencyStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_emerald))
                    } else {
                        tvTransparencyIcon.text = "⚠️"
                        tvTransparencyStatus.text = "Tidak Mendukung Transparansi (Latar diberi warna putih)"
                        tvTransparencyStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_amber))
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            btnConfirm.setOnClickListener {
                val chosenRes = resolutions[spinnerResolutions.selectedItemPosition]
                val chosenFormat = formats[spinnerFormats.selectedItemPosition]
                onPhotoExportConfirmed?.invoke(chosenRes, chosenFormat, currentQuality)
                dismiss()
            }
        }
    }
}
