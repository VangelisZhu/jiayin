package com.eddyizm.tempus.jiayin.core

import androidx.annotation.Keep

/** Browsing ownership never replaces performers, album credits or audio identity. */
@Keep data class PersonalOwnership(val trackId: String, val target: ArtistRef, val stamp: String)
@Keep data class SharedOwnership(val trackId: String, val target: ArtistRef?, val sequence: Long, val requestId: String = "")
@Keep data class OwnershipSubmission(val id: String, val tracks: List<PersonalOwnership>)

object ArtistOwnership {
    fun resolve(personal: List<PersonalOwnership>, shared: List<SharedOwnership>): Map<String, ArtistRef> =
        shared.mapNotNull { row -> row.target?.let { row.trackId to it } }.toMap() + personal.associate { it.trackId to it.target }

    fun artists(track: Track, owners: Map<String, ArtistRef>): List<ArtistRef> = owners[track.id]?.let(::listOf) ?: track.performers()

    /** Only an unchanged personal edit matching this publication may become inherited. */
    fun follows(personal: PersonalOwnership, submitted: PersonalOwnership, shared: SharedOwnership, requestId: String) =
        personal == submitted && shared.requestId == requestId && shared.target == submitted.target
}

/** Retain all performer constraints and also connect combined credits to their chosen main group. */
fun Track.recommendationArtistKeys(ownership: Map<String, ArtistRef>) = artistKeys() + listOfNotNull(ownership[id]?.key)
