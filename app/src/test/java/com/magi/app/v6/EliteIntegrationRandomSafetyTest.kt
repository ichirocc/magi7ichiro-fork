package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import java.util.Random

/**
 * Manual fuzz harness (not a JUnit test — no @Test, invoked via `main`). Hammers
 * EliteIntegrationPolish with random boards/elites across random dimensions and asserts the three
 * safety invariants that keep-best on top of it depends on: never worse than the root, report
 * matches a fresh official recheck, and the input board is never mutated.
 */
object EliteIntegrationRandomSafetyTest {
    private fun randomState(target: Array<IntArray>): MagiState {
        val s = target.size
        val t = if (s > 0) target[0].size else 0
        return MagiState(
            startDate = "2026-08-01",
            endDate = java.time.LocalDate.parse("2026-08-01").plusDays((t - 1).coerceAtLeast(0).toLong()).toString(),
            shifts = listOf(
                Shift("休", "休", "", ""),
                Shift("A", "A", "", ""),
                Shift("B", "B", "", ""),
            ),
            groups = listOf(Group("G", "G")),
            staff = (0 until s).map { Staff("S$it", 0) },
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)),
            groupShiftApt = listOf(listOf("", "", "")),
            schedule = target.map { row -> row.toList() },
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(),
            cons2 = emptyList(),
            cons3 = emptyList(),
            cons3n = emptyList(),
            cons3m = emptyList(),
            cons3mn = emptyList(),
            cons41 = emptyList(),
            cons42 = emptyList(),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val rng = Random(0xE11E7L)
        var improved = 0
        repeat(500) { case ->
            val s = 2 + rng.nextInt(4)
            val t = 2 + rng.nextInt(6)
            val target = Array(s) { IntArray(t) { rng.nextInt(3) } }
            val state = randomState(target)
            val root = Array(s) { IntArray(t) { rng.nextInt(3) } }
            val saved = root.copy2D()
            val rootRep = UnifiedViolationChecker.check(state, root)
            val elites = ArrayList<AdaptiveElite>()
            repeat(8) { idx ->
                val e = root.copy2D()
                repeat(1 + rng.nextInt(maxOf(1, s * t / 2))) {
                    e[rng.nextInt(s)][rng.nextInt(t)] = rng.nextInt(3)
                }
                val rep = UnifiedViolationChecker.check(state, e)
                elites.add(
                    AdaptiveElite(
                        e, rep,
                        HypothesisEpochRole.entries[idx % HypothesisEpochRole.entries.size],
                        idx, idx, bridge = rep.hard == rootRep.hard + 1,
                    ),
                )
            }
            val out = EliteIntegrationPolish.apply(
                state, root, elites, { false }, System.currentTimeMillis() + 500L,
                EliteIntegrationPolish.Config(maxPairs = 4, maxFusionGroups = 4, maxFusionCells = 8, beamWidth = 8),
            )
            val checked = UnifiedViolationChecker.check(state, out.schedule)
            check(!AdaptiveEliteArchive.better(rootRep, checked)) { "regression case=$case root=$rootRep out=$checked" }
            check(AdaptiveEliteArchive.sameObjective(checked, out.report)) { "report mismatch case=$case" }
            check(AdaptiveEliteArchive.sameSchedule(root, saved)) { "input mutated case=$case" }
            if (AdaptiveEliteArchive.better(checked, rootRep)) improved++
        }
        println("RANDOM cases=500 improved=$improved regressions=0 inputMutations=0 reportMismatch=0")
    }
}
