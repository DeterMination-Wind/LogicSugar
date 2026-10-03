# record 记录：编译期的结构体，字段名只是糖。
record p hp team ~ ~ ~ ~ ~ ~
set maxhp 100
p.hp = maxhp - 25
p.team = 3
hp = p.hp
team = p.team
print "p.hp="
print hp
print " p.team="
print team
printflush board
