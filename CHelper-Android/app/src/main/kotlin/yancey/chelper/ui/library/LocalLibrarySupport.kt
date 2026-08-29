package yancey.chelper.ui.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import yancey.chelper.network.library.data.LibraryFunction

enum class LocalLibrarySort(val label: String) {
    DEFAULT("默认顺序"),
    NEWEST("最近添加"),
    OLDEST("最早添加"),
    NAME_ASC("名称 A-Z"),
    NAME_DESC("名称 Z-A")
}

data class LocalLibraryEntry(
    val storageIndex: Int,
    val library: LibraryFunction
) {
    val localEntryId: String get() = requireNotNull(library.localEntryId)
}

private val localLibraryJson = Json { ignoreUnknownKeys = true }
private val stateLineRegex = Regex(
    """^>\s*([ICRH_])?([?_])?([!_])?(?:t(\d+|_))?\s*$""",
    RegexOption.IGNORE_CASE
)
private val mcdVersion2LineRegex = Regex(
    """^@mcd_version\s*=\s*2$""",
    RegexOption.IGNORE_CASE
)
private val localMetadataLineRegex = Regex(
    """^@(name|version|tags|note|mcd_version|uuid)\s*=""",
    RegexOption.IGNORE_CASE
)
private val functionMarkerRegex = Regex("(?m)^[ \\t]*###Function###[ \\t]*\\r?$")

fun filterAndSortLocalLibraries(
    libraries: List<LibraryFunction>,
    query: String,
    sort: LocalLibrarySort
): List<LocalLibraryEntry> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val entries = libraries.mapIndexed { index, library -> LocalLibraryEntry(index, library) }
        .filter { entry -> terms.all { term -> entry.library.matchesLocalQuery(term) } }

    return when (sort) {
        LocalLibrarySort.DEFAULT -> entries
        LocalLibrarySort.NEWEST -> entries.sortedByDescending { it.storageIndex }
        LocalLibrarySort.OLDEST -> entries.sortedBy { it.storageIndex }
        LocalLibrarySort.NAME_ASC -> entries.sortedWith(localLibraryNameComparator())
        LocalLibrarySort.NAME_DESC -> entries.sortedWith(localLibraryNameComparator().reversed())
    }
}

private fun LibraryFunction.matchesLocalQuery(term: String): Boolean =
    sequenceOf(
        name,
        note,
        version,
        author?.name,
        content
    ).filterNotNull().any { it.contains(term, ignoreCase = true) } ||
            tags.orEmpty().any { it.contains(term, ignoreCase = true) }

private fun localLibraryNameComparator(): Comparator<LocalLibraryEntry> =
    Comparator { first, second ->
        val nameOrder = String.CASE_INSENSITIVE_ORDER.compare(
            first.library.name.orEmpty(),
            second.library.name.orEmpty()
        )
        if (nameOrder != 0) {
            nameOrder
        } else {
            compareValuesBy(first, second, { it.storageIndex }, { it.localEntryId })
        }
    }

fun decodeLocalLibraryImport(raw: String): List<LibraryFunction> {
    val decoded = when (val root = localLibraryJson.parseToJsonElement(raw)) {
        is JsonArray -> root.map { localLibraryJson.decodeFromJsonElement<LibraryFunction>(it) }
        is JsonObject -> listOf(localLibraryJson.decodeFromJsonElement<LibraryFunction>(root))
        else -> error("命令库备份必须是 JSON 对象或数组")
    }
    return decoded.map { library ->
        if (library.localIsV2 == null) library.copy(localIsV2 = library.usesLocalMcdV2()) else library
    }
}

fun LibraryFunction.toLocalDuplicate(): LibraryFunction {
    val isV2 = usesLocalMcdV2()
    return copy(
        id = null,
        uuid = null,
        name = "${name?.takeIf(String::isNotBlank) ?: "未命名"} - 副本",
        createdAt = null,
        preview = null,
        likeCount = null,
        isLiked = null,
        isFavorited = null,
        hasPublicVersion = null,
        isPublish = null,
        isOwner = null,
        chainData = null,
        autoSync = false,
        hasUnsyncedChanges = null,
        localUnsynced = false,
        localEntryId = null,
        localIsV2 = isV2
    )
}

fun LibraryFunction.toLocalImportedCopy(): LibraryFunction = copy(
    id = null,
    uuid = null,
    content = localBody(),
    createdAt = null,
    preview = null,
    likeCount = null,
    isLiked = null,
    isFavorited = null,
    hasPublicVersion = null,
    isPublish = null,
    isOwner = null,
    chainData = null,
    autoSync = false,
    hasUnsyncedChanges = null,
    localUnsynced = false,
    localEntryId = null,
    localIsV2 = usesLocalMcdV2()
)

fun LibraryFunction.hasSameLocalContent(other: LibraryFunction): Boolean =
    name.orEmpty().trim() == other.name.orEmpty().trim() &&
            localBody().trim() == other.localBody().trim()

