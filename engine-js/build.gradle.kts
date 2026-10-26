import com.google.javascript.jscomp.CheckLevel
import com.google.javascript.jscomp.CommandLineRunner
import com.google.javascript.jscomp.CompilationLevel
import com.google.javascript.jscomp.Compiler
import com.google.javascript.jscomp.CompilerOptions
import com.google.javascript.jscomp.DiagnosticGroups
import com.google.javascript.jscomp.SourceFile

// All engine JS in one place, built into single artifacts shipped with the app and remotely
// replaceable as a whole. Pipeline: tsc --noEmit (typecheck) -> esbuild (bundle only, no minify)
// -> Closure Compiler ADVANCED (minify + full property mangling). Closure knows the stdlib via its
// builtin externs, so EVERYTHING internal mangles — identifiers and property names alike; the only
// preserved names are externs.js (the C natives, the __js* transport globals, `versions`) and
// quoted wire keys (Closure never renames quoted properties; the coding standard writes all wire
// objects quoted/bracketed).
//
// Prod artifacts, same pipeline, ADVANCED: engine.js (scrape, src/index.ts) and
// player.js (MSE player, src/player-index.ts). engine-test.js is both + fixtures.

// Closure Compiler (pure-JVM) on the build classpath, only for this module.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.google.javascript:closure-compiler:v20260629") }
}

plugins {
    base
}

// Each bundle's own revision — Versions.engineJsVersion / playerJsVersion (buildSrc), bumped there
// on every remote push of that bundle. Inlined by esbuild --define as BACKEND_VERSION (`declare
// const` in types.d.ts): the "init" health check reports it; the host logs it on swaps and stamps
// it into error reports. The test bundle carries the engine's.

// Compile-time defines: bare `declare const` identifiers substituted inline at bundle time —
// no runtime global, no extern. PRODUCTION is the same -Pproduction bake as Kotlin
// BuildFlags.PRODUCTION: `debug(function () { return concat; })` does not call the thunk
// when true (esbuild inlines; Closure DCE drops the dead branch).
val production = isProductionBuild()

val srcDir = layout.projectDirectory.dir("src")
val tsconfig = layout.projectDirectory.file("tsconfig.json")
val externsJs = layout.projectDirectory.file("externs.js")
val rawBundleFile = layout.buildDirectory.file("raw/engine.raw.js")
val rawBundleMeta = layout.buildDirectory.file("raw/engine.meta.json")
val rawTestBundleMeta = layout.buildDirectory.file("raw/engine-test.meta.json")
val sizeReportDir = layout.buildDirectory.dir("reports/engine-js")
val bundleFile = layout.buildDirectory.file("dist/engine.js")
val bundleMapFile = layout.buildDirectory.file("dist/engine.js.map")
val rawPlayerBundleFile = layout.buildDirectory.file("raw/player.raw.js")
val rawPlayerBundleMeta = layout.buildDirectory.file("raw/player.meta.json")
val playerBundleFile = layout.buildDirectory.file("dist/player.js")
val playerBundleMapFile = layout.buildDirectory.file("dist/player.js.map")
val testBundleFile = layout.buildDirectory.file("dist/engine-test.js")

fun Exec.unixPath() {
    // Windows PATH uses ';'. Prepending Unix dirs with ':' glues them onto the first
    // real entry and hides node. Leave the process PATH alone there.
    if (System.getProperty("os.name").lowercase().contains("win")) return
    environment(
        "PATH",
        "/opt/homebrew/bin:/usr/local/bin:" + (System.getenv("PATH") ?: "/usr/bin:/bin"),
    )
}

/** Windows has npx.cmd, which CreateProcess will not start. Run the JS entry through node.exe. */
fun Exec.npx(vararg args: String) {
    val win = System.getProperty("os.name").lowercase().contains("win")
    if (!win) {
        commandLine(listOf("npx") + args)
        return
    }
    val nodeHome = File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "nodejs")
    val node = File(nodeHome, "node.exe")
    val cli = File(nodeHome, "node_modules/npm/bin/npx-cli.js")
    commandLine(listOf(node.absolutePath, cli.absolutePath) + args)
}

val jsToolsDir = layout.buildDirectory.dir("js-tools")

