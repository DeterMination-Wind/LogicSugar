package mindustry.logic;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Base64Coder;
import mindustry.Vars;
import mindustry.logic.LExecutor;
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.BreakStatement;
import mindustry.logic.SugarStatements.ContinueStatement;
import mindustry.logic.SugarStatements.CaseStatement;
import mindustry.logic.SugarStatements.ElseIfStatement;
import mindustry.logic.SugarStatements.ElseStatement;
import mindustry.logic.SugarStatements.ForBeginStatement;
import mindustry.logic.SugarStatements.FuncCallStatement;
import mindustry.logic.SugarStatements.FuncDefStatement;
import mindustry.logic.SugarStatements.IfBeginStatement;
import mindustry.logic.SugarStatements.ReturnStatement;
import mindustry.logic.SugarStatements.SwitchBeginStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;
import logicsugar.assist.data.DataModules;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprIntrinsics;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SugarCompiler{
    private static final String markerBegin = "# @logic-sugar-v1 begin";
    private static final String markerLine = "# @logic-sugar-line ";
    private static final String markerEnd = "# @logic-sugar-v1 end";

    /** Persistence carrier prefixes: real "set" statements that survive the vanilla
     *  parse/save round trip (comment markers are dropped by it). The sugar carrier holds
     *  the sugar source; the library carrier holds the used subset of the function library.
     *
     *  <p>Carriers come in two shapes. The single shape {@code set __ls_sugar "<encoded>"}
     *  is byte-for-byte what every LogicSugar version has emitted and is used whenever the
     *  encoded payload fits {@link #carrierMaxChars}, so small saves never change. A larger
     *  payload is split into the sharded shape {@code set __ls_sugar_1 "<chunk>"},
     *  {@code set __ls_sugar_2 "<chunk>"}, ... (the same scheme for {@code set __ls_lib_N
     *  "..."}): consecutive shard numbers starting at 1, one "set" line per shard, every
     *  chunk within the limit. Readers (restore, libraryFromCode, isSugarProgram) and the
     *  decompiler strips accept exactly these shapes; a gap in the numbering or any other
     *  suffix means the line is user data, not a shard.</p> */
    private static final String carrierSugarPrefix = "set __ls_sugar \"";
    private static final String carrierLibPrefix = "set __ls_lib \"";
    /** Head of a sharded carrier line; the shard number and the quoted payload follow
     *  ({@code set __ls_sugar_1 "..."}). Only purely numeric suffixes form a carrier, so a
     *  variable called {@code __ls_sugar_1x} is never one. */
    private static final String carrierSugarShardPrefix = "set __ls_sugar_";
    private static final String carrierLibShardPrefix = "set __ls_lib_";
    /** LParser rejects string tokens longer than 65535 UTF bytes; staying well below that
     *  keeps a stored program from ever making a vanilla client fail to open the editor.
     *  An encoded payload larger than this is split into shards of at most this many chars
     *  instead of being dropped, which keeps every shard line under the LParser cap too. */
    private static final int carrierMaxChars = 60000;

    /** Function expansion mode. normal = shared @counter subroutine; inline = per-call copy. */
    public enum FuncMode{
        normal, inline;

        public static FuncMode parse(String value){
            if("inline".equalsIgnoreCase(value)) return inline;
            return normal;
        }
    }

    /** switch dispatch shape. auto picks per switch between the comparison chain and an
     *  @counter jump table by instruction cost; chainOnly always lowers the comparison chain
     *  (byte-identical to pre-2.3.1 output). Jump tables assume integer case values: any
     *  non-integer or out-of-range value set falls back to the chain regardless. */
    public enum SwitchStrategy{
        auto, chainOnly;

        public static SwitchStrategy parse(String value){
            if("chainOnly".equalsIgnoreCase(value)) return chainOnly;
            return auto;
        }
    }

    /** What happens to assertion statements (SugarAsserts) at lowering time. strip (the
     *  default) compiles them away: the sugar — assertions included — lives in the
     *  persistence carrier and the saved mlog stays vanilla-parseable. emit (debug build)
     *  writes them as real custom instructions, which vanilla clients degrade to
     *  InvalidStatement placeholders. */
    public enum AssertEmit{
        strip, emit;

        public static AssertEmit parse(String value){
            if("emit".equalsIgnoreCase(value)) return emit;
            return strip;
        }
    }

    private SugarCompiler(){}

    /** Extracts the sugar source from stored code. The persistence carrier is authoritative;
     *  without one (v2.0.0 legacy programs) the comment marker block is used. Scanning from
     *  the end, a sharded carrier is assembled first (continuous {@code __ls_sugar_N}
     *  numbering from 1 — any gap means "not a shard set"), then the single
     *  {@code set __ls_sugar "..."} shape, then the marker block. */
    public static String restore(String code){
        String normalized = code.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        // Scan from the end: genuine carriers are always the last sugar-carrying lines, so a
        // user statement that happens to look like a carrier loses the race only in its favor.
        String sharded = joinShardedCarrier(lines, carrierSugarShardPrefix);
        if(sharded != null) return sharded;
        for(int i = lines.length - 1; i >= 0; i--){
            String line = lines[i];
            if(line.startsWith(carrierSugarPrefix) && line.endsWith("\"")){
                try{
                    return decode(line.substring(carrierSugarPrefix.length(), line.length() - 1));
                }catch(Exception ignored){
                    // damaged carrier: fall back to the marker block below
                }
            }
        }

        int begin = -1, end = -1;
        for(int i = 0; i < lines.length; i++){
            if(lines[i].equals(markerBegin)) begin = i;
            if(begin >= 0 && lines[i].equals(markerEnd)) end = i;
        }
        if(begin < 0 || end <= begin) return code;

        StringBuilder result = new StringBuilder();
        for(int i = begin + 1; i < end; i++){
            if(!lines[i].startsWith(markerLine)) return code;
            result.append(lines[i].substring(markerLine.length())).append('\n');
        }
        return result.toString();
    }

    /** Returns the library source embedded in stored code (the used subset the program was
     *  compiled with), or null when the code carries no embedded library. Sharded
     *  {@code __ls_lib_N} carriers are assembled first (continuous numbering from 1), then
     *  the single {@code set __ls_lib "..."} shape; both scan from the end. */
    public static String libraryFromCode(String code){
        String normalized = code.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        String sharded = joinShardedCarrier(lines, carrierLibShardPrefix);
        if(sharded != null) return sharded;
        for(int i = lines.length - 1; i >= 0; i--){
            String line = lines[i];
            if(line.startsWith(carrierLibPrefix) && line.endsWith("\"")){
                try{
                    return decode(line.substring(carrierLibPrefix.length(), line.length() - 1));
                }catch(Exception ignored){
                    return null;
                }
            }
        }
        return null;
    }

    /** Removes the comment marker block (the redundant sugar source) from compiled code.
     *  The persistence carriers are kept, so restore() still works afterwards. */
    public static String stripMarkers(String code){
        String normalized = code.replace("\r\n", "\n");
        int begin = normalized.indexOf(markerBegin);
        if(begin < 0) return code;
        int end = normalized.indexOf(markerEnd, begin);
        if(end < 0) return code;
        int after = end + markerEnd.length();
        if(after < normalized.length() && normalized.charAt(after) == '\n') after++;
        return normalized.substring(0, begin) + normalized.substring(after);
    }

    /**
     * Checks that the restored sugar really compiles to the stored code. Legacy v2.0.0
     * programs carry no carrier and are trusted as-is. Otherwise the sugar is recompiled
     * against the embedded library in both function modes and normalized (vanilla
     * read/write round trip, which also folds label jumps back into numeric indices); any
     * matching mode passes. A mismatch means the stored code was edited outside Logic Sugar.
     */
    public static boolean verifyRestore(String code, String restored){
        if(!hasSugarCarrier(code)) return true;
        String libText = libraryFromCode(code);
        SugarFunctions.LibraryIndex embedded = null;
        String embeddedSource = null;
        if(libText != null && !libText.trim().isEmpty()){
            SugarFunctions.SanitizedLibrary sanitized = SugarFunctions.sanitizedLibrary(libText);
            if(!sanitized.index.functions.isEmpty()){
                embedded = sanitized.index;
                embeddedSource = sanitized.text;
            }
        }
        for(FuncMode mode : FuncMode.values()){
            // Programs saved as debug builds carry assert instructions in the stored stream;
            // recompiling with the local (possibly strip) setting would drop them and fail
            // the comparison, so both emit shapes are tried for assertion-bearing programs.
            // The stored stream is checked as well as the sugar: array/matrix subscript
            // bounds asserts are generated at lowering time and never appear in the sugar.
            AssertEmit[] emitShapes = SugarAsserts.containsAssertStatements(restored)
                || SugarAsserts.containsAssertStatements(code)
                ? AssertEmit.values() : new AssertEmit[]{AssertEmit.strip};
            for(AssertEmit emit : emitShapes){
                try{
                    String recompiled = compile(restored, mode, embedded, embeddedSource, currentStrategy(), emit);
                    // Threaded current output against either the stored stream (saved by this
                    // version) or the same stream normalized through the idempotent threading
                    // pass (pre-2.3.1 saves were lowered without it).
                    if(matchesStoredStream(recompiled, code)) return true;
                }catch(RuntimeException ignored){
                    // one mode may legitimately fail (e.g. inline blowup); the other may match
                }
            }
        }
        return false;
    }

    /**
     * Stale-close protection: do not submit when the canvas was left untouched while the
     * stored code changed underneath it (another client saved). Edited canvases always
     * submit (last writer wins, same as vanilla).
     */
    public static boolean shouldSubmit(String canvasText, String editable, String openedCode, String currentCode){
        return !(canvasText.equals(editable) && !currentCode.equals(openedCode));
    }

    /** Compiles with the user-selected function mode (normal when settings are unavailable). */
    public static String compile(String sugar){
        return compile(sugar, currentMode());
    }

    public static String compile(String sugar, FuncMode mode){
        return compile(sugar, mode, SugarFunctions.library());
    }

    public static String compile(String sugar, FuncMode mode, SugarFunctions.LibraryIndex library){
        return compile(sugar, mode, library, null);
    }

    /** Compiles against an explicit library and its text, using the user-selected switch strategy. */
    public static String compile(String sugar, FuncMode mode, SugarFunctions.LibraryIndex library, String libraryText){
        return compile(sugar, mode, library, libraryText, currentStrategy());
    }

    /** Compiles against an explicit library and its text with an explicit switch strategy.
     *  The library text is used to embed the used subset into the output
     *  ({@code set __ls_lib "..."}), so other machines can recompile the program without
     *  the local library file. Assertion statements follow the user's assert-emit setting. */
    public static String compile(String sugar, FuncMode mode, SugarFunctions.LibraryIndex library, String libraryText,
                                 SwitchStrategy switchStrategy){
        return compile(sugar, mode, library, libraryText, switchStrategy, currentAssertEmit());
    }

    /** Compiles with an explicit switch strategy and assertion emission shape. */
    public static String compile(String sugar, FuncMode mode, SugarFunctions.LibraryIndex library, String libraryText,
                                 SwitchStrategy switchStrategy, AssertEmit assertEmit){
        Seq<LStatement> statements = LAssembler.read(sugar, true);
        if(!containsSugar(statements)) return sugar;

        validatePairs(statements);

        // F2: 用户函数名（本地 funcdef + 库函数）在表达式展开时遮蔽同名 intrinsic
        // （sum/avg/count/... 走普通 funccall）。在 analyze 之前安装：collectCalls 与
        // lower 阶段的解析器必须看到同一套遮蔽关系。try/finally 配对，异常路径同样恢复。
        Set<String> userFunctionNames = new HashSet<>();
        for(LStatement statement : statements){
            if(statement instanceof FuncDefStatement def) userFunctionNames.add(def.name);
        }
        if(library != null) userFunctionNames.addAll(library.functions.keySet());
        Set<String> previousUserFunctions = ExprIntrinsics.enterUserFunctions(userFunctionNames);
        // F2: 数据模块注入的内置函数库并入本次编译使用的 LibraryIndex（只影响本次编译；
        // extractLibrarySource 仍只作用于纯用户库文本，内置函数不会进入 __ls_lib 载体）。
        SugarFunctions.LibraryIndex compileLibrary = SugarFunctions.withBuiltins(library, DataModules.builtinSugar());
        ArrayRegistry previousArrays = null;
        boolean arraysEntered = false;
        boolean modulesCollected = false;
        try{
            SugarFunctions.FunctionSet functions = SugarFunctions.analyze(statements, compileLibrary);

            // Array declaration cards → program-level registry: the compile-time basis for
            // resolving `buf[i]` in condition/return/argument expressions (and for the editor's
            // fold/unfold when no explicit context is active). Strict validation (duplicates,
            // overlapping ranges, illegal literals) runs before lowering and aborts the compile.
            // The function-name set is local funcdefs plus library functions; array names must
            // not shadow them. The registry is installed as a static compile-time context
            // (same pattern as currentAssertEmit) and popped in finally, so lower()/throwing
            // paths and the recompile inside verifyRestore() always see a consistent table.
            Set<String> functionNames = new HashSet<>(functions.functions.keySet());
            if(functions.library != null) functionNames.addAll(functions.library.functions.keySet());
            ArrayRegistry arrays = ArrayRegistry.compileRegistry(statements, functionNames);
            previousArrays = ArrayRegistry.enter(arrays);
            arraysEntered = true;

            // F2: 数据模块编译期上下文（analyze 之后、lower 之前）；restore() 在 finally 统一清理。
            // 标记在 collectAll 之前置位：collectAll 会先安装上下文再逐模块 collect，任一模块
            // collect 抛错都必须由 finally 的 restore() 配对清理，否则注册表泄漏到下一次编译/编辑器渲染。
            java.util.List<LStatement> statementList = new java.util.ArrayList<>(statements.size);
            for(LStatement statement : statements) statementList.add(statement);
            modulesCollected = true;
            DataModules.collectAll(statementList, functionNames);

            StringBuilder out = new StringBuilder();
            SugarFunctions.CallIds ids = new SugarFunctions.CallIds();
            if(mode == FuncMode.normal){
                SugarFunctions.lower(functions.main, "", functions, mode, out, ids, null, switchStrategy, assertEmit);
                java.util.List<SugarFunctions.Function> hoisted = functions.hoistOrder();
                if(!hoisted.isEmpty()){
                    // Normal-mode function bodies sit right after the main program. A call site's
                    // return point (the `set <result> <retName>` after its jump) is inside main;
                    // once main runs past it, the instruction stream would fall through into the
                    // shared function body and re-execute it every tick (caller variables like
                    // <result> keep incrementing). Jump past all bodies at the end of main.
                    out.append("jump __ls_end always x false\n");
                    for(SugarFunctions.Function function : hoisted){
                        out.append(function.entryName()).append(":\n");
                        SugarFunctions.lower(function.body, "func_" + function.name + "_", functions, mode, out, ids, function.name, switchStrategy, assertEmit);
                        out.append(function.exitName()).append(":\n");
                        out.append("set @counter ").append(function.retName()).append('\n');
                    }
                    out.append("__ls_end:\n");
                }
            }else{
                SugarFunctions.lower(functions.main, "", functions, mode, out, ids, null, switchStrategy, assertEmit);
            }

            // Jump-thread the lowered label text (before marker/carriers): a jump whose target
            // label is immediately followed by another unconditional jump now points at the
            // final destination directly. Semantics-preserving; merges stacked structure-exit
            // defaults and jump-table hole rows that would otherwise hop twice at runtime.
            String lowered = threadAlwaysJumpTargets(out.toString());

            // Persistence carriers: real "set" statements appended after the marker block. They
            // survive the vanilla parse/save round trip that drops the comment markers, and are
            // placed after them so lowered-code consumers (and the test helper) see the lowered
            // program untouched. They execute harmlessly every tick and count toward the limit.
            // A payload whose encoded form fits carrierMaxChars keeps the exact single-carrier
            // line every previous version emitted; only a larger one is sharded (see
            // appendCarrier), so small saves stay byte-identical.
            String sugarPayload = sugar.replace("\r\n", "\n");
            String libPayload = null;
            Set<String> usedLibrary = new HashSet<>();
            for(SugarFunctions.Function function : functions.hoistOrder()){
                if(function.library) usedLibrary.add(function.name);
            }
            if(libraryText != null && !libraryText.trim().isEmpty() && !usedLibrary.isEmpty()){
                String extracted = SugarFunctions.extractLibrarySource(libraryText, usedLibrary);
                if(!extracted.isEmpty()) libPayload = extracted;
            }
            StringBuilder carriers = new StringBuilder();
            boolean anySharded = appendCarriers(carriers, libPayload, sugarPayload, true);

            // LAssembler.read silently truncates at LExecutor.maxInstructions lines, so the count
            // must be computed from the emitted text itself (one instruction per non-label line).
            // Shard lines are ordinary statements and count one each; nothing here assumes the
            // old single-line carrier shape.
            int loweredCount = countInstructions(new StringBuilder(lowered));
            int instructionCount = loweredCount + countInstructions(carriers);
            if(instructionCount > LExecutor.maxInstructions && anySharded){
                // Before sharding, an oversized payload was dropped with a warning instead of
                // blocking the save. Keep that degradation when the extra shard lines would push
                // the program past the executor limit: drop the sharded payloads (the lowered
                // stream alone may still fit) rather than failing a save that used to succeed.
                // A lowered stream that exceeds the limit on its own still throws below, exactly
                // as before; sharding can only add lines, never remove them.
                StringBuilder degraded = new StringBuilder();
                appendCarriers(degraded, libPayload, sugarPayload, false);
                int degradedCount = loweredCount + countInstructions(degraded);
                if(degradedCount <= LExecutor.maxInstructions){
                    Log.warn("LogicSugar: carrier shards would exceed the instruction limit (@ statements, limit @); the source will not survive this save",
                        instructionCount, LExecutor.maxInstructions);
                    carriers = degraded;
                    instructionCount = degradedCount;
                }
            }
            if(instructionCount > LExecutor.maxInstructions){
                String hint = mode == FuncMode.inline ? " Switch to normal mode to share function bodies." : "";
                throw new IllegalArgumentException("Compiled program has " + instructionCount + " instructions; maximum is " + LExecutor.maxInstructions + "." + hint);
            }

            StringBuilder result = new StringBuilder(lowered);
            appendMarker(result, sugar);
            result.append(carriers);
            return result.toString();
        }finally{
            if(modulesCollected) DataModules.restore();
            if(arraysEntered) ArrayRegistry.restore(previousArrays);
            ExprIntrinsics.restoreUserFunctions(previousUserFunctions);
        }
    }

    /** The merged library for editing a stored program: embedded functions first, then
     *  local functions the embedded ones do not shadow. Both sources are salvaged through
     *  {@link SugarFunctions#sanitizedLibrary} before merging, so a damaged local file yields
     *  an index with the recoverable functions instead of silently null. The text mirrors the
     *  index, so the compiler can re-extract the used subset from it when saving
     *  (self-correcting). */
    public static EffectiveLibrary effectiveLibrary(String code, SugarFunctions.LibraryIndex local, String localText){
        // the caller-provided local index is advisory; the sanitized local text is authoritative
        String embeddedText = libraryFromCode(code);
        String embedded = embeddedText == null ? "" : embeddedText.trim();
        Set<String> embeddedNames = new HashSet<>();
        String embeddedSource = "";
        SugarFunctions.SanitizedLibrary sanitizedEmbedded = null;
        if(!embedded.isEmpty()){
            sanitizedEmbedded = SugarFunctions.sanitizedLibrary(embedded);
            embeddedSource = sanitizedEmbedded.text;
            for(String name : sanitizedEmbedded.index.functions.keySet()) embeddedNames.add(name);
        }
        StringBuilder text = new StringBuilder(embeddedSource);
        SugarFunctions.SanitizedLibrary sanitizedLocal = null;
        if(localText != null && !localText.trim().isEmpty()){
            sanitizedLocal = SugarFunctions.sanitizedLibrary(localText);
            Set<String> extras = new HashSet<>(sanitizedLocal.index.functions.keySet());
            extras.removeAll(embeddedNames);
            if(!extras.isEmpty()){
                // extract from the sanitized text: raw slices could copy damaged duplicates
                String extracted = SugarFunctions.extractLibrarySource(sanitizedLocal.text, extras);
                if(!extracted.isEmpty()){
                    if(text.length() > 0) text.append('\n');
                    text.append(extracted);
                }
            }
        }
        String effectiveText = text.toString();
        // nothing to merge (no embedded carrier and no local file) behaves like an unavailable
        // library; otherwise the sanitized merge is always valid, but keeps the sources' damage
        // state so unresolved calls can point the user at the repair path
        SugarFunctions.LibraryIndex effective = null;
        if(!effectiveText.trim().isEmpty()){
            effective = SugarFunctions.sanitizedLibrary(effectiveText).index;
            boolean damaged = (sanitizedEmbedded != null && sanitizedEmbedded.damaged)
                || (sanitizedLocal != null && sanitizedLocal.damaged);
            if(damaged){
                effective.damaged = true;
                if(effective.warnings.isEmpty()){
                    effective.warnings = new java.util.ArrayList<>();
                    if(sanitizedEmbedded != null && !sanitizedEmbedded.warnings.isEmpty()){
                        effective.warnings.addAll(sanitizedEmbedded.warnings);
                    }
                    if(sanitizedLocal != null && !sanitizedLocal.warnings.isEmpty()){
                        effective.warnings.addAll(sanitizedLocal.warnings);
                    }
                }
            }
        }
        return new EffectiveLibrary(effective, effectiveText);
    }

    /** Parsed library index plus the text it was built from. */
    public static final class EffectiveLibrary{
        public final SugarFunctions.LibraryIndex index;
        public final String text;

        public EffectiveLibrary(SugarFunctions.LibraryIndex index, String text){
            this.index = index;
            this.text = text;
        }
    }

    /** Appends the persistence carriers for the library and sugar payloads; returns whether
     *  any payload exceeded the single-carrier limit (and was sharded, or dropped when
     *  {@code allowSharding} is off). */
    private static boolean appendCarriers(StringBuilder out, String libPayload, String sugarPayload, boolean allowSharding){
        boolean oversize = false;
        if(libPayload != null) oversize = appendCarrier(out, carrierLibPrefix, carrierLibShardPrefix, libPayload, allowSharding);
        if(appendCarrier(out, carrierSugarPrefix, carrierSugarShardPrefix, sugarPayload, allowSharding)) oversize = true;
        return oversize;
    }

    /** Appends one carrier as real "set" statements. A payload whose encoded form fits
     *  {@link #carrierMaxChars} keeps the exact single-line shape every LogicSugar version
     *  has emitted ({@code set <prefix>"<encoded>"}). A larger payload is split into
     *  consecutive {@code set <shardPrefix>N "<chunk>"} shards numbered from 1, each chunk
     *  within the limit, so every shard line stays under the LParser string cap; readers
     *  reassemble them by the continuous numbering. With {@code allowSharding} off, an
     *  oversized payload is skipped with a warning instead — the pre-sharding degradation,
     *  still used when the shard lines themselves would overflow the instruction limit.
     *  Returns whether the payload exceeded the single-carrier limit. */
    private static boolean appendCarrier(StringBuilder out, String prefix, String shardPrefix, String text, boolean allowSharding){
        String encoded = encode(text);
        if(encoded.length() <= carrierMaxChars){
            out.append(prefix).append(encoded).append("\"\n");
            return false;
        }
        if(!allowSharding){
            // The carrier must not be dropped silently: without it the saved program still
            // works, but the sugar source (and the library) can no longer be restored.
            Log.warn("LogicSugar: sugar text too large for the carrier (@ chars, limit @); the source will not survive this save",
                encoded.length(), carrierMaxChars);
            return true;
        }
        int shards = (encoded.length() + carrierMaxChars - 1) / carrierMaxChars;
        for(int i = 0; i < shards; i++){
            out.append(shardPrefix).append(i + 1).append(" \"")
                .append(encoded, i * carrierMaxChars, Math.min((i + 1) * carrierMaxChars, encoded.length()))
                .append("\"\n");
        }
        return true;
    }

    /** Joins a sharded carrier ({@code set <shardPrefix>N "<payload>"} lines). The last
     *  shard-shaped line anchors the set; walking back over the contiguous run must produce
     *  consecutive numbers down to 1 — a gap, a repeat or a damaged shard means this was
     *  not a compiler-made shard set, and null is returned so the caller falls back to the
     *  single-carrier shape. The encoded payloads are concatenated in number order and
     *  decoded once: chunk boundaries always land on 4-char base64 groups, but a UTF-8
     *  sequence could still straddle them, so the bytes must be joined before decoding. */
    private static String joinShardedCarrier(String[] lines, String shardPrefix){
        int anchor = -1, top = -1;
        for(int i = lines.length - 1; i >= 0; i--){
            String line = lines[i];
            if(!line.endsWith("\"")) continue;
            top = carrierShardNumber(line, shardPrefix);
            if(top > 0){
                anchor = i;
                break;
            }
        }
        if(anchor < 0) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        int number = top;
        for(int i = anchor; i >= 0 && number >= 1; i--){
            String line = lines[i];
            if(!line.endsWith("\"") || carrierShardNumber(line, shardPrefix) != number) return null;
            parts.add(line.substring(shardPayloadStart(line, shardPrefix), line.length() - 1));
            number--;
        }
        if(number != 0) return null; // numbering did not run down to 1: gap, not a shard set
        StringBuilder encoded = new StringBuilder();
        for(int i = parts.size() - 1; i >= 0; i--) encoded.append(parts.get(i));
        try{
            return decode(encoded.toString());
        }catch(Exception ignored){
            return null; // damaged shards: fall back to the single-carrier shape
        }
    }

    /** The shard number of a {@code set <shardPrefix>N "<payload>"} line, or -1 when the
     *  line does not have the exact shape: the base prefix, a purely numeric suffix, then a
     *  space and the opening quote of a non-empty payload. */
    private static int carrierShardNumber(String line, String shardPrefix){
        if(!line.startsWith(shardPrefix)) return -1;
        int i = shardPrefix.length();
        int number = 0, digits = 0;
        while(i < line.length() && line.charAt(i) >= '0' && line.charAt(i) <= '9'){
            number = number * 10 + (line.charAt(i) - '0');
            digits++;
            i++;
        }
        // the digit-run cap doubles as an overflow guard; shards start at 1
        if(digits == 0 || digits > 6 || number <= 0) return -1;
        if(i + 2 >= line.length() || line.charAt(i) != ' ' || line.charAt(i + 1) != '"') return -1;
        return number;
    }

    /** Payload start index (just after the opening quote) of a line already validated by
     *  {@link #carrierShardNumber}. */
    private static int shardPayloadStart(String line, String shardPrefix){
        int i = shardPrefix.length();
        while(i < line.length() && line.charAt(i) >= '0' && line.charAt(i) <= '9') i++;
        return i + 2; // skip the space and the opening quote
    }

    /** Whether stored code carries a sugar persistence carrier in either shape (single or
     *  sharded). Line-level recognition like the old single-shape scan: the first
     *  carrier-shaped line from the end decides, mirroring what restore() attempts first. */
    private static boolean hasSugarCarrier(String code){
        String[] lines = code.replace("\r\n", "\n").split("\n", -1);
        for(int i = lines.length - 1; i >= 0; i--){
            String line = lines[i];
            if(!line.endsWith("\"")) continue;
            if(line.startsWith(carrierSugarPrefix) || carrierShardNumber(line, carrierSugarShardPrefix) > 0) return true;
        }
        return false;
    }

    /** Whether stored code was compiled by Logic Sugar (carries the persistence carrier,
     *  single or sharded). */
    public static boolean isSugarProgram(String code){
        return code != null && hasSugarCarrier(code);
    }

    /** UTF-8 base64 via arc's coder (minSdk 21 forbids java.util.Base64). */
    private static String encode(String text){
        return new String(Base64Coder.encode(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String decode(String text){
        return new String(Base64Coder.decode(text), StandardCharsets.UTF_8);
    }

    public static boolean[] invalidStatements(Seq<LStatement> statements){
        boolean[] invalid = new boolean[statements.size];
        boolean[] claimed = new boolean[statements.size];

        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof BeginStatement begin)) continue;
            int destination = begin.destIndex;
            if(destination <= i || destination >= statements.size || !(statements.get(destination) instanceof BlockEndStatement)){
                invalid[i] = true;
            }else if(claimed[destination]){
                invalid[i] = true;
            }else{
                claimed[destination] = true;
            }
        }

        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof BlockEndStatement && !claimed[i]) invalid[i] = true;
        }

        Deque<Integer> ends = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!ends.isEmpty() && ends.peek() < i) ends.pop();
            if(statements.get(i) instanceof BeginStatement begin && begin.destIndex > i && begin.destIndex < statements.size){
                if(!ends.isEmpty() && begin.destIndex > ends.peek()) invalid[i] = true;
                ends.push(begin.destIndex);
            }
        }

        int[] switchOwner = switchOwners(statements);
        int[] breakOwner = breakOwners(statements);
        int[] continueOwner = continueOwners(statements);
        int[] ifOwner = ifOwners(statements);
        int[] funcOwner = funcOwners(statements);
        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof CaseStatement && switchOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof BreakStatement && breakOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof ContinueStatement && continueOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof ElseIfStatement && ifOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof ElseStatement && ifOwner[i] < 0){
                invalid[i] = true;
            }
            // return is only legal inside a function body; mirror the compile-time
            // "return ... is outside a function" error in the editor (red marking)
            if(statements.get(i) instanceof ReturnStatement ret){
                if(funcOwner[i] < 0){
                    invalid[i] = true;
                }else if(!ret.expr.isEmpty() && !validConditionExpression(ret.expr, statements)){
                    // return 表达式本身非法（如 a1.1 = 变量后接数字成员）时编译期会抛错，
                    // 编辑期必须同步标红，否则保存/编译失败但编辑器毫无提示。
                    invalid[i] = true;
                }
            }
            if(statements.get(i) instanceof IfBeginStatement ifBegin && ifBegin.expressionMode){
                invalid[i] |= !validConditionExpression(ifBegin.conditionExpr, statements);
            }
            if(statements.get(i) instanceof ElseIfStatement elseIf && elseIf.expressionMode){
                invalid[i] |= !validConditionExpression(elseIf.conditionExpr, statements);
            }
            if(statements.get(i) instanceof WhileBeginStatement whileBegin && whileBegin.expressionMode){
                invalid[i] |= !validConditionExpression(whileBegin.conditionExpr, statements);
            }
            if(statements.get(i) instanceof ForBeginStatement forBegin && forBegin.expressionMode){
                invalid[i] |= !validConditionExpression(forBegin.conditionExpr, statements);
            }
        }

        // an if chain may have at most one else, and no elif may follow it (shared rule,
        // also enforced by the compile path and the library builder)
        boolean[] ifBad = SugarFunctions.ifChainViolations(statements, ifOwner);
        for(int i = 0; i < statements.size; i++){
            if(ifBad[i]) invalid[i] = true;
        }

        // function calls whose name resolves nowhere are marked invalid (library is loaded lazily)
        Set<String> local = new HashSet<>();
        for(LStatement statement : statements){
            if(statement instanceof FuncDefStatement def) local.add(def.name);
        }
        SugarFunctions.LibraryIndex library = SugarFunctions.library();
        // F2: 数据模块注入的内置函数名（编辑器里对内置 funccall 不标红）
        Set<String> builtinNames = DataModules.builtinFunctionNames();
        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof FuncCallStatement call){
                if(!local.contains(call.name)
                    && (library == null || !library.functions.containsKey(call.name))
                    && !builtinNames.contains(call.name)){
                    invalid[i] = true;
                }
                // 实参表达式非法（如 a1.1）时编译期会抛错，编辑期同步标红；
                // 用括号感知的 splitArgs 拆分，避免 max(1, 2) 这类嵌套实参被朴素逗号切分误伤
                if(!call.args.isEmpty()){
                    for(String arg : SugarFunctions.splitArgs(call.args)){
                        if(!validConditionExpression(arg, statements)){
                            invalid[i] = true;
                            break;
                        }
                    }
                }
            }
        }

        // array 声明卡的字段级问题（重名/同内存块区间重叠/非法 base/size/与函数重名）在
        // 编辑期同步标红；严格校验仍由编译路径（compileRegistry）拦截保存
        Set<String> arrayReservedNames = new HashSet<>(local);
        if(library != null) arrayReservedNames.addAll(library.functions.keySet());
        ArrayRegistry.markInvalidStatements(statements, invalid, arrayReservedNames);
        // F2: 数据模块自有声明卡的字段级标红（record/stack/... 的注册表校验）
        java.util.List<LStatement> statementList = new java.util.ArrayList<>(statements.size);
        for(LStatement statement : statements) statementList.add(statement);
        DataModules.markInvalid(statementList, invalid, arrayReservedNames);
        return invalid;
    }

    private static boolean validConditionExpression(String expression, Seq<LStatement> statements){
        try{
            ExprCompiler.compile("__ls_cond_check", expression, conditionChecker(statements));
            return true;
        }catch(Exception ignored){
            return false;
        }
    }

    /** 条件表达式里的函数名校验：本地 funcdef + 库函数 + 数据模块 intrinsic（数学函数由 ExprCompiler 内置）。 */
    private static ExprCompiler.FunctionChecker conditionChecker(Seq<LStatement> statements){
        Set<String> names = new HashSet<>();
        for(LStatement statement : statements){
            if(statement instanceof FuncDefStatement def) names.add(def.name);
        }
        SugarFunctions.LibraryIndex library = SugarFunctions.library();
        if(library != null) names.addAll(library.functions.keySet());
        // F2: 数据模块的表达式函数名（sum/avg/count/... 与 record 成员）在条件表达式里合法
        names.addAll(ExprIntrinsics.intrinsicNames());
        names.addAll(DataModules.builtinFunctionNames());
        return names::contains;
    }

    public static FuncMode currentMode(){
        try{
            if(Core.settings != null){
                return FuncMode.parse(Core.settings.getString("logicsugar.funcMode", "normal"));
            }
        }catch(Exception ignored){
            // settings unavailable (e.g. self-test environment): fall back to normal
        }
        return FuncMode.normal;
    }

    /** The user-selected switch lowering strategy (auto when settings are unavailable). */
    public static SwitchStrategy currentStrategy(){
        try{
            if(Core.settings != null){
                return SwitchStrategy.parse(Core.settings.getString("logicsugar.switchStrategy", "auto"));
            }
        }catch(Exception ignored){
            // settings unavailable (e.g. self-test environment): fall back to auto
        }
        return SwitchStrategy.auto;
    }

    /** The user-selected assertion emission shape (strip when settings are unavailable).
     *  Hard project requirement: multiplayer saves must stay vanilla-parseable, and emitted
     *  assert instructions degrade to InvalidStatement on vanilla clients — so debug builds
     *  only exist in single-player/editor sessions (net inactive). The explicit
     *  {@code compile(..., AssertEmit)} overload bypasses this gate on purpose: it is used
     *  by the verification matrix and self-tests, never by the save path. */
    public static AssertEmit currentAssertEmit(){
        if(Vars.net != null && Vars.net.active()) return AssertEmit.strip;
        try{
            if(Core.settings != null){
                return AssertEmit.parse(Core.settings.getString("logicsugar.assertEmit", "strip"));
            }
        }catch(Exception ignored){
            // settings unavailable (e.g. self-test environment): fall back to strip
        }
        return AssertEmit.strip;
    }

    private static boolean containsSugar(Seq<LStatement> statements){
        // every LogicSugar-owned card (SugarStatements structures, SugarAsserts assertions)
        // descends from SugarStatement; vanilla statements never do
        for(LStatement statement : statements){
            if(statement instanceof SugarStatements.SugarStatement) return true;
        }
        return false;
    }

    private static void validatePairs(Seq<LStatement> statements){
        boolean[] claimed = new boolean[statements.size];
        for(int i = 0; i < statements.size; i++){
            LStatement statement = statements.get(i);
            if(!(statement instanceof BeginStatement begin)) continue;
            int destination = begin.destIndex;
            if(destination <= i || destination >= statements.size || !(statements.get(destination) instanceof BlockEndStatement)){
                throw error(statement.typeName(), i, "must point to a block end below it");
            }
            if(claimed[destination]) throw error(statement.typeName(), i, "shares an end block with another begin block");
            claimed[destination] = true;
        }

        for(int i = 0; i < statements.size; i++){
            LStatement statement = statements.get(i);
            if(statement instanceof BlockEndStatement && !claimed[i]){
                throw error(statement.typeName(), i, "has no matching begin block");
            }
        }

        Deque<Integer> ends = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!ends.isEmpty() && ends.peek() < i) ends.pop();
            if(statements.get(i) instanceof BeginStatement begin){
                if(!ends.isEmpty() && begin.destIndex > ends.peek()){
                    throw error(begin.typeName(), i, "crosses another structured block");
                }
                ends.push(begin.destIndex);
            }
        }
    }

    private static int[] switchOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((SwitchBeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof SwitchBeginStatement) stack.push(i);
        }
        return result;
    }

    /** Returns the innermost enclosing if block index for each statement. */
    private static int[] ifOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((IfBeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof IfBeginStatement) stack.push(i);
        }
        return result;
    }

    /** Returns the innermost enclosing structure that accepts a break statement. */
    private static int[] breakOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof WhileBeginStatement || statements.get(i) instanceof SwitchBeginStatement
                || statements.get(i) instanceof ForBeginStatement) stack.push(i);
        }
        return result;
    }
    
    /** Returns the innermost enclosing loop that accepts a continue statement. */
    private static int[] continueOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof WhileBeginStatement || statements.get(i) instanceof ForBeginStatement) stack.push(i);
        }
        return result;
    }

    /** Returns the innermost enclosing function definition for each statement (-1 if none).
     *  Mirrors the compile-time function scope: a statement is "inside a function" iff it
     *  lies strictly between a FuncDefStatement and the block end it points to. */
    private static int[] funcOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof FuncDefStatement) stack.push(i);
        }
        return result;
    }

    private static int countInstructions(StringBuilder out){
        int count = 0;
        int index = 0;
        while(index < out.length()){
            int end = out.indexOf("\n", index);
            if(end < 0) end = out.length();
            String line = out.substring(index, end);
            if(!line.isEmpty() && !line.endsWith(":")) count++;
            index = end + 1;
        }
        return count;
    }

    private static IllegalArgumentException error(String block, int index, String detail){
        return new IllegalArgumentException(block + " at statement " + index + " " + detail + ".");
    }

    private static void appendMarker(StringBuilder out, String sugar){
        out.append(markerBegin).append('\n');
        String normalized = sugar.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        int count = lines.length;
        if(count > 0 && lines[count - 1].isEmpty()) count--;
        for(int i = 0; i < count; i++) out.append(markerLine).append(lines[i]).append('\n');
        out.append(markerEnd).append('\n');
    }

    /**
     * Jump-threading over lowered label text (Bang TagCodes::follow_always_jump_chain):
     * whenever a label's first real instruction is an unconditional {@code jump T always x false},
     * every jump aimed at that label is retargeted straight at T, iterated to a fixed point.
     * Conditional jumps are left alone. Cyclic or self-reaching chains keep their original
     * targets so deliberate infinite loops survive; each lookup is bounded by its own label
     * chain. The pass is idempotent and preserves the line structure (instruction count and
     * order stay identical), which keeps carriers/markers and the instruction limit intact.
     *
     * <p>The input may be a full stored program: blank lines, {@code #} comments and the
     * persistence carriers are simply not jumps, so they terminate adjacency scans and no
     * metadata is ever rewritten.</p>
     */
    public static String threadAlwaysJumpTargets(String code){
        if(code == null || code.isEmpty()) return code == null ? "" : code;
        String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        Map<String, Integer> labels = new HashMap<>();
        for(int i = 0; i < lines.length; i++){
            String bare = lines[i].trim();
            if(!bare.endsWith(":") || bare.length() < 2 || bare.contains(" ")) continue;
            String name = bare.substring(0, bare.length() - 1);
            if(isPlainIdentifier(name)) labels.putIfAbsent(name, i);
        }
        if(labels.isEmpty()) return code;

        Set<String> pending = new HashSet<>(labels.keySet());
        Map<String, String> resolved = new HashMap<>();
        while(!pending.isEmpty()){
            String label = pending.iterator().next();
            pending.remove(label);
            resolveLabelChain(label, labels, lines, resolved, new HashSet<>());
        }

        StringBuilder result = new StringBuilder(code.length());
        for(int i = 0; i < lines.length; i++){
            String target = alwaysJumpTargetToken(lines[i]);
            String mapped = target == null ? null : resolved.get(target);
            if(mapped != null && !mapped.equals(target)){
                result.append("jump ").append(mapped).append(" always x false");
            }else{
                result.append(lines[i]);
            }
            if(i < lines.length - 1) result.append('\n');
        }
        return result.toString();
    }

    /** Resolves one label to the final destination of the always-jump chain under it. */
    private static String resolveLabelChain(String label, Map<String, Integer> labels, String[] lines,
                                            Map<String, String> resolved, Set<String> path){
        String done = resolved.get(label);
        if(done != null) return done;
        Integer at = labels.get(label);
        if(at == null){
            resolved.put(label, label);
            return label;
        }
        // A cyclic chain has no terminal destination. Propagate null to every member so the
        // rewrite pass leaves the entire cycle untouched instead of turning one edge into a
        // self-loop (which would be equivalent only for the label-only case, not as a general
        // control-flow transformation).
        if(!path.add(label)) return null;
        String deep = label;
        int next = firstRealInstruction(lines, at + 1);
        if(next >= 0){
            String target = alwaysJumpTargetToken(lines[next]);
            if(target != null && !target.equals(label)){
                deep = resolveLabelChain(target, labels, lines, resolved, path);
                if(deep == null){
                    path.remove(label);
                    return null;
                }
            }
        }
        resolved.put(label, deep);
        path.remove(label);
        return deep;
    }

    /** Index of the next non-blank, non-comment, non-label line at/after {@code from}, or -1. */
    private static int firstRealInstruction(String[] lines, int from){
        for(int i = Math.max(from, 0); i < lines.length; i++){
            String bare = lines[i].trim();
            if(bare.isEmpty() || bare.startsWith("#") || (bare.endsWith(":") && !bare.contains(" ") && bare.length() >= 2)) continue;
            return i;
        }
        return -1;
    }

    /** The destination token of an unconditional {@code jump T always x false} line, else null. */
    private static String alwaysJumpTargetToken(String line){
        String[] tokens = line.trim().split("\\s+");
        if(tokens.length != 5 || !"jump".equals(tokens[0]) || !"always".equals(tokens[2])
            || !"x".equals(tokens[3]) || !"false".equals(tokens[4])) return null;
        return tokens[1];
    }

    private static boolean isPlainIdentifier(String name){
        if(name.isEmpty()) return false;
        char first = name.charAt(0);
        if(!(Character.isLetter(first) || first == '_')) return false;
        for(int i = 1; i < name.length(); i++){
            char c = name.charAt(i);
            if(!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    /** Whether {@code compiled} matches the stored program under either lowering era:
     *  current output is jump-threaded, pre-2.3.1 saves are not; the thread pass is
     *  idempotent, so normalizing the stored text through it covers both. */
    public static boolean matchesStoredStream(String recompiled, String stored){
        try{
            if(LAssembler.write(LAssembler.read(recompiled, true)).equals(LAssembler.write(LAssembler.read(stored, true)))) return true;
        }catch(RuntimeException ignored){
            return false;
        }
        try{
            return LAssembler.write(LAssembler.read(recompiled, true))
                .equals(LAssembler.write(LAssembler.read(threadAlwaysJumpTargets(stored), true)));
        }catch(RuntimeException ignored){
            return false;
        }
    }
}
