package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class SongInformationTest {
    @Test fun sameNamedAlbumsUseIdsAndDiscOrder() {
        val a = Track("a", "A", album = "Same", albumId = "album-a", number = 2, disc = 1)
        val b = a.copy(id = "b", number = 1)
        val c = a.copy(id = "c", albumId = "album-b")
        assertEquals(listOf("b", "a"), SongInformationTargets.album(a, listOf(a, c, b))!!.tracks)
    }
    @Test fun missingIdsRespectExistingAlbumGroupingAndMissingMetadataIsNotClickable() {
        val a = Track("a", "A", "Artist A", album = "Same")
        val b = a.copy(id = "b", artist = "Artist B")
        assertEquals(listOf("a"), SongInformationTargets.album(a, listOf(a, b))!!.tracks)
        assertNull(SongInformationTargets.album(Track("none", "None"), listOf(a)))
        assertNull(SongInformationTargets.performer(ArtistRef(name = "未知歌手"), listOf(a), ""))
        assertNull(SongInformationTargets.performer(ArtistRef("missing", "Not in library"), listOf(a), ""))
    }
    @Test fun CollaboratorsAndSameNamedArtistsRemainIndependent() {
        val a = ArtistRef("a", "Same Name"); val b = ArtistRef("b", "Same Name")
        val duet = Track("duet", "Duet", artists = listOf(a, b))
        val solo = Track("solo", "Solo", artists = listOf(a))
        assertEquals(listOf("duet", "solo"), SongInformationTargets.performer(a, listOf(duet, solo), "")!!.tracks)
        assertEquals(listOf("duet"), SongInformationTargets.performer(b, listOf(duet, solo), "")!!.tracks)
        assertEquals(listOf(a, b), duet.performers())
    }
}