val installEsbuild by tasks.registering(Exec::class) {
    outputs.file(jsToolsDir.map { it.file("node_modules/esbuild/package.json") })
    inputs.property("esbuild", Versions.esbuild)
    val win = System.getProperty("os.name").lowercase().contains("win")
    val dest = jsToolsDir.get().asFile
    doFirst { dest.mkdirs() }
    if (win) {
        val nodeHome = File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "nodejs")
        commandLine(
            File(nodeHome, "node.exe").absolutePath,
            File(nodeHome, "node_modules/npm/bin/npm-cli.js").absolutePath,
            "install", "--prefix", dest.absolutePath, "esbuild@${Versions.esbuild}",
        )
    } else {
        commandLine("npm", "install", "--prefix", dest.absolutePath, "esbuild@${Versions.esbuild}")
    }
}

fun Exec.esbuildBundle(
    entry: String,
    out: Provider<org.gradle.api.file.RegularFile>,
    meta: Provider<org.gradle.api.file.RegularFile>,
    bundleName: String,
    version: Long,
) {
    dependsOn(typecheckJs, installEsbuild)
    inputs.dir(srcDir)
    inputs.property("bundle", "$bundleName|$version|$production")
    outputs.files(out, meta)
    workingDir = projectDir
    val win = System.getProperty("os.name").lowercase().contains("win")
    val node = if (win) {
        File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "nodejs/node.exe").absolutePath
    } else {
        "node"
    }
    commandLine(node, projectDir.resolve("bundle-host.mjs").absolutePath)
    doFirst {
        environment("ESBUILD_PREFIX", jsToolsDir.get().asFile.absolutePath)
        environment("ENTRY", entry)
        environment("OUTFILE", out.get().asFile.absolutePath)
        environment("METAFILE", meta.get().asFile.absolutePath)
        environment("BUNDLE_NAME", bundleName)
        environment("BACKEND_VERSION", version.toString())
        environment("PRODUCTION", production.toString())
    }
}

val typecheckJs by tasks.registering(Exec::class) {
    inputs.dir(srcDir)
    inputs.file(tsconfig)
    outputs.file(layout.buildDirectory.file("typecheck.ok"))
    workingDir = projectDir
    unixPath()
    npx("-y", "-p", "typescript@${Versions.typescript}", "tsc", "--noEmit", "-p", ".")
    doLast { outputs.files.singleFile.writeText("ok") }
}

// esbuild resolves TS + modules into one plain script. target=esnext ON PURPOSE: lowering would
// rewrite class fields into __publicField(this, "_x", …) — a QUOTED define whose dotted accesses
// Closure then renames apart (a split property = undefined at runtime). Closure parses the native
// syntax fine. No minification here — Closure owns that.
val bundleRawJs by tasks.registering(Exec::class) {
    esbuildBundle("src/index.ts", rawBundleFile, rawBundleMeta, "engine", Versions.engineJsVersion)
}

// One Closure invocation, shared by both artifacts. Level ADVANCED = full mangling (production);
// SIMPLE = locals only, globals/properties preserved — the interim level for the test bundle so
// the externally-evaled test snippets keep working while they migrate to fixtures, with Closure's
// parser/emitter still validating the output every build. The fixture-migration end state flips
// the test bundle to ADVANCED too.
// What went INTO the raw bundle, per source directory (esbuild's metafile): the shape of the
// growth. Closure's output has no module boundaries left, so the breakdown can only be measured
// before it — the ratio to the final size is what it is, but the ranking survives minification.
fun sourceBreakdown(meta: java.io.File): List<Pair<String, Long>> {
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(meta) as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    val outputs = (json["outputs"] as Map<String, Map<String, Any>>).values
        .first { (it["inputs"] as? Map<*, *>)?.isNotEmpty() == true }

    @Suppress("UNCHECKED_CAST")
    val inputs = outputs["inputs"] as Map<String, Map<String, Any>>
    return inputs.entries
        .groupBy { it.key.substringBeforeLast('/') }
        .map { (dir, entries) -> dir to entries.sumOf { (it.value["bytesInOutput"] as Number).toLong() } }
        .sortedByDescending { it.second }
}

fun reportSize(out: java.io.File, meta: java.io.File) {
    val size = out.length()
    val report = buildString {
        appendLine("${out.name}: $size bytes")
        sourceBreakdown(meta).forEach { (dir, bytes) -> appendLine("  %7d  %s".format(bytes, dir)) }
        appendLine("  (pre-Closure source bytes, from esbuild's metafile)")
    }
    sizeReportDir.get().asFile.apply { mkdirs() }.resolve("${out.name}.size.txt").writeText(report)
    logger.lifecycle(report.trimEnd())
}

