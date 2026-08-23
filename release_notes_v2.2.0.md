# Logic Sugar v2.2.0

## 中文

- **解析器加固**：`forbegin` / `switchbegin` / `funcdef` / `funccall` 遇到损坏或截断的行时不再崩溃（裸 NPE / NumberFormatException），改为清晰的编译错误信息；打开编辑器前会预校验代码，代码无法解析时不会再因加载回退而静默清空处理器程序。
- **引号往返修复**：函数调用实参 / return 表达式中的引号、波浪号（`~`）和多空格现在可以无损保存与读取，不再因序列化损坏而丢数据。
- **字符串字面量保护**：函数体内 `print "cost  _1  credits"` 这类带多空格和 `_1` 模式的字符串不再被错误改写（空格被压平、内容被改名的问题已修复）。
- **memoryN 存储设备豁免**：`memory1` 等设备在库函数中不再被错误改名（与 `cellN` / `bankN` 一致）。
- **回归测试复活**：if/elif/else 编译测试（IfElseCompileTest，141 行断言）此前从未被执行，现已接入构建并在每次构建时运行；新增 4 个针对本次修复的回归场景。
- 其它修复：表达式编辑器打开时每帧全量重排、按键监听器重复注册、函数库文件每次编译重复读盘、草稿泄漏、Neon 捆绑时设置项重复、折叠表达式时链外引用检查、`~` 波浪号在文本重写时的处理等。
- 上线前经两份独立代码审查复核，全部测试通过（selfTest + ifElseTest + build）。

## English

- **Parser hardening**: corrupted/truncated `forbegin` / `switchbegin` / `funcdef` / `funccall` lines now fail with a clear compile error instead of raw NPE / NumberFormatException crashes; the editor pre-validates code before opening, so an unparseable document can no longer silently wipe the processor program via the load-fallback empty canvas.
- **Quote round-trip fix**: quotes, tildes (`~`) and multi-space text inside function call arguments / return expressions now save and load losslessly.
- **String literal protection**: strings like `print "cost  _1  credits"` inside function bodies are no longer rewritten (whitespace collapsing and in-string renaming fixed).
- **memoryN device exemption**: `memory1` etc. are no longer mis-mangled inside library functions (now consistent with `cellN` / `bankN`).
- **Revived regression suite**: the if/elif/else compile test (IfElseCompileTest, 141 assertions) had never run; it is now wired into every build, plus 4 new regression scenarios for these fixes.
- Other fixes: per-frame full layout while the editor is open, duplicate key listener registration, repeated library file disk reads per compile, draft leakage, duplicate settings rows when bundled into Neon, out-of-chain references checked before expression folding, tilde handling in text rewriting, etc.
- Reviewed by two independent code reviews before release; all tests pass (selfTest + ifElseTest + build).

The universal JAR works on desktop and Android.