# 跳转线着色：结构语句的跳转线（while 回跳、if/else 分支）按目标着色。
# 设置 → 逻辑辅助 → 「跳转线着色」三档：关闭 / 分散色 / 积木色。
set i 0
whilebegin i lessThan 5 999
i = i + 1
ifbegin i lessThan 3 999
print "小 "
else
print "大 "
blockend
blockend
printflush board
