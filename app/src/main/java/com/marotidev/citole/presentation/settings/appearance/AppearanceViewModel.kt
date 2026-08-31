package com.marotidev.citole.presentation.settings.appearance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marotidev.citole.data.repository.DataStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val repo: DataStoreRepository
) : ViewModel() {

    val themeMode = repo.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val colorSource = repo.colorSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val customColor = repo.customColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFF3FDAEE.toInt())
    val paletteStyle = repo.paletteStyle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val useBlackTheme = repo.useBlackTheme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setThemeMode(v: Int) = viewModelScope.launch { repo.saveThemeMode(v) }
    fun setColorSource(v: Int) = viewModelScope.launch { repo.saveColorSource(v) }
    fun setCustomColor(v: Int) = viewModelScope.launch { repo.saveCustomColor(v) }
    fun setPaletteStyle(v: Int) = viewModelScope.launch { repo.savePaletteStyle(v) }
    fun setBlackTheme(v: Boolean) = viewModelScope.launch { repo.saveUseBlackTheme(v) }
}
