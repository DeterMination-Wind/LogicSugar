# How the unit-control cards compile

> [Tutorial index](README.md) | Player tutorial: [unit-control.md](unit-control.md) | Chinese: [../unit-control-compilation.md](../unit-control-compilation.md)

## The short version

The four unit-control cards are not sugar for a single `ubind`. They expand a complete flag-claim protocol: compute the processor's flag, scan units, skip the ones that cannot be taken, write the flag, return to the top, and only on the next entry confirm the claim and hand the unit to your variable or loop body.

The product uses only vanilla instructions: `op`, `set`, `ubind`, `sensor`, `ucontrol`, `jump`, `end`, plus whatever you write in the loop body. A vanilla client parses and runs it without the mod.

The cost is length. Below are the full lowerings for `unitbind`, `unitnext`, `unitfree`, and `unitfor`; they eat into the processor's 1000-instruction budget quickly.

## Variable and label names

The real product uses `__ls_ub_*` names, which are noisy to read. This page uses short names while keeping the “original → short → meaning” table; the Original view still shows the left column.

| Original | Short | Meaning |
| --- | --- | --- |
| `__ls_ub_uid` | `myFlag` | this processor's unit flag |
| `__ls_ub_h_0` | `held` | remembered candidate unit |
| `__ls_ub_f_0` | `flag` | scratch read of `@unit.@flag` |
| `__ls_ub_d_0` | `dead` | scratch read of `@unit.@dead` |
| `__ls_ub_c_0` | `controlled` | scratch read of `@unit.@controlled` |
| `__ls_ub_a_0` | `anchor` | first unit seen in this scan |
| `__ls_ub_s_0` | `steps` | scan step counter |
| `__ls_ub_n_0` | `delivered` | `unitfor`: units handed to the body this pass |
| `__ls_ub_p_0` | `phase` | `unitfor`: `0` count, `1` deliver |
| `__ls_ub_o_0` | `owned` | `unitfor`: units already owned |
| `__ls_ub_r_0` | `room` | `unitfor`: new claims still allowed |
| `__ls_ub_scan_0` | `scan` | scan entry |
| `__ls_ub_loop_0` | `loop` | scan loop head |
| `__ls_ub_keep_0` | `keep` | unit passed the liveness check |
| `__ls_ub_take_0` | `take` | reuse/claim succeeded |
| `__ls_ub_lost_0` | `lost` | the reused unit was lost |
| `__ls_ub_fail_0` | `fail` | no unit found |
| `__ls_ub_ok_0` | `ok` | card finished |
| `__ls_ub_ours_0` | `ours` | `unitfor`: this unit is already mine |
| `__ls_ub_count_0` | `count` | `unitfor`: count owned units |
| `__ls_ub_give_0` | `give` | `unitfor`: hand the unit to the body |
| `__ls_ub_wrap_0` | `wrap` | `unitfor`: scan wrapped / list ended |
| `__ls_ub_mark_0` | `mark` | `unitfor`: record the first unit |
| `__ls_ub_free_0` | `free` | `unitfree`: skip the release |
| `__ls_stmt_3` | `afterLoop` | statement position after the `unitfor` block |
| `__ls_ub_step_<i>` | `step` | `unitfor`'s `continue` target (only emitted when used) |

Two things are easy to misread:

- `_0` is the **statement index**, not “card number zero”. A second card in the same program uses `_1`, the third `_2`, and so on.
- `afterLoop` is not always `__ls_stmt_3`. It is `__ls_stmt_<destIndex + 1>`, the position after `blockend`. In the `unitfor` example below, `unitfor` has index 0, the body 1, and `blockend` 2, so the exit label is `__ls_stmt_3`; a different position changes it.

The `__ls_` prefix is reserved by LogicSugar. Do not use these names in your own program.

## The shared prefix: myFlag

