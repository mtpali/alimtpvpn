package com.v2ray.ang.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.FragmentGroupServerBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.viewmodel.MainViewModel

/** MobileTina manual server page. Editing/sharing/reordering are intentionally not exposed. */
class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>(), SwipeRefreshLayout.OnRefreshListener {
    private val ownerActivity: MainActivity get() = requireActivity() as MainActivity
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MainRecyclerAdapter
    private val subId: String by lazy { arguments?.getString(ARG_SUB_ID).orEmpty() }

    companion object {
        private const val ARG_SUB_ID = "subscriptionId"
        fun newInstance(subId: String) = GroupServerFragment().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentGroupServerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MainRecyclerAdapter(mainViewModel, ActivityAdapterListener())
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 1)
        addCustomDividerToRecyclerView(binding.recyclerView, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter
        binding.refreshLayout.setOnRefreshListener(this)

        mainViewModel.updateListAction.observe(viewLifecycleOwner) { index ->
            if (mainViewModel.subscriptionId == subId) {
                adapter.setData(mainViewModel.serversCache, index)
                ownerActivity.refreshSelectedServerUi()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.subscriptionIdChanged(subId)
        ownerActivity.refreshSelectedServerUi()
    }

    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid == selected) return
        MmkvManager.setSelectServer(guid)
        val fromPosition = mainViewModel.getPosition(selected.orEmpty())
        val toPosition = mainViewModel.getPosition(guid)
        adapter.setSelectServer(fromPosition, toPosition)
        ownerActivity.refreshSelectedServerUi()
        if (mainViewModel.isRunning.value == true) ownerActivity.restartV2Ray()
    }

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) = Unit
        override fun onShare(url: String) = Unit
        override fun onRefreshData() = Unit
        override fun onRemove(guid: String, position: Int) = Unit
        override fun onEdit(guid: String, position: Int, profile: ProfileItem) = Unit
        override fun onSelectServer(guid: String) = setSelectServer(guid)
        override fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean) = Unit
    }

    override fun onRefresh() {
        ownerActivity.importConfigViaSub()
        binding.refreshLayout.isRefreshing = false
    }

    fun scrollToSelectedServer() {
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            ownerActivity.toast(R.string.title_file_chooser)
            return
        }
        val position = mainViewModel.serversCache.indexOfFirst { it.guid == selectedGuid }
        val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager
        if (position >= 0 && layoutManager != null) {
            binding.recyclerView.post {
                layoutManager.scrollToPositionWithOffset(position, binding.recyclerView.height / 3)
            }
        }
    }
}
