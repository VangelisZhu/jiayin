package com.eddyizm.tempus.jiayin.core

import kotlin.random.Random

/** Physical timeline IS listening order. Media3 shuffle is deliberately disabled.
 * Each occurrence has its own key, so duplicate songs in a playlist stay distinct. */
object QueueEngine {
    fun create(ids: List<String>, source: String, shuffle: Boolean, tracks: Map<String, Track>, recent: Set<String> = emptySet(), random: Random = Random.Default): QueueState {
        val stamp = java.util.UUID.randomUUID().toString()
        val entries = ids.mapIndexed { i, id -> QueueEntry("$stamp:$i", id, i) }
        return QueueState(if (shuffle) diverse(entries, tracks, recent, random) else entries, source = source, shuffled = shuffle)
    }
    fun toggle(state: QueueState, tracks: Map<String, Track>, recent: Set<String> = emptySet(), random: Random = Random.Default): QueueState {
        if (state.entries.isEmpty()) return state
        val cut = state.index + 1
        val upcoming = state.entries.drop(cut)
        val ordinary = upcoming.filterNot { it.manual }
        val reordered = if (!state.shuffled) diverse(ordinary, tracks, recent, random) else ordinary.sortedBy { it.sourceOrder }
        var j = 0
        // Manual additions retain their slots, including successive "play next" entries.
        val tail = upcoming.map { if (it.manual) it else reordered[j++] }
        return state.copy(entries = state.entries.take(cut) + tail, shuffled = !state.shuffled)
    }
    fun insert(state: QueueState, ids: List<String>, next: Boolean): QueueState {
        val added = ids.map { QueueEntry(java.util.UUID.randomUUID().toString(), it, Int.MAX_VALUE, true) }
        val items = state.entries.toMutableList()
        val at = if (next) (state.index + 1).coerceAtMost(items.size) else items.size
        items.addAll(at, added)
        return state.copy(entries = items)
    }
    fun remove(state: QueueState, index: Int): QueueState {
        if (index !in state.entries.indices) return state
        val items = state.entries.toMutableList().also { it.removeAt(index) }
        return state.copy(entries = items, index = (state.index - if (index < state.index) 1 else 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)), positionMs = if (index == state.index) 0 else state.positionMs)
    }
    fun move(state: QueueState, from: Int, to: Int): QueueState {
        if (from !in state.entries.indices || to !in state.entries.indices) return state
        val currentKey = state.current?.key
        val items = state.entries.toMutableList().also { it.add(to, it.removeAt(from)) }
        return state.copy(entries = items, index = items.indexOfFirst { it.key == currentKey }.coerceAtLeast(0))
    }
    private fun diverse(entries: List<QueueEntry>, tracks: Map<String, Track>, recent: Set<String>, random: Random): List<QueueEntry> {
        // O(n log n), suitable for full libraries. Artist buckets prevent a quadratic scan.
        val buckets = entries.shuffled(random).groupBy { tracks[it.trackId]?.artist.orEmpty() }
            .mapValues { (_, v) -> ArrayDeque(v.sortedBy { it.trackId in recent }) }.toMutableMap()
        val result = ArrayList<QueueEntry>(entries.size)
        var previous = ""
        val heap = java.util.PriorityQueue<String>(compareByDescending<String> { buckets[it]?.size ?: 0 }.thenBy { it })
        heap.addAll(buckets.keys)
        while (heap.isNotEmpty()) {
            var artist = heap.remove()
            if (artist == previous && heap.isNotEmpty()) {
                val alternative = heap.remove(); heap.add(artist); artist = alternative
            }
            result.add(buckets.getValue(artist).removeFirst())
            previous = artist
            if (buckets.getValue(artist).isNotEmpty()) heap.add(artist)
        }
        // Keep recent tracks later where artist spacing allows it.
        return result.filterNot { it.trackId in recent } + result.filter { it.trackId in recent }
    }
}