Every card recomputes it at the top:

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
```

`@thisx` / `@thisy` are the processor's tile coordinates. `@thisx * 100000` puts x in the high digits, `+ @thisy` packs y in, and the final `+ 1` matters because a processor at the origin `(0, 0)` would otherwise get `0`, while `0` is reserved for “unowned”.

This value has three properties:

- every unit-control card on the same processor shares it, so they operate on the same fleet;
- different processors have different coordinates and therefore different flags, so they do not claim each other's units;
- it is an ordinary variable, not a built-in constant; it is recomputed on every card entry, so placing the processor elsewhere changes the flag.

## unitbind: 53 lines in full (51 for the card), phases A/B/C/D

The example is `unitbind @poly unit`, followed by `print unit` in the program. The 51 lines below are only the card's lowering; the 53 in the heading is the complete example product: 51 card lines + the example body `print unit` + the program-level entry skip `set @counter 0` (carrier excluded).

### Card lowering (renamed)

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
lost:
set held null
scan:
set steps 0
set anchor null
loop:
op add steps steps 1
jump fail greaterThan steps 8192
ubind @poly
jump fail equal @unit null
jump fail equal @unit anchor
jump keep notEqual anchor null
set anchor @unit
keep:
sensor dead @unit @dead
jump loop notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
jump loop notEqual flag 0
sensor controlled @unit @controlled
jump loop notEqual controlled 0
sensor flag @unit @flag
jump loop notEqual flag 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump loop equal @unit null
sensor flag @unit @flag
jump loop notEqual flag myFlag
take:
set unit @unit
set held @unit
jump ok always x false
fail:
set unit null
set held null
ok:
```

### A. Reuse held

```mlog
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
lost:
set held null
```

`held` is the unit this card claimed or reused last time. `ubind held` does not bind by type; it binds the **unit object** in `held`, so if the unit still exists, the card returns to exactly that unit.

Three checks follow:

- is `@unit` `null`? The unit may have died, been removed, or the variable may be empty;
- is `@dead` nonzero? The unit is dead;
- is `@flag` still `myFlag`? Another processor may have reclaimed it, or `unitfree` may have returned it to idle.

If any check fails, control jumps to `lost`, clears `held`, and enters the scan. If all pass, it jumps straight to `take`, writes both `unit` and `held`, and neither scans nor writes a flag.

Note that this reuse path checks only `@dead` and `@flag`, not `@controlled`. A unit that already carries `myFlag` is reused even if a player or a command has taken it over; `@controlled` matters only when the card considers claiming a strange unit.

### B. Scan

```mlog
scan:
set steps 0
set anchor null
loop:
op add steps steps 1
jump fail greaterThan steps 8192
ubind @poly
jump fail equal @unit null
jump fail equal @unit anchor
jump keep notEqual anchor null
set anchor @unit
keep:
sensor dead @unit @dead
jump loop notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
jump loop notEqual flag 0
sensor controlled @unit @controlled
jump loop notEqual controlled 0
sensor flag @unit @flag
jump loop notEqual flag 0
```

`ubind @poly` takes the next unit of the type and wraps to the start at the end of the list. `anchor` records the first unit of this scan; seeing it again means a full cycle, so the card jumps to `fail`.

`steps` is a hard bound: if 8192 steps pass without returning to `anchor`, the scan stops. The unit list can change while scanning (units die, units spawn), and without this cap some cases could loop forever.

The skip rules, in order:

- `@dead != 0`: skip dead units;
- `flag == myFlag`: it is ours, go straight to `take`;
- `flag != 0`: another owner, skip;
- `@controlled != 0`: an unowned unit that logic, a player, or a command already controls is skipped;
- read `flag` again: if another processor took it in between, skip.

Notice that `@flag` is read twice: first to accept your own flag, then again after the `@controlled` check. The goal is to narrow the window between reading the flag and writing it; it is not atomic, only a smaller race.

`jump take equal flag myFlag` comes before the nonzero-flag skip, so a scan can also pick up a unit that already carries this processor's flag. That is one difference between `unitbind` and `unitnext`: `unitbind`'s scan accepts its own units, while `unitnext`'s scan skips every nonzero flag and only the top `held` branch confirms the unit claimed on the previous pass.

### C. Claim handshake

```mlog
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump loop equal @unit null
sensor flag @unit @flag
jump loop notEqual flag myFlag
```

The claim has four steps:

