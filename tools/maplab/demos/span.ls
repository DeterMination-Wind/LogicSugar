# span 合并内存：两块 cell 拼成一段连续逻辑地址。
# buf[9] 落在 cell1，buf[12] 落在 cell2，读写由编译器换算。
span big "cell1 + cell2"
array buf big 0 16
buf[9] = 7
buf[12] = 3
x = buf[9]
y = buf[12]
print "buf[9]="
print x
print " buf[12]="
print y
printflush board
