# list 列表 / heap 小顶堆。
list l cell1 0 8
heap h cell1 32 8
datacall vector_push_back r1 "l, 4"
datacall vector_push_back r2 "l, 2"
datacall heap_push r3 "h, 9"
datacall heap_push r4 "h, 3"
l0 = l[0]
datacall heap_pop hp "h"
datacall vector_size n "l"
print "l[0]="
print l0
print " l.size="
print n
print " heap 弹出最小="
print hp
printflush board
