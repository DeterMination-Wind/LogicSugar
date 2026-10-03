# funcdef：带参数、带返回值的函数。
# normal 模式下两个调用共享同一段子程序体（设置里可切换成 inline 内联展开）。
funcdef area w,h 999
return "w * h"
blockend
funcdef perimeter w,h 999
return "(w + h) * 2"
blockend
funccall area "3, 4" a
funccall perimeter "3, 4" p
print "3x4 -> area="
print a
print " perimeter="
print p
printflush board
