package com.pemutar.video

import android.annotation.SuppressLint
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.pemutar.video.databinding.ItemFolderBinding
import com.pemutar.video.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
    private val onLongClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private var items: List<VideoItem> = emptyList()
    private var selectedIds: Set<String> = emptySet()

    @SuppressLint("NotifyDataSetChanged")
    fun setSelectedIds(ids: Set<String>) {
        selectedIds = ids
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<VideoItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = items[position]
        val ctx = holder.itemView.context

        holder.b.tvName.text = if (selectedIds.contains(v.uri.toString())) "✓  ${v.name}" else v.name
        holder.b.tvDuration.text = Fmt.duration(v.durationMs)

        val sizeText = Formatter.formatShortFileSize(ctx, v.size)
        holder.b.tvInfo.text = if (v.width > 0 && v.height > 0) {
            "${v.width}×${v.height} • $sizeText"
        } else {
            sizeText
        }

        val pos = Prefs.getPosition(ctx, v.uri.toString())
        if (pos > 0 && v.durationMs > 0) {
            holder.b.watched.visibility = View.VISIBLE
            holder.b.watched.progress = ((pos * 1000) / v.durationMs).toInt().coerceIn(0, 1000)
        } else {
            holder.b.watched.visibility = View.GONE
        }

        Glide.with(holder.b.thumb).load(v.uri).centerCrop().into(holder.b.thumb)
        holder.itemView.setOnClickListener { onClick(v) }
        holder.itemView.setOnLongClickListener {
            onLongClick(v)
            true
        }
    }
}

class FolderAdapter(
    private val onClick: (FolderItem) -> Unit,
    private val onLongClick: (FolderItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    private var items: List<FolderItem> = emptyList()
    private var selectedIds: Set<String> = emptySet()

    @SuppressLint("NotifyDataSetChanged")
    fun setSelectedIds(ids: Set<String>) {
        selectedIds = ids
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<FolderItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        val ctx = holder.itemView.context
        holder.b.tvFolderName.text = if (selectedIds.contains(f.bucketId)) "✓  ${f.name}" else f.name
        holder.b.tvFolderInfo.text = ctx.getString(
            R.string.folder_info,
            f.count,
            Formatter.formatShortFileSize(ctx, f.totalSize)
        )
        holder.itemView.setOnClickListener { onClick(f) }
        holder.itemView.setOnLongClickListener {
            onLongClick(f)
            true
        }
    }
}
