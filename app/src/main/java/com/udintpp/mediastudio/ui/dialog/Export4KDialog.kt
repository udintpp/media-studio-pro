package com.udintpp.mediastudio.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import com.udintpp.mediastudio.R
import com.udintpp.mediastudio.data.model.ExportResolution

class Export4KDialog(
    context: Context,
    private val onExportConfirmed: (ExportResolution) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_export_4k)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val spinner = findViewById<Spinner>(R.id.spinner_resolutions)
        val btnExport = findViewById<Button>(R.id.btn_confirm_export)
        val tvNote = findViewById<TextView>(R.id.tv_resolution_note)

        val resolutions = ExportResolution.values()
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            resolutions.map { it.label }
        )
        spinner.adapter = adapter

        // Default to 4K UHD or 1080p
        spinner.setSelection(ExportResolution.UHD_4K.ordinal)

        btnExport.setOnClickListener {
            val selected = resolutions[spinner.selectedItemPosition]
            onExportConfirmed(selected)
            dismiss()
        }
    }
}
