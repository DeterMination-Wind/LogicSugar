# 短路条件 exprsc：&& / || 只在必要时求值。
# 与普通 expr 条件相比，exprsc 会把每个子条件编译成独立的 jump。
set a 1
set b 0
ifbegin exprsc "a > 0 && b > 5" 999
print "两个条件都成立"
else
print "exprsc: a>0 成立、b>5 不成立 -> else 分支"
blockend
printflush board
