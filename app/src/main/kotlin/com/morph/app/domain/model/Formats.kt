package com.morph.app.domain.model

enum class Category(val label: String) {
    IMAGE("Images"), VIDEO("Video"), AUDIO("Audio"), DOCUMENT("Documents"),
    SHEET("Spreadsheets"), SLIDES("Presentations"), ARCHIVE("Archives"), DATA("Data")
}

enum class Format(val extension: String, val category: Category, val mime: String, vararg val aliases: String) {
    JPG("jpg", Category.IMAGE, "image/jpeg", "jpeg"),
    PNG("png", Category.IMAGE, "image/png"),
    WEBP("webp", Category.IMAGE, "image/webp"),
    HEIC("heic", Category.IMAGE, "image/heic", "heif"),
    AVIF("avif", Category.IMAGE, "image/avif"),
    BMP("bmp", Category.IMAGE, "image/bmp"),
    GIF("gif", Category.IMAGE, "image/gif"),
    TIFF("tiff", Category.IMAGE, "image/tiff", "tif"),
    ICO("ico", Category.IMAGE, "image/x-icon"),
    SVG("svg", Category.IMAGE, "image/svg+xml"),
    TGA("tga", Category.IMAGE, "image/x-tga"),
    FRAMES("frames.zip", Category.VIDEO, "application/zip"),
    MP4("mp4", Category.VIDEO, "video/mp4"),
    MKV("mkv", Category.VIDEO, "video/x-matroska"),
    MOV("mov", Category.VIDEO, "video/quicktime"),
    WEBM("webm", Category.VIDEO, "video/webm"),
    AVI("avi", Category.VIDEO, "video/x-msvideo"),
    FLV("flv", Category.VIDEO, "video/x-flv"),
    WMV("wmv", Category.VIDEO, "video/x-ms-wmv"),
    GPP("3gp", Category.VIDEO, "video/3gpp"),
    M4V("m4v", Category.VIDEO, "video/x-m4v"),
    MP3("mp3", Category.AUDIO, "audio/mpeg"),
    WAV("wav", Category.AUDIO, "audio/wav"),
    AAC("aac", Category.AUDIO, "audio/aac"),
    M4A("m4a", Category.AUDIO, "audio/mp4"),
    FLAC("flac", Category.AUDIO, "audio/flac"),
    OGG("ogg", Category.AUDIO, "audio/ogg"),
    OPUS("opus", Category.AUDIO, "audio/opus"),
    WMA("wma", Category.AUDIO, "audio/x-ms-wma"),
    AIFF("aiff", Category.AUDIO, "audio/aiff", "aif"),
    AMR("amr", Category.AUDIO, "audio/amr"),
    AC3("ac3", Category.AUDIO, "audio/ac3"),
    DOCX("docx", Category.DOCUMENT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    DOC("doc", Category.DOCUMENT, "application/msword"),
    PDF("pdf", Category.DOCUMENT, "application/pdf"),
    RTF("rtf", Category.DOCUMENT, "application/rtf"),
    TXT("txt", Category.DOCUMENT, "text/plain"),
    MD("md", Category.DOCUMENT, "text/markdown", "markdown"),
    HTML("html", Category.DOCUMENT, "text/html", "htm"),
    EPUB("epub", Category.DOCUMENT, "application/epub+zip"),
    ODT("odt", Category.DOCUMENT, "application/vnd.oasis.opendocument.text"),
    XLS("xls", Category.SHEET, "application/vnd.ms-excel"),
    XLSX("xlsx", Category.SHEET, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ODS("ods", Category.SHEET, "application/vnd.oasis.opendocument.spreadsheet"),
    CSV("csv", Category.SHEET, "text/csv"),
    TSV("tsv", Category.SHEET, "text/tab-separated-values"),
    PPTX("pptx", Category.SLIDES, "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    PPT("ppt", Category.SLIDES, "application/vnd.ms-powerpoint"),
    ZIP("zip", Category.ARCHIVE, "application/zip"),
    TAR("tar", Category.ARCHIVE, "application/x-tar"),
    TGZ("tar.gz", Category.ARCHIVE, "application/gzip", "tgz"),
    TBZ("tar.bz2", Category.ARCHIVE, "application/x-bzip2", "tbz", "tbz2"),
    TXZ("tar.xz", Category.ARCHIVE, "application/x-xz", "txz"),
    GZ("gz", Category.ARCHIVE, "application/gzip"),
    BZ("bz2", Category.ARCHIVE, "application/x-bzip2"),
    XZ("xz", Category.ARCHIVE, "application/x-xz"),
    SEVEN("7z", Category.ARCHIVE, "application/x-7z-compressed"),
    JSON("json", Category.DATA, "application/json"),
    YAML("yaml", Category.DATA, "application/yaml", "yml"),
    XML("xml", Category.DATA, "application/xml"),
    TOML("toml", Category.DATA, "application/toml"),
    INI("ini", Category.DATA, "text/plain"),
    PROPERTIES("properties", Category.DATA, "text/plain"),
    SQL("sql", Category.DATA, "application/sql"),
    B64("b64", Category.DATA, "text/plain"),
    RAW("bin", Category.DATA, "application/octet-stream");

    val label: String get() = extension.uppercase()

    fun supportsEncodingOn(api: Int): Boolean = this != HEIC || api >= 28

    companion object {
        fun named(filename: String): Format? = entries.sortedByDescending { it.extension.length }
            .firstOrNull { item -> (listOf(item.extension) + item.aliases).any { filename.endsWith(".$it", true) } }
        fun basename(filename: String): String {
            val extension = named(filename) ?: return filename.substringBeforeLast('.', filename)
            val suffix = (listOf(extension.extension) + extension.aliases).first { filename.endsWith(".$it", true) }
            return filename.dropLast(suffix.length + 1)
        }
    }
}
