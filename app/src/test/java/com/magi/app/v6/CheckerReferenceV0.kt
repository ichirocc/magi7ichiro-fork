package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 2026-10-03 の check 高速化（c41/c42 の前計算・ログの遅延生成）より前の `UnifiedViolationChecker` の写し。
 * `CheckerEquivalenceTest` が新旧の報告を全フィールドで突き合わせるための基準＝ここは直さない。
 */
internal object CheckerReferenceV0 {
    /**
     * [3.395.0/高速化] mark 系の重み優先比較のための事前表。
     *
     * 旧: `MirrorKeys.weightOf(prev.removePrefix("vio-"))` ＝**マークが重なるたびに String を1個作る**
     * うえ、`weightOf` は String の `when`（ハッシュ分岐＋equals）。実測で `mark` が `check()` の
     * 自己時間の 14.1%、`weightOf` が 2.7% を占めていた。クラス名から直接引けば割り当てゼロで済む。
     * 値は `weightOf` から作るので**重みの定義は `MirrorKeys` の1箇所のまま**（ドリフトしない）。
     */
    private val classWeight: Map<String, Double> by lazy { vioClass.entries.associate { it.value to MirrorKeys.weightOf(it.key) } }

    private val vioClass = mapOf(
        "c1" to "vio-c1", "c2" to "vio-c2", "c3" to "vio-c3", "c3n" to "vio-c3n", "c3w" to "vio-c3w",
        "c3m" to "vio-c3m", "c3mn" to "vio-c3mn", "c41" to "vio-c41", "c42" to "vio-c42",
        "c41s" to "vio-c41s", "c42s" to "vio-c42s",
        "covU" to "vio-covU", "covO" to "vio-covO", "pref" to "vio-pref",
        "low" to "vio-low", "high" to "vio-high", "groupViol" to "vio-groupViol",
        // 適切回数(双方向目標): 不足=赤 / 超過=橙（range と同色だが家族は別。TallyCard/内訳で個別解決可能にする）。
        "aptLow" to "vio-aptLow", "aptHigh" to "vio-aptHigh",
        "extWish" to "vio-extWish",
    )

