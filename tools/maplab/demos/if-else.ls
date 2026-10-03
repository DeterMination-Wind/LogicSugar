# if / elif / else：结构化条件分支。
# 编辑器里看到的是 if / elif / else 三张积木，条件用 Expr 卡写成 hp < 25。
set hp 42
ifbegin expr "hp < 25" 999
set state "濒死"
elif expr "hp < 60"
set state "受伤"
else
set state "健康"
blockend
print "hp = "
print hp
print " -> "
print state
printflush board
