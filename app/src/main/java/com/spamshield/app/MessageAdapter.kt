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

        private fun pill(context: android.content.Context, colorRes: Int) =
            ContextCompat.getDrawable(context, R.drawable.bg_badge_pill)
                ?.mutate()
                ?.apply { setTint(ContextCompat.getColor(context, colorRes)) }
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

        // One pill drawable (bg_badge_pill) backs SPAM/SAFE/corrected-by-you alike; only the tint
        // differs, applied to a mutated copy per row so recycled views don't share one colored
        // Drawable instance (that would recolor every row still holding a reference to it).
        val finalIsSpam = item.reviewedLabel ?: item.isSpam
        val (label, containerColor, onContainerColor) = if (finalIsSpam) {
            Triple("SPAM", R.color.spam_container, R.color.on_spam_container)
        } else {
            Triple("SAFE", R.color.safe_container, R.color.on_safe_container)
        }
        holder.txtVerdictBadge.text = label
        holder.txtVerdictBadge.setTextColor(ContextCompat.getColor(context, onContainerColor))
        holder.txtVerdictBadge.background = pill(context, containerColor)

        val confidencePct = (item.confidence * 100).toInt()
        holder.txtTimestamp.text = "${dateFormat.format(item.timestamp)} · $confidencePct% confidence"
        holder.txtCorrected.visibility = if (item.reviewedLabel != null) View.VISIBLE else View.GONE
        if (item.reviewedLabel != null) {
            holder.txtCorrected.setTextColor(ContextCompat.getColor(context, R.color.on_review_container))
            holder.txtCorrected.background = pill(context, R.color.review_container)
        }

        holder.itemView.setOnLongClickListener {
            onLongPress(item)
            true
        }
    }
}
