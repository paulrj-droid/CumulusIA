package com.cumulusia.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cumulusia.app.R
import com.cumulusia.app.model.GgufModel

class ModelAdapter(
    private var models: MutableList<GgufModel>,
    private val onLoadClick: (GgufModel) -> Unit,
    private val onDeleteClick: (GgufModel) -> Unit
) : RecyclerView.Adapter<ModelAdapter.ModelViewHolder>() {

    class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvModelName: TextView = view.findViewById(R.id.tv_model_name)
        val tvQuantTag: TextView = view.findViewById(R.id.tv_quant_tag)
        val tvModelDetails: TextView = view.findViewById(R.id.tv_model_details)
        val btnLoad: Button = view.findViewById(R.id.btn_load_model)
        val btnDelete: Button = view.findViewById(R.id.btn_delete_model)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_card, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val item = models[position]
        holder.tvModelName.text = item.name
        holder.tvQuantTag.text = item.quantType
        holder.tvModelDetails.text = "${item.formattedSize}  •  ${item.estimatedRamGb} GB RAM"

        if (item.isLoaded) {
            holder.btnLoad.text = "Cargado"
            holder.btnLoad.isEnabled = false
        } else {
            holder.btnLoad.text = "Cargar"
            holder.btnLoad.isEnabled = true
        }

        holder.btnLoad.setOnClickListener {
            models.forEach { isLoaded = false }
            item.isLoaded = true
            notifyDataSetChanged()
            onLoadClick(item)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(item)
        }
    }

    override fun getItemCount(): Int = models.size

    fun updateData(newModels: List<GgufModel>) {
        models.clear()
        models.addAll(newModels)
        notifyDataSetChanged()
    }
}

