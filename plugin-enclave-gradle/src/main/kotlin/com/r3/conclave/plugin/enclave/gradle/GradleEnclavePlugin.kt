package com.r3.conclave.plugin.enclave.gradle

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.r3.conclave.common.EnclaveMode
import com.r3.conclave.common.internal.PluginUtils.ENCLAVE_BUNDLES_PATH
import com.r3.conclave.common.internal.PluginUtils.ENCLAVE_PROPERTIES
import com.r3.conclave.common.internal.PluginUtils.GRAALVM_BUNDLE_NAME
import com.r3.conclave.common.internal.PluginUtils.GRAMINE_BUNDLE_NAME
import com.r3.conclave.common.internal.PluginUtils.getManifestAttribute
import com.r3.conclave.plugin.enclave.gradle.ConclaveTask.Companion.CONCLAVE_GROUP
import com.r3.conclave.plugin.enclave.gradle.gramine.GenerateGramineBundle
import com.r3.conclave.plugin.enclave.gradle.RuntimeType.GRAALVM
import com.r3.conclave.plugin.enclave.gradle.RuntimeType.GRAMINE
import com.r3.conclave.utilities.internal.copyResource
import org.gradle.api.*
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression.STORED
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.util.VersionNumber
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.util.stream.Collectors.toList
import javax.inject.Inject
import kotlin.io.path.*

class GradleEnclavePlugin @Inject constructor(private val layout: ProjectLayout) : Plugin<Project> {
    companion object {
        private val CONCLAVE_SDK_VERSION = getManifestAttribute("Conclave-Release-Version")
        private val CONCLAVE_GRAALVM_VERSION = getManifestAttribute("Conclave-GraalVM-Version")
    }

    private var pythonSourcePath: Path? = null
    private lateinit var runtimeType: Provider<RuntimeType>

    private val baseDirectory: Path by lazy { layout.buildDirectory.get().asFile.toPath() / "conclave" }

