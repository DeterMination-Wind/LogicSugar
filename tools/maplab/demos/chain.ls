# chain 链表：空闲链 + 节点链接。
chain c cell1 0 8
datacall chain_init r0 "c"
datacall chain_alloc n1 "c"
datacall chain_alloc n2 "c"
datacall chain_set s1 "c, 0, 11"
datacall chain_set s2 "c, 1, 22"
datacall chain_link lk "c, 0, 1"
datacall chain_set_head sh "c, 0"
head = c.head()
nxt = c.next(0)
len = c.len()
print "head="
print head
print " next(0)="
print nxt
print " len="
print len
printflush board
