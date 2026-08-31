package app.fjj.stun.ui

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.core.databinding.ItemLogLineBinding
import app.fjj.stun.repo.LogEntry
import app.fjj.stun.repo.LogLevel

class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry)
    }

    class LogViewHolder(private val binding: ItemLogLineBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: LogEntry) {
            val line = entry.fullText
            val spannable = SpannableString(line)

            // 日志级别标识符固定在时间戳后（例如 "12:34:56.789 " 长度为 13）
            val start = 13
            val end = (start + entry.level.name.length).coerceAtMost(line.length)
            if (line.length >= end) {
                spannable.setSpan(
                    ForegroundColorSpan(entry.level.color),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            binding.tvLogLine.text = spannable
            binding.root.setBackgroundColor(entry.level.rowBgColor)
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean = oldItem == newItem
    }
}