    override fun apply(target: Project) {
        checkGradleVersionCompatibility(target)
        target.logger.info("Applying Conclave gradle plugin for version $CONCLAVE_SDK_VERSION")

        // Allow users to specify the enclave dependency like this: implementation "com.r3.conclave:conclave-enclave"
        autoconfigureDependencyVersions(target)

        target.pluginManager.apply(JavaPlugin::class.java)
        target.pluginManager.apply(ShadowPlugin::class.java)

        val conclaveExtension = target.extensions.create("conclave", ConclaveExtension::class.java)

        val sourcePaths = Files.list(target.projectDir.toPath() / "src" / "main").use { it.collect(toList()) }
        pythonSourcePath = if (sourcePaths.size == 1 && sourcePaths[0].name == "python") sourcePaths[0] else null

        // Parse the runtime string into the enum and then make sure it's consistent with whether this project is
        // Python or not.
        runtimeType = conclaveExtension.runtime
            // Provider.map is not called if the upstream value is not set (i.e. null), but we want execute for null,
            // so we supply a token string for null
            .convention(" null ")
            .map { string ->
                val enum = try {
                    if (string == " null ") null else RuntimeType.valueOf(string.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw GradleException(
                        "'${conclaveExtension.runtime.get()}' is not a valid enclave runtime type.\n" +
                                "Valid runtime types are: ${RuntimeType.values().joinToString { it.name.lowercase() }}.")
                }
                if (pythonSourcePath != null) {
                    if (enum == GRAALVM) {
                        // The user has explicitly specified GraalVM whilst also intending to have Python code.
                        throw GradleException("Python enclave with GraalVM not supported. Use 'gramine' instead.")
                    }
                    GRAMINE  // Python projects must always use Gramine
                } else {
                    enum ?: GRAALVM
                }
            }

        target.afterEvaluate {
            // This is called before the build tasks are executed but after the build.gradle file
            // has been parsed. This gives us an opportunity to perform actions based on the user configuration
            // of the enclave.

            // Only add the GraalVM SDK as a dependency if the enclave is going to need it.
            if (runtimeType.get() == GRAALVM) {
                // If the user has asked for language support, then we need to expose the GraalVM polygot API to
                // them with "implementation", otherwise "runtimeOnly" is sufficient for a GraalVM enclave.
                val configuration = if (conclaveExtension.supportLanguages.get().isEmpty()) "runtimeOnly" else "implementation"
                target.dependencies.add(configuration, "com.r3.conclave:graal-sdk:$CONCLAVE_GRAALVM_VERSION")
            }

            // Add dependencies automatically (so developers don't have to)
            target.dependencies.add("implementation", "com.r3.conclave:conclave-enclave:$CONCLAVE_SDK_VERSION")
            target.dependencies.add("testImplementation", "com.r3.conclave:conclave-host:$CONCLAVE_SDK_VERSION")

            // Make sure that the user has specified productID, print friendly error message if not
            if (!conclaveExtension.productID.isPresent) {
                throw GradleException(
                        "Enclave product ID not specified! " +
                        "Please set the 'productID' property in the build configuration for your enclave.\n" +
                        "If you're unsure what this error message means, please consult the conclave documentation.")
            }
            // Make sure that the user has specified revocationLevel, print friendly error message if not
            if (!conclaveExtension.revocationLevel.isPresent) {
                throw GradleException(
                        "Enclave revocation level not specified! " +
                        "Please set the 'revocationLevel' property in the build configuration for your enclave.\n" +
                        "If you're unsure what this error message means, please consult the conclave documentation.")
            }
        }

        target.createTask<EnclaveClassName>("enclaveClassName") { task ->
            task.dependsOn(target.tasks.withType(JavaCompile::class.java))
            task.inputClassPath.set(getMainSourceSet(target).runtimeClasspath)
        }

        val generateEnclavePropertiesTask = target.createTask<GenerateEnclaveProperties>(
            "generateEnclaveProperties",
        ) {
            it.conclaveExtension.set(conclaveExtension)
            it.enclavePropertiesFile.set(baseDirectory.resolve(ENCLAVE_PROPERTIES).toFile())
        }

        val enclaveFatJarTask = if (pythonSourcePath == null) {
            target.tasks.withType(ShadowJar::class.java).getByName("shadowJar")
        } else {
            // This is a bit hacky. It essentially "converts" the pre-compiled adapter jar into a Gradle Jar task.
            target.createTask<Jar>("pythonEnclaveAdapterJar") { task ->
                task.first {
                    val adapterJar = task.temporaryDir.resolve("python-enclave-adapter.jar").toPath()
                    javaClass.copyResource("/python-support/enclave-adapter.jar", adapterJar)
                    task.from(target.zipTree(adapterJar))
                }
            }
        }

        enclaveFatJarTask.makeReproducible()
        enclaveFatJarTask.archiveAppendix.set("fat")
        enclaveFatJarTask.from(generateEnclavePropertiesTask.enclavePropertiesFile) { copySpec ->
            enclaveFatJarTask.onEnclaveClassName { enclaveClassName ->
                copySpec.into(enclaveClassName.substringBeforeLast('.').replace('.', '/'))
                copySpec.rename { ENCLAVE_PROPERTIES }
            }
        }

        registerNonMockArtifacts(target, conclaveExtension, enclaveFatJarTask)
        // The artifact for mock mode is the just the enclave fat jar.
        registerCrossProjectArtifact(target, EnclaveMode.MOCK, enclaveFatJarTask)
    }

    private fun createGenerateGramineBundleTask(
            target: Project,
            enclaveMode: EnclaveMode,
            conclaveExtension: ConclaveExtension,
            enclaveFatJarTask: Jar,
            signingKey: Provider<RegularFile?>,
            linuxExec: LinuxExec
    ): GenerateGramineBundle {
        return target.createTask("generateGramine${enclaveMode.capitalise()}Bundle", enclaveMode, linuxExec) { task ->
            task.dependsOn(linuxExec)
            task.signingKey.set(signingKey)
            task.productId.set(conclaveExtension.productID)
            task.revocationLevel.set(conclaveExtension.revocationLevel)
            task.maxThreads.set(conclaveExtension.maxThreads)
            task.enclaveJar.set(enclaveFatJarTask.archiveFile)
            task.extraJavaModules.set(conclaveExtension.extraJavaModules)
            task.enclaveSize.set(conclaveExtension.enclaveSize)

            if (pythonSourcePath != null) {
                val pythonFiles = target.fileTree(pythonSourcePath).files
                task.pythonFile.set(pythonFiles.first())
            }
            task.outputDir.set((baseDirectory / enclaveMode.name.lowercase() / "gramine-bundle").toFile())
        }
    }

    private fun createGramineBundleZipTask(
        target: Project,
        enclaveMode: EnclaveMode,
        generateGramineBundleTask: GenerateGramineBundle
    ): TaskProvider<Zip> {
        return target.tasks.register("gramine${enclaveMode.capitalise()}BundleZip", Zip::class.java) { task ->
            // No need to do any compression here, we're only using zip as a container. The compression will be done
            // by the containing jar.
            task.entryCompression = STORED
            task.from(generateGramineBundleTask.outputDir)
            task.destinationDirectory.set((baseDirectory / enclaveMode.name.lowercase()).toFile())
            task.archiveBaseName.set("gramine-bundle")
        }
    }

    fun signToolPath(): Path = getSgxTool("sgx_sign")

    fun ldPath(): Path = getSgxTool("ld")

    private fun getSgxTool(name: String): Path {
        val path = baseDirectory / "sgx-tools" / name
        if (!path.exists()) {
            javaClass.copyResource("/sgx-tools/$name", path)
            path.setPosixFilePermissions(path.getPosixFilePermissions() + OWNER_EXECUTE)
        }
        return path
    }

    /**
     * Get the main source set for a given project
     */
    private fun getMainSourceSet(project: Project): SourceSet {
        return (project.properties["sourceSets"] as SourceSetContainer).getByName("main")
    }

    private fun registerNonMockArtifacts(
        target: Project,
        conclaveExtension: ConclaveExtension,
        enclaveFatJarTask: Jar
    ) {
        val createDummyKeyTask = target.createTask<GenerateDummyMrsignerKey>("createDummyKey") { task ->
            task.outputKey.set(baseDirectory.resolve("dummy_key.pem").toFile())
        }

        val generateReflectionConfigTask =
            target.createTask<GenerateReflectionConfig>("generateReflectionConfig") { task ->
                val enclaveClassNameTask = target.tasks.withType(EnclaveClassName::class.java).single()
                task.dependsOn(enclaveClassNameTask)
                task.enclaveClass.set(enclaveClassNameTask.outputEnclaveClassName)
                task.reflectionConfig.set(baseDirectory.resolve("reflectconfig").toFile())
            }

        val generateAppResourcesConfigTask =
            target.createTask<GenerateAppResourcesConfig>("generateAppResourcesConfig") { task ->
                task.jarFile.set(enclaveFatJarTask.archiveFile)
                task.appResourcesConfigFile.set((baseDirectory / "app-resources-config.json").toFile())
            }

        val copyGraalVM = target.createTask<Exec>("copyGraalVM") { task ->
            // Create a configuration for downloading graalvm-*.tar.gz using Gradle
            val graalVMConfigName = "${task.name}Config"
            val configuration = target.configurations.create(graalVMConfigName)
            target.dependencies.add(graalVMConfigName, "com.r3.conclave:graalvm:$CONCLAVE_GRAALVM_VERSION@tar.gz")
            task.dependsOn(configuration)

            // This is a hack to delay the execution of the code inside toString.
            // Gradle has three stages, initialization, configuration, and execution.
            // The code inside the toString function must run during the execution stage. For that to happen,
            // the following wrapper was created
            class LazyGraalVmFile(target: Project) {
                val graalVMAbsolutePath by lazy { target.configurations.findByName(graalVMConfigName)!!.files.single() { it.name.endsWith("tar.gz") }.absolutePath }
                override fun toString(): String {
                    return graalVMAbsolutePath
                }
            }

            val outputDir = baseDirectory.resolve("graalvm").createDirectories()
            task.outputs.dir(outputDir)
            task.workingDir(outputDir)
            // Uncompress the graalvm-*.tar.gz
            task.commandLine("tar", "xf", LazyGraalVmFile(target))
        }

        val isPythonEnclave = pythonSourcePath != null

        val linuxExec = target.createTask<LinuxExec>("setupLinuxExecEnvironment", isPythonEnclave) { task ->
            task.baseDirectory.set(target.projectDir.toPath().toString())
            task.tag.set("conclave-build:latest")
            task.buildInDocker.set(conclaveExtension.buildInDocker)
            task.runtimeType.set(runtimeType)
        }

        for (enclaveMode in EnclaveMode.values()) {
            val enclaveExtension = when (enclaveMode) {
                EnclaveMode.RELEASE -> conclaveExtension.release
                EnclaveMode.DEBUG -> conclaveExtension.debug
                EnclaveMode.SIMULATION -> conclaveExtension.simulation
                EnclaveMode.MOCK -> continue
            }
            val enclaveBundleJar = createNonMockEnclaveBundleJar(
                target,
                conclaveExtension,
                enclaveExtension,
                createDummyKeyTask,
                enclaveFatJarTask,
                linuxExec,
                copyGraalVM,
                generateReflectionConfigTask,
                generateAppResourcesConfigTask
            )
            registerCrossProjectArtifact(target, enclaveMode, enclaveBundleJar)
        }
    }

    private fun createNonMockEnclaveBundleJar(
        target: Project,
        conclaveExtension: ConclaveExtension,
        enclaveExtension: EnclaveExtension,
        createDummyKeyTask: GenerateDummyMrsignerKey,
        enclaveFatJarTask: Jar,
        linuxExec: LinuxExec,
        copyGraalVM: Task,
        generateReflectionConfigTask: GenerateReflectionConfig,
        generateAppResourcesConfigTask: GenerateAppResourcesConfig
    ): Jar {
            val signingKey = enclaveExtension.signingType.flatMap {
                when (it) {
                    SigningType.DummyKey -> createDummyKeyTask.outputKey
                    SigningType.PrivateKey -> enclaveExtension.signingKey
                    else -> target.provider { null }
                }
            }

            val enclaveMode = enclaveExtension.enclaveMode
            val modeLowerCase = enclaveMode.name.lowercase()
            val enclaveModeDir = baseDirectory.resolve(modeLowerCase)

            // Gramine related tasks
            val generateGramineBundleTask = createGenerateGramineBundleTask(
                target,
                enclaveMode,
                conclaveExtension,
                enclaveFatJarTask,
                signingKey,
                linuxExec
            )
            val gramineBundleZipTask = createGramineBundleZipTask(target, enclaveMode, generateGramineBundleTask)

            // GraalVM related tasks

            val unsignedEnclaveFile = enclaveModeDir.resolve("enclave.so").toFile()

            val linkerScriptFile = baseDirectory.resolve("Enclave.lds")
            val buildUnsignedGraalEnclaveTask = target.createTask<NativeImage>(
                "buildUnsignedGraalEnclave${enclaveMode.capitalise()}",
                this,
                enclaveMode,
                linkerScriptFile,
                linuxExec
            ) { task ->
                task.dependsOn(
                    copyGraalVM,
                    generateReflectionConfigTask,
                    generateAppResourcesConfigTask,
                    linuxExec
                )
                task.nativeImagePath.set(copyGraalVM.outputs.files.singleFile)
                task.jarFile.set(enclaveFatJarTask.archiveFile)
                task.reflectionConfiguration.set(generateReflectionConfigTask.reflectionConfig)
                task.appResourcesConfig.set(generateAppResourcesConfigTask.appResourcesConfigFile)
                task.reflectionConfigurationFiles.from(conclaveExtension.reflectionConfigurationFiles)
                task.serializationConfigurationFiles.from(conclaveExtension.serializationConfigurationFiles)
                task.maxStackSize.set(conclaveExtension.maxStackSize)
                task.maxHeapSize.set(conclaveExtension.maxHeapSize)
                task.supportLanguages.set(conclaveExtension.supportLanguages)
                task.deadlockTimeout.set(conclaveExtension.deadlockTimeout)
                task.outputEnclave.set(unsignedEnclaveFile)
            }

            val buildUnsignedEnclaveTask =
                target.createTask<BuildUnsignedEnclave>("buildUnsignedEnclave${enclaveMode.capitalise()}") { task ->
                    task.inputEnclave.set(buildUnsignedGraalEnclaveTask.outputEnclave)
                    task.outputEnclave.set(task.inputEnclave.get())
                }

            val generateEnclaveConfigTask =
                target.createTask<GenerateEnclaveConfig>("generateEnclaveConfig${enclaveMode.capitalise()}", enclaveMode) { task ->
                    task.productID.set(conclaveExtension.productID)
                    task.revocationLevel.set(conclaveExtension.revocationLevel)
                    task.maxHeapSize.set(conclaveExtension.maxHeapSize)
                    task.maxStackSize.set(conclaveExtension.maxStackSize)
                    task.tcsNum.set(conclaveExtension.maxThreads)
                    task.outputConfigFile.set(enclaveModeDir.resolve("enclave.xml").toFile())
                }

            val signEnclaveWithKeyTask = target.createTask<SignEnclave>(
                    "signEnclaveWithKey${enclaveMode.capitalise()}",
                    this,
                    linuxExec
                ) { task ->
                    task.inputs.files(
                        buildUnsignedEnclaveTask.outputEnclave,
                        generateEnclaveConfigTask.outputConfigFile
                    )
                    task.inputEnclave.set(buildUnsignedEnclaveTask.outputEnclave)
                    task.inputEnclaveConfig.set(generateEnclaveConfigTask.outputConfigFile)
                    task.inputKey.set(signingKey)
                    task.outputSignedEnclave.set(enclaveModeDir.resolve("enclave.signed.so").toFile())
                    task.buildInDocker.set(conclaveExtension.buildInDocker)
                }

            val generateEnclaveSigningMaterialTask = target.createTask<GenerateEnclaveSigningMaterial>(
                "generateEnclaveSigningMaterial${enclaveMode.capitalise()}",
                this,
                linuxExec
            ) { task ->
                task.description = "Generate standalone signing material for an ${enclaveMode.name.lowercase()} mode " +
                        "enclave that can be used with an external signing source."
                task.inputs.files(
                    buildUnsignedEnclaveTask.outputEnclave,
                    generateEnclaveConfigTask.outputConfigFile,
                )
                task.buildInDocker.set(conclaveExtension.buildInDocker)
                task.inputEnclave.set(buildUnsignedEnclaveTask.outputEnclave)
                task.inputEnclaveConfig.set(generateEnclaveConfigTask.outputConfigFile)
                task.signatureDate.set(enclaveExtension.signatureDate)
                task.outputSigningMaterial.set(enclaveExtension.signingMaterial)
            }

            val addEnclaveSignatureTask = target.createTask<AddEnclaveSignature>(
                "addEnclaveSignature${enclaveMode.capitalise()}",
                this,
                linuxExec
            ) { task ->
                    /**
                     * Setting a dependency on a task (at least a `Copy` task) doesn't mean we'll be depending on the task's output.
                     * Despite the dependency task running when out of date, the dependent task would then be considered up-to-date,
                     * even when declaring `dependsOn`.
                     */
                    task.inputs.files(
                        generateEnclaveSigningMaterialTask.inputEnclave,
                        generateEnclaveSigningMaterialTask.outputSigningMaterial,
                        generateEnclaveConfigTask.outputConfigFile,
                        enclaveExtension.mrsignerPublicKey,
                        enclaveExtension.mrsignerSignature
                    )
                    task.inputEnclave.set(generateEnclaveSigningMaterialTask.inputEnclave)
                    task.inputSigningMaterial.set(generateEnclaveSigningMaterialTask.outputSigningMaterial)
                    task.inputEnclaveConfig.set(generateEnclaveConfigTask.outputConfigFile)
                    task.inputMrsignerPublicKey.set(enclaveExtension.mrsignerPublicKey.map {
                        if (!it.asFile.exists()) {
                            throwMissingFileForExternalSigning("mrsignerPublicKey")
                        }
                        it
                    })
                    task.inputMrsignerSignature.set(enclaveExtension.mrsignerSignature.map {
                        if (!it.asFile.exists()) {
                            throwMissingFileForExternalSigning("mrsignerSignature")
                        }
                        it
                    })
                    task.outputSignedEnclave.set(enclaveModeDir.resolve("enclave.signed.so").toFile())
                    task.buildInDocker.set(conclaveExtension.buildInDocker)
                }

            val generateEnclaveMetadataTask = target.createTask<GenerateEnclaveMetadata>(
                "generateEnclaveMetadata${enclaveMode.capitalise()}",
                this,
                enclaveMode,
                linuxExec
            ) { task ->
                val signingTask = enclaveExtension.signingType.map {
                    when (it) {
                        SigningType.DummyKey -> signEnclaveWithKeyTask
                        SigningType.PrivateKey -> signEnclaveWithKeyTask
                        else -> addEnclaveSignatureTask
                    }
                }
                task.dependsOn(signingTask)
                val signedEnclaveFile = enclaveExtension.signingType.flatMap {
                    when (it) {
                        SigningType.DummyKey -> signEnclaveWithKeyTask.outputSignedEnclave
                        SigningType.PrivateKey -> signEnclaveWithKeyTask.outputSignedEnclave
                        else -> {
                            if (!enclaveExtension.mrsignerPublicKey.isPresent) {
                                throwMissingConfigForExternalSigning("mrsignerPublicKey")
                            }
                            if (!enclaveExtension.mrsignerSignature.isPresent) {
                                throwMissingConfigForExternalSigning("mrsignerSignature")
                            }
                            addEnclaveSignatureTask.outputSignedEnclave
                        }
                    }
                }
                task.buildInDocker.set(conclaveExtension.buildInDocker)
                task.inputSignedEnclave.set(signedEnclaveFile)
                task.inputs.files(signedEnclaveFile)
                task.mrsignerOutputFile.set(baseDirectory.resolve("mrsigner").toFile())
                task.mrenclaveOutputFile.set(baseDirectory.resolve("mrenclave").toFile())
            }

            val buildSignedEnclaveTask = target.createTask<BuildSignedEnclave>("buildSignedEnclave${enclaveMode.capitalise()}") { task ->
                task.dependsOn(generateEnclaveMetadataTask)
                task.inputs.files(generateEnclaveMetadataTask.inputSignedEnclave)
                task.outputSignedEnclave.set(generateEnclaveMetadataTask.inputSignedEnclave)
            }

            return target.createTask("enclaveBundle${enclaveMode.capitalise()}Jar") { task ->
                task.group = CONCLAVE_GROUP
                task.description = "Build a Conclave enclave in ${enclaveMode.name.lowercase()} mode"
                task.archiveAppendix.set("bundle")
                task.archiveClassifier.set(modeLowerCase)
                task.makeReproducible()

                val bundleOutput: Provider<RegularFile> = runtimeType.flatMap {
                    when (it) {
                        // buildSignedEnclaveTask determines which of the three Conclave supported signing methods
                        // to use to sign the enclave and invokes the correct task accordingly.
                        GRAALVM -> buildSignedEnclaveTask.outputSignedEnclave
                        GRAMINE -> gramineBundleZipTask.get().archiveFile
                        else -> throw IllegalArgumentException()
                    }
                }
                task.from(bundleOutput)

                task.rename {
                    val bundleName = when (runtimeType.get()) {
                        GRAALVM -> GRAALVM_BUNDLE_NAME
                        GRAMINE -> GRAMINE_BUNDLE_NAME
                        else -> throw IllegalArgumentException()
                    }
                    "$modeLowerCase-$bundleName"
                }

                task.onEnclaveClassName { enclaveClassName ->
                    task.into("$ENCLAVE_BUNDLES_PATH/$enclaveClassName")
                }
            }
    }

    private fun Jar.makeReproducible() {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        isZip64 = true
    }

    // https://docs.gradle.org/current/userguide/cross_project_publications.html
    private fun registerCrossProjectArtifact(target: Project, enclaveMode: EnclaveMode, artifactJar: Jar) {
        val configuration = target.configurations.create(enclaveMode.name.lowercase()) {
            it.isCanBeConsumed = true
            it.isCanBeResolved = false
        }
        target.artifacts.add(configuration.name, artifactJar.archiveFile)
    }

    private fun throwMissingFileForExternalSigning(config: String): Nothing {
        throw GradleException(
            "Your enclave is configured to be signed with an external key but the file specified by '$config' in " +
                    "your build.gradle does not exist. Refer to " +
                    "https://docs.conclave.net/signing.html#generating-keys-for-signing-an-enclave for instructions " +
                    "on how to sign your enclave."
        )
    }

    private fun throwMissingConfigForExternalSigning(config: String): Nothing {
        throw GradleException(
            "Your enclave is configured to be signed with an external key but the configuration '$config' in your " +
                    "build.gradle does not exist. Refer to " +
                    "https://docs.conclave.net/signing.html#how-to-configure-signing-for-your-enclaves for " +
                    "instructions on how to configure signing your enclave."
        )
    }

    private fun checkGradleVersionCompatibility(target: Project) {
        val gradleVersion = target.gradle.gradleVersion
        if (VersionNumber.parse(gradleVersion).baseVersion < VersionNumber(5, 6, 4, null)) {
            throw GradleException("Project ${target.name} is using Gradle version $gradleVersion but the Conclave " +
                    "plugin requires at least version 5.6.4.")
        }
    }

    private fun autoconfigureDependencyVersions(target: Project) {
        target.configurations.all { configuration ->
            configuration.withDependencies { dependencySet ->
                dependencySet
                        .filterIsInstance<ExternalDependency>()
                        .filter { it.group == "com.r3.conclave" && it.version.isNullOrEmpty() }
                        .forEach { dep ->
                            dep.version {
                                it.require(CONCLAVE_SDK_VERSION)
                            }
                        }
            }
        }
    }

    private fun EnclaveMode.capitalise(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * Helper method to perform some action when the enclave class name is available.
     */
    private fun Task.onEnclaveClassName(block: (String) -> Unit) {
        val enclaveClassNameTask = project.tasks.withType(EnclaveClassName::class.java).singleOrNull()
        checkNotNull(enclaveClassNameTask) {
            "onEnclaveClassName can only be used after the EnclaveClassName task has been created"
        }
        if (pythonSourcePath == null) {
            dependsOn(enclaveClassNameTask)
            first {
                block(enclaveClassNameTask.outputEnclaveClassName.get())
            }
        } else {
            // This is a bit of a hack, but if the enclave is in Python then we're using the
            // PythonEnclaveAdapter class.
            first {
                block("com.r3.conclave.python.PythonEnclaveAdapter")
            }
        }
    }

    // Hack to get around Gradle warning on Java lambdas: https://docs.gradle.org/7.3.1/userguide/validation_problems.html#implementation_unknown
    private fun Task.first(block: () -> Unit) {
        doFirst(ActionWrapper(block))
    }

    private class ActionWrapper(private val block: () -> Unit) : Action<Task> {
        override fun execute(t: Task) = block()
    }

    private inline fun <reified T : Task> Project.createTask(name: String, vararg constructorArgs: Any?, configure: (T) -> Unit): T {
        val task = tasks.create(name, T::class.java, *constructorArgs)
        configure(task)
        return task
    }
}
