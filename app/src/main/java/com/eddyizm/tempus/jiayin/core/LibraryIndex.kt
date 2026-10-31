package com.eddyizm.tempus.jiayin.core

import androidx.annotation.Keep
import java.text.Normalizer
import java.util.Locale

data class SearchReturnLocation(val route: String, val previous: String, val collection: MusicList?, val parent: MusicList?, val category: String, val folder: String, val entryFolder: String? = null)
data class BrowseLocation(val category: String = "专辑", val path: String = "", val request: Long = 0)
enum class SearchKind(val label: String) { ALL("全部"), SONGS("歌曲"), ARTISTS("歌手"), ALBUMS("专辑"), PLAYLISTS("歌单"), FOLDERS("文件夹") }
@Keep data class SearchScope(val kind: SearchKind = SearchKind.ALL, val folder: String? = null, val artist: String? = null, val region: String = "全部", val artistType: String = "全部", val originalPerformer: Boolean = false) {
    val label get() = when { folder != null -> "当前目录及子目录"; artist != null -> "当前歌手作品"; else -> listOfNotNull(region.takeUnless { it == "全部" }, artistType.takeUnless { it == "全部" }, kind.label.takeUnless { kind == SearchKind.ALL }).joinToString(" · ").ifBlank { "全部曲库" } }
}
@Keep data class SearchHistory(val query: String, val time: Long)
@Keep data class PathRegionRule(val path: String, val region: String)
data class SearchDocument(val id: String, val kind: SearchKind, val title: String, val subtitle: String = "", val cover: String = "", val tracks: List<String> = emptyList(), val artist: String? = null, val folder: String? = null) {
    val nameKey = normalizeSearch(title)
    val textKey = normalizeSearch("$title $subtitle")
}
data class ArtistEntry(val id: String, val name: String, val tracks: List<Track>, val profile: ArtistProfile, val region: String, val section: String)
data class FolderNode(val path: String, val name: String, val parent: String?, val children: List<String>, val directTracks: List<String>, val allTracks: List<String>, val section: String)
data class IndexedSection<T>(val label: String, val entries: List<T>, val firstIndex: Int)
interface NameOrdering {
    fun section(name: String, region: String = "未分类", alias: String? = null): String
    fun compare(a: String, b: String): Int
}
fun normalizeSearch(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

object DirectoryRegions {
    val choices = listOf("全部", "华语", "欧美", "日本", "韩国", "其他", "未分类")
    const val MISSING = "@missing-path"
    private val tags = mapOf(
        "华语" to "华语", "国语" to "华语", "粤语" to "华语", "中文" to "华语", "chinese" to "华语", "mandarin" to "华语",
        "日本" to "日本", "日语" to "日本", "j-pop" to "日本", "jpop" to "日本", "japanese" to "日本",
        "韩国" to "韩国", "韩语" to "韩国", "k-pop" to "韩国", "kpop" to "韩国", "korean" to "韩国",
        "欧美" to "欧美", "英语" to "欧美", "western" to "欧美", "english" to "欧美", "其他" to "其他", "other" to "其他")
    fun directory(file: String): String {
        val parts = file.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        return if (parts.size < 2 || ".." in parts) MISSING else parts.dropLast(1).joinToString("/")
    }
    fun contains(parent: String, path: String) = parent.isEmpty() || parent == path || path.startsWith("$parent/")
    fun pathRegion(path: String, rules: Map<String, String>): String {
        manualRegion(path, rules)?.let { return it }
        return path.split('/').asReversed().firstNotNullOfOrNull { tags[normalizeSearch(it)] } ?: "未分类"
    }
    fun manualRegion(path: String, rules: Map<String, String>): String? {
        var current = path
        while (current.isNotBlank()) {
            rules[current]?.let { return it }
            current = current.substringBeforeLast('/', "")
        }
        return null
    }
}

/** Immutable derived data. Built on Dispatchers.Default; never persisted as one database row. */
class LibraryIndex private constructor(
    val tracks: Map<String, Track>, val artists: List<ArtistEntry>, val folders: Map<String, FolderNode>,
    val folderOrder: List<String>, val regions: Map<String, String>, val documents: List<SearchDocument>,
    val ownership: Map<String, ArtistRef> = emptyMap(),
) {
    private val artistTypes = artists.associate { it.id to it.profile.type }
    fun matchesTrack(id: String, scope: SearchScope): Boolean {
        val t = tracks[id] ?: return false
        val effectiveArtists = ArtistOwnership.artists(t, ownership).map { it.key }
        return (scope.artist == null || scope.artist in (if (scope.originalPerformer) t.artistKeys() else effectiveArtists)) &&
            (scope.folder == null || DirectoryRegions.contains(scope.folder, DirectoryRegions.directory(t.path))) &&
            (scope.region == "全部" || regions[id] == scope.region) &&
            (scope.artistType == "全部" || effectiveArtists.any { artistTypes[it] == scope.artistType })
    }
    fun search(query: String, scope: SearchScope, category: SearchKind = SearchKind.ALL, cancellationCheck: () -> Unit = {}): List<SearchDocument> {
        val needle = normalizeSearch(query)
        if (needle.isBlank()) return emptyList()
        val scored = mutableListOf<Pair<SearchDocument, Int>>()
        documents.forEachIndexed { i, d ->
            if (i % 128 == 0) cancellationCheck()
            if (scope.kind != SearchKind.ALL && d.kind != scope.kind || category != SearchKind.ALL && d.kind != category) return@forEachIndexed
            if (scope.folder != null && d.kind !in listOf(SearchKind.SONGS, SearchKind.FOLDERS)) return@forEachIndexed
            if (scope.artist != null && d.kind !in listOf(SearchKind.SONGS, SearchKind.ALBUMS)) return@forEachIndexed
            val rank = when { d.nameKey == needle -> 0; d.nameKey.startsWith(needle) -> 1; d.nameKey.contains(needle) -> 2; d.textKey.contains(needle) -> 3; else -> return@forEachIndexed }
            if ((d.tracks.isNotEmpty() || scope.folder != null || scope.artist != null || scope.region != "全部" || scope.artistType != "全部") && d.tracks.none { matchesTrack(it, scope) }) return@forEachIndexed
            if (d.kind == SearchKind.FOLDERS && scope.folder != null && !DirectoryRegions.contains(scope.folder, d.folder.orEmpty())) return@forEachIndexed
            scored += d to rank
        }
        return scored.sortedBy { it.second }.map { it.first }
    }
    fun folderTracks(path: String, recursive: Boolean, region: String): List<Track> =
        (if (recursive) folders[path]?.allTracks else folders[path]?.directTracks).orEmpty().mapNotNull { tracks[it] }.filter { region == "全部" || regions[it.id] == region }
    fun folderRegion(path: String): String = folders[path]?.allTracks.orEmpty().mapNotNull { regions[it] }.distinct().singleOrNull() ?: "混合"
    companion object {
        val EMPTY = LibraryIndex(emptyMap(), emptyList(), emptyMap(), emptyList(), emptyMap(), emptyList())
        fun build(library: Library, profiles: Map<String, ArtistProfile>, rules: List<PathRegionRule>, order: NameOrdering, ownership: Map<String, ArtistRef> = emptyMap()): LibraryIndex {
            val tracks = library.tracks.distinctBy { it.id }.associateBy { it.id }
            val ruleMap = rules.associate { it.path to it.region }
            val directories = tracks.mapValues { DirectoryRegions.directory(it.value.path) }
            val pathRegions = directories.mapValues { DirectoryRegions.pathRegion(it.value, ruleMap) }
            val regions = tracks.mapValues { (id, t) ->
                val explicit = ArtistOwnership.artists(t, ownership).mapNotNull { profiles[it.key]?.region?.takeUnless { r -> r == "未分类" || r.isBlank() } }.distinct()
                explicit.singleOrNull() ?: DirectoryRegions.manualRegion(directories.getValue(id), ruleMap) ?:
                    t.sharedRegion?.takeIf { it in DirectoryRegions.choices && it != "全部" && it != "未分类" } ?: pathRegions.getValue(id)
            }
            val artists = tracks.values.flatMap { t -> ArtistOwnership.artists(t, ownership).map { it to t } }.groupBy { it.first.key }.map { (id, entries) ->
                val songs = entries.map { it.second }.distinctBy { it.id }
                val name = entries.first().first.name
                val profile = profiles[id] ?: ArtistProfile(id, name)
                val region = profile.region.takeUnless { it == "未分类" || it.isBlank() } ?: songs.map { regions.getValue(it.id) }.distinct().singleOrNull() ?: "未分类"
                ArtistEntry(id, name, songs, profile, region, order.section(name, region, profile.sortName))
            }.sortedWith { a, b ->
                val section = sectionOrder(a.section).compareTo(sectionOrder(b.section))
                if (section != 0) section else order.compare(a.profile.sortName?.takeIf { it.isNotBlank() } ?: a.name, b.profile.sortName?.takeIf { it.isNotBlank() } ?: b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
            }
            val direct = linkedMapOf<String, MutableList<String>>("" to mutableListOf())
            val children = linkedMapOf<String, MutableSet<String>>("" to linkedSetOf())
            tracks.forEach { (id, _) ->
                val dir = directories.getValue(id)
                var parent = ""
                val segments = if (dir == DirectoryRegions.MISSING) listOf(dir) else dir.split('/')
                segments.forEach { part ->
                    val path = if (parent.isBlank()) part else "$parent/$part"
                    children.getOrPut(parent) { linkedSetOf() }.add(path)
                    direct.getOrPut(path) { mutableListOf() }; children.getOrPut(path) { linkedSetOf() }; parent = path
                }
                direct.getValue(dir).add(id)
            }
            val all = mutableMapOf<String, List<String>>()
            direct.keys.sortedByDescending { it.count { c -> c == '/' } * 2 + if (it.isNotBlank()) 1 else 0 }.forEach { path ->
                all[path] = direct.getValue(path) + children.getValue(path).flatMap { all[it].orEmpty() }
            }
            val folderOrder = direct.keys.filter { it.isNotBlank() }.sortedWith { a, b ->
                val sa = if (a == DirectoryRegions.MISSING) "#" else order.section(a.substringAfterLast('/'))
                val sb = if (b == DirectoryRegions.MISSING) "#" else order.section(b.substringAfterLast('/'))
                sectionOrder(sa).compareTo(sectionOrder(sb)).takeIf { it != 0 } ?: order.compare(a.substringAfterLast('/'), b.substringAfterLast('/')).takeIf { it != 0 } ?: a.compareTo(b)
            }
            val nodes = direct.mapValues { (path, ids) -> FolderNode(path, if (path == DirectoryRegions.MISSING) "未提供路径" else path.substringAfterLast('/').ifBlank { "音乐文件夹" }, path.takeIf { it.isNotBlank() }?.substringBeforeLast('/', ""), children.getValue(path).toList(), ids.toList(), all[path].orEmpty(), if (path == DirectoryRegions.MISSING) "#" else order.section(path.substringAfterLast('/'))) }
            val docs = mutableListOf<SearchDocument>()
            artists.forEach { a -> docs += SearchDocument(a.id, SearchKind.ARTISTS, a.name, "${a.tracks.size} 首 · ${a.region} · ${a.profile.type}", a.tracks.firstOrNull()?.cover.orEmpty(), a.tracks.map { it.id }, artist = a.id) }
            tracks.values.forEach { t -> docs += SearchDocument(t.id, SearchKind.SONGS, t.title, "${t.artist} · ${t.performers().joinToString(" / ") { it.name }} · ${ArtistOwnership.artists(t, ownership).joinToString(" / ") { it.name }} · ${t.album} · ${t.genreNames().joinToString(" / ")}", t.cover, listOf(t.id), artistKey(t), directories[t.id]) }
            tracks.values.groupBy(::albumKey).forEach { (id, songs) -> val t = songs.first(); docs += SearchDocument(id, SearchKind.ALBUMS, t.album.ifBlank { "未命名专辑" }, "${t.albumCredits().joinToString(" / ") { it.name }} · ${t.albumVersion.orEmpty()} · ${songs.size} 首", t.cover, songs.sortedWith(compareBy<Track> { it.disc }.thenBy { it.number }).map { it.id }) }
            library.playlists.forEach { p -> docs += SearchDocument(p.id, SearchKind.PLAYLISTS, p.name, "${p.tracks.size} 首", p.cover, p.tracks) }
            folderOrder.forEach { path -> val f = nodes.getValue(path); docs += SearchDocument(path, SearchKind.FOLDERS, f.name, "${f.parent.orEmpty().ifBlank { "音乐文件夹" }} · ${f.allTracks.size} 首", tracks[f.allTracks.firstOrNull()]?.cover.orEmpty(), f.allTracks, folder = path) }
            return LibraryIndex(tracks, artists, nodes, folderOrder, regions, docs, ownership)
        }
        fun sectionOrder(label: String) = if (label == "#") 26 else label.firstOrNull()?.minus('A') ?: 26
        fun <T> sections(entries: List<T>, label: (T) -> String): List<IndexedSection<T>> {
            var at = 0
            return entries.groupBy(label).entries.sortedBy { sectionOrder(it.key) }.map { (key, values) -> IndexedSection(key, values, at).also { at += values.size + 1 } }
        }
    }
}
