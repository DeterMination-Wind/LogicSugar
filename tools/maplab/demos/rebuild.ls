set sum 0
set i 0
loop_check:
jump loop_body lessThan i 10
jump loop_exit always x false
loop_body:
op add sum sum i
op add i i 1
jump loop_check always x false
loop_exit:
print "1+...+9 = "
print sum
printflush board
