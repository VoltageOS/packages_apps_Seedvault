/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.R

public abstract class FileSelectionFragment : Fragment() {

    protected abstract val viewModel: SnapshotViewModel
    private lateinit var list: RecyclerView
    private lateinit var adapter: FilesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        requireActivity().setTitle(R.string.select_files_title)

        val v = inflater.inflate(R.layout.fragment_select_files, container, false)
        list = v.findViewById(R.id.list)
        v.findViewById<View>(R.id.fab).setOnClickListener {
            onRestoreButtonClicked()
        }

        return v
    }

    @CallSuper
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FilesAdapter(
            viewModel.fileSelectionManager::onExpandClicked,
            viewModel.fileSelectionManager::onCheckedChanged,
        )
        list.adapter = adapter
        lifecycleScope.launch {
            viewModel.fileSelectionManager.files.flowWithLifecycle(lifecycle, STARTED).collect {
                onFileItemsChanged(it)
            }
        }
    }

    protected abstract fun onRestoreButtonClicked()

    @CallSuper
    protected open fun onFileItemsChanged(filesItems: List<FilesItem>) {
        adapter.submitList(filesItems)
    }
}
