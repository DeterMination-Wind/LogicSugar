# Unit-control cards (unitbind / unitnext / unitfor / unitfree)

> [Tutorial index](README.md) | Want every instruction explained: [compilation companion](unit-control-compilation.md) | Chinese: [../unit-control.md](../unit-control.md)

## When to use

Picture the usual accident: you write `ubind @poly`, then `ucontrol move`, and it works in single player. Copy the same program to a second processor and the two start fighting over the same `@poly`: your processor binds it on one tick, the other binds it on the next, and the commands overwrite each other.

Vanilla `ubind` only points `@unit` at a unit. It does not decide who *owns* that unit. The unit-control cards add the missing half: they write a flag that belongs to the processor, and every card on that processor uses the flag to find the same fleet. Think of it as a name tag on the unit; the number comes from the processor's position, and whoever wrote it keeps it.

Use these cards when:

- you need stable control of the same units instead of re-binding one every tick;
- several processors must each keep a squad without stealing from each other;
- you want a counted loop such as “up to 8 `@poly`, read each one's health”;
- you want to hand units back so another processor can claim them.

If you only need to peek at a unit's property once, plain `ubind` is enough.

## Which card to use

| Card (UI name) | Source form | What it does | Use it when |
| --- | --- | --- | --- |
| Control One Unit | `unitbind <type> <variable>` | Reuses the unit this card claimed last time; otherwise scans the type and reuses or claims one | You want one stable unit for repeated commands |
| Control Next Idle Unit | `unitnext <type> <variable>` | Scans past units that already carry a flag and looks for the next idle one | Rotating through idle units, one at a time |
| Release Unit | `unitfree <variable>` | Releases a unit only if its flag belongs to this processor | Handing a unit back, changing shifts, cleaning up |
| Control Units | `unitfor <count> <type> <variable>` … `blockend` | Runs the body for up to `count` owned units per pass, claiming idle ones to fill the slots | Reading or commanding a group |

> UI names follow the game's language. The source form is what the Sugar text and the card's `write()` produce. The `unitfor` card only shows `count`, `type`, and `variable` in the editor; pasted text may carry an internal destination index, explained below.

## First, the flag: who owns this fleet

