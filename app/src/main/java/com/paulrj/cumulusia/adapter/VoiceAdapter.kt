package com.cumulusia.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cumulusia.app.R
import com.cumulusia.app.model.TtsVoice

class VoiceAdapter(
    private var voices: MutableList<TtsVoice>,
    private val onLoadClick: (TtsVoice) -> Unit,
    private val onDeleteClick: (TtsVoice) -> Unit
) : RecyclerView.Adapter<VoiceAdapter.VoiceViewHolder>() {

    class VoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvVoiceName: TextView = view.findViewById(R.id.tv_voice_name)
        val tvFormatTag: TextView = view.findViewById(R.id.tv_voice_format)
        val tvVoiceDetails: TextView = view.findViewById(R.id.tv_voice_details)
        val btnLoad: Button = view.findViewById(R.id.btn_load_voice)
        val btnDelete: Button = view.findViewById(R.id.btn_delete_voice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice_card, parent, false)
        return VoiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        val item = voices[position]
        holder.tvVoiceName.text = item.name
        holder.tvFormatTag.text = item.formatTag
        holder.tvVoiceDetails.text = "Tamaño: ${item.formattedSize}"

        if (item.isLoaded) {
            holder.btnLoad.text = "Activa"
            holder.btnLoad.isEnabled = false
        } else {
            holder.btnLoad.text = "Usar Voz"
            holder.btnLoad.isEnabled = true
        }

        holder.btnLoad.setOnClickListener {
            voices.forEach { it.isLoaded = false }
            item.isLoaded = true
            notifyDataSetChanged()
            onLoadClick(item)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(item)
        }
    }

    override fun getItemCount(): Int = voices.size

    fun updateData(newVoices: List<TtsVoice>) {
        voices.clear()
        voices.addAll(newVoices)
        notifyDataSetChanged()
    }
}
