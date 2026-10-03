# 随处表达式：函数实参、return 返回值、条件里都能写表达式。
funcdef midpoint a,b 999
return "(a + b) / 2"
blockend
set x 3
set y 8
funccall midpoint "x * 2, y + 4" mid
print "midpoint(x*2, y+4) = midpoint(6, 12) = "
print mid
printflush board