1. remember the candidate in `held`;
2. write the flag with `ucontrol flag myFlag`, which also puts the unit under logic control;
3. write four `end` instructions;
4. confirm with `ubind held` / `sensor flag` / `jump loop`.

Steps 3 and 4 are the ones people misread. In vanilla v160.1, `EndI` does exactly one thing: it sets `@counter` to the instruction count, ending the current pass; the next `runOnce` resets it to 0 and starts at instruction 0. With the default one instruction per tick, that “next” is the next tick; if instructions-per-tick is higher and budget remains, the same tick can continue from the top. So after the first `end`, the immediately following `ubind held` does not run sequentially, and the last three `end`s have no jump pointing at them. Four `end` lines are not four ticks and provide no fixed wait.

Where does confirmation happen, then? On the **next entry** into the card, in phase A: `held` already stores the candidate, `ubind held` binds it, and the flag is read again; if it is still `myFlag`, the card goes to `take`. That is where the delayed confirmation actually lands.

Why delay at all? Another processor may have observed “flag 0” at the same moment and may be about to write its own flag. Returning to the top and re-reading on the next entry decides the winner; the loser sees a flag that is not its own and gives up this unit.

### D. Success and failure

```mlog
take:
set unit @unit
set held @unit
jump ok always x false
fail:
set unit null
set held null
ok:
```

On success, the unit goes into the user variable `unit`, `held` is updated, and the failure branch is skipped. On failure, both are set to `null`.

`unit` is your variable; `@unit` is vanilla's bound unit. On success they point at the same unit. On failure, `unit` is guaranteed to be `null`, but `@unit` is not: it may still point at the last scanned unit.

## unitnext: 51 lines in full (50 for the card), one-shot confirmation

The example is `unitnext @flare scout`. The 50 lines below are the card itself; adding the program-level entry skip `set @counter 0` makes 51.

### Card lowering (renamed)

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
lost:
set held null
scan:
set steps 0
set anchor null
loop:
op add steps steps 1
jump fail greaterThan steps 8192
ubind @flare
jump fail equal @unit null
jump fail equal @unit anchor
jump keep notEqual anchor null
set anchor @unit
keep:
sensor dead @unit @dead
jump loop notEqual dead 0
sensor flag @unit @flag
jump loop notEqual flag 0
sensor controlled @unit @controlled
jump loop notEqual controlled 0
sensor flag @unit @flag
jump loop notEqual flag 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump loop equal @unit null
sensor flag @unit @flag
jump loop notEqual flag myFlag
take:
set scout @unit
set held null
jump ok always x false
fail:
set scout null
set held null
ok:
```

Compared with `unitbind`, a few things differ:

- it also has a `held` confirmation branch at the top, but `take` adds `set held null`: after confirming and handing the unit over, it clears the memory and scans again instead of sticking like `unitbind`;
- the scan has no `jump take equal flag myFlag`, so every nonzero flag is skipped, including yours; only the top `held` branch accepts “the unit claimed on the previous pass”;
- the failure branch sets `scout` to `null` and clears `held`.

### One-shot confirmation

`take:` sits after the claim handshake. As with `unitbind`, it is not reached by falling through the code after `end`; it is reached on the next entry into the card through the top `held` branch:

1. the claim runs `set held @unit`, writes the flag, and `end`s;
2. on the next entry, `jump scan equal held null` sees that `held` is not null;
3. `ubind held` binds the candidate again, checks `@dead`, then checks `flag == myFlag`;
4. if both pass, it jumps to `take`, runs `set scout @unit`, and then `set held null`;
5. the next `unitnext` call sees `held == null` and scans for the next idle unit.

So `unitnext`'s `held` is a one-shot receipt: it only confirms the unit claimed on the previous pass and is dropped after delivery. `unitbind`'s `held` is long-term memory: it keeps pointing at the same unit after confirmation. Both confirm on the next entry, but their delivery semantics differ.

## unitfree: 14 lines in full (13 for the card)

The example is `unitfree unit`. The 13 lines below are the card itself; adding the program-level entry skip makes 14.

### Card lowering (renamed)

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
ubind unit
jump free equal @unit null
sensor dead @unit @dead
jump free notEqual dead 0
sensor flag @unit @flag
jump free notEqual flag myFlag
ucontrol flag 0 0 0 0 0
ucontrol unbind 0 0 0 0 0
set unit null
free:
```

