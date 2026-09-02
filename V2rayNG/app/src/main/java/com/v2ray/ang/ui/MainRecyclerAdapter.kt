package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel

/** MobileTina manual list: one-tap selection, server name and Real Delay only. */
class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {

    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        data = newData?.toMutableList() ?: mutableListOf()
        if (position >= 0 && position in data.indices) notifyItemChanged(position) else notifyDataSetChanged()
    }

    override fun getItemCount(): Int = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder !is MainViewHolder || position !in data.indices) return

        val context = holder.itemView.context
        val item = data[position]
        val guid = item.guid
        holder.itemMainBinding.tvName.text = item.profile.remarks

        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
        holder.itemMainBinding.tvTestResult.text = when {
            delay > 0L -> delay.toString()
            delay < 0L -> context.getString(R.string.mobiletina_ping_inactive)
            else -> ""
        }
        holder.itemMainBinding.tvTestResult.visibility = if (delay == 0L) View.GONE else View.VISIBLE
        holder.itemMainBinding.tvTestResult.setBackgroundResource(
            if (delay < 0L) R.drawable.mobiletina_ping_chip_inactive else R.drawable.mobiletina_ping_chip
        )
        holder.itemMainBinding.tvTestResult.setTextColor(
            ContextCompat.getColor(
                context,
                if (delay < 0L) R.color.colorPingRed else R.color.colorPing
            )
        )

        holder.itemMainBinding.layoutIndicator.setBackgroundColor(
            if (guid == MmkvManager.getSelectServer()) ContextCompat.getColor(context, R.color.mobiletina_server_stroke_selected)
            else ContextCompat.getColor(context, android.R.color.transparent)
        )
        val selected = guid == MmkvManager.getSelectServer()
        holder.itemMainBinding.itemBg.setCardBackgroundColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.mobiletina_server_card_selected else R.color.mobiletina_server_card
            )
        )
        holder.itemMainBinding.itemBg.strokeColor = ContextCompat.getColor(
            context,
            if (selected) R.color.mobiletina_server_stroke_selected else R.color.mobiletina_server_stroke
        )
        holder.itemMainBinding.itemBg.strokeWidth = context.resources.displayMetrics.density
            .times(if (selected) 1.5f else 1f).toInt().coerceAtLeast(1)
        holder.itemMainBinding.tvName.alpha = if (delay < 0L) 0.62f else 1f
        holder.itemMainBinding.infoContainer.setOnClickListener { adapterListener?.onSelectServer(guid) }
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        if (fromPosition in data.indices) notifyItemChanged(fromPosition)
        if (toPosition in data.indices) notifyItemChanged(toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder =
        if (viewType == VIEW_TYPE_ITEM) {
            MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun getItemViewType(position: Int): Int = if (position == data.size) VIEW_TYPE_FOOTER else VIEW_TYPE_ITEM

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() = Unit
        fun onItemClear() = Unit
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    // Reorder is intentionally disabled in MobileTina manual mode.
    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean = false
    override fun onItemMoveCompleted() = Unit
    override fun onItemDismiss(position: Int) = Unit
}
