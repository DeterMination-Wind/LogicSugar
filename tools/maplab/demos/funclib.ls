# 函数库：functions.txt 里的函数所有处理器共享。
# 保存时只把用到的函数子集写进载体（set __ls_lib "..."），换台电脑也能重开。
funccall gcd "48, 36" g
print "gcd(48, 36) = "
print g
printflush board
