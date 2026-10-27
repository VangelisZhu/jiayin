package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistEditsTest {
    @Test fun duplicateRemovalTargetsOnlyConfirmedOccurrence() {
        val p = MusicList("p", "Generated", listOf("same", "different", "same"))
        assertEquals(listOf("same", "different"), PlaylistEdits.remove(p.tracks, p.entryRef(2)))
    }
    @Test fun missingMetadataMustNotShiftRawIndex() {
        val p = MusicList("p", "Generated", listOf("missing", "one", "two"))
        assertEquals(listOf("missing", "one"), PlaylistEdits.remove(p.tracks, p.entryRef(2)))
    }
    @Test fun changedOrderRejectsStaleConfirmation() {
        val p = MusicList("p", "Generated", listOf("one", "two"))
        assertNull(PlaylistEdits.remove(p.tracks.reversed(), p.entryRef(0)))
    }
    @Test fun additionsPreserveExistingDuplicatesButNeverCreateNewOnes() {
        assertEquals(listOf("a", "a", "b", "c"), PlaylistEdits.appendMissing(listOf("a", "a"), listOf("a", "b", "b", "c")))
    }
    @Test fun identityDoesNotNormalizeSongIds() {
        assertEquals(listOf("A", "a", "01"), PlaylistEdits.appendMissing(listOf("A"), listOf("a", "01")))
    }
    @Test fun versionHasUnambiguousBoundaries() { assertNotEquals(playlistVersion(listOf("a:b", "c")), playlistVersion(listOf("a", "b:c"))) }
    @Test fun uncertainRemovalDistinguishesBeforeAfterAndConflict() {
        assertEquals("apply", PlaylistEdits.removalState(listOf("a", "b"), listOf("a", "b"), listOf("b")))
        assertEquals("done", PlaylistEdits.removalState(listOf("b"), listOf("a", "b"), listOf("b")))
        assertEquals("conflict", PlaylistEdits.removalState(listOf("b", "a"), listOf("a", "b"), listOf("b")))
    }
    @Test fun emptyAndSingleEntryPlaylists() {
        val p = MusicList("p", "Generated", listOf("a"))
        assertEquals(emptyList<String>(), PlaylistEdits.remove(p.tracks, p.entryRef(0)))
        assertEquals(listOf("a"), PlaylistEdits.appendMissing(emptyList(), listOf("a", "a")))
    }
}