It does one job: return your own unit to idle. All conditions must hold:

1. after `ubind unit`, `@unit` is not `null`;
2. `@dead` is `0`;
3. `@flag` is exactly `myFlag`.

Then it:

- writes flag `0` with `ucontrol flag 0`;
- drops logic control with `ucontrol unbind`;
- clears your variable with `set unit null`.

If any condition fails, it jumps to `free:` and changes nothing. “Changes nothing” refers to the flag and the variable; `ubind unit` has already run, so `@unit` points at that unit. A foreign unit, an idle unit, or a unit with a custom flag is not released.

`unitfree` does not check `@controlled` either: if the flag is exactly `myFlag`, it clears it. “Release” here means releasing the flag-protocol ownership, not removing a player from the controller seat.

## unitfor: 65 lines in full (64 for the card), two phases

The example is `unitfor 2 @poly unit`, with `print unit` as the body. That is the card form; text import also carries an internal `destIndex` at the end, which the compiler recomputes from `blockend`, so it never appears in the lowering below. The 64 lines below include the example body; adding the program-level entry skip makes 65.

### Card lowering (renamed)

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
set delivered 0
set phase 0
set owned 0
set anchor null
set steps 0
scan:
op add steps steps 1
jump afterLoop greaterThan steps 8192
ubind @poly
jump wrap equal @unit null
jump mark equal anchor null
jump wrap equal @unit anchor
jump keep always x false
mark:
set anchor @unit
keep:
sensor dead @unit @dead
jump scan notEqual dead 0
sensor flag @unit @flag
jump ours equal flag myFlag
jump scan equal phase 0
jump scan notEqual flag 0
sensor controlled @unit @controlled
jump scan notEqual controlled 0
sensor flag @unit @flag
jump scan notEqual flag 0
jump scan greaterThanEq delivered 2
jump scan lessThanEq room 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump scan equal @unit null
sensor flag @unit @flag
jump scan notEqual flag myFlag
op sub room room 1
jump give always x false
ours:
jump count equal phase 0
jump scan greaterThanEq delivered 2
jump give always x false
count:
op add owned owned 1
jump scan always x false
wrap:
jump afterLoop notEqual phase 0
set phase 1
set delivered 0
set steps 0
op sub room 2 owned
jump scan equal @unit null
jump keep always x false
give:
set unit @unit
print unit
op add delivered delivered 1
jump scan always x false
afterLoop:
```

`print unit` is the example body. Replace it with your own statements and the `print unit` line changes; `set unit @unit` and `op add delivered delivered 1` belong to the card. The `2` in the comparisons and in `op sub` is the example's `count`; with `8`, those literals change with it.

### Initialization and census (phase 0)

The top clears the state:

```mlog
set delivered 0
set phase 0
set owned 0
set anchor null
set steps 0
```

After `scan:`, the card performs the same bounds and scan checks as `unitbind`: step cap, `ubind @poly`, `anchor` cycle detection, `dead` skip, and flag read.

Then:

```mlog
jump ours equal flag myFlag
jump scan equal phase 0
jump scan notEqual flag 0
```

If the flag is `myFlag`, control goes to `ours:`. While `phase` is 0, `ours:` jumps to `count:`, which only does `owned++` and continues scanning; it does not deliver.

If the flag is not `myFlag` and `phase` is still 0, the card jumps straight back to `scan`, without claiming even an idle unit. Phase 0 only counts what you already own; it changes no unit.

### Switching from census to delivery (wrap)

`wrap:` is reached when the scan returns to its start or `ubind` returns `null`:

```mlog
wrap:
jump afterLoop notEqual phase 0
set phase 1
set delivered 0
set steps 0
op sub room 2 owned
jump scan equal @unit null
jump keep always x false
```

If `phase` is already 1, the second pass is done and the card leaves via `afterLoop`. Otherwise it switches to `phase = 1`, resets `delivered` and `steps`, and computes:

```text
room = count - owned
```

`room` is “how many new units this pass may still claim”. If `owned` already exceeds `count`, `room` is negative; the later `jump scan lessThanEq room 0` blocks claims, so the card never over-fills.

After switching, if the current `@unit` is `null`, it goes back to `scan`; otherwise `jump keep` continues with the current unit.

### Delivery and claims (phase 1)

In phase 1, `ours:` does:

```mlog
ours:
jump count equal phase 0
jump scan greaterThanEq delivered 2
jump give always x false
```

If `count` units have already been delivered, skip; otherwise go to `give:` and hand this unit to the body.

Note that `ours` runs before the `@controlled` check, so a unit that already carries `myFlag` is delivered even if a player or a command has taken it over; `@controlled` only blocks claiming a strange unit.

A unit that is not yours takes the claim path in phase 1:

```mlog
jump scan equal phase 0
jump scan notEqual flag 0
sensor controlled @unit @controlled
jump scan notEqual controlled 0
sensor flag @unit @flag
jump scan notEqual flag 0
jump scan greaterThanEq delivered 2
jump scan lessThanEq room 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump scan equal @unit null
sensor flag @unit @flag
jump scan notEqual flag myFlag
op sub room room 1
jump give always x false
```

The order is:

1. skip nonzero flags and units whose `@controlled` is nonzero;
2. read the flag again to narrow the race window;
3. if `delivered` has reached `count`, or `room <= 0`, stop claiming;
4. otherwise run the same claim handshake as `unitbind`;
5. the code after the handshake writes `room--` and jumps to `give:`. Under the `end` semantics execution restarts at the top, so the next phase 0 recounts `owned` and recomputes `room` from `count - owned`; that recount, not sequential execution of those two lines, is what really caps the fill.

`give:` is the delivery point:

```mlog
give:
set unit @unit
print unit
op add delivered delivered 1
jump scan always x false
```

It writes `@unit` into the loop variable, runs your body, increments `delivered`, and continues scanning. `delivered` reaches at most `count` per pass; after that, both the `ours` path and the claim path skip.

### break and continue

`break` uses the generic block logic and jumps to `__ls_stmt_<destIndex + 1>`, which is `afterLoop`; it leaves the `unitfor` immediately.

`continue` jumps to `__ls_ub_step_<i>`. That label is emitted at `blockend`, just before `delivered++`:

```mlog
step:
op add delivered delivered 1
jump scan always x false
```

So `continue` skips the rest of the body, but the unit still counts as processed: `delivered` is incremented anyway. A `unitfor` with no `continue` does not emit this label, saving one jump location.

### Why two phases are necessary

If the card claimed units while still scanning, it would not yet know how many units it already owns. New claims change the “owned” count, so filling slots during the census could overshoot `count`; statistics and delivery would also be mixed together, making it unclear when and how often the body runs.

The two phases separate the jobs:

- phase 0 only counts `owned`; it neither claims nor delivers;
- when the scan wraps, `room = count - owned` says how many new units may be claimed;
- phase 1 delivers owned units and claims idle ones, with `delivered` capped at `count`.

This also lets the card survive the re-entry caused by `end`: claiming a unit ends the current pass and returns to the top; with the default one instruction per tick the next tick continues. Phase 0 recounts and includes the newly claimed unit in `owned`, then phase 1 delivers it. The card does not depend on “which line we reached last time”.

## Why the code looks like this

Compressed into design reasons:

- **Two processors must not claim the same unit.** Read the flag, write it, return to the top, then re-read on the next entry (`unitbind` / `unitnext`'s `held` branch) or recount (`unitfor`'s phase 0). Without that re-entry check, both could see flag 0 and both could think they won.
- **Do not steal controlled units.** Before claiming a strange unit, `@controlled != 0` is skipped, so a unit used by a player, a command, or another logic program is left alone. Units you already own skip that check, so reuse and delivery still work.
- **The unit list changes.** Units die and spawn while scanning, and `ubind`'s cursor moves; `anchor` detects a full cycle and `steps > 8192` bounds the scan when the anchor never comes back.
- **Skip dead units.** The `@dead` check comes before the flag check, so dead units cannot be claimed.
- **The product must be vanilla-parseable.** It uses only `op` / `set` / `ubind` / `sensor` / `ucontrol` / `jump` / `end`; labels and variable names are vanilla syntax.
- **State lives in ordinary variables.** `myFlag`, `held`, `phase`, and the rest are plain mlog variables; they do not need a mod runtime. After a reload they return to their initial values and the card scans again.
- **Names carry statement indices for inference.** `__ls_ub_*_<i>` and `__ls_stmt_<n>` let a program without a carrier be matched against the fixed lowering and restored as cards; if the shape does not match, it stays vanilla.

## The tail: set @counter 0 and the carrier

After the card lowering, the program ends with the entry skip, a marker block, and a carrier. For `unitbind @poly unit` plus `print unit`, the real tail is:

```text
set @counter 0
# @logic-sugar-v2 begin
# @logic-sugar-line unitbind @poly unit
# @logic-sugar-line print unit
# @logic-sugar-line set @counter 0
# @logic-sugar-v2 end
set __ls_sugar "<Base64 of the Sugar source>"
```

`set @counter 0` is the program-level entry skip, not one per card. It sends execution back to instruction 0 so the `set __ls_sugar` carrier below never runs.

The marker block (`# @logic-sugar-v2 begin` … `# @logic-sugar-v2 end`) is comments: it does not execute and costs no instructions; each `# @logic-sugar-line` holds one line of the Sugar source. It is the most readable carrier, but a vanilla client drops comments when it saves, so the compiler also writes a real `set` statement: the Sugar source, Base64-encoded, inside `set __ls_sugar "..."`. That line survives a vanilla parse/save round trip. The Base64 in the listing above is a placeholder; the real payload is one long string.

