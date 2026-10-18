/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 两端词库的三方合并：base（上次同步的云端快照）/ local / remote。
 *
 * 单端改动用单端结果，双方都改同一条时按修改时间新者胜。
 * 这样删除不会因为另一端还留着旧条目就复活；两端都改同一条时也不会卡住自动同步。
 */
object GlossaryMerge {
    data class Outcome(
        val snapshot: VoiceDictionary.Snapshot,
        val conflicts: Int,
        val conflictKeys: List<String>,
        val changedLocal: Boolean,
    )

    fun merge(
        base: VoiceDictionary.Snapshot?,
        local: VoiceDictionary.Snapshot,
        remote: VoiceDictionary.Snapshot,
        localChangedAt: Long,
        remoteUpdatedAt: Long,
    ): Outcome {
        val localHotwords = hotwordMap(local.hotwords)
        val remoteHotwords = hotwordMap(remote.hotwords)
        val baseHotwords = base?.let { hotwordMap(it.hotwords) }.orEmpty()

        val localRules = ruleMap(local.rules)
        val remoteRules = ruleMap(remote.rules)
        val baseRules = base?.let { ruleMap(it.rules) }.orEmpty()

        val words = LinkedHashMap<String, String?>()
        val rules = LinkedHashMap<String, VoiceDictionary.Rule?>()
        val conflicts = ArrayList<String>()

        val hotwordKeys = LinkedHashSet<String>().apply {
            addAll(localHotwords.keys)
            addAll(remoteHotwords.keys)
            addAll(baseHotwords.keys)
        }
        for (key in hotwordKeys) {
            val b = baseHotwords[key]
            val l = localHotwords[key]
            val r = remoteHotwords[key]
            val picked = pick(base != null, b, l, r, localChangedAt, remoteUpdatedAt)
            if (picked.conflict) conflicts += "hotword:$key"
            words[key] = picked.value
        }

        val ruleKeys = LinkedHashSet<String>().apply {
            addAll(localRules.keys)
            addAll(remoteRules.keys)
            addAll(baseRules.keys)
        }
        for (key in ruleKeys) {
            val b = baseRules[key]
            val l = localRules[key]
            val r = remoteRules[key]
            val picked = pick(base != null, b, l, r, localChangedAt, remoteUpdatedAt)
            if (picked.conflict) conflicts += "rule:$key"
            rules[key] = picked.value
        }

        val mergedHotwords = orderedHotwords(local.hotwords, remote.hotwords, words)
        val mergedRules = orderedRules(local.rules, remote.rules, rules)
        val snapshot = VoiceDictionary.Snapshot(mergedHotwords, mergedRules)
        return Outcome(
            snapshot = snapshot,
            conflicts = conflicts.size,
            conflictKeys = conflicts,
            changedLocal = !same(snapshot, local),
        )
    }

    fun same(
        a: VoiceDictionary.Snapshot,
        b: VoiceDictionary.Snapshot,
    ): Boolean = hotwordMap(a.hotwords) == hotwordMap(b.hotwords) && ruleMap(a.rules) == ruleMap(b.rules)

    private data class Picked<T>(
        val value: T?,
        val conflict: Boolean,
    )

    private fun <T> pick(
        hasBase: Boolean,
        base: T?,
        local: T?,
        remote: T?,
        localChangedAt: Long,
        remoteUpdatedAt: Long,
    ): Picked<T> {
        if (!hasBase) {
            // 首次同步没有共同基线：两边都保留，同一条以本机为准。
            return Picked(local ?: remote, conflict = false)
        }
        if (local == base) return Picked(remote, conflict = false)
        if (remote == base) return Picked(local, conflict = false)
        if (local == remote) return Picked(local, conflict = false)
        return Picked(
            value = if (remoteUpdatedAt > localChangedAt) remote else local,
            conflict = true,
        )
    }

    private fun orderedHotwords(
        local: List<String>,
        remote: List<String>,
        picked: Map<String, String?>,
    ): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (source in listOf(local, remote)) {
            for (word in source) {
                val key = hotwordKey(word)
                if (!seen.add(key)) continue
                picked[key]?.let { out += it }
            }
        }
        return out
    }

    private fun orderedRules(
        local: List<VoiceDictionary.Rule>,
        remote: List<VoiceDictionary.Rule>,
        picked: Map<String, VoiceDictionary.Rule?>,
    ): List<VoiceDictionary.Rule> {
        val out = ArrayList<VoiceDictionary.Rule>()
        val seen = HashSet<String>()
        for (source in listOf(local, remote)) {
            for (rule in source) {
                val key = ruleKey(rule.from)
                if (!seen.add(key)) continue
                picked[key]?.let { out += it }
            }
        }
        return out
    }

    private fun hotwordMap(words: List<String>): Map<String, String> =
        words
            .map { HotwordSanitizer.normalize(it) }
            .filter { it.isNotEmpty() }
            .associateBy { it.lowercase() }

    private fun ruleMap(rules: List<VoiceDictionary.Rule>): Map<String, VoiceDictionary.Rule> =
        rules
            .map { VoiceDictionary.Rule(it.from.trim(), it.to.trim()) }
            .filter { it.from.isNotEmpty() && it.to.isNotEmpty() }
            .associateBy { it.from.lowercase() }

    private fun hotwordKey(word: String): String = HotwordSanitizer.normalize(word).lowercase()

    private fun ruleKey(from: String): String = from.trim().lowercase()
}