All four cards share one identity: the processor flag. The compiler recomputes it at the top of every card:

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
```

To keep the listings readable, this page writes the compiler's `__ls_ub_uid` as `myFlag` and shortens the other hidden variables. The Original view still shows `__ls_ub_*`; the full rename table is in the [compilation companion](unit-control-compilation.md).

What the three lines mean:

- `@thisx` / `@thisy` are the processor's tile coordinates;
- `* 100000` moves the x coordinate into the high digits, and `+ @thisy` packs y in;
- the final `+ 1` matters because a processor at the origin `(0, 0)` would otherwise get `0`, and `0` is reserved for “unowned”.

Three useful consequences:

1. **Flag 0 means unclaimed.** A nonzero flag means someone has already tagged the unit; only a flag equal to this processor's `myFlag` counts as “mine”.
2. **All cards on one processor share the flag.** A unit claimed by `unitbind` is also visible to `unitfor`.
3. **Different processors have different flags.** On a normal map they do not collide, so other processors do not take your units and you do not take theirs.

A unit's flag is just a number. The processor has no global registry: each card scans with `ubind`, reads `@flag`, and compares. That is why the rest of this page keeps warning you not to write your own flag on the same units.

## unitbind: one stable unit

The card is “Control One Unit”:

```text
unitbind @poly unit
```

| Field | Meaning |
| --- | --- |
| `type` | Unit type, such as `@poly` or `@flare` |
| `variable` | Ordinary variable that receives the unit object, here `unit` |

It does three things:

1. It looks at the `held` unit remembered by this card and runs `ubind held`, which binds that **unit object**. If the unit is still alive and still carries `myFlag`, the card reuses it without scanning.
2. Otherwise it scans units of the type: `ubind @poly` walks the vanilla unit cursor, dead units are skipped, a flag equal to `myFlag` is accepted immediately, other nonzero flags are skipped, and an idle unit (flag 0) is skipped when `@controlled` is nonzero. If nothing matches, it claims an idle unit.
3. On success it writes the unit into `unit`; if nothing is found, it sets `unit` to `null`.

`held` belongs to this card (its name includes the statement index); it is not shared by variable name. Two `unitbind` cards that both write `unit` still keep separate `held` values.

Smallest example:

```text
unitbind @poly unit
print unit
health = unit.health
print health
```

The first line is the card; the next three are an ordinary print, an Expr assignment, and another print. `health = unit.health` lowers to `sensor health unit @health`, reading the unit stored in the `unit` variable, not `@unit`. If no usable unit exists (neither an idle one nor one already carrying `myFlag`), `unit` becomes `null`; reading health is then pointless, so check before issuing commands.

The claim handshake looks like this (excerpt):

```mlog
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
```

This is only the “remember the candidate, write the flag, end the pass” part; the real product has scanning, confirmation, and jumps around it. `end` is not a sleep: the first `end` sends execution back to the top of the program; with the default one instruction per tick, the next tick restarts from the top and the card confirms only when execution reaches it again; a higher instructions-per-tick may continue from the top in the same tick. The last three lines have no jump pointing at them. The real confirmation happens on the next entry into the card through the `held` branch: `ubind held`, read the flag, and `take` if it is still `myFlag`. The [compilation companion](unit-control-compilation.md) walks through it.

## unitnext: the next idle unit

The card is “Control Next Idle Unit”:

```text
unitnext @flare scout
```

It shares the scan, skip, and claim logic with `unitbind`, but the goal is different: `unitbind` says “I want this one”, while `unitnext` says “give me the next idle one”.

How it does that:

- it also has a one-shot `held` confirmation branch: if the previous pass claimed a unit and has not handed it over yet, `ubind held` binds it again, and if the flag is still `myFlag`, the unit goes into `scout`;
- after handing it over, it immediately runs `set held null`, so the next call does not stick to that unit and scans again;
- the scan skips every unit with a nonzero flag, including units you already own, and only takes a flag-0 unit with `@controlled == 0`.

Repeating the card therefore walks the vanilla `ubind` cursor and takes one new idle unit at a time; units you already took stay in your fleet and are not handed over again. Use `unitfree` to give one back. If every `@flare` is already claimed by some processor, the scan takes the failure branch and sets `scout` to `null`.

> “One-shot” is the key difference: `unitbind` keeps `held` as long-term memory, while `unitnext` uses it once. After confirming and handing over, it clears `held` and scans again. Both confirm on the next entry into the card, but only `unitbind` keeps reusing the same unit.

## unitfree: release only your own

The card is “Release Unit”:

```text
unitfree unit
```

It binds the named unit with `ubind unit`, then releases it only when all three conditions hold: the unit exists, it is not dead, and its flag is exactly `myFlag`. On release it:

- writes flag `0`;
- runs `ucontrol unbind` to drop logic control;
- sets the variable to `null`.

It does not touch a unit that is not yours:

- `null` variable: skip;
- dead unit: skip;
- flag `0` (idle) or another processor's flag: skip;
- a custom flag you wrote with `ucontrol flag`: not `myFlag`, so skip.

That last point matters: `unitfree` is not a universal flag cleaner. If you wrote a custom flag, bind the unit yourself and run `ucontrol flag 0 0 0 0 0` to make it idle again.

## unitfor: a counted group

The card is “Control Units”:

```text
unitfor <count> <type> <variable> <destIndex>
    ...
blockend
```

| Field | Meaning |
| --- | --- |
| `count` | Maximum units processed per pass, here `8` |
| `type` | Unit type, for example `@poly` |
| `variable` | Loop variable, pointing at one unit per iteration |
| Body | Your loop body; `break` and `continue` work |

The trailing `<destIndex>` is an internal destination index; the editor card does not show it. Text import needs an integer at the end (for example `0`), and the compiler recomputes it from `blockend`. It is not a field you need to understand.

The card works in two phases: first it counts how many units of the type you already own, then it hands units to the body within that allowance. The full mechanism is in the [compilation companion](unit-control-compilation.md). For now, remember: each pass handles at most `count` units, and empty slots are filled by claiming idle units.

Example:

```text
unitfor 8 @poly unit 42
    print unit
    health = unit.health
    print health
