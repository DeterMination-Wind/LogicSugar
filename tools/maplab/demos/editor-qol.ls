# 编辑器辅助功能演示：长表达式（悬停提示会折行）、嵌套循环（指令预算显示）、
# 以及可以随手试的撤销/重做、Ctrl+点击复制、Ctrl+C/V 跨逻辑复制粘贴、搜索高亮。
set alpha 12
set beta 34
set gamma 56
delta = (alpha * alpha + beta * beta + gamma * gamma) / (alpha + beta + gamma) + max(alpha, max(beta, gamma)) - min(alpha, min(beta, gamma))
print "delta="
print delta
printflush board
set total 0
forbegin i 1 1 lessThanEq 12 999
forbegin j 1 1 lessThanEq 12 999
total = total + i * j
blockend
blockend
print " total="
print total
printflush board