    fun check(
        state: MagiState,
        schedule: Array<IntArray> = state.schedule.toIntArray2D(),
        quantitativeRangeEval: Boolean = false,
    ): ViolationReport {
        val t0 = System.nanoTime()
        val p = cachedProblem(state, quantitativeRangeEval)
        val scratch = RefScratch(p)
        val s = scratch.normalize(schedule, p)
        // [3.395.0/高速化] 集計は添字加算の IntArray で行い、最後に `MirrorKeys.all` の順で Map へ起こす
        //   （返り値の中身と順序は従来と完全に同じ）。`inc` に渡すキーは全て `MirrorKeys.all` にある
        //   ことを確認済み（c3系は `checkC3Family` が受け取った族名をそのまま返す）。
        val bd = IntArray(MirrorKeys.all.size)

        fun inc(key: String, amount: Int = 1) { bd[MirrorKeys.index.getValue(key)] += amount }
        // [判読性/レビュー指摘] 同一セルに複数族が重なる場合、従来は「後にマークした族」が無条件上書きで、
        //   評価順の最後(c3系)が pref/groupViol(必須)のマークを潰し、実線枠が角マーク(軽ソフト)へ降格し得た
        //   （重大度の逆転）。MirrorKeys.weights を表示優先度として使い、常に最重の族のマークを保持する。
        //   1セル1クラスの型は維持（複数違反の全保持=Set化は別段の改修）。inc/breakdown は従来どおり全件計上
        //   ＝スコアリング不変・表示のみ。
        // [Set化] 重なった全クラスは cellFams("i,j"→クラス列)にも蓄積（重複なし・後で重み降順に整列）。
        //   violations は従来どおり最重1クラス＝既存読者は不変。
        val cellFams = linkedMapOf<String, List<String>>()
        val countFams = linkedMapOf<String, List<String>>()
        val needFams = linkedMapOf<String, List<String>>()
        val c1Runs = ArrayList<List<Int>>()
        // [3.395.0/高速化] 「最重1クラス」を毎回ここで決めるのをやめ、末尾で `cellFams` の**整列済み先頭**
        //   から起こす。両者は定義上いつも同じ値になる：整列は重み降順の**安定ソート**なので先頭＝最初に
        //   マークされた最大重みのクラス、旧ロジックの「厳密に重いものだけが置き換える」も同じものを残す
        //   （このファイルの `cellFamilies` の注記が元から「先頭は violations[key] と常に一致」と書いている）。
        //   挿入順も同じ（どちらも最初のマークで生える LinkedHashMap）。これで1マークあたり
        //   ハッシュ探索1回＋重み比較2回が消える（実測で `mark` が `check()` 自己時間の 20% だった）。
        fun mark(i: Int, j: Int, family: String) {
            val cls = vioClass[family] ?: family
            val fams = cellFams.getOrPut(pairKey(i, j)) { ArrayList(2) } as MutableList<String>
            if (cls !in fams) fams.add(cls)
        }
        // [判読性] mark() と同じ重み優先。旧: 後勝ちで軽い族(旧 covO=0.5 等)が重い族(c41 等)のマークを上書きし得た。
        // [/code-review] 重なった全クラスを needFams へ蓄積（重複なし・後で重み降順に整列）。
        // [3.395.0] mark() と同じ理由で「最重1クラス」は末尾で先頭から起こす。
        fun markNeed(k: Int, j: Int, family: String) {
            val cls0 = vioClass[family] ?: family
            val fams = needFams.getOrPut(pairKey(k, j)) { ArrayList(2) } as MutableList<String>
            if (cls0 !in fams) fams.add(cls0)
        }
        // [防御的統一/敵対的監査で確認] mark()/markNeed() と同じ重み優先へ統一。旧: 無条件上書き
        //   (last-write-wins)は、現在の呼出順(c2→low→high→apt)と apt呼出側の手動 containsKey ガードが
        //   偶然噛み合っているだけで安全が成立していた（低い重みの族が後から呼ばれると高い重みの族の
        //   マークを消し得る潜在的な地雷）。今回は実害の確認された不具合ではないが、mark/markNeed と
        //   同じ規律に揃えて将来の族追加に対して頑健にする。
        //   [3.243.0, HF77明示指示] aptLow/aptHigh は `MirrorKeys.weightOf` により apt 本体と同じ重み1.0で
        //   解決する（旧: weights にキーが無く 0.0 扱い＝c2/low/high 等の全実族に対し常に劣後していた）。
        //   同重み同士は先勝ち(mark順)＝c2(先に呼ばれる)が apt(後に呼ばれる)より引き続き優先される。
        //   表示のみ・スコアリング(weightedScore/breakdown/inc)は不変。
        // [3.353.0] 重なった全クラスを countFams へ蓄積（重複なし・後で重み降順に整列）。
        // [3.395.0] mark() と同じ理由で「最重1クラス」は末尾で先頭から起こす。
        fun markCount(i: Int, k: Int, family: String) {
            val cls0 = vioClass[family] ?: family
            val fams = countFams.getOrPut(pairKey(i, k)) { ArrayList(2) } as MutableList<String>
            if (cls0 !in fams) fams.add(cls0)
        }
        fun cellIs(i: Int, j: Int, k: Int): Boolean = i in 0 until p.S && j in 0 until p.T && s[i][j] == k

        for (c in p.cons1) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                var j = 0
                // [視認性] scoring(inc)は各違反窓ごとに従来どおり計上（不変）。表示(mark)だけは
                //   窓幅ぶんの塗り広げを止め、違反窓ランの先頭1セルにアンカーする。スライド窓が重複して
                //   持続不足で行全体を破線で埋めていた（1論理違反≒窓幅×重複数セル）のを 1不足領域=1マーカーへ。
                var prevViol = false
                var runStart = 0
                // [3.395.0/高速化] 旧: 窓の開始位置ごとに day1 個を数え直す O(T×day1)。窓は1日ずつ滑るので
                //   「出た日を引き、入った日を足す」だけで同じ数になる＝O(T)。`j` は `0..T-day1`・`l < day1`
                //   なので `j+l <= T-1`＝常に範囲内で、`cellIs` の境界検査も外せる（`s` は S×T に正規化済み）。
                //   数える値が同じなので結果は1ビットも変わらない。
                if (c.day1 > p.T) continue
                val row = s[i]
                var z = 0
                for (l in 0 until c.day1) if (row[l] == c.shiftIdx) z++
                while (j <= p.T - c.day1) {
                    if (j > 0) {
                        if (row[j - 1] == c.shiftIdx) z--
                        if (row[j + c.day1 - 1] == c.shiftIdx) z++
                    }
                    val viol = z < c.day2
                    if (viol) {
                        inc("c1")
                        if (!prevViol) { mark(i, j, "c1"); runStart = j }
                    } else if (prevViol) c1Runs.add(listOf(i, runStart, j - runStart, c.day1))
                    prevViol = viol
                    j++
                }
                if (prevViol) c1Runs.add(listOf(i, runStart, j - runStart, c.day1))
            }
        }

        val counts = scratch.countMatrix(s, p)
        for (c in p.cons2) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                if (p.quantitativeRangeEval) {
                    val amt = c2Amount(counts[i][c.shiftIdx], c.count)
                    if (amt > 0) { inc("c2", amt.toInt()); markCount(i, c.shiftIdx, "c2") }
                } else if (counts[i][c.shiftIdx] < c.count) {
                    inc("c2")
                    markCount(i, c.shiftIdx, "c2")
                }
            }
        }

        for (c in p.cons41) {
            for (j in 0 until p.T) {
                var z = 0
                for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && cellIs(i, j, c.shiftIdx)) z++
                if (p.quantitativeRangeEval) {
                    val amt = rangeDistance(z, c.l, c.u)
                    if (amt > 0) { inc("c41", amt.toInt()); markNeed(c.shiftIdx, j, "c41") }
                } else if (z < c.l || z > c.u) {
                    inc("c41")
                    markNeed(c.shiftIdx, j, "c41")
                }
            }
        }

        // [3.395.0/高速化] 旧: (規則×日) ごとに ArrayList を2個作っていた。違反が出るのは稀なので
        //   大半は「片側が空」で捨てられる＝割り当てが丸ごと無駄だった（実測で L267/268/273 が
        //   `check()` の 16.3%）。使い回しの IntArray ＋ 件数で同じ走査をする（結果は同じ）。
        val pairL = IntArray(p.S)
        val pairR = IntArray(p.S)
        for (c in p.cons42) {
            for (j in 0 until p.T) {
                var nL = 0
                var nR = 0
                for (i in 0 until p.S) {
                    if (p.sgrp[i] == c.g1 && cellIs(i, j, c.s1)) pairL[nL++] = i
                    if (p.sgrp[i] == c.g2 && cellIs(i, j, c.s2)) pairR[nR++] = i
                }
                if (nL == 0 || nR == 0) continue
                // [3.318.0] 自己ペア／同一集合の順序重複を数えない（`c42PairCount` と同じ意味論）。
                //   left と right が同じ集合になるのは g1==g2 かつ s1==s2 のときだけ。
                val sameSet = c.g1 == c.g2 && c.s1 == c.s2
                for (a in 0 until nL) for (b in 0 until nR) {
                    val i = pairL[a]
                    val i2 = pairR[b]
                    if (i == i2) continue
                    if (sameSet && i2 < i) continue
                    inc("c42")
                    mark(i, j, "c42")
                    mark(i2, j, "c42")
                }
            }
        }

        // [スキルグループ新設] スキル群の C41/C42 相当（ssk を参照・既存ユニットの sgrp とは独立）。
        for (c in p.cons41s) {
            for (j in 0 until p.T) {
                var z = 0
                for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && cellIs(i, j, c.shiftIdx)) z++
                if (p.quantitativeRangeEval) {
                    val amt = rangeDistance(z, c.l, c.u)
                    if (amt > 0) { inc("c41s", amt.toInt()); markNeed(c.shiftIdx, j, "c41s") }
                } else if (z < c.l || z > c.u) { inc("c41s"); markNeed(c.shiftIdx, j, "c41s") }
            }
        }
        for (c in p.cons42s) {
            for (j in 0 until p.T) {
                var nL = 0
                var nR = 0
                for (i in 0 until p.S) {
                    if (p.ssk[i] == c.g1 && cellIs(i, j, c.s1)) pairL[nL++] = i
                    if (p.ssk[i] == c.g2 && cellIs(i, j, c.s2)) pairR[nR++] = i
                }
                if (nL == 0 || nR == 0) continue
                val sameSet = c.g1 == c.g2 && c.s1 == c.s2   // [3.318.0] c42 と同じ（自己ペア／順序重複を除く）
                for (a in 0 until nL) for (b in 0 until nR) {
                    val i = pairL[a]
                    val i2 = pairR[b]
                    if (i == i2) continue
                    if (sameSet && i2 < i) continue
                    inc("c42s"); mark(i, j, "c42s"); mark(i2, j, "c42s")
                }
            }
        }

        checkC3Family(p, s, p.cons3, "c3", forbidden = false, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3n, "c3n", forbidden = true, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3m, "c3m", forbidden = false, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3mn, "c3mn", forbidden = true, { key, amt -> inc(key, amt) }, ::mark)

        // [3.542.0] 希望の前日に禁止(c3w, HARD)。前日側のセル（動かせる側）を違反箇所にする。
        if (p.c3wBan != null) for (i in 0 until p.S) for (j in 0 until p.T) {
            if (p.c3wBanned(i, j, s[i][j])) { inc("c3w"); mark(i, j, "c3w") }
        }
        // [3.653.0] 族の追加は高速化と別の意味の変更＝新旧の両方へ同じ位置で入れる（比較の対象は高速化だけ）。
        if (p.hasExtBan) for (i in 0 until p.S) for (j in 0 until p.T) {
            if (p.extBanned(i, j, s[i][j])) { inc("extWish"); mark(i, j, "extWish") }
        }

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val w = p.wish[i][j]
            // [監査#11②] 実現可能な希望の未充足のみ HARD(pref) 計上・着色。担当不可の不可能希望は
            //   充足しようがなく「配布可(HARD=0)」を恒久不能にしていたため計数から対称除外する。
            //   可視性は impossibleWishCount と Sanity の不可能希望案内が担う。
            if (w in 0 until p.K && p.canDo(i, w) && s[i][j] != w) {
                inc("pref")
                mark(i, j, "pref")
            }
        }

        for (i in 0 until p.S) {
            for (k in 0 until p.K) {
                val lo = p.rangeLo[i][k]
                val hi = p.rangeHi[i][k]
                val n = counts[i][k]
                if (lo != Int.MIN_VALUE && lo != 0 && p.canDo(i, k) && n < lo) {
                    inc("low", lo - n)
                    markCount(i, k, "low")
                }
                if (hi != Int.MAX_VALUE && n > hi) {
                    inc("high", n - hi)
                    markCount(i, k, "high")
                }
                // [統一apt] 適切回数(群単位の双方向目標)。SOFT・L1偏差|n-t|。担当可シフトのみ(apt 構築時に canDo ガード済)。
                // セル着色は range(low/high)を優先し、markCount の重み優先ガードにより低優先の
                // apt 色(不足=赤/超過=橙)は既存マークを上書きしない（手動 containsKey ガードは markCount 側の
                // 重み優先に統合済みのため撤去）。
                val t = p.apt[i][k]
                if (t >= 0 && n != t) {
                    inc("apt", kotlin.math.abs(n - t))
                    markCount(i, k, if (n > t) "aptHigh" else "aptLow")
                }
            }
        }

        // [統一fair/3.538.0] グループ内公平化: 群×担当ONシフトごと、`Problem.fairDevOfBucket`（達成率モード、
        // 全員に基準が無ければ従来の生回数round(平均)方式）からのL1偏差和。SOFT・重み2。最適化器(Evaluator/Delta)
        // と同一指標。内訳チップ(UI)には出さず weightedScore/total に算入。
        // [場所表示] 偏っているメンバー(x,k,dev)を収集（内訳パネル用・グリッドには出さない）。
        val fairLocs = ArrayList<List<Int>>()
        for (g in 0 until p.G) {
            val mem = p.groupMembers[g]
            if (mem.size < 2) continue
            for (k in p.bucket[g]) {
                val res = p.fairDevOfBucket(g, k) { x -> counts[x][k] }
                for ((x, dx) in res.perMember) fairLocs.add(listOf(x, k, dx))
                if (res.total > 0) inc("fair", res.total)
            }
        }

        // [統一weekly] 7日周期のシフト平準化: 職員ごと、**シフトごと**に、そのシフトが入る日の曜日別カウントの
        // round(そのシフトの回数/7) からの L1 偏差和。SOFT・重み1。最適化器(Evaluator/Delta)と同一指標。
        // [3.345.0] 休は通常のシフト種の一つとして扱う＝勤務/休の二値でなくシフト別に均す。旧定義（勤務日=非休の
        //   曜日カウント）は「毎週おなじ曜日に働く偏り」しか見ておらず、「夜勤が毎週水曜」「休みが毎週月曜」を
        //   区別できなかった。回数0のシフトは偏差0で無害（対象から外す必要はない）。
        // [場所表示] 偏っている(職員,シフト,dev)を収集（内訳パネル用・グリッドには出さない）。
        val weeklyLocs = ArrayList<List<Int>>()
        val wd = scratch.wd
        for (i in 0 until p.S) {
            for (w in wd) w.fill(0)
            for (j in 0 until p.T) { val k = s[i][j]; if (k in 0 until p.K) wd[k][(p.dow0 + j) % 7]++ }
            for (k in 0 until p.K) {
                val d = weeklyDevOfBucket(wd[k])
                if (d > 0) { inc("weekly", d); weeklyLocs.add(listOf(i, k, d)) }
            }
        }
        val distLocations = mapOf(
            "weekly" to weeklyLocs.sortedByDescending { it[2] },
            "fair" to fairLocs.sortedByDescending { it[2] },
        )

        val cov = scratch.coverage(s, p)
        // [監査#4b] 被覆は per-cell OR/AND（VBA本家=Web HF574 と三面統一）。件数=Σセル寄与、
        //   着色=そのセルのU/Oが正のときのみ（「P2で救済されるP1不足は光らない」を自然に内包）。
        //   U>0とO>0は同一セルで両立しないため旧else-if遮蔽は不要。共有ヘルパで最適化器と同式。
        for (j in 0 until p.T) {
            for (k in 0 until p.K) {
                val got = cov[j][k]
                val u = p.covUCell(k, j, got)
                if (u > 0) { inc("covU", u); markNeed(k, j, "covU") }
                val o = p.covOCell(k, j, got)
                if (o > 0) { inc("covO", o); markNeed(k, j, "covO") }
            }
        }

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = s[i][j]
            if (k in 0 until p.K && !p.canDo(i, k)) {
                inc("groupViol")
                mark(i, j, "groupViol")
            }
        }

        // [3.395.0] 集計 IntArray を `MirrorKeys.all` の順で Map へ起こす（内容も順序も旧実装と同じ）。
        val breakdown = linkedMapOf<String, Int>()
        for ((bi, bk) in MirrorKeys.all.withIndex()) breakdown[bk] = bd[bi]

        var total = 0
        for (v in breakdown.values) total += v
        var hard = 0
        for (key0 in MirrorKeys.hard) hard += breakdown[key0] ?: 0
        val soft = total - hard
        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L)
        val hardParts = ArrayList<String>()
        for (key0 in MirrorKeys.hard) hardParts.add("${key0}=${breakdown[key0] ?: 0}")
        val hardStr = hardParts.joinToString(" ")
        val softParts = ArrayList<String>()
        for (key0 in MirrorKeys.soft) {
            val n = breakdown[key0] ?: 0
            if (n > 0) softParts.add("${key0}=${n}")
        }
        val softStr = softParts.joinToString(" ")
        val msg = if (total == 0) {
            "違反なし"
        } else {
            "合計=$total | HARD=$hard [$hardStr]" + if (soft > 0) " | SOFT=$soft [$softStr]" else ""
        }
        val level = if (total == 0) "I" else "W"
        // [Set化] クラス列を重み降順に整列（安定ソート＝同重みはマーク順維持 → 先頭は violations[key] と常に一致）。
        val violations = LinkedHashMap<String, String>(cellFams.size)
        for (e in cellFams.entries) {
            val ck = e.key; val cv = e.value
            val sorted = if (cv.size <= 1) cv else cv.sortedByDescending { classWeight[it] ?: 0.0 }
            e.setValue(sorted)
            violations[ck] = sorted[0]   // [3.395.0] 最重1クラス＝整列済み先頭（旧 mark() と同値）
        }
        val countViolations = LinkedHashMap<String, String>(countFams.size)
        for (e in countFams.entries) {
            val ck = e.key; val cv = e.value
            val sorted = if (cv.size <= 1) cv else cv.sortedByDescending { classWeight[it] ?: 0.0 }
            e.setValue(sorted)
            countViolations[ck] = sorted[0]
        }
        val needViolations = LinkedHashMap<String, String>(needFams.size)
        for (e in needFams.entries) {
            val ck = e.key; val cv = e.value
            val sorted = if (cv.size <= 1) cv else cv.sortedByDescending { classWeight[it] ?: 0.0 }
            e.setValue(sorted)
            needViolations[ck] = sorted[0]
        }
        return ViolationReport(
            violations = violations,
            needViolations = needViolations,
            countViolations = countViolations,
            cellFamilies = cellFams,
            countFamilies = countFams,
            needFamilies = needFams,
            breakdown = breakdown,
            total = total,
            hard = hard,
            soft = soft,
            weightedScore = weightedScore(breakdown),
            distLocations = distLocations,
            c1Runs = c1Runs,
            logs = listOf(MirrorLog(iter = 0, level = level, tag = "UnifiedCheck", message = "$msg (${elapsedMs}ms)")),
        )
    }

    private fun checkC3Family(
        p: Problem,
        schedule: Array<IntArray>,
        list: List<C3>,
        key: String,
        forbidden: Boolean,
        inc: (String, Int) -> Unit,
        mark: (Int, Int, String) -> Unit,
    ) {
        for (c in list) {
            val seq = c.seq
            val d = seq.size
            if (d == 0 || d > p.T) continue
            // [統一: 最適化器 Evaluator の HF507 と一致] 非forbidden の単一シフト連は run-deficit で評価する。
            // 長さ r(<d) の run ごとに (d-r) を加算し、その run のセルを強調。素の窓マッチとは違反の「方向」が
            // 異なる（窓=未完成窓数 / run=不足ぶん）ため、最適化器と表示・提案が食い違わないよう統一する。
            if (!forbidden && C3Run.isSingleShiftSeq(seq)) {
                val first = seq[0]
                for (i in 0 until p.S) {
                    val row = schedule[i]
                    val t = row.size
                    var runStart = -1
                    var r = 0
                    var j = 0
                    while (j <= t) {
                        val on = j < t && row[j] == first
                        if (on) {
                            if (r == 0) runStart = j
                            r++
                        } else if (r > 0) {
                            val deficit = d - r
                            if (deficit > 0) {
                                inc(key, deficit)
                                // [視認性] 不足run全塗り→run先頭1セルへアンカー（scoring不変: incは不足ぶん従来どおり）。
                                mark(i, runStart, key)
                            }
                            r = 0; runStart = -1
                        }
                        j++
                    }
                }
                continue
            }
            for (i in 0 until p.S) {
                var j = 0
                while (j <= p.T - d) {
                    if (schedule[i][j] == seq[0]) {
                        var z = 0
                        for (l in 1 until d) if (schedule[i][j + l] == seq[l]) z++
                        val fire = if (forbidden) z == d - 1 else z < d - 1
                        if (fire) {
                            inc(key, 1)
                            // [視認性] SOFT want窓は先頭1セルへアンカー。forbidden(c3n=HARD/c3mn)は禁止パターン
                            //   全体を表示（短く、致命は「どの並びが禁止か」を示す方が有益）。scoring(inc)は不変。
                            if (forbidden) { for (l in 0 until d) mark(i, j + l, key) } else mark(i, j, key)
                        }
                    }
                    j++
                }
            }
        }
    }


    private fun weightedScore(b: Map<String, Int>): Double {
        // [N2/⛏11] 重みは MirrorKeys.weights を単一の真実として参照。挿入順を保持しているため
        //   加算順は従来と同一＝Double 結果は不変。UI の重み表も同マップを描画する。
        var out = 0.0
        for ((key, weight) in MirrorKeys.weights) out += (b[key] ?: 0).toDouble() * weight
        return out
    }

}


private class RefScratch(p: Problem) {
    val wd = Array(p.K) { IntArray(7) }
    fun normalize(schedule: Array<IntArray>, p: Problem) = normalizeSchedule(schedule, p)
    fun countMatrix(sc: Array<IntArray>, p: Problem) = countMatrix(p, sc)
    fun coverage(sc: Array<IntArray>, p: Problem) = coverage(p, sc)
}
