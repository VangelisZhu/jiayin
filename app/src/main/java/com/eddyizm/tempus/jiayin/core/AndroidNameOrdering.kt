package com.eddyizm.tempus.jiayin.core

import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import android.icu.util.ULocale

/** One instance per background index build: ICU collators are not shared across threads. */
class AndroidNameOrdering : NameOrdering {
    private val locale = ULocale.forLanguageTag("zh-Hans-u-co-pinyin")
    private val alphabet = AlphabeticIndex<Any>(locale).addLabels(ULocale.ENGLISH).buildImmutableIndex()
    private val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    private val chunks = mutableMapOf<String, List<String>>()
    private fun clean(s: String) = normalizeSearch(s)
    override fun section(name: String, region: String, alias: String?): String {
        val text = clean(alias?.takeIf { it.isNotBlank() } ?: name)
        val first = text.firstOrNull() ?: return "#"
        if (first in 'a'..'z') return first.uppercaseChar().toString()
        if (!first.isLetter() || alias.isNullOrBlank() && (region in listOf("日本", "韩国") || text.any { it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' })) return "#"
        val label = alphabet.getBucket(alphabet.getBucketIndex(text))?.label.orEmpty()
        return label.takeIf { it.length == 1 && it[0] in 'A'..'Z' } ?: "#"
    }
    override fun compare(a: String, b: String): Int {
        val aa = chunks.getOrPut(a) { Regex("[0-9]+|[^0-9]+").findAll(clean(a)).map { it.value }.toList() }
        val bb = chunks.getOrPut(b) { Regex("[0-9]+|[^0-9]+").findAll(clean(b)).map { it.value }.toList() }
        aa.zip(bb).forEach { (x, y) ->
            val value = if (x.first().isDigit() && y.first().isDigit()) {
                val nx = x.trimStart('0').ifBlank { "0" }; val ny = y.trimStart('0').ifBlank { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else collator.compare(x, y)
            if (value != 0) return value
        }
        return aa.size.compareTo(bb.size)
    }
}
