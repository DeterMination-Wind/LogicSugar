# bitset 位集：一个槽存 64 个布尔位。
bitset bits cell1 0 2
datacall bitset_set r1 "bits, 3"
datacall bitset_set r2 "bits, 5"
datacall bitset_count n "bits"
b3 = bits[3]
b4 = bits[4]
print "位3="
print b3
print " 位4="
print b4
print " 置位总数="
print n
printflush board
