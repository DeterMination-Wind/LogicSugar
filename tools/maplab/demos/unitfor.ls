# 单位控制卡：unitfor 遍历本队 @poly 并逐个绑进变量，unitbind 再绑定一只读它的血量。
# 右侧两只 @flare 只用来演示「单位 flag 显示」：打上 flag 7，设置里打开开关就能看到数字。
#
# 注意（容易踩的坑）：LogicSugar 的单位卡用 flag 认领单位 —— 只认 flag 0 或本处理器自己的
# uid。所以不能给 unitfor 正在遍历的那一类单位打自定义 flag，否则下一轮它们会被当成
# 「别人的单位」跳过，数量直接变 0。
set count 0
unitfor 8 @poly unit 999
count = count + 1
blockend
unitbind @poly unit
hp = unit.@health
unitbind @flare target
ucontrol flag 7 0 0 0 0
print "本队 @poly 数量 = "
print count
print "，首只血量 = "
print hp
printflush board
