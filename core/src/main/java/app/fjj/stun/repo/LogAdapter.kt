package app.fjj.stun.ui

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.core.databinding.ItemLogLineBinding
import androidx.core.graphics.toColorInt

class LogAdapter : ListAdapter<String, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val line = getItem(position)
        holder.bind(line)
    }

    class LogViewHolder(private val binding: ItemLogLineBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(line: String) {
            val (spannable, bgColor) = formatLogLine(line)
            binding.tvLogLine.text = spannable
            binding.root.setBackgroundColor(bgColor)
        }

        private fun formatLogLine(line: String): Pair<SpannableString, Int> {
            val spannable = SpannableString(line)
            val lowerLine = line.uppercase()
            
            val levelColor: Int
            val rowBgColor: Int
            
            when {
                lowerLine.contains("ERROR") || lowerLine.contains("FATAL") -> {
                    levelColor = Color.parseColor("#F44336")
                    rowBgColor = Color.argb(30, 244, 67, 54) // Very light red
                }
                lowerLine.contains("WARN") -> {
                    levelColor = Color.parseColor("#FF9800")
                    rowBgColor = Color.TRANSPARENT
                }
                lowerLine.contains("DEBUG") -> {
                    levelColor = Color.parseColor("#757575")
                    rowBgColor = Color.TRANSPARENT
                }
                lowerLine.contains("INFO") -> {
                    levelColor = Color.parseColor("#2196F3")
                    rowBgColor = Color.TRANSPARENT
                }
                else -> {
                    return Pair(spannable, Color.TRANSPARENT)
                }
            }

            val start = line.indexOf("[")
            val end = line.indexOf("]")
            if (start != -1 && end > start) {
                spannable.setSpan(ForegroundColorSpan(levelColor), start, end + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val words = listOf("ERROR", "FATAL", "WARN", "DEBUG", "INFO")
                for (word in words) {
                    val index = lowerLine.indexOf(word)
                    if (index != -1) {
                        spannable.setSpan(ForegroundColorSpan(levelColor), index, index + word.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        break
                    }
                }
            }
            return Pair(spannable, rowBgColor)
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }
}
