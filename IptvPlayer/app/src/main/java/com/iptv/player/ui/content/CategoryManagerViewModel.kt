/*
 * CategoryManagerViewModel.kt
 * Backs the per-type category editor: exposes every category (incl. hidden) with
 * its hidden flag + count in the user's custom order, and persists hide toggles
 * and reordering through SettingsStore (DataStore — never the destructive Room
 * database), so they survive playlist/EPG refreshes.
 */
package com.iptv.player.ui.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.ManagedCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class CategoryManagerViewModel(private val type: ContentType) : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    val categories: Flow<List<ManagedCategory>> = repo.observeManagedCategories(type)

    fun setHidden(categoryId: String, hidden: Boolean) {
        viewModelScope.launch { settings.setCategoryHidden(type, categoryId, hidden) }
    }

    fun setOrder(orderedIds: List<String>) {
        viewModelScope.launch { settings.setCategoryOrder(type, orderedIds) }
    }

    fun resetOrder() {
        viewModelScope.launch { settings.resetCategoryOrder(type) }
    }

    class Factory(private val type: ContentType) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CategoryManagerViewModel(type) as T
    }
}
