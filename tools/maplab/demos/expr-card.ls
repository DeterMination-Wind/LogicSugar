# Expr 卡：一整行表达式写成卡片，保存时自动展开成 op 指令。
set w 5
set h 3
area = w * h
half = area / 2
diag = sqrt(w * w + h * h)
print "w=5 h=3: area="
print area
print " half="
print half
print " diag="
print diag
printflush board
