package com.obsidian.apkeditor.ops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class OpStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** Immutable snapshot. Logs capped per op; op list capped globally. */
data class Operation(
    val id: String,
    val type: String,
    val label: String,
    val status: OpStatus,
    val progress: Float = 0f,
    val error: String = "",
    val resultPath: String = "",
    val logs: List<String> = emptyList(),
)

class OperationTracker {

    private val _ops = MutableStateFlow<List<Operation>>(emptyList())
    val ops: StateFlow<List<Operation>> = _ops.asStateFlow()

    fun create(type: String, label: String): Operation {
        val op = Operation(
            id = UUID.randomUUID().toString().take(8),
            type = type,
            label = label.take(128),
            status = OpStatus.PENDING,
        )
        _ops.update { (listOf(op) + it).take(MAX_OPS) }
        return op
    }

    fun update(id: String, transform: (Operation) -> Operation) {
        _ops.update { list ->
            list.map { op ->
                if (op.id != id) op
                else transform(op).let {
                    it.copy(logs = it.logs.takeLast(MAX_LOGS))
                }
            }.take(MAX_OPS)
        }
    }

    fun get(id: String): Operation? = _ops.value.firstOrNull { it.id == id }

    fun appendLog(id: String, line: String) {
        update(id) { it.copy(logs = (it.logs + line.take(500)).takeLast(MAX_LOGS)) }
    }

    companion object {
        private const val MAX_OPS = 50
        private const val MAX_LOGS = 60
    }
}
