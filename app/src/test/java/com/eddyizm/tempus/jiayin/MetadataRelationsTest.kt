package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MetadataRelationsTest {
    private val order = object : NameOrdering {
        override fun section(name: String, region: String, alias: String?) = "A"
        override fun compare(a: String, b: String) = a.compareTo(b)
    }
    private val a = ArtistRef("a", "A")
    private val b = ArtistRef("b", "B")
    private fun duet(id: String = "1") = Track(id, "Song", "A feat. B", artists = listOf(a, b), genres = listOf("Pop", "Jazz"), album = "Album", albumArtists = listOf(a))
    @Test fun parsesStructuredFieldsInsteadOfSplittingDisplayCredit() {
        val t = JiayinRepository.parseTrack(JsonParser.parseString("""{"id":"1","title":"Song","artist":"legacy","displayArtist":"A feat. B","artists":[{"id":"a","name":"A"},{"id":"b","name":"B"}],"albumArtists":[{"id":"a","name":"A"}],"genres":[{"name":"Pop"},{"name":"Jazz"}]}""").asJsonObject)
        assertEquals(setOf("a", "b"), t.artistKeys()); assertEquals(listOf("Pop", "Jazz"), t.genreNames())
        assertEquals("A feat. B", t.artist); assertEquals(listOf(a), t.albumCredits())
    }
    @Test fun legacySavedRowsKeepDefaultsWithoutRewritingIds() {
        val t = Gson().fromJson("""{"id":"old","title":"Song","artist":"North & South","artistId":"band","album":"Album","genre":"Soul"}""", Track::class.java)
        assertEquals(setOf("band"), t.artistKeys()); assertEquals(listOf("Soul"), t.genreNames())
        assertEquals("old", t.id); assertEquals(1, t.performers().size)
    }
    @Test fun collaborationAppearsInBothArtistsWithoutDuplicatingSongIndex() {
        val i = LibraryIndex.build(Library(listOf(duet())), emptyMap(), emptyList(), order)
        assertEquals(1, i.tracks.size); assertEquals(setOf("a", "b"), i.artists.map { it.id }.toSet())
        assertEquals(listOf("1"), i.search("Song", SearchScope(artist = "b")).map { it.id })
        assertEquals("1", i.search("Jazz", SearchScope(SearchKind.SONGS)).single().id)
    }
    @Test fun albumFallbackUsesAlbumArtistAcrossGuestPerformances() {
        val first = duet()
        val second = first.copy(id = "2", artists = listOf(a), artist = "A", title = "Solo")
        assertEquals(albumKey(first), albumKey(second))
    }
    @Test fun sameAlbumTitleKeepsDifferentReleasesSeparate() {
        assertNotEquals(albumKey(duet()), albumKey(duet().copy(albumVersion = "Deluxe")))
        assertNotEquals(albumKey(duet()), albumKey(duet().copy(releaseDate = 20240101)))
        assertNotEquals(albumKey(duet().copy(albumId = "one")), albumKey(duet().copy(albumId = "two")))
    }
    @Test fun sharedClassificationDoesNotOverridePersonalPreference() {
        val t = duet().copy(sharedRegion = "日本", path = "music/欧美/song.flac")
        val i = LibraryIndex.build(Library(listOf(t)), mapOf("b" to ArtistProfile("b", "B", region = "华语")), emptyList(), order)
        assertEquals("华语", i.regions[t.id])
        val shared = LibraryIndex.build(Library(listOf(t)), emptyMap(), emptyList(), order)
        assertEquals("日本", shared.regions[t.id])
    }
    @Test fun sameNameArtistsWithDifferentIdsRemainDistinct() {
        val t = duet().copy(artists = listOf(a.copy(name = "Same"), b.copy(name = "Same")))
        assertEquals(2, LibraryIndex.build(Library(listOf(t)), emptyMap(), emptyList(), order).artists.size)
    }
    @Test fun personalFolderRuleOverridesSharedClassification() {
        val t = duet().copy(sharedRegion = "日本", path = "music/song.flac")
        val i = LibraryIndex.build(Library(listOf(t)), emptyMap(), listOf(PathRegionRule("music", "华语")), order)
        assertEquals("华语", i.regions[t.id])
    }
    @Test fun recordingFoldingRequiresIdAndKeepsLiveSeparate() {
        val t = duet().copy(recordingId = "recording", duration = 240)
        assertEquals(1, recordingGroups(listOf(t, t.copy(id = "2", album = "Best"))).size)
        assertEquals(2, recordingGroups(listOf(t, t.copy(id = "2", title = "Song Live"))).size)
        assertEquals(2, recordingGroups(listOf(duet(), duet("2"))).size)
        assertEquals(2, recordingGroups(listOf(t, t.copy(id = "2", duration = 270))).size)
    }
    @Test fun discoverySpacesEveryCollaboratingArtist() {
        val songs = (0..35).map { n -> Track("$n", "Song $n", artists = listOf(ArtistRef("a$n", "Artist $n"))) } + duet()
        val library = Library(songs)
        val picks = DiscoveryEngine.pick(library, emptyMap(), 42, limit = 30).map { r -> songs.first { it.id == r.id } }
        picks.windowed(10).forEach { window -> val keys = window.flatMap { it.artistKeys() }; assertEquals(keys.size, keys.distinct().size) }
    }
}
