package com.r3.conclave.plugin.enclave.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException

abstract class ConclaveTask : DefaultTask() {
    companion object {
        const val CONCLAVE_GROUP = "Conclave"
    }

    init {
        group = CONCLAVE_GROUP
    }

    abstract fun sgxAction()

    @TaskAction
    fun run() {
        try {
            sgxAction()
        } catch (e: Exception) {
            throw (e as? RuntimeException) ?: TaskExecutionException(this, e)
        }
    }
}