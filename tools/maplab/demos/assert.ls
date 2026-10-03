# 断言（调试构建）：运行时把期望值/实际值显示在处理器头顶。
# 本程序用 AssertEmit = emit 保存，含真实的 assert 指令；
# 只在单机/编辑器生效，联机与纯原版客户端会把它降级成无效语句。
set hp 120
print "正在检查 hp <= 100 ..."
printflush board
assert lessThanEq hp 100 "hp 超出上限"
print "断言通过"
printflush board
