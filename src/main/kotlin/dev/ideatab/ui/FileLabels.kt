package dev.ideatab.ui

internal object FileLabels {
    fun directory(path: String, projectPath: String?): String {
        val normalized = path.replace('\\', '/')
        val base = projectPath?.replace('\\', '/')?.trimEnd('/')
        val windowsPath = base != null && (base.getOrNull(1) == ':' || base.startsWith("//"))
        val prefix = base?.let { "$it/" }
        val relative = if (prefix != null && normalized.startsWith(prefix, ignoreCase = windowsPath)) {
            normalized.substring(prefix.length)
        } else normalized
        return relative.substringBeforeLast('/', "")
    }
}
