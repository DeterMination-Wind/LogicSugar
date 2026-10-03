# while 循环 + break / continue：跳过奇数，累加到 10 就停。
set i 0
set sum 0
whilebegin i lessThan 20 999
i = i + 1
ifbegin expr "i % 2 == 1" 999
continue
blockend
ifbegin i greaterThan 10 999
break
blockend
sum = sum + i
blockend
print "while 偶数 2+4+6+8+10 = "
print sum
printflush board
