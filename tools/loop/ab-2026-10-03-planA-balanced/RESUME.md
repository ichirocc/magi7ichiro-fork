再開: `setsid nohup bash /tmp/claude-0/-home-user/9af34729-1ee3-5dcc-8433-ea3a204d0dda/scratchpad/pa/pc/run3.sh >/dev/null 2>&1 &`（同じコマンドで再開。二重起動は flock で無視。out3.csv は追記のみ）。
再開の単位は板＝(budget,fixture,seed)。捕獲が非決定的で板ハッシュが再現しないため、rep1-4×ON/OFF の 8 行が揃った板だけ飛ばし、途中の板は捕獲し直して 8 行を取り直す（古い行は解析で捨てる）。新しい JVM は最初に捨てる空回しの対 (rep=0) を入れる。
進捗は pc/progress.txt、完了は pc/err3.log 末尾の DONE、解析は `python3 pc/an3.py pc/out3.csv`（揃った板だけ使う）。
