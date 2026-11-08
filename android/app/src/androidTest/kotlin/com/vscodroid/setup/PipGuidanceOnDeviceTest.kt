package com.vscodroid.setup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.Environment
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Generated helpers run in the APK's bash; only the Python command is substituted. */
@RunWith(AndroidJUnit4::class)
class PipGuidanceOnDeviceTest {
    private lateinit var appContext: Context
    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var bash: File
    private var shellNumber = 0

    private val preferencePrefix = "pip-guidance-on-device-"
    private val home get() = File(sandbox, "home")
    private val bashrc get() = File(home, ".bashrc")
    private val temporary get() = File(sandbox, "tmp")
    private val buildNote = "vscodroid: a package failed to build"
    private val buildError = "ERROR: Failed building wheel for sample"

    private val failingPython = """
        python3() {
            [ "${'$'}1" = -P ] && [ "${'$'}2" = -c ] || return 97
            printf '%s\n' "${'$'}ERRORS" > "${'$'}4"
            printf 'pip-error:%s\n' "${'$'}ERRORS" >&2
            return 3
        }
    """.trimIndent() + "\n"

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(appContext.filesDir, "pip-guidance-on-device")
        assertTrue("could not clear the previous sandbox", sandbox.deleteRecursively())
        assertTrue(home.mkdirs())
        assertTrue(temporary.mkdirs())
        assertTrue(File(sandbox, "projects").mkdirs())
        context = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = sandbox
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File = File(sandbox, "external")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$preferencePrefix$name", mode)
        }
        assertEquals(sandbox, context.applicationContext.filesDir)
        assertTrue(context.getSharedPreferences("vscodroid_setup", Context.MODE_PRIVATE).edit().clear().commit())
        bash = File(context.applicationInfo.nativeLibraryDir, "libbash.so")
        assertTrue("the APK's bash binary is missing: $bash", bash.isFile)
        assertTrue("the APK's bash binary is not executable", bash.canExecute())

        // Bash and readline load these from the extracted prefix on a real install.
        val libraries = File(sandbox, "usr/lib")
        assertTrue(libraries.mkdirs())
        for (name in listOf("libandroid-support.so", "libreadline.so.8", "libncursesw.so.6", "libiconv.so")) {
            context.assets.open("usr/lib/$name").use { input ->
                File(libraries, name).outputStream().use { input.copyTo(it) }
            }
        }

        val npmPath = "usr/lib/node_modules/npm/bin/npm-cli.js"
        val npm = File(sandbox, npmPath)
        assertTrue(npm.parentFile!!.mkdirs())
        context.assets.open(npmPath).use { input -> npm.outputStream().use { input.copyTo(it) } }
        val setup = FirstRunSetup(context)
        FirstRunSetup::class.java.getDeclaredMethod("createBashrc")
            .apply { isAccessible = true }.invoke(setup)
        setup.createNpmWrappers()
        setup.createBashEnvFile()
        assertTrue(bashrc.isFile)
        assertTrue(File(Environment.getBashEnvPath(context)).isFile)
    }

    @After
    fun tearDown() {
        assertTrue("could not remove the test sandbox", sandbox.deleteRecursively())
        assertTrue(appContext.getSharedPreferences("${preferencePrefix}vscodroid_setup", Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    private data class ShellResult(val status: Int, val stdout: String, val stderr: String)

    private fun runShell(script: String, interactive: Boolean = false): ShellResult {
        val streams = File(sandbox, "streams").apply { mkdirs() }
        val number = shellNumber++
        val stdout = File(streams, "$number.stdout")
        val stderr = File(streams, "$number.stderr")
        val arguments = if (interactive) listOf("--noprofile", "--rcfile", bashrc.path, "-ic")
            else listOf("--noprofile", "--norc", "-c")
        val builder = ProcessBuilder(listOf(bash.path) + arguments + script)
            .directory(sandbox).redirectOutput(stdout).redirectError(stderr)
        builder.environment().apply {
            clear()
            put("HOME", home.path)
            put("TMPDIR", temporary.path)
            put("PREFIX", File(sandbox, "usr").path)
            put("PATH", "/system/bin:/system/xbin")
            put("LD_LIBRARY_PATH", "${context.applicationInfo.nativeLibraryDir}:${File(sandbox, "usr/lib")}")
            put("TERM", "dumb")
            if (!interactive) put("BASH_ENV", Environment.getBashEnvPath(context))
        }
        val process = builder.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("the APK's bash did not finish", finished)
        return ShellResult(process.exitValue(), stdout.readText(), stderr.readText())
    }

    private fun assertBuildGuidance(stderr: String) {
        assertEquals("the build note must appear once per shell", 1,
            stderr.lineSequence().count { it.startsWith(buildNote) })
        assertTrue(stderr.contains("optional C/C++ toolchain"))
        assertTrue(stderr.contains("Android-compatible dependencies"))
        assertTrue(stderr.contains("compatible prebuilt wheel"))
        assertFalse(stderr.contains("cannot run one"))
        assertFalse(stderr.contains("cannot be built here"))
    }

    @Test
    fun noninteractiveBuildFailuresKeepStderrAndStatusWithOneAccurateNote() {
        val missingError = "ERROR: No matching distribution found for absent"
        val result = runShell(failingPython + """
            ERRORS='$missingError'
            pip install absent
            printf 'missing=%s\n' "${'$'}?"
            ERRORS='$buildError'
            pip install sample
            printf 'first=%s\n' "${'$'}?"
            pip3 install sample
            status=${'$'}?
            printf 'second=%s\n' "${'$'}status"
            exit "${'$'}status"
        """.trimIndent())

        assertEquals("stdout=${result.stdout}\nstderr=${result.stderr}", 3, result.status)
        assertEquals("missing=3\nfirst=3\nsecond=3\n", result.stdout)
        assertEquals(listOf("pip-error:$missingError", "pip-error:$buildError", "pip-error:$buildError"),
            result.stderr.lineSequence().filter { it.startsWith("pip-error:") }.toList())
        assertFalse("a missing package received build advice",
            result.stderr.substringBefore("pip-error:$buildError").contains("vscodroid:"))
        assertBuildGuidance(result.stderr)
        assertEquals("pip left an error record behind", emptyList<String>(), temporary.list()!!.toList())
    }

    @Test
    fun successfulNoninteractiveInstallIsQuiet() {
        val result = runShell("python3() { return 0; }\npip install sample")

        assertEquals("stdout=${result.stdout}\nstderr=${result.stderr}", 0, result.status)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(emptyList<String>(), temporary.list()!!.toList())
    }

    @Test
    fun existingInteractiveHelperIsUpdatedWithoutReplacingUserContent() {
        bashrc.appendText("\nalias keep_me='printf preserved'\n")
        bashrc.writeText(bashrc.readText().replace(
            "a package failed to build. Native builds may need the optional C/C++ toolchain",
            "a package failed to build. If it has a part written in C, it cannot",
        ).replace(
            "and Android-compatible dependencies, or a compatible prebuilt wheel.",
            "be built here: this device has no C compiler and cannot run one.",
        ))
        assertTrue("the fixture did not stage the older restriction", bashrc.readText().contains("cannot run one"))
        val legacy = bashrc.readBytes()

        FirstRunSetup(context).createNpmWrappers()
        val updated = bashrc.readBytes()
        assertArrayEquals("the update changed existing user bytes", legacy, updated.copyOfRange(0, legacy.size))
        FirstRunSetup(context).createNpmWrappers()
        assertArrayEquals("the next launch appended the helper again", updated, bashrc.readBytes())
        val result = runShell(failingPython + """
            ERRORS='$buildError'
            pip install sample
            status=${'$'}?
            keep_me
            exit "${'$'}status"
        """.trimIndent(), interactive = true)

        assertEquals("stdout=${result.stdout}\nstderr=${result.stderr}", 3, result.status)
        assertEquals("preserved", result.stdout)
        assertTrue(result.stderr.contains("pip-error:$buildError"))
        assertBuildGuidance(result.stderr)
        assertEquals(emptyList<String>(), temporary.list()!!.toList())
    }
}
