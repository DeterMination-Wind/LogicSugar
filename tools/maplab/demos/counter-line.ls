# @counter 指示线与跳转线着色。
# while 的回跳是原版 jump 线（按目标着色）；最后一行是显式写入 @counter 的 Expr 卡，
# 会在卡片左侧画一条镜像到目标卡片的箭头线。
# 赋值卡 / 运算卡（op add @counter …）/ Expr 卡三种写法都会画线；
# 文本里直接写 `@counter = 0` 会导入成 Expr 卡（它落在载体里是 set @counter 0 + 自描述标记）。
set i 0
whilebegin i lessThan 3 999
i = i + 1
blockend
print "i="
print i
printflush board
@counter = 0
