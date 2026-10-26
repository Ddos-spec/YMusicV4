import { mkdirSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";

// Loaded from the npm prefix Gradle just installed. createRequire needs a file inside that
// prefix so "esbuild" resolves to <prefix>/node_modules/esbuild, not a nested path.
const require = createRequire(join(process.env.ESBUILD_PREFIX, "package.json"));
const esbuild = require("esbuild");

mkdirSync(dirname(process.env.OUTFILE), { recursive: true });
mkdirSync(dirname(process.env.METAFILE), { recursive: true });

const result = await esbuild.build({
    entryPoints: [process.env.ENTRY],
    bundle: true,
    format: "iife",
    target: "esnext",
    charset: "utf8",
    loader: { ".raw.js": "text" },
    define: {
        BUNDLE_NAME: JSON.stringify(process.env.BUNDLE_NAME),
        BACKEND_VERSION: process.env.BACKEND_VERSION,
        PRODUCTION: process.env.PRODUCTION,
    },
    metafile: true,
    outfile: process.env.OUTFILE,
    logLevel: "info",
});

writeFileSync(process.env.METAFILE, JSON.stringify(result.metafile));
