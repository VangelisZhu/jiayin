package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class LibraryIndexTest {
    private val order = object : NameOrdering {
        override fun section(name: String, region: String, alias: String?) = (alias ?: name).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
        override fun compare(a: String, b: String) = a.compareTo(b, true)
    }
    private fun song(id: String, path: String, artist: String = "a", name: String = "Song $id", artistName: String = artist) = Track(id, name, artistName, "Album $artist", "album-$artist", artist, path = path)
    private fun index(songs: List<Track>, profiles: List<ArtistProfile> = emptyList(), rules: List<PathRegionRule> = emptyList(), playlists: List<MusicList> = emptyList()) = LibraryIndex.build(Library(songs, playlists), profiles.associateBy { it.id }, rules, order)
    @Test fun hierarchyKeepsDirectSongsSeparateFromDescendants() {
        val i = index(listOf(song("1", "music/a.wav"), song("2", "music/Pop/b.wav"), song("3", "music/Pop/Album/c.wav")))
        assertEquals(listOf("1"), i.folders.getValue("music").directTracks)
        assertEquals(setOf("1", "2", "3"), i.folders.getValue("music").allTracks.toSet())
        assertEquals(listOf("music/Pop"), i.folders.getValue("music").children)
        assertEquals("music", i.folders.getValue("music/Pop").parent)
    }
    @Test fun sameFolderLeafNamesKeepTheirFullPathIdentity() {
        val i = index(listOf(song("1", "music/One/Best/a.wav"), song("2", "music/Two/Best/b.wav")))
        val results = i.search("Best", SearchScope(SearchKind.FOLDERS))
        assertEquals(2, results.size); assertEquals(2, results.map { it.id }.distinct().size)
        assertEquals(setOf("music/One", "music/Two"), results.map { it.subtitle.substringBefore(" · ") }.toSet())
    }
    @Test fun missingAndUnsafePathsAreNotInventedDirectories() {
        val i = index(listOf(song("1", ""), song("2", "a.wav"), song("3", "music/../a.wav")))
        assertEquals(3, i.folders.getValue(DirectoryRegions.MISSING).directTracks.size)
        assertFalse(i.folders.containsKey("music/.."))
    }
    @Test fun windowsAndRepeatedSeparatorsNormalizeWithoutFilesystemAccess() {
        assertEquals("music/华语/Album", DirectoryRegions.directory("music\\华语\\Album\\one.wav"))
        assertEquals("music/华语", DirectoryRegions.directory("music//./华语//one.wav"))
    }
    @Test fun directoryPrefixDoesNotMatchSiblingWithSimilarName() {
        val i = index(listOf(song("1", "music/Pop/a.wav"), song("2", "music/Popular/b.wav")))
        assertEquals(listOf("1"), i.search("Song", SearchScope(folder = "music/Pop")).map { it.id })
    }
    @Test fun automaticRegionRequiresExactDirectorySegment() {
        val i = index(listOf(song("1", "music/J-Pop/a.wav"), song("2", "music/东京爱情故事/b.wav", "b")))
        assertEquals("日本", i.regions["1"]); assertEquals("未分类", i.regions["2"])
    }
    @Test fun nestedAutomaticTagTakesPrecedence() {
        assertEquals("日本", DirectoryRegions.pathRegion("music/华语/日本/album", emptyMap()))
    }
    @Test fun explicitArtistBeatsDirectoryRulesAndTags() {
        val i = index(listOf(song("1", "music/日本/a.wav")), listOf(ArtistProfile("a", "A", region = "华语")), listOf(PathRegionRule("music", "欧美")))
        assertEquals("华语", i.regions["1"])
    }
    @Test fun nearestManualDirectoryRuleOverridesAutomaticTag() {
        val i = index(listOf(song("1", "music/日本/Album/a.wav")), rules = listOf(PathRegionRule("music", "欧美"), PathRegionRule("music/日本", "韩国")))
        assertEquals("韩国", i.regions["1"])
    }
    @Test fun conflictingArtistDirectoriesRemainUnclassified() {
        val i = index(listOf(song("1", "music/华语/a.wav"), song("2", "music/日本/b.wav")))
        assertEquals("未分类", i.artists.single().region); assertEquals("混合", i.folderRegion("music"))
    }
    @Test fun partialMissingArtistRegionAlsoRemainsUnclassified() {
        val i = index(listOf(song("1", "music/华语/a.wav"), song("2", "misc/b.wav")))
        assertEquals("未分类", i.artists.single().region)
    }
    @Test fun regionFilterRetainsMatchingParentFolder() {
        val i = index(listOf(song("1", "music/华语/Album/a.wav"), song("2", "music/日本/b.wav", "b")))
        assertTrue(i.search("music", SearchScope(SearchKind.FOLDERS, region = "日本")).any { it.folder == "music" })
        assertEquals(listOf("2"), i.folderTracks("music", true, "日本").map { it.id })
    }
    @Test fun sameArtistNameDoesNotMergeDifferentServerIds() {
        val i = index(listOf(song("1", "m/a.wav", "a", artistName = "Same"), song("2", "m/b.wav", "b", artistName = "Same")))
        assertEquals(2, i.artists.size); assertEquals(2, i.search("Same", SearchScope(SearchKind.ARTISTS)).size)
    }
    @Test fun searchRankExactThenPrefixThenContainsThenMetadata() {
        val songs = listOf(song("3", "m/c.wav", name = "A Rain Song"), song("2", "m/b.wav", name = "Rainfall"), song("1", "m/a.wav", name = "Rain"), song("4", "m/d.wav", name = "Sun", artistName = "Rain"))
        assertEquals(listOf("1", "2", "3", "4"), index(songs).search("rain", SearchScope(SearchKind.SONGS)).map { it.id })
    }
    @Test fun unicodeSearchIgnoresWidthAndCase() {
        val i = index(listOf(song("1", "m/a.wav", name = "ＡＢＣ Song")))
        assertEquals("1", i.search("abc", SearchScope(SearchKind.SONGS)).single().id)
    }
    @Test fun categoryAndArtistScopesDoNotLeakOtherResults() {
        val i = index(listOf(song("1", "m/a.wav", "a", name = "Needle"), song("2", "m/b.wav", "b", name = "Needle")))
        assertEquals(listOf("1"), i.search("Needle", SearchScope(artist = "a")).map { it.id })
        assertTrue(i.search("Needle", SearchScope(SearchKind.ARTISTS)).isEmpty())
    }
    @Test fun emptyPlaylistsAreStillSearchableGlobally() {
        assertEquals("p", index(emptyList(), playlists = listOf(MusicList("p", "Empty list"))).search("Empty", SearchScope()).single().id)
    }
    @Test fun filteringByArtistTypeUsesStoredProfile() {
        val i = index(listOf(song("1", "m/a.wav")), listOf(ArtistProfile("a", "A", type = "乐队/组合")))
        assertEquals(1, i.search("Song", SearchScope(SearchKind.SONGS, artistType = "乐队/组合")).size)
        assertTrue(i.search("Song", SearchScope(SearchKind.SONGS, artistType = "女")).isEmpty())
    }
    @Test fun sectionOffsetsIncludeStickyHeaders() {
        val groups = LibraryIndex.sections(listOf("A1", "A2", "C1")) { it.take(1) }
        assertEquals(listOf(0, 3), groups.map { it.firstIndex })
    }
    @Test fun searchInvokesCooperativeCancellation() {
        var called = false
        try { index(listOf(song("1", "m/a.wav"))).search("Song", SearchScope()) { called = true; throw IllegalStateException("cancelled") }; fail() } catch (_: IllegalStateException) { assertTrue(called) }
    }
}
