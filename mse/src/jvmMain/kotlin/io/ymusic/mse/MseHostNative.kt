package io.ymusic.mse

import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface MseHostSink {
    @Keep fun onFrame(width: Int, height: Int, bgra: ByteBuffer)

    @Keep fun onPcm(rate: Int, channels: Int, s16: ByteArray)

    @Keep fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)
}

actual object MseHostNative {
    private val loaded = AtomicBoolean(false)
    private var frameScratch = ByteArray(0)
    private val frameMeta = IntArray(3)

    init {
        loadLibrary()
    }

    @JvmStatic
    actual external fun vtable(): Long

    @JvmStatic
    actual external fun create(): Long

    @JvmStatic
    actual external fun destroy(handle: Long)

    @JvmStatic
    actual external fun frames(handle: Long): Int

    @JvmStatic
    actual external fun samplesPlayed(handle: Long): Long

    actual fun copyFrame(handle: Long, afterGen: Int): NativeMseFrame? {
        if (handle == 0L) return null
        var n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
        if (n <= 0) return null
        if (n > frameScratch.size) {
            frameScratch = ByteArray(n)
            n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
            if (n <= 0 || n > frameScratch.size) return null
        }
        return NativeMseFrame(frameMeta[0], frameMeta[1], frameMeta[2], frameScratch)
    }

    @JvmStatic
    private external fun copyFrameBytes(handle: Long, afterGen: Int, meta: IntArray, dest: ByteArray): Int

    @JvmStatic
    external fun setSink(handle: Long, sink: MseHostSink)

    @JvmStatic
    external fun videoSize(handle: Long, outWh: IntArray): Boolean

    /** 0×0 = decode size. Downscale-only letterbox fit when both > 0. */
    @JvmStatic
    external fun setBlitSize(handle: Long, width: Int, height: Int)

    @JvmStatic
    actual external fun setVolume(handle: Long, vol: Float)

    @JvmStatic
    actual external fun setSpeed(handle: Long, speed: Float, tempo: Float)

    @JvmStatic
    actual external fun setPitch(handle: Long, pitch: Float)

    @JvmStatic
    actual external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic
    actual external fun setBass(handle: Long, db: Float)

    @JvmStatic
    actual external fun setVirtualizer(handle: Long, width: Float)

    actual external fun setPreamp(handle: Long, db: Float)

    @JvmStatic
    actual external fun pcmLevels(handle: Long, out: FloatArray): Int

    /** macOS: overlay VideoToolbox on [component] via JAWT. Other JVMs return false. */
    @JvmStatic
    external fun setVideoComponent(handle: Long, component: java.awt.Component?): Boolean

    private fun loadLibrary() {
        if (!loaded.compareAndSet(false, true)) return
        runCatching { System.loadLibrary("jawt") }
        val name = "mseffmpegjni"
        val libFileName = System.mapLibraryName(name)
        System.getProperty("mse.native.dir")?.let { dir ->
            val folder = File(dir)
            if (isWindows()) loadWinSiblings(folder, libFileName)
            System.load(File(folder, libFileName).absolutePath)
            return
        }
        val libraryPathError = try {
            System.loadLibrary(name)
            return
        } catch (error: UnsatisfiedLinkError) {
            error
        }
        try {
            loadBundled(name, libFileName)
        } catch (error: Throwable) {
            error.addSuppressed(libraryPathError)
            throw error
        }
    }

    private fun loadBundled(name: String, libFileName: String) {
        // Unpack JNI + sibling libav into one temp dir so @loader_path / $ORIGIN resolves.
        val folder = "jni/${hostJniFolder()}"
        val cl = MseHostNative::class.java.classLoader
            ?: throw UnsatisfiedLinkError("No classloader for $name")
        val names = cl.getResourceAsStream("$folder/natives.txt")?.bufferedReader()?.use { it.readLines() }
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf(libFileName)
        val tmpDir = Files.createTempDirectory("mseffmpegjni").toFile().also { it.deleteOnExit() }
        for (n in names) {
            val stream = cl.getResourceAsStream("$folder/$n")
                ?: throw UnsatisfiedLinkError("No bundled $name at $folder/$n")
            stream.use { inp ->
                val dest = File(tmpDir, n).also { it.deleteOnExit() }
                Files.copy(inp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        if (isWindows()) loadWinSiblings(tmpDir, libFileName)
        System.load(File(tmpDir, libFileName).absolutePath)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.US).contains("win")

    /**
     * Windows has no `$ORIGIN`. `System.load` of the JNI library does not search its own
     * folder for avcodec/zlib/winpthread. Load those siblings by absolute path first, leaves
     * before the libraries that import them. A failed attempt is retried once its own
     * dependencies are already mapped.
     */
    private fun loadWinSiblings(folder: File, libFileName: String) {
        val pending = folder.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dll", ignoreCase = true) && !it.name.equals(libFileName, ignoreCase = true) }
            ?.toMutableList()
            ?: return
        var progressed = true
        while (pending.isNotEmpty() && progressed) {
            progressed = false
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val dll = iter.next()
                try {
                    System.load(dll.absolutePath)
                    iter.remove()
                    progressed = true
                } catch (_: UnsatisfiedLinkError) {
                }
            }
        }
    }

    private fun hostJniFolder(): String {
        val os = System.getProperty("os.name").lowercase(Locale.US)
        val arch = System.getProperty("os.arch").lowercase(Locale.US)
        val osPrefix = when {
            os.contains("mac") || os.contains("osx") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("win") -> "windows"
            else -> error("Unsupported OS: $os")
        }
        val archSuffix = when (arch) {
            "aarch64" -> "aarch64"
            "amd64", "x86_64" -> "x64"
            else -> error("Unsupported arch: $arch")
        }
        return "${osPrefix}_$archSuffix"
    }
}