Some practical details:

- A large source splits the single `set __ls_sugar` into `set __ls_sugar_1`, `set __ls_sugar_2`, … with consecutive numbering; the reader joins the shards back together.
- If the program uses the function library, there is also `set __ls_lib "..."` (or `set __ls_lib_N` shards), holding the used subset of library functions.
- Older saves may contain a `# @logic-sugar-v1 begin` / `# @logic-sugar-v1 end` marker block; the current version still reads it, while new saves write v2.
- Marker comments are free; the `set __ls_sugar` / `set __ls_lib` carrier lines are real instructions, and together with the entry skip they count toward the 1000-instruction budget. Longer sources and more shards cost more.
- If the source is so large that even the shards do not fit, the compiler falls back to saving without the carrier and warns: the program still runs, but the Sugar source no longer travels with this save.

If you strip the carrier:

- the program still runs, because the executable part is unchanged;
- If you delete only `set __ls_sugar` and keep the marker block, `SugarCompiler.restore` can still read the marker block; the editor's opening path, however, trusts only the `set __ls_sugar` carrier, so it goes to inference and does not guarantee the whole source comes back verbatim. Delete the marker block too if you want to exercise inference end to end.
- once the marker block is gone too (or a vanilla save dropped it), LogicSugar tries to infer the cards from the `__ls_ub_*` names and the exact instruction sequence;
- if names, order, or statement positions do not match (for example, you edited a jump or renamed a variable), inference refuses and the program opens as vanilla instructions;
- ordinary hand-written `ubind` / `@flag` code is not mistaken for these cards, because inference only accepts the compiler's own lowering.

