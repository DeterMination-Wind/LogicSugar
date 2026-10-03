# 数组批量运算卡（datacall：array_*）：
# fill 填充、sort 排序、sum 求和、min 取最小、find 查下标。
array buf cell1 0 8
datacall array_fill ~ "buf, 3"
buf[0] = 9
buf[1] = 1
buf[2] = 5
buf[3] = 7
datacall array_sort ~ "buf"
datacall array_sum sum "buf"
datacall array_min minv "buf"
datacall array_find pos "buf, 7"
print "排序后 sum="
print sum
print " min="
print minv
print " 7 的下标="
print pos
printflush board
