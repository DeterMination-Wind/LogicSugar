# 处理器状态指示：长等待进度圆环。
# wait 期间处理器头顶会画一圈进度圆环（设置里可调阈值与扫描频率）。
set i 0
whilebegin i lessThan 3 999
i = i + 1
wait 5
blockend
print "wait 3 次 x 5 秒 结束"
printflush board
