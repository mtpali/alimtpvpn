package com.v2ray.ang.ui

import android.graphics.Color
import android.os.SystemClock
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerSubSettingBinding
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel

class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?,
    private val secretMode: Boolean = false,
    private val onSecretTap: (String) -> Unit = {}
) : RecyclerView.Adapter<SubSettingRecyclerAdapter.MainViewHolder>(), ItemTouchHelperAdapter {

    override fun getItemCount() = viewModel.getAll().size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val subscriptions = viewModel.getAll()
        val subId = subscriptions[position].guid
        val subItem = subscriptions[position].subscription
        holder.itemSubSettingBinding.tvName.text = subItem.remarks
        holder.itemSubSettingBinding.tvUrl.text = subItem.url
        holder.itemSubSettingBinding.chkEnable.isChecked = subItem.enabled
        holder.itemSubSettingBinding.tvLastUpdated.text = Utils.formatTimestamp(subItem.lastUpdated)
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        holder.resetSecretTaps()

        holder.itemSubSettingBinding.infoContainer.setOnClickListener(if (secretMode) {
            View.OnClickListener {
                if (holder.recordSecretTap(subId)) onSecretTap(subId)
            }
        } else null)

        holder.itemSubSettingBinding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit(subId, position)
        }

        holder.itemSubSettingBinding.layoutRemove.setOnClickListener {
            adapterListener?.onRemove(subId, position)
        }

        holder.itemSubSettingBinding.chkEnable.setOnCheckedChangeListener { it, isChecked ->
            if (!it.isPressed) return@setOnCheckedChangeListener
            subItem.enabled = isChecked
            viewModel.update(subId, subItem)
        }

        if (secretMode) {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.GONE
            holder.itemSubSettingBinding.layoutShare.visibility = View.GONE
            holder.itemSubSettingBinding.layoutEdit.visibility = View.GONE
            holder.itemSubSettingBinding.layoutRemove.visibility = View.GONE
            holder.itemSubSettingBinding.chkEnable.visibility = View.GONE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.VISIBLE
        } else if (TextUtils.isEmpty(subItem.url)) {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.GONE
            holder.itemSubSettingBinding.layoutShare.visibility = View.INVISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.INVISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.INVISIBLE
        } else {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutShare.visibility = View.VISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutShare.setOnClickListener {
                adapterListener?.onShare(subItem.url)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerSubSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onViewRecycled(holder: MainViewHolder) {
        holder.resetSecretTaps()
        super.onViewRecycled(holder)
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerSubSettingBinding) :
        BaseViewHolder(itemSubSettingBinding.root), ItemTouchHelperViewHolder {
        private var secretSubscriptionId: String? = null
        private var secretTapCount = 0
        private var secretTapStartedAt = 0L

        fun resetSecretTaps() {
            secretSubscriptionId = null
            secretTapCount = 0
            secretTapStartedAt = 0L
        }

        fun recordSecretTap(subscriptionId: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (secretSubscriptionId != subscriptionId ||
                secretTapStartedAt == 0L ||
                now - secretTapStartedAt > SECRET_TAP_WINDOW_MS
            ) {
                secretSubscriptionId = subscriptionId
                secretTapCount = 0
                secretTapStartedAt = now
            }
            secretTapCount++
            if (secretTapCount < SECRET_TAP_COUNT) return false
            resetSecretTaps()
            return true
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        viewModel.swap(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
    }

    private companion object {
        const val SECRET_TAP_COUNT = 10
        const val SECRET_TAP_WINDOW_MS = 5_000L
    }
}