`set @counter 0` is part of the product too. Removing it makes the stream differ from the compiler's expectation; carrier verification fails, and inference usually will not match either. Do not edit it unless you are debugging.

## Reading the four end instructions

This is the part that trips people up.

In vanilla v160.1, `end`'s `run` does one thing: it sets `@counter` to the instruction count. The next `runOnce` sees the counter out of range, resets it to `0`, and executes instruction 0. The default processor runs one instruction per tick, so “next” is usually the next tick; if instructions-per-tick is higher and budget remains, the same tick continues from the top. `end` is not “sleep, then execute the next line”, and it provides no fixed wait.

Therefore:

- after the first `end`, the immediately following instruction does not run; execution has returned to the top;
- the next three `end`s have no jump pointing at them, so normal execution never reaches them;
- four `end` lines are not four ticks and provide no fixed wait.

So how does `unitbind` confirm a claim? Look at phase A:

```mlog
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
```

The claim already wrote the candidate into `held`. On the next execution from the top, when the card is reached again, phase A binds `held` and reads its flag; if the flag is still `myFlag`, it goes straight to `take`. That is where the delayed confirmation actually happens.

The `ubind held` / `sensor flag` / `jump loop` lines after the four `end`s are not reached by normal fall-through; execution has already returned to the top. `unitbind` / `unitnext` do the same check on the next entry through their `held` branch; `unitfor` does it through the phase 0 recount. The only difference is what happens to `held` after delivery: `unitbind` keeps it, `unitnext` clears it in `take`.

