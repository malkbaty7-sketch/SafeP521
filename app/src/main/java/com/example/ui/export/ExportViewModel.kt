@file:kotlin.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EditingOperation
import com.example.data.EditingProject
import com.example.data.ExportTask
import com.example.data.ExportTaskDao
import com.example.export.ExportEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExportViewModel(
    private val exportEngine: ExportEngine,
    private val exportTaskDao: ExportTaskDao
) : ViewModel() {
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun getTasksForProject(projectId: Int): Flow<List<ExportTask>> =
        exportTaskDao.getTasksForProject(projectId)

    fun startExport(project: EditingProject, operations: List<EditingOperation>, quality: String) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                exportEngine.startExport(project = project, operations = operations, quality = quality)
            } catch (error: Exception) {
                _errorMessage.value = error.message ?: "Could not start export"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun cancelExport() {
        exportEngine.cancelCurrentExport()
    }
}
