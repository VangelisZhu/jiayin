package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.VersionedCover
import org.junit.Assert.*
import org.junit.Test

class VersionedCoverTest {
    @Test fun originalIdSurvivesSpecialCharactersAndRepeatedRefresh() {
        val id = "al-中文:+/cover"
        val first = VersionedCover.encode(id, "1234")
        val second = VersionedCover.encode(first, "5678")
        assertEquals(id, VersionedCover.original(second))
        assertNotEquals(first, second)
        assertEquals("ir_external", VersionedCover.encode("ir_external", "1234"))
        assertEquals("", VersionedCover.encode("", "1234"))
    }
}