blockend
```

The `42` is the internal destination index; the compiler recomputes it from `blockend`, so it does not change what the program does, and the editor card does not show it.

This runs the body for up to 8 owned `@poly` units: print the unit, read health, print health. If you own none and there is no idle unit to claim, the body does not run at all. When idle units exist, the card claims them first; that claim sends execution back to the top, and with the default one instruction per tick the next tick restarts from the top. The units are handed to the body only after the card is reached again and recounts.

`break` leaves the whole block. `continue` skips the rest of the current body, but that unit still counts as processed (the compilation companion shows the jump to the counting step).

There is one easily missed behavior: **the program itself keeps starting over, and claiming a new unit makes the current pass end early.** The card recomputes its own state, but your body may see already-processed units again. Write the body so it can run repeatedly (issue commands, read state); do not rely on one-time accumulation. If you need statistics, guard or mark them inside the body, or put the accumulator somewhere that tolerates re-entry.

Also, `unitfor` does not clear the loop variable when there are no units; it keeps the previous value (the unset variable reads as the number `0`). Do not test “variable is `null`” to decide whether the group was empty.

## What the processor sees

| Name | What it is | When it changes |
| --- | --- | --- |
| `@unit` | Vanilla's currently bound unit | On `ubind` or any card that switches units |
| `unit` | Your ordinary variable holding a unit object | Written on card success; `unitbind` sets it to `null` on failure |
| `unit.flag` / `@flag` | The unit's flag | When a card or your code writes `ucontrol flag` |
| `unit.dead` / `@dead` | Whether the unit is dead/invalid | When the unit dies |
| `unit.controlled` / `@controlled` | `0` means no logic/player/command controller; nonzero means controlled | When control changes |
| `@thisx` / `@thisy` | Processor tile coordinates | When the processor is placed elsewhere |

The important distinction is `@unit` versus `unit`:

- `@unit` is vanilla's bound unit; `ubind` and the cards change it;
- `unit` is your variable; only a card or your own code writes it;
- a later `ubind` may change `@unit`, but `unit` still points at the old unit;
- `ucontrol` acts on `@unit`, so to command the unit in `unit`, first run `ubind unit`, or read its properties through the variable as in `unit.health`.

When `unitbind` fails, it only guarantees that your variable is `null`; it does not guarantee that `@unit` is `null`. `@unit` may still point at the last scanned unit. Check the variable before issuing commands, and re-bind if needed.

## Pitfalls

- **Do not write your own flag on the same units.** The protocol treats any nonzero flag as “owned by someone”. With a custom flag, `unitfor` treats those units as neither “mine” nor “idle” and skips them; `unitfree` will not clean them either. Clear the flag manually to recover.
- **`unitbind` sticks, `unitnext` does not.** Both use `held` to confirm across ticks; `unitbind` keeps `held` pointing at the unit after confirmation and prefers it next time, while `unitnext` clears `held` in `take`, so the next call scans again and skips units you already own.
- **`unitfree` releases only your own.** Foreign flags, custom flags, and dead units are skipped; it is not a universal flag cleaner.
- **`@controlled` blocks claiming, not reuse.** A unit that already carries `myFlag` is reused by `unitbind` and delivered by `unitfor` even if a player or a command has taken it over; `@controlled` is only checked when the card considers pulling in a strange unit. And owning the flag is not the same as owning control: `ucontrol` commands on a player-controlled unit do nothing.
- **Placing the processor elsewhere changes the flag.** The flag comes from `@thisx` / `@thisy`; after you tear the processor down and rebuild it elsewhere, old units still carry the old flag, and the new position sees them as someone else's. Release first or clear the flags.
- **Variables can be `null`.** `unitbind` sets `null` when nothing is found; `unitfor` keeps the old value when the group is empty. Check before `ucontrol`.
- **`end` is not a sleep.** Vanilla `end` ends the current pass and sends `@counter` to the instruction count; the next `runOnce` starts again at the top. With the default one instruction per tick, that means the next tick restarts from the top; with a higher instructions-per-tick and budget left over, it may continue from the top within the same tick. Only the first `end` is reached; the other three have no jump pointing at them; four `end` lines are not four ticks. The real confirmation happens when the card is entered again and the reuse branch runs (`unitfor` recounts). See the [compilation companion](unit-control-compilation.md).
- **Multiplayer/vanilla.** The saved product is plain vanilla mlog; other clients need no mod. But the cards exist only in the LogicSugar editor; a vanilla client sees a long list of instructions, not four cards.
- **Reloading does not keep hidden variables.** `held` and the other `__ls_ub_*` variables are ordinary variables and return to their initial values after a processor reload or a move to another processor. Unit flags are not cleared by a processor reload. Do not treat reload as a release.

## Budget

The processor hard limit is 1000 instructions. These cards are not sugar for one `ubind`; they expand a full claim protocol, and they eat into the budget quickly.

At this chapter's example size (one card plus the body, plus the program-level `set @counter 0`, carrier excluded), `unitbind` / `unitnext` / `unitfree` / `unitfor` are roughly 53 / 51 / 14 / 65 lines. That is a reading aid, not a constant: type names, variable names, and the body change the count; label lines do not consume instruction slots, the instructions do, and every `jump` is one instruction. Every extra statement in a `unitfor` body adds that statement's own lowering, and nesting or stacking cards burns the budget quickly.

Vanilla `LParser` also caps jump locations at 500, and labels in these cards count against that. With heavy stacking, you may hit the label cap before the 1000-instruction cap.

Compiling past the limit is an error; LogicSugar has no switch to raise it. Switch to the Original view and count, or read the compile error.

## Verifying it yourself

1. Place one card in the editor and save once.
2. Switch to the Original view and compare with the full listing in the [compilation companion](unit-control-compilation.md). Start with the three `myFlag` lines, then follow `ubind` / `sensor` / `ucontrol` / `jump`.
3. Use the editor's copy-compiled-code button to paste the product into a normal processor. If it parses and runs, it does not depend on the mod.
4. To check the carrier, reopen the save: the cards should still be there. Delete both the trailing `set __ls_sugar "..."` line and the `# @logic-sugar-v2` marker block, then reopen to exercise inference: LogicSugar tries to recover the cards from the `__ls_ub_*` names and exact sequence; if it cannot, it shows vanilla instructions.
5. To check that units are not stolen, run the same `unitbind @poly unit` on two processors and see whether they end up with different units.

