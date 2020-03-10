# First enclave

!!! note

    This tutorial assumes you've read and understood the [conceptual overview](enclaves.md).

You can find a **sample app** in the `hello-world` directory of your SDK. You can use this app as a template 
for your own if you want a quick start, or follow the instructions below to add Conclave to an existing project.
It's recommended to read the tutorial in all cases so you understand what the code is doing.

We'll do these things to make our own version of the hello enclave:

1. Configure Gradle. At this time Conclave projects must use Gradle as their build system.
2. Create a new subclass of [`Enclave`](api/com/r3/conclave/enclave/Enclave.html)
3. Implement the `EnclaveCall` interface for local communication.
4. **(Temporary)** Supply XML configurations
5. Write a host program.
6. Run the host and enclave in simulation mode.
7. Run the program in debug mode.

<!---
TODO: Complete this tutorial:

      Signing/running in release mode.
      Remote attestation.
      Java versions of the code (mkdocs-material supports code tabs out of the box)
      Update as API is completed.
-->

!!! important

    * You need the Conclave SDK. If you don't have it please [contact R3 and request a trial](https://www.conclave.net).
    * At this time Conclave development must be done on Linux.

## Configure Gradle

Create a new Gradle project via whatever mechanism you prefer, e.g. IntelliJ can do this via the New Project wizard.
Create two modules defined in the project: one for the host and one for the enclave. The host program may be an
existing server program of some kind, e.g. a web server, but in this tutorial we'll write a command line host.   
    
### `settings.gradle`

In the unzipped SDK there is a directory called `repo` that contains a local Maven repository. This is where the libraries
and Gradle plugin can be found. We need to tell Gradle to look there for plugins.

Create or modify a file called `settings.gradle` in your project root directory so it looks like this:

```groovy
pluginManagement {
    repositories {
        maven {
            def repoPath = file(rootDir.relativePath(file(settings['conclave.repo'])))
            if (repoPath == null)
                throw new Exception("Make sure the 'conclave.repo' setting exists in gradle.properties, or your \$HOME/gradle.properties file. See the Conclave tutorial on https://docs.conclave.net")
            else if (!new File(repoPath, "com").isDirectory())
                throw new Exception("The $repoPath directory doesn't seem to exist or isn't a Maven repository; it should be the SDK 'repo' subdirectory. See the Conclave tutorial on https://docs.conclave.net")
            url = repoPath
        }
        // Add standard repositories back.
        gradlePluginPortal()
        jcenter()
        mavenCentral()
    }
}

include 'enclave'
include 'host'
```
    
The `pluginManagement` block tells Gradle to use a property called `conclave.repo` to find the `repo` directory
in your SDK download. Because developers on your team could unpack the SDK anywhere, they must configure the path
before the build will work. The code above will print a helpful error if they forget or get it wrong.

