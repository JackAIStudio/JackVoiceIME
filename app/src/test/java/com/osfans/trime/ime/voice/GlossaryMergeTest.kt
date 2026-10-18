/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun snapshot(
    words: List<String>,
    vararg rules: Pair<String, String>,
) = VoiceDictionary.Snapshot(words, rules.map { VoiceDictionary.Rule(it.first, it.second) })

class GlossaryMergeTest :
    FunSpec({
        test("本地新增在云端未变时保留") {
            val base = snapshot(listOf("A"))
            val local = snapshot(listOf("A", "B"))
            val remote = snapshot(listOf("A"))
            val out = GlossaryMerge.merge(base, local, remote, 2_000, 1_000)
            out.snapshot.hotwords shouldBe listOf("A", "B")
            out.conflicts shouldBe 0
        }

        test("云端新增在本机未变时拉取") {
            val base = snapshot(listOf("A"))
            val local = snapshot(listOf("A"))
            val remote = snapshot(listOf("A", "B"))
            val out = GlossaryMerge.merge(base, local, remote, 1_000, 2_000)
            out.snapshot.hotwords shouldBe listOf("A", "B")
            out.changedLocal shouldBe true
        }

        test("本机删除会传播") {
            val base = snapshot(listOf("A", "B"))
            val local = snapshot(listOf("A"))
            val remote = snapshot(listOf("A", "B"))
            val out = GlossaryMerge.merge(base, local, remote, 2_000, 1_000)
            out.snapshot.hotwords shouldBe listOf("A")
        }

        test("云端删除会传播") {
            val base = snapshot(listOf("A", "B"))
            val local = snapshot(listOf("A", "B"))
            val remote = snapshot(listOf("A"))
            val out = GlossaryMerge.merge(base, local, remote, 1_000, 2_000)
            out.snapshot.hotwords shouldBe listOf("A")
            out.changedLocal shouldBe true
        }

        test("同一条规则两边都改时按更新时间取新") {
            val base = snapshot(emptyList(), "Cloud" to "Claude")
            val local = snapshot(emptyList(), "Cloud" to "云端")
            val remote = snapshot(emptyList(), "Cloud" to "Claude 2")
            val out = GlossaryMerge.merge(base, local, remote, 1_000, 2_000)
            out.snapshot.rules.single().to shouldBe "Claude 2"
            out.conflicts shouldBe 1
        }

        test("首次同步保留两边内容") {
            val local = snapshot(listOf("A"), "Cloud" to "Claude")
            val remote = snapshot(listOf("B"), "Tree" to "Trae")
            val out = GlossaryMerge.merge(null, local, remote, 0, 0)
            out.snapshot.hotwords.toSet() shouldBe setOf("A", "B")
            out.snapshot.rules.map { it.from }.toSet() shouldBe setOf("Cloud", "Tree")
        }
    })
