# map 哈希表 / uset 无序集合。
map m cell1 0 4
uset u cell1 8 4
datacall map_set old "m, 1, 55"
datacall set_add r "u, 9"
v = m[1]
has = u.has(9)
size = u.size()
print "m[1]="
print v
print " u 含 9 = "
print has
print " u 元素数="
print size
printflush board
