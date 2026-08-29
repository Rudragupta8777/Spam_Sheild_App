package com.spamshield.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.spamshield.app.data.MessageRecord
import java.text.SimpleDateFormat
import java.util.Locale

class MessageAdapter(
    private val onLongPress: (MessageRecord) -> Unit
) : ListAdapter<MessageRecord, MessageAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MessageRecord>() {
            override fun areItemsTheSame(old: MessageRecord, new: MessageRecord) = old.id == new.id
            override fun areContentsTheSame(old: MessageRecord, new: MessageRecord) = old == new
        }
        private val dateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtSender: android.widget.TextView = view.findViewById(R.id.txtSender)
        val txtBody: android.widget.TextView = view.findViewById(R.id.txtBody)
        val txtVerdictBadge: android.widget.TextView = view.findViewById(R.id.txtVerdictBadge)
        val txtTimestamp: android.widget.TextView = view.findViewById(R.id.txtTimestamp)
        val txtCorrected: android.widget.TextView = view.findViewById(R.id.txtCorrected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        holder.txtSender.text = item.sender
        holder.txtBody.text = item.body

        val finalIsSpam = item.reviewedLabel ?: item.isSpam
        if (finalIsSpam) {
            holder.txtVerdictBadge.text = "SPAM"
            holder.txtVerdictBadge.setTextColor(ContextCompat.getColor(context, R.color.spam_red))
            holder.txtVerdictBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.spam_red_bg))
        } else {
            holder.txtVerdictBadge.text = "SAFE"
            holder.txtVerdictBadge.setTextColor(ContextCompat.getColor(context, R.color.safe_green))
            holder.txtVerdictBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.safe_green_bg))
        }

        val confidencePct = (item.confidence * 100).toInt()
        holder.txtTimestamp.text = "${dateFormat.format(item.timestamp)} · $confidencePct% confidence"
        holder.txtCorrected.visibility = if (item.reviewedLabel != null) View.VISIBLE else View.GONE

        holder.itemView.setOnLongClickListener {
            onLongPress(item)
            true
        }
    }
}