fun closureCompile(raw: java.io.File, out: java.io.File, map: java.io.File, meta: java.io.File, level: CompilationLevel) {
    val options = CompilerOptions().apply {
        setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT)
        setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE)
        // CUSTOM = pure-ECMAScript builtin externs (no DOM) — QuickJS has no browser surface,
        // and anything not in externs is fair game for renaming.
        setEnvironment(CompilerOptions.Environment.CUSTOM)
        sourceMapOutputPath = map.absolutePath
    }
    level.setOptionsForCompilationLevel(options)
    // ADVANCED enables quoted->dotted normalization, which would feed the QUOTED wire keys into
    // the renamer and corrupt every JSON contract with the host. Quoted must stay quoted.
    options.setConvertToDottedProperties(false)
    // esnext class fields (assigned via field syntax) false-positive this check en masse;
    // renaming stays consistent regardless. Undefined-variable checks (missing externs) remain.
    options.setWarningLevel(DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.OFF)
    // The input is TS with types ERASED (~64% inferable): the type-BASED property passes split
    // one runtime property into several names on bad inference (observed: _headers in build()).
    // Name-based renaming (same name -> same rename, everywhere) is the sound mode here.
    options.setDisambiguateProperties(false)
    options.setAmbiguateProperties(false)
    options.setInlineProperties(false)
    val externs = CommandLineRunner.getBuiltinExterns(options.environment) +
        SourceFile.fromFile(externsJs.asFile.absolutePath)
    val compiler = Compiler()
    val result = compiler.compile(externs, listOf(SourceFile.fromFile(raw.absolutePath)), options)
    // Warnings surface loudly: an undeclared name here usually means a missing extern — under
    // ADVANCED that is a future runtime breakage, never noise.
    result.warnings.forEach { logger.warn("closure: $it") }
    if (!result.success) {
        throw GradleException("closure $level failed:\n" + result.errors.joinToString("\n"))
    }
    out.parentFile.mkdirs()
    val minified = compiler.toSource()
    check(minified.isNotBlank()) { "closure produced empty output" }
    out.writeText(minified + "\n//# sourceMappingURL=${map.name}\n")
    map.writer().use { compiler.sourceMap.appendTo(it, out.name) }
    reportSize(out, meta)
}

val closureJs by tasks.registering {
    dependsOn(bundleRawJs)
    inputs.file(rawBundleFile)
    inputs.file(externsJs)
    outputs.files(bundleFile, bundleMapFile)
    doLast {
        closureCompile(
            rawBundleFile.get().asFile,
            bundleFile.get().asFile,
            bundleMapFile.get().asFile,
            rawBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

val bundleRawPlayerJs by tasks.registering(Exec::class) {
    esbuildBundle("src/player-index.ts", rawPlayerBundleFile, rawPlayerBundleMeta, "player", Versions.playerJsVersion)
}

val closurePlayerJs by tasks.registering {
    dependsOn(bundleRawPlayerJs)
    inputs.file(rawPlayerBundleFile)
    inputs.file(externsJs)
    outputs.files(playerBundleFile, playerBundleMapFile)
    doLast {
        closureCompile(
            rawPlayerBundleFile.get().asFile,
            playerBundleFile.get().asFile,
            playerBundleMapFile.get().asFile,
            rawPlayerBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

// The TEST bundle: production + fixtures, same pipeline and same ADVANCED level — tests run on
// output as mangled as production's. The few remaining load() snippets touch only externed
// natives, which is exactly what survives.
val rawTestBundleFile = layout.buildDirectory.file("raw/engine-test.raw.js")
val testBundleMapFile = layout.buildDirectory.file("dist/engine-test.js.map")

val bundleRawTestJs by tasks.registering(Exec::class) {
    esbuildBundle("src/test/index.ts", rawTestBundleFile, rawTestBundleMeta, "engine", Versions.engineJsVersion)
}

val bundleTestJs by tasks.registering {
    dependsOn(bundleRawTestJs)
    inputs.file(rawTestBundleFile)
    inputs.file(externsJs)
    outputs.files(testBundleFile, testBundleMapFile)
    doLast {
        closureCompile(
            rawTestBundleFile.get().asFile,
            testBundleFile.get().asFile,
            testBundleMapFile.get().asFile,
            rawTestBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

tasks.named("assemble") { dependsOn(closureJs, closurePlayerJs, bundleTestJs) }

// Consumable artifacts: engineJs = scrape; enginePlayerJs = MSE player;
// engineTestJs = both + fixtures.
val engineJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val enginePlayerJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val engineTestJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(engineJs.name, bundleFile) { builtBy(closureJs) }
    add(enginePlayerJs.name, playerBundleFile) { builtBy(closurePlayerJs) }
    add(engineTestJs.name, testBundleFile) { builtBy(bundleTestJs) }
}
