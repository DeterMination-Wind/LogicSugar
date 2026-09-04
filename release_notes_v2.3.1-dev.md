# Logic Sugar v2.3.1-dev

## 中文

- 新增 `switch` 自动分派策略：整数 case 值且值域跨度不超过 255 时，按实际可执行指令成本在比较链与 `@counter` 跳转表之间选择；重复 case 值可共享首个匹配槽位。
- 跳转表先执行下界/上界守卫，再通过 `op add @counter @counter` 分派；越界和空洞槽按默认路径处理。非整数值、过宽值域会安全回退到比较链。
- 新增设置 `logicsugar.switchStrategy`：默认 `auto`，`chainOnly` 可复现旧版比较链输出。
- 编译后增加无条件跳转链穿线，合并连续的默认/退出跳转；循环与自跳转带环检测。
- 反编译器新增跳转表识别，并继续通过重编译流校验门；识别失败时保留原始 vanilla mlog。
- mod 身份保持本地开发模式：`LogicSugar-dev` / `0.0.0`。本说明仅用于本地开发验证，未发布 Release。

## English

- Added automatic `switch` dispatch selection: for integer case values with a span of at most 255, the compiler chooses between the comparison chain and an `@counter` jump table by executable-instruction cost; repeated case values can share the first matching slot.
- Jump tables perform lower/upper guards before `op add @counter @counter` dispatch. Out-of-range values and holes follow the default path. Non-integer values and spans above the limit safely fall back to the comparison chain.
- Added `logicsugar.switchStrategy`: `auto` by default, with `chainOnly` available for legacy comparison-chain output.
- Added unconditional jump-chain threading after lowering, with cycle detection so loops and self-jumps remain safe.
- Added jump-table recovery to the decompiler while keeping the recompilation verification gate; unrecognized patterns remain vanilla mlog.
- The mod remains in local development identity mode: `LogicSugar-dev` / `0.0.0`. This note is for local development validation only; no Release was published.
