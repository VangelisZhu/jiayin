package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class ArtistOwnershipTest {
    private val main = ArtistRef("main", "Main Artist")
    private val combo = ArtistRef("combined", "Main Artist/Guest")
    private val order = object : NameOrdering {
        override fun section(name: String, region: String, alias: String?) = name.firstOrNull()?.uppercase() ?: "#"
        override fun compare(a: String, b: String) = a.compareTo(b)
    }
    private fun track(id: String, artist: ArtistRef) = Track(id, "Song $id", artist.name, artistId = artist.id, path = "music/$id.wav", artists = listOf(artist))
    private fun index(tracks: List<Track>, owners: Map<String, ArtistRef>) = LibraryIndex.build(Library(tracks), emptyMap(), emptyList(), order, owners)

    @Test fun personalPrecedesSharedAndTombstoneRestoresOriginal() {
        val personal = PersonalOwnership("one", main, "stamp")
        val shared = SharedOwnership("one", combo, 1)
        assertEquals(main, ArtistOwnership.resolve(listOf(personal), listOf(shared))["one"])
        assertEquals(combo, ArtistOwnership.resolve(emptyList(), listOf(shared))["one"])
        assertTrue(ArtistOwnership.resolve(emptyList(), listOf(shared.copy(target = null))).isEmpty())
    }
    @Test fun wholeGroupMovesWithoutChangingCreditsAndEmptyGroupDisappears() {
        val tracks = listOf(track("one", main), track("two", combo), track("three", combo))
        val i = index(tracks, mapOf("two" to main, "three" to main))
        assertEquals(listOf("main"), i.artists.map { it.id })
        assertEquals(3, i.artists.single().tracks.size)
        assertEquals(combo.name, i.tracks["two"]?.artist)
        assertEquals(setOf(combo.key), i.tracks.getValue("two").artistKeys())
        assertEquals(2, i.search("Guest", SearchScope(SearchKind.SONGS)).size)
        assertEquals(3, i.search("Song", SearchScope(artist = main.key)).size)
        assertTrue(i.search("Song", SearchScope(artist = combo.key)).isEmpty())
    }
    @Test fun movingSelectedSongRetainsOtherSongsInSourceGroup() {
        val i = index(listOf(track("one", combo), track("two", combo)), mapOf("one" to main))
        assertEquals(setOf("main", "combined"), i.artists.map { it.id }.toSet())
        assertEquals(listOf("two"), i.artists.first { it.id == combo.key }.tracks.map { it.id })
    }
    @Test fun sameNameDoesNotMergeDifferentIdsAndSlashIsNeverSplit() {
        val i = index(listOf(track("one", ArtistRef("a", "Same")), track("two", ArtistRef("b", "Same")), track("three", ArtistRef("band", "A/B"))), emptyMap())
        assertEquals(3, i.artists.size)
        assertEquals("A/B", i.artists.first { it.id == "band" }.name)
    }
    @Test fun structuredCollaboratorsRemainIntactAfterReclassification() {
        val original = track("one", main).copy(artists = listOf(main, ArtistRef("guest", "Guest")))
        val i = index(listOf(original), mapOf("one" to ArtistRef(name = "My group")))
        assertEquals(2, i.tracks.getValue("one").performers().size)
        assertEquals("My group", i.artists.single().name)
        assertEquals(original.albumCredits(), i.tracks.getValue("one").albumCredits())
    }
    @Test fun onlyUnchangedSubmittedPersonalEditFollowsItsPublication() {
        val p = PersonalOwnership("one", main, "stamp")
        val shared = SharedOwnership("one", main, 1, "request")
        assertTrue(ArtistOwnership.follows(p, p, shared, "request"))
        assertFalse(ArtistOwnership.follows(p.copy(stamp = "new"), p, shared, "request"))
        assertFalse(ArtistOwnership.follows(p, p, shared.copy(requestId = "other"), "request"))
        assertFalse(ArtistOwnership.follows(p, p, shared.copy(target = combo), "request"))
    }
    @Test fun newOwnershipUsesTargetRegionAndTypeButPreservesProfiles() {
        val profiles = mapOf(main.key to ArtistProfile(main.key, main.name, region = "韩国", type = "女"), combo.key to ArtistProfile(combo.key, combo.name, region = "欧美"))
        val i = LibraryIndex.build(Library(listOf(track("one", combo))), profiles, emptyList(), order, mapOf("one" to main))
        assertEquals("韩国", i.regions["one"])
        assertEquals(1, i.search("Song", SearchScope(artistType = "女")).size)
        assertEquals("欧美", profiles[combo.key]?.region)
    }
    @Test fun generatedTenThousandSongIndexHasNoDuplicateOwnershipOrLostPaths() {
        val tracks = (0 until 10000).map { n -> track("$n", ArtistRef("artist-${n % 2000}", "Artist ${n % 2000}")) }
        val owners = tracks.take(1000).associate { it.id to main }
        val i = index(tracks, owners)
        assertEquals(10000, i.tracks.size)
        assertEquals(1000, i.artists.first { it.id == main.key }.tracks.size)
        assertEquals(10000, i.folderTracks("music", true, "全部").size)
        assertEquals(1000, i.search("Song", SearchScope(artist = main.key)).size)
    }
    @Test fun discoveryTreatsOriginalCreditsAndChosenMainGroupAsConstraints() {
        val guest = ArtistRef("guest", "Guest")
        val duet = track("duet", combo).copy(artists = listOf(combo, guest))
        assertEquals(setOf(combo.key, guest.key, main.key), duet.recommendationArtistKeys(mapOf("duet" to main)))
        val others = (1..20).map { track("other-$it", ArtistRef("artist-$it", "Artist $it")) }
        val library = Library(listOf(track("solo", main), duet) + others)
        val result = DiscoveryEngine.extend(library, emptyMap(), DiscoveryState(), listOf("solo"), setOf("solo"), 42, 5, mapOf("duet" to main))
        assertFalse(result.picks.any { it.id == "duet" })
    }
}