To set the value a developer can add to [the `gradle.properties` file](https://docs.gradle.org/current/userguide/organizing_gradle_projects.html#declare_properties_in_gradle_properties_file)
a line like this:

```
conclave.repo=/path/to/sdk/repo
```

Gradle properties can be set using a file in the project directory, or more usefully in the developer's home directory. 
    
### `build.gradle`
    
Add the following code to your root `build.gradle` file to import the repository and
load the enclave Gradle plugin:

```groovy
plugins {
    id 'com.r3.sgx.enclave' version '2.0-nightly-224-g32e160dbae' apply false
}

subprojects {
    repositories {
        maven {
            url = rootProject.file(findProperty("conclave.repo"))
        }
        mavenCentral()
    }
}
```

This will ensure the SDK repository can be found in both `enclave` and `host` projects. The `plugin` block tells
Gradle to load but not integrate the enclave plugin.

!!! warning
    Although tempting to load and apply the plugin in the enclave module only, this can cause trouble with Gradle.
    Loading the plugin without applying it like in the above example avoids the issue.

### Configure the host build

In the host module add a dependency on the Conclave host library:

```groovy hl_lines="2"
dependencies {
    implementation "com.r3.conclave:conclave-host:0.1-SNAPSHOT"
}
```

<!--- TODO(mike): CON-61: Sort out versioning -->

SGX enclaves can be be built in one of three modes: simulation, debug and release. Simulation mode doesn't require any
SGX capable hardware. Debug executes the enclave as normal but allows the host process to snoop on and modify the
protected address space, so provides no protection. Release locks out the host and provides the standard SGX
security model, but (at this time) requires the enclave to be signed with a key whitelisted by Intel. See ["Deployment"](deployment.md)
for more information on this.

Add this bit of code to your Gradle file to let the mode be chosen from the command line:

```groovy
// Override the default (simulation) with -PenclaveMode=
def mode = findProperty("enclaveMode")?.toString()?.toLowerCase() ?: "simulation"
```

and then use `mode` in another dependency, this time, on the enclave module:

```groovy hl_lines="2"
dependencies {
    runtimeOnly project(path: ":my-enclave", configuration: mode)
}
```

This says that at runtime (but not compile time) the enclave must be on the classpath, and configures dependencies to
respect the three different variants of the enclave.

### Configure the enclave build

Add the Conclave Gradle plugin:

```groovy hl_lines="2"
plugins {
    id 'com.r3.sgx.enclave'
}
```

and a dependency on the Conclave enclave library:

```groovy hl_lines="2"
dependencies {
    implementation "com.r3.conclave:conclave-enclave:0.1-SNAPSHOT"
}
```

Enclaves are similar to standalone programs and as such have an equivalent to a "main class". This class must be a
subclass of [`Enclave`](/api/com/r3/conclave/enclave/Enclave.html) and we'll write it in a moment. The name of the
class must be specified in the JAR manifest like this, so Conclave can find it:

```groovy hl_lines="3"
jar {
    manifest {
        attributes("Enclave-Class": "com.superfirm.enclave.MyEnclave")    // CHANGE THIS NAME!
    }
}
```

And with that, we're done configuring the build.

## Create a new subclass of `Enclave`

Create your enclave class:

```java
package com.superfirm.enclave;   // CHANGE THIS

import com.r3.conclave.common.enclave.EnclaveCall;
import com.r3.conclave.enclave.Enclave;

/**
 * Simply reverses the bytes that are passed in.
 */
public class ReverseEnclave extends Enclave implements EnclaveCall {
    @Override
    public byte[] invoke(byte[] bytes) {
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++)
            result[i] = bytes[bytes.length - 1 - i];
        return result;
    }
}
```

The `Enclave` class by itself doesn't require you to support direct communication with the host. This is because
sometimes you don't need that and shouldn't have to implement message handlers. In this case we'll use that
functionality because it's a good place to start learning, so we also implement the `EnclaveCall` interface.
There's one method we must supply: `invoke` which takes a byte array and optionally returns a byte array back. Here
we just reverse the contents.

!!! tip
    In a real app you would use the byte array to hold serialised data structures. You can use whatever data formats you
    like. You could use a simple string format or a binary format like protocol buffers.

## Supply XML configurations

!!! note
    This step is temporary. Future versions of the Conclave Gradle plugin will automate it for you.

Enclaves must be configured with some environmental information. This is done via an XML file, one for each type of
release.

Create directories called:

* `src/sgx/Simulation`
* `src/sgx/Debug`
* `src/sgx/Release`

where `src` is the same directory your `src/main/java` directory is in.

!!! warning

    The uppercase starting letter of this directory matters.

Inside the `Simulation` directory create a file called `enclave.xml` and copy/paste this content:

```xml
<EnclaveConfiguration>
    <ProdID>0</ProdID>
    <ISVSVN>0</ISVSVN>
    <StackMaxSize>0x280000</StackMaxSize>
    <HeapMaxSize>0x8000000</HeapMaxSize>
    <TCSNum>10</TCSNum>
    <TCSPolicy>1</TCSPolicy>
    <DisableDebug>0</DisableDebug>
    <MiscSelect>0</MiscSelect>
    <MiscMask>0xFFFFFFFF</MiscMask>
</EnclaveConfiguration>
```

This switches control resource usage and identity of the enclave. For now we can accept the defaults.

<!--- TODO: We should force developers to specify the ISV SVN and ProdID in future -->

## Write a host program

An enclave by itself is just a library. It can't (at this time) be invoked directly. Instead you load it from inside
a host program.

It's easy to load then pass data to and from an enclave:

```java
/**
 * This class demonstrates how to load an enclave and exchange byte arrays with it.
 */
public class Host {
    public static void main(String[] args) throws InvalidEnclaveException {
        // We start by loading the enclave using EnclaveHost, and passing the class name of the Enclave subclass
        // that we defined in our enclave module.
        EnclaveHost enclave = EnclaveHost.load("com.superfirm.enclave.ReverseEnclave");   // CHANGE THIS
        // Start it up.
        enclave.start();
        try {
            // !dlrow olleH      :-)
            System.out.println(callEnclave(enclave,  "Hello world!"));
        } finally {
            // We make sure the .close() method is called on the enclave no matter what.
            //
            // This doesn't actually matter in such a tiny hello world sample, because the enclave will be unloaded by
            // the kernel once we exit like any other resource. It's just here to remind you that an enclave must be
            // explicitly unloaded if you need to reinitialise it for whatever reason, or if you need the memory back.
            //
            // Don't load and unload enclaves too often as it's quite slow.
            enclave.close();
        }
    }

    public static String callEnclave(EnclaveHost enclave, String input) {
        // We'll convert strings to bytes and back.
        final byte[] inputBytes = input.getBytes();

        // Enclaves in general don't have to give bytes back if we send data, but in our case we know it always
        // will so we can just assert it's non-null here.
        final byte[] outputBytes = Objects.requireNonNull(enclave.callEnclave(inputBytes));
        return new String(outputBytes);
    }
}
```

This code starts by creating an [`EnclaveHost`](api/com/r3/conclave/host/EnclaveHost.html) object. It names the class
to load and then calls `start`, which actually loads and initialises the enclave and the `MyEnclave` class inside it.
Note that an `EnclaveHost` allocates memory out of a pool called the "enclave page cache" which is a machine-wide limited
resource. It'll be freed if the host JVM quits, but it's good practice to close the `EnclaveHost` object by calling
`close` on it when done.

Starting and stopping an enclave is not free, so **don't** load the enclave, use it and immediately close it again
as in the above example. Treat the enclave like any other expensive resource and keep it around for as long as you
might need it.

Once we started the enclave, we call it passing in a string as bytes. The enclave will reverse it and we'll print out
the answer.

!!! tip
    You can have multiple `EnclaveHost` objects in the same host JVM but they must all use same mode.

## Run the host and enclave in simulation mode

We can apply the Gradle `application` plugin and set the `mainClassName` property
[in the usual manner](https://docs.gradle.org/current/userguide/application_plugin.html#application_plugin) to let us run
the host from the command line.

Now run:

`gradle host:run`

and it should print "Hello World!" backwards.

During the build you should see output like this:

```
> Task :enclave:generateEnclaveletMetadataSimulation
Succeed.
Enclave measurement: 89cec147162cf2174d3404a2d8b3814eb7c6f818f84ee1ab202ae4e4381f4b49
```

This hex value is called the *measurement*, and is a hash of the code of the enclave. It includes both all the Java
code inside the enclave as a fat-JAR, and all the support and JVM runtime code required. As such it will change any
time you alter the code of your enclave, the version of Conclave in use or the mode (sim/debug/release) of the enclave.

The measurement is reported in an `EnclaveInstanceInfo` remote attestation structure (see [enclaves](enclaves.md) for
a discussion of remote attestation). Everyone should be able to get the exact same value when doing the build, so in
this way your users can audit the contents of a remote enclave over the network.

## Unit testing

In the unit tests you can just load and invoke the enclave as normal. Future versions of Conclave will provide mocked
out APIs so the enclave logic can be tested without involving the real SGX build process, for cross platform portability
and speed.