## Summary and questions

The point of the four cards is the flag-claim protocol, not `ubind` itself:

- `unitbind` keeps one unit stable;
- `unitnext` uses a one-shot `held` confirmation, clears it after handing the unit over, and scans for the next idle unit on the next call;
- `unitfree` releases only your own;
- `unitfor` processes a group by count and fills empty slots with idle units.

The product is plain vanilla mlog; the card identity lives in the carrier. Without a carrier, LogicSugar tries to infer the cards from the fixed `__ls_ub_*` lowering, and keeps vanilla when the shape does not match.

Two questions to check your understanding:

1. Why does `unitfor` count first and claim later, instead of claiming during the first scan?
2. If you write `total = 0` before a `unitfor` and add to `total` inside the body, what happens when claiming a new unit sends execution back to the top mid-pass?

For the instruction-by-instruction view, continue with the [compilation companion](unit-control-compilation.md).

## Related docs

- [Compilation companion](unit-control-compilation.md): full renamed lowering, the two-phase loop, and the `end` reading.
- [Architecture overview](../../architecture.md): carrier restore, decompiler inference, and where unit-control fits.
- [Reconstruction rules](../../reconstruction.md): the `unit-control` inference and rejection rules.
- [Glossary](../../glossary.md): unit-control cards, carrier, hidden state variables.
- [Testing guide](../../testing.md): `unitControlTest` and the manual checklist.
- [Chinese](../unit-control.md).
