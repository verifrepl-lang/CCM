package com.crabcode.ide.files

/**
 * Extension → SVG icon mapping, ported from the desktop iconMap.ts.
 * The original SVG assets live in assets/img/ and render via coil-svg.
 */
object FileIcons {

    private val BY_EXTENSION: Map<String, String> = mapOf(
        "py" to "python", "pyw" to "python", "pyi" to "python",
        "js" to "js", "mjs" to "js", "cjs" to "js",
        "ts" to "ts", "mts" to "ts", "cts" to "ts",
        "tsx" to "react-ts", "jsx" to "react",
        "rs" to "rust", "go" to "go", "java" to "java", "kt" to "kotlin",
        "kts" to "kotlin", "swift" to "swift", "c" to "c", "h" to "c",
        "cpp" to "cplus", "cc" to "cplus", "cxx" to "cplus", "hpp" to "cplus",
        "cs" to "csharp", "rb" to "ruby", "php" to "php", "pl" to "perl",
        "lua" to "lua", "r" to "r", "jl" to "julia", "ex" to "elixir",
        "exs" to "elixir", "erl" to "erlang", "hs" to "haskell",
        "scala" to "scala", "clj" to "clojure", "dart" to "dart",
        "zig" to "zig", "nim" to "nim", "v" to "v", "asm" to "assembly", "s" to "assembly",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell", "fish" to "shell",
        "ps1" to "shell", "bat" to "shell", "cmd" to "shell",
        "html" to "brackets-orange", "htm" to "brackets-orange", "xhtml" to "brackets-orange",
        "css" to "css.png", "scss" to "sass", "sass" to "sass", "less" to "brackets-pink",
        "json" to "brackets-blue", "jsonc" to "brackets-blue",
        "yaml" to "yaml", "yml" to "yaml", "toml" to "brackets-gray", "ini" to "brackets-gray",
        "xml" to "xml", "svg" to "svg", "md" to "markdown", "markdown" to "markdown",
        "txt" to "text", "log" to "text", "csv" to "csv", "tsv" to "csv",
        "sql" to "database", "graphql" to "graphql", "gql" to "graphql",
        "dockerfile" to "docker", "env" to "brackets-gray",
        "vue" to "vue", "svelte" to "svelte", "astro" to "astro",
        "gradle" to "gradle", "cmake" to "cmake", "mk" to "code-gray",
        "nix" to "nix", "sol" to "solidity", "wat" to "code-purple", "wasm" to "code-purple",
        "png" to "image", "jpg" to "image", "jpeg" to "image", "gif" to "image",
        "webp" to "image", "bmp" to "image", "ico" to "image",
        "mp3" to "audio", "wav" to "audio", "flac" to "audio", "ogg" to "audio",
        "mp4" to "video", "mkv" to "video", "webm" to "video",
        "zip" to "brackets-gray", "tar" to "brackets-gray", "gz" to "brackets-gray", "7z" to "brackets-gray",
        "pdf" to "pdf", "lock" to "lock",
        "gitignore" to "git", "gitattributes" to "git",
        "nvmrc" to "node", "nuxt" to "nuxt"
    )

    private val BY_NAME: Map<String, String> = mapOf(
        "Dockerfile" to "docker", "Makefile" to "code-gray", "CMakeLists.txt" to "cmake",
        ".gitignore" to "git", ".gitattributes" to "git", ".env" to "brackets-gray",
        ".bashrc" to "shell", ".profile" to "shell", ".zshrc" to "shell",
        "package.json" to "node", "pnpm-lock.yaml" to "node", "yarn.lock" to "node",
        "package-lock.json" to "node", "tsconfig.json" to "tsconfig",
        "vite.config.ts" to "vite", "next.config.js" to "next", "nuxt.config.ts" to "nuxt",
        "AGENTS.md" to "markdown", "CRAB.md" to "markdown", "README.md" to "markdown",
        "LICENSE" to "text", "CHANGELOG.md" to "markdown", "SKILL.md" to "markdown",
        "go.mod" to "go", "go.sum" to "go", "Cargo.toml" to "rust", "Cargo.lock" to "rust",
        "requirements.txt" to "python", "pyproject.toml" to "python", "setup.py" to "python",
        "Gemfile" to "ruby", "composer.json" to "php", "pom.xml" to "java",
        "build.gradle" to "gradle", "build.gradle.kts" to "gradle"
    )

    private val FOLDER_BY_NAME: Map<String, String> = mapOf(
        ".git" to "git", ".github" to "github", "src" to "src", "source" to "src",
        "app" to "app", "components" to "react", "hooks" to "hooks", "utils" to "utils",
        "lib" to "src", "shared" to "src", "core" to "core", "modules" to "modules",
        "models" to "models", "services" to "services", "providers" to "providers",
        "config" to "config", "configs" to "config", "constants" to "constants",
        "router" to "router", "routes" to "router", "public" to "assets",
        "assets" to "assets", "static" to "assets",
        "docs" to "documents", "doc" to "documents", "node_modules" to "node-modules",
        ".vscode" to "vscode", ".idea" to "config",
        ".docker" to "docker", ".next" to "cognitive", ".nuxt" to "cognitive"
    )

    /** asset path inside assets/img/ for a file; null → generic file icon. */
    fun forFile(name: String): String? {
        val lower = name.lowercase()
        BY_NAME[name]?.let { return assetPath(it) }
        val ext = lower.substringAfterLast('.', "")
        if (ext.isNotEmpty()) BY_EXTENSION[ext]?.let { return assetPath(it) }
        if (lower == "dockerfile" || lower.endsWith("dockerfile")) return "img/docker.svg"
        return "img/text.svg"
    }

    /** Same rule as the desktop iconMap: names with a dot keep their extension. */
    private fun assetPath(icon: String): String =
        if (icon.contains('.')) "img/$icon" else "img/$icon.svg"

    fun forFolder(name: String): String? =
        FOLDER_BY_NAME[name.lowercase()]?.let { "img/floder/folder-$it.svg" }

    fun genericFolder(): String = "img/floder/folder.svg"
}
