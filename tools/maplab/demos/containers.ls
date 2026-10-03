# stack / queue / deque：三类容器共用一块内存的不同地址段。
stack s cell1 0 4
queue q cell1 8 4
deque d cell1 16 4
datacall stack_push r1 "s, 7"
datacall queue_push r2 "q, 8"
datacall deque_push_front r3 "d, 9"
top = s.top()
front = q.front()
back = d.back()
print "stack top="
print top
print " queue front="
print front
print " deque back="
print back
printflush board
