# switch / case / default：一个值匹配多个分支。
set mode 2
switchbegin mode 999
case 1
set text "启动"
break
case 2
set text "运行"
break
case 3
set text "停止"
break
default
set text "未知"
break
blockend
print "mode = 2 匹配到 "
print text
printflush board
