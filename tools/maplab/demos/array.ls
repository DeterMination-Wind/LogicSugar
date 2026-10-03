# array 数组 + 下标糖 buf[i]。
# 声明卡只告诉编译器「buf 是 cell1 的 0~8」，不产出任何 mlog 行。
array buf cell1 0 8
set i 0
forbegin i 0 1 lessThan 8 999
buf[i] = i * i
blockend
x = buf[5]
print "buf[5] = 5*5 = "
print x
printflush board