`unitfor` behaves the same way: the claim's `end` ends the current pass and returns to the top; initialization runs again, phase 0 recounts and includes the newly claimed unit in `owned`, and phase 1 delivers it. The two-phase design is what makes re-entry safe.

If you need a real wait, use a wait/timer card; do not count `end` lines.

## Audit it yourself

To confirm this page is not “prettifying” the product:

1. Place one card in the editor, save, and switch to the Original view.
2. Compare against the full listing; walk `myFlag`, `ubind`, `sensor`, `ucontrol`, and `jump` in order. The names should be `__ls_ub_*`, not this page's short names.
3. Verify every line with a vanilla processor or `LAssembler.read`. The repo's `unitControlTest.productIsVanilla` does exactly this: it allows only `op` / `set` / `ubind` / `sensor` / `ucontrol` / `jump` / `end` plus user body, and rejects `InvalidStatement`.
4. Check the safety points: `@controlled` blocking only claims, the double `@flag` read, `steps > 8192`, `anchor` cycle detection, and the fact that the confirmation code after `end` does not run sequentially; confirm that `unitbind` / `unitnext` both have the `take` re-entry, `unitfor` has the phase 0 recount, and `unitnext`'s `take` clears `held`.
5. Check the two-phase `unitfor`: phase 0 only does `owned++`; `wrap` computes `room = count - owned`; phase 1 does `give` and claims, with `delivered` capped.
6. Delete both `set __ls_sugar "..."` and the `# @logic-sugar-v2` marker block, then reopen; see whether inference restores the cards. Break one scan guard or rename a variable and confirm it stays vanilla instead of being force-restored.
7. To see the `end` effect directly, step a small “assignment after `end`” program through `LExecutor` and watch the counter reset to 0 after going out of range; the later lines do not run. This is the most direct check of the four `end`s.
8. For development, run `.\gradlew.bat unitControlTest`. It checks the lowering's shape (flag packing, four `end`s, the `unitbind` / `unitnext` `take` re-entry, `unitnext`'s one-shot `held` clear, `unitfor` `break`/`continue`, vanilla parseability, carrier round-trip), not runtime behavior; use step 7 for runtime behavior.

## Summary and questions

The four cards share one flag-claim protocol, and the product is vanilla mlog:

- `myFlag = @thisx * 100000 + @thisy + 1`; `0` is reserved for unowned;
- `unitbind` reuses `held`, then scans and claims;
- `unitnext` uses a one-shot `held` confirmation, clears it after delivery, and its scan skips every nonzero flag, so the next call finds the next idle unit;
- `unitfree` clears only its own flag;
- `unitfor` counts `owned`, computes `room`, then delivers and claims with `delivered` capped;
- the carrier stores card identity; inference only accepts the fixed lowering and stays vanilla otherwise.

Three questions:

1. Why does `unitfor` count before delivering? If it claimed while scanning, what would go wrong with `room`?
2. Why read `@flag` both before and after the `@controlled` check? Does that narrow the race or eliminate it?
3. Under vanilla `end` semantics, where do `unitbind` / `unitnext` actually confirm? Why can't you read the lines after the four `end`s by line number? Why does `unitnext` clear `held` after delivery?

Related docs:

- [Player tutorial](unit-control.md): which card to use, fields, and pitfalls.
- [Reconstruction rules](../../reconstruction.md): the `unit-control` inference and rejection rules.
- [Glossary](../../glossary.md): unit-control cards, carrier, hidden state variables.
- [Architecture overview](../../architecture.md): carrier restore and decompiler inference.