fun LibraryFunction.localBody(): String {
    val source = content.orEmpty()
    val lines = source.lines()
    val start = lines.indexOfFirst { it.trim() == "###Function###" }
    if (start < 0) {
        return stripLeadingLocalMcdMetadata(lines)
    }
    val end = lines.withIndex()
        .firstOrNull { (index, line) -> index > start && line.trim() == "###End###" }
        ?.index ?: -1
    if (end < 0) return source
    return lines.subList(start + 1, end).joinToString("\n").trim('\n', '\r')
}

fun LibraryFunction.usesLocalMcdV2(): Boolean {
    localIsV2?.let { return it }
    val source = content.orEmpty()
    if (hasLocalMcdV2Header(source)) {
        return true
    }
    return localBody().lineSequence().any { stateLineRegex.matches(it.trim()) }
}

fun LibraryFunction.toFullLocalMcd(): String {
    val source = content.orEmpty()
    val lines = source.lines()
    val start = lines.indexOfFirst { it.trim() == "###Function###" }
    val end = lines.withIndex()
        .firstOrNull { (index, line) -> index > start && line.trim() == "###End###" }
        ?.index ?: -1
    if (start >= 0 && end > start) {
        val sourceIsV2 = hasLocalMcdV2Header(source)
        val shouldUseV2 = usesLocalMcdV2()
        var normalizedSource = when {
            shouldUseV2 && !sourceIsV2 -> insertLocalMcdV2Header(source)
            !shouldUseV2 && sourceIsV2 -> removeLocalMcdV2Header(source)
            else -> source
        }
        if (!uuid.isNullOrBlank()) {
            normalizedSource = upsertLocalMcdHeader(normalizedSource, "uuid", uuid!!)
        }
        return normalizedSource
    }

    return buildString {
        append("@name=${name.orEmpty()}\n")
        append("@version=${version?.ifBlank { "1.0.0" } ?: "1.0.0"}\n")
        if (!tags.isNullOrEmpty()) append("@tags=${tags!!.joinToString(",")}\n")
        append("@note=${note.orEmpty().replace('\n', ' ').replace('\r', ' ')}\n")
        if (usesLocalMcdV2()) append("@mcd_version=2\n")
        if (!uuid.isNullOrBlank()) append("@uuid=$uuid\n")
        append("\n###Function###\n")
        append(localBody())
        append("\n###End###")
    }
}

private fun insertLocalMcdV2Header(source: String): String {
    return upsertLocalMcdHeader(source, "mcd_version", "2")
}

private fun upsertLocalMcdHeader(source: String, key: String, value: String): String {
    val markerIndex = functionMarkerRegex.find(source)?.range?.first ?: return source
    val prefix = source.substring(0, markerIndex)
    val suffix = source.substring(markerIndex)
    val existingLine = Regex(
        "(?im)^[ \\t]*@${Regex.escape(key)}[ \\t]*=[^\\r\\n]*"
    ).find(prefix)
    if (existingLine != null) {
        return prefix.replaceRange(existingLine.range, "@$key=$value") + suffix
    }

    val newline = if ("\r\n" in source) "\r\n" else "\n"
    val cleanPrefix = prefix.trimEnd('\r', '\n')
    val leading = if (cleanPrefix.isEmpty()) "" else "$cleanPrefix$newline"
    return "$leading@$key=$value$newline$newline$suffix"
}

private fun removeLocalMcdV2Header(source: String): String {
    val markerIndex = functionMarkerRegex.find(source)?.range?.first ?: return source
    val prefix = source.substring(0, markerIndex)
    val suffix = source.substring(markerIndex)
    val versionHeaderRegex = Regex(
        "(?im)^[ \\t]*@mcd_version[ \\t]*=[ \\t]*2[ \\t]*(?:\\r?\\n|$)"
    )
    return versionHeaderRegex.replace(prefix, "") + suffix
}

private fun hasLocalMcdV2Header(source: String): Boolean {
    val markerIndex = functionMarkerRegex.find(source)?.range?.first
    if (markerIndex != null) {
        return source.substring(0, markerIndex).lineSequence()
            .any { mcdVersion2LineRegex.matches(it.trim()) }
    }

    for (line in source.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (!localMetadataLineRegex.containsMatchIn(trimmed)) break
        if (mcdVersion2LineRegex.matches(trimmed)) return true
    }
    return false
}

private fun stripLeadingLocalMcdMetadata(lines: List<String>): String {
    var index = 0
    while (index < lines.size && lines[index].isBlank()) index++

    var foundMetadata = false
    while (index < lines.size && localMetadataLineRegex.containsMatchIn(lines[index].trim())) {
        foundMetadata = true
        index++
    }
    if (foundMetadata) while (index < lines.size && lines[index].isBlank()) index++

    val bodyStart = if (foundMetadata) index else lines.indexOfFirst(String::isNotBlank)
        .takeIf { it >= 0 } ?: lines.size
    return lines.drop(bodyStart).joinToString("\n")
}
