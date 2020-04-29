package com.r3.conclave.plugin.enclave.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import javax.inject.Inject

open class SignEnclave @Inject constructor(objects: ObjectFactory, private val enclaveExtension: EnclaveExtension) : ConclaveTask() {
    @get:InputFile
    val inputEnclave: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    val inputKey: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    val signTool: RegularFileProperty = objects.fileProperty()

    @get:OutputFile
    val outputSignedEnclave: RegularFileProperty = objects.fileProperty()

    @get:Internal
    val signedEnclavePath: String get() = outputSignedEnclave.asFile.get().absolutePath

    override fun action() {
        project.exec { spec ->
            spec.commandLine(signTool.asFile.get(), "sign",
                "-key", inputKey.asFile.get(),
                "-enclave", inputEnclave.asFile.get(),
                "-out", outputSignedEnclave.asFile.get(),
                "-config", enclaveExtension.configuration.asFile.get()
            )
        }
        project.logger.lifecycle("Signed enclave binary: $signedEnclavePath")
    }
}
