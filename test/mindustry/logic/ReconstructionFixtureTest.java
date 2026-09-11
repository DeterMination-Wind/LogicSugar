package mindustry.logic;

import arc.struct.Seq;
import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.logic.LStatements.InvalidStatement;
import mindustry.logic.SugarStatements.ArrayStatement;

/**
 * Reconstruction fixtures: carrier restore, stale destIndex comments, and data-structure
 * cards that exist only in the Sugar source (never in vanilla mlog).
 */
public final class ReconstructionFixtureTest{
    private ReconstructionFixtureTest(){}

    public static void main(String[] args){
        Vars.logicVars = new GlobalVars();
        LogicSugarMod.registerStatements();

        staleDestCommentIsRepairedOnCompile();
        sampleACarrierRestoresStructuredSugar();
        sampleBStaleDestRestoresStructuredSugar();
        dataDeclarationsSurviveCarrierDecompile();
        injectedBuiltinsAreNotRecoveredAsUserFunctions();

        System.out.println("LogicSugar reconstruction fixture self-test passed.");
    }

    /** destIndex is a jump comment; nesting still compiles when the comment is stale. */
    private static void staleDestCommentIsRepairedOnCompile(){
        String stale = "ifbegin x notEqual false 99\nset y 1\nblockend\n";
        String fresh = "ifbegin x notEqual false 2\nset y 1\nblockend\n";
        String compiledStale = SugarCompiler.compile(stale);
        String compiledFresh = SugarCompiler.compile(fresh);
        check(SugarCompiler.matchesStoredStream(compiledStale, compiledFresh),
            "stale destIndex comment changed the lowered stream");
        String restored = SugarCompiler.restore(compiledStale);
        check(restored.contains("ifbegin x notEqual false 2"),
            "restore did not rewrite the stale dest comment:\n" + restored);
        check(SugarCompiler.verifyRestore(compiledStale, restored),
            "verifyRestore rejected a dest-repaired program");
    }

    private static void sampleACarrierRestoresStructuredSugar(){
        String vanilla = sampleA();
        check(SugarCompiler.isSugarProgram(vanilla), "sample A was not recognized as a sugar program");
        String restored = SugarCompiler.restore(vanilla);
        check(restored.contains("ifbegin") && restored.contains("forbegin") && restored.contains("blockend"),
            "sample A restore lost structured cards:\n" + restored);
        check(countInvalid(restored) == 0, "sample A restored sugar parsed with invalid statements");
        check(SugarCompiler.verifyRestore(vanilla, restored), "sample A verifyRestore failed");
        SugarDecompiler.Result result = SugarDecompiler.decompile(vanilla, true);
        check(result.verified && "carrier".equals(result.matchedMode),
            "sample A did not take the carrier path: mode=" + result.matchedMode + " notes=" + result.notes);
        check(result.sugar.contains("ifbegin") && result.sugar.contains("forbegin"),
            "sample A decompile lost if/for: " + result.sugar);
        check(!result.sugar.contains("jump "), "sample A decompile still looks like jump soup:\n" + result.sugar);
    }

    private static void sampleBStaleDestRestoresStructuredSugar(){
        String vanilla = sampleB();
        check(SugarCompiler.isSugarProgram(vanilla), "sample B was not recognized as a sugar program");
        String restored = SugarCompiler.restore(vanilla);
        check(restored.contains("ifbegin e notEqual false 26"),
            "sample B dest comment was not repaired to the matching blockend:\n" + restored);
        check(!restored.contains("ifbegin e notEqual false 56"),
            "sample B kept the stale dest 56:\n" + restored);
        check(restored.contains("forbegin i ") && restored.contains("forbegin i2 "),
            "sample B restore lost nested for cards:\n" + restored);
        check(countInvalid(restored) == 0, "sample B restored sugar parsed with invalid statements");
        check(SugarCompiler.verifyRestore(vanilla, restored), "sample B verifyRestore failed after dest repair");
        SugarDecompiler.Result result = SugarDecompiler.decompile(vanilla, true);
        check(result.verified && "carrier".equals(result.matchedMode),
            "sample B did not take the carrier path: mode=" + result.matchedMode + " notes=" + result.notes);
        check(result.sugar.contains("ifbegin") && result.sugar.contains("forbegin"),
            "sample B decompile lost if/for: " + result.sugar);
        check(!result.sugar.contains("jump "), "sample B decompile still looks like jump soup:\n" + result.sugar);
    }

    /** Declaration cards never appear in vanilla mlog; reconstruction must come from the carrier. */
    private static void dataDeclarationsSurviveCarrierDecompile(){
        String sugar = "array buf cell1 0 8\n"
            + "stack s cell1 8 8\n"
            + "record p hp team ~ ~ ~ ~ ~ ~\n"
            + "ifbegin expr \"spush(s, 1) > 0\" 5\n"
            + "set x 1\n"
            + "blockend\n";
        String compiled = SugarCompiler.compile(sugar);
        check(compiled.contains("set __ls_sugar "), "data program lost its sugar carrier");
        check(!SugarCompiler.stripMarkers(compiled).contains("array "),
            "array declaration leaked into vanilla mlog");
        check(SugarCompiler.verifyRestore(compiled, SugarCompiler.restore(compiled)),
            "data-program carrier failed verifyRestore");

        SugarDecompiler.Result withCarrier = SugarDecompiler.decompile(compiled, true);
        check(withCarrier.verified && "carrier".equals(withCarrier.matchedMode),
            "data program did not restore from the carrier: mode=" + withCarrier.matchedMode
                + " notes=" + withCarrier.notes);
        check(withCarrier.sugar.contains("array buf cell1 0 8"),
            "array card was not restored:\n" + withCarrier.sugar);
        check(withCarrier.sugar.contains("stack s cell1 8 8"),
            "stack card was not restored:\n" + withCarrier.sugar);
        check(withCarrier.sugar.contains("record p hp team"),
            "record card was not restored:\n" + withCarrier.sugar);
        check(withCarrier.sugar.contains("ifbegin"),
            "ifbegin was not restored with the data cards:\n" + withCarrier.sugar);

        Seq<LStatement> restored = LAssembler.read(withCarrier.sugar, true);
        check(restored.size >= 3 && restored.get(0) instanceof ArrayStatement,
            "restored first statement is not an array card");
    }

    /** Without a carrier, injected {@code __ls_builtin_*} trampolines must not become user funcdefs. */
    private static void injectedBuiltinsAreNotRecoveredAsUserFunctions(){
        String sugar = "stack s cell1 0 4\n"
            + "ifbegin expr \"spush(s, 1) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String compiled = SugarCompiler.compile(sugar);
        String stripped = stripCarrier(compiled);
        check(stripped.contains("__ls_func___ls_builtin_stkpush"),
            "fixture did not lower a stack push builtin:\n" + stripped);
        SugarDecompiler.Result result = SugarDecompiler.decompile(stripped, true);
        check(result.verified, "stripped data program was rewritten instead of verified: " + result.notes);
        check(!result.sugar.contains("funcdef __ls_builtin"),
            "injected builtin was recovered as a user function:\n" + result.sugar);
        check(!result.sugar.contains("stack s "),
            "stack declaration was invented from vanilla mlog:\n" + result.sugar);
    }

    private static int countInvalid(String sugar){
        int invalid = 0;
        for(LStatement statement : LAssembler.read(sugar, true)){
            if(statement instanceof InvalidStatement) invalid++;
        }
        return invalid;
    }

    private static String stripCarrier(String code){
        StringBuilder out = new StringBuilder();
        boolean marker = false;
        for(String line : code.replace("\r\n", "\n").split("\n", -1)){
            if(line.equals("# @logic-sugar-v1 begin")){ marker = true; continue; }
            if(line.equals("# @logic-sugar-v1 end")){ marker = false; continue; }
            if(marker || line.startsWith("set __ls_sugar \"") || line.startsWith("set __ls_lib \"")
                || line.startsWith("set __ls_sugar_") || line.startsWith("set __ls_lib_")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static String sampleA(){
        return """
setrate 99999999999999999999999
sensor e switch1 @enabled
sensor t @this @team
op add re @thisx 0.5
op sub rebe @thisy 0.5
jump 71 equal e false
op add rere @thisy 9
makemarker text 1 re rere true
makemarker shape 2 re rebe true
setmarker color 2 %f8505d 0 0
setmarker stroke 2 20 0 0
setmarker radius 2 40 0 0
setmarker fontSize 1 4 0 0
setmarker textHeight 1 150 0 0
setmarker drawLayer 2 210.0 0 0
setmarker drawLayer 1 220.0 0 0
print "[#f9515e]NUCLEAR MISSILE INCOMING"
setmarker flushText 1 40 0 0
effect blockFall re rebe 2 %ffaaff @rtg-generator
wait 1.5
set i 0
jump 23 lessThanEq i 500
jump 29 always x false
playsound true @sfx-explosionMissile 999999999 1 0 re rebe false
playsound true @sfx-explosionTitan 999999999 1 0 re rebe false
playsound true @sfx-explosionReactor 999999999 1 0 re rebe false
playsound true @sfx-unitExplode3 999999999 1 0 re rebe false
op add i i 1
jump 21 always x false
effect crossExplosion re rebe 100000 %ffd1aa 
effect explosion re rebe 15 %ffaaff 
setmarker stroke 1 15 0 0
explosion t re rebe 3 10000000000000 true true true false
explosion t re rebe 7 5000 true true true false
explosion t re rebe 15 1000 true true true false
explosion t re rebe 30 500 true true true false
set i 0
jump 39 lessThanEq i 15
jump 47 always x false
op pow x^2 i 2
op mul ax^2 x^2 5
op mul bx i 4
op add ax^2+bx ax^2 bx
op add ax^2+bx+c ax^2+bx c
effect trail re rebe ax^2+bx+c %ffbd5307 
op add i i 1
jump 37 always x false
set e 0
jump 50 lessThanEq e 200
jump 68 always x false
set e2 0
jump 53 lessThanEq e2 75
jump 66 always x false
op rand dihir 720 b
op div dihir dihir 2
op sin egg dihir 2
op mul egg egg e
op add egg egg re
op cos whiy dihir i
op mul whiy whiy e
op add whiy whiy rebe
op sub yo 200 e
op mul yo yo 4
bullet result @ripple @pyratite egg whiy dihir t null yo 1 0 egg whiy
op add e2 e2 1
jump 51 always x false
op add e e 3
jump 48 always x false
control enabled switch1 0 0 0 0
setmarker remove 1 15 0 0
setmarker remove 2 15 0 0
set __ls_sugar "c2V0cmF0ZSA5OTk5OTk5OTk5OTk5OTk5OTk5OTk5OQpzZW5zb3IgZSBzd2l0Y2gxIEBlbmFibGVkCnNlbnNvciB0IEB0aGlzIEB0ZWFtCm9wIGFkZCByZSBAdGhpc3ggMC41Cm9wIHN1YiByZWJlIEB0aGlzeSAwLjUKaWZiZWdpbiBlIG5vdEVxdWFsIGZhbHNlIDU5Cm9wIGFkZCByZXJlIEB0aGlzeSA5Cm1ha2VtYXJrZXIgdGV4dCAxIHJlIHJlcmUgdHJ1ZQptYWtlbWFya2VyIHNoYXBlIDIgcmUgcmViZSB0cnVlCnNldG1hcmtlciBjb2xvciAyICVmODUwNWQgMCAwCnNldG1hcmtlciBzdHJva2UgMiAyMCAwIDAKc2V0bWFya2VyIHJhZGl1cyAyIDQwIDAgMApzZXRtYXJrZXIgZm9udFNpemUgMSA0IDAgMApzZXRtYXJrZXIgdGV4dEhlaWdodCAxIDE1MCAwIDAKc2V0bWFya2VyIGRyYXdMYXllciAyIDIxMC4wIDAgMApzZXRtYXJrZXIgZHJhd0xheWVyIDEgMjIwLjAgMCAwCnByaW50ICJbI2Y5NTE1ZV1OVUNMRUFSIE1JU1NJTEUgSU5DT01JTkciCnNldG1hcmtlciBmbHVzaFRleHQgMSA0MCAwIDAKZWZmZWN0IGJsb2NrRmFsbCByZSByZWJlIDIgJWZmYWFmZiBAcnRnLWdlbmVyYXRvcgp3YWl0IDEuNQpmb3JiZWdpbiBpIDAgMSBsZXNzVGhhbkVxIDUwMCAyNQpwbGF5c291bmQgdHJ1ZSBAc2Z4LWV4cGxvc2lvbk1pc3NpbGUgOTk5OTk5OTk5IDEgMCByZSByZWJlIGZhbHNlCnBsYXlzb3VuZCB0cnVlIEBzZngtZXhwbG9zaW9uVGl0YW4gOTk5OTk5OTk5IDEgMCByZSByZWJlIGZhbHNlCnBsYXlzb3VuZCB0cnVlIEBzZngtZXhwbG9zaW9uUmVhY3RvciA5OTk5OTk5OTkgMSAwIHJlIHJlYmUgZmFsc2UKcGxheXNvdW5kIHRydWUgQHNmeC11bml0RXhwbG9kZTMgOTk5OTk5OTk5IDEgMCByZSByZWJlIGZhbHNlCmJsb2NrZW5kCmVmZmVjdCBjcm9zc0V4cGxvc2lvbiByZSByZWJlIDEwMDAwMCAlZmZkMWFhIAplZmZlY3QgZXhwbG9zaW9uIHJlIHJlYmUgMTUgJWZmYWFmZiAKc2V0bWFya2VyIHN0cm9rZSAxIDE1IDAgMApleHBsb3Npb24gdCByZSByZWJlIDMgMTAwMDAwMDAwMDAwMDAgdHJ1ZSB0cnVlIHRydWUgZmFsc2UKZXhwbG9zaW9uIHQgcmUgcmViZSA3IDUwMDAgdHJ1ZSB0cnVlIHRydWUgZmFsc2UKZXhwbG9zaW9uIHQgcmUgcmViZSAxNSAxMDAwIHRydWUgdHJ1ZSB0cnVlIGZhbHNlCmV4cGxvc2lvbiB0IHJlIHJlYmUgMzAgNTAwIHRydWUgdHJ1ZSB0cnVlIGZhbHNlCmZvcmJlZ2luIGkgMCAxIGxlc3NUaGFuRXEgMTUgNDAKb3AgcG93IHheMiBpIDIKb3AgbXVsIGF4XjIgeF4yIDUKb3AgbXVsIGJ4IGkgNApvcCBhZGQgYXheMitieCBheF4yIGJ4Cm9wIGFkZCBheF4yK2J4K2MgYXheMitieCBjCmVmZmVjdCB0cmFpbCByZSByZWJlIGF4XjIrYngrYyAlZmZiZDUzMDcgCmJsb2NrZW5kCmZvcmJlZ2luIGUgMCAzIGxlc3NUaGFuRXEgMjAwIDU1CmZvcmJlZ2luIGUyIDAgMSBsZXNzVGhhbkVxIDc1IDU0Cm9wIHJhbmQgZGloaXIgNzIwIGIKb3AgZGl2IGRpaGlyIGRpaGlyIDIKb3Agc2luIGVnZyBkaWhpciAyCm9wIG11bCBlZ2cgZWdnIGUKb3AgYWRkIGVnZyBlZ2cgcmUKb3AgY29zIHdoaXkgZGloaXIgaQpvcCBtdWwgd2hpeSB3aGl5IGUKb3AgYWRkIHdoaXkgd2hpeSByZWJlCm9wIHN1YiB5byAyMDAgZQpvcCBtdWwgeW8geW8gNApidWxsZXQgcmVzdWx0IEByaXBwbGUgQHB5cmF0aXRlIGVnZyB3aGl5IGRpaGlyIHQgbnVsbCB5byAxIDAgZWdnIHdoaXkKYmxvY2tlbmQKYmxvY2tlbmQKY29udHJvbCBlbmFibGVkIHN3aXRjaDEgMCAwIDAgMApzZXRtYXJrZXIgcmVtb3ZlIDEgMTUgMCAwCnNldG1hcmtlciByZW1vdmUgMiAxNSAwIDAKYmxvY2tlbmQK"
""";
    }

    private static String sampleB(){
        return """
setrate 999999999999999999999999
sensor e switch1 @enabled
sensor t @this @team
op sub re @thisx 0.5
op add rebe @thisy 0.5
jump 32 equal e false
wait 1.5
set i 0
jump 10 lessThanEq i 150
jump 31 always x false
set i2 0
jump 13 lessThanEq i2 50
jump 29 always x false
op rand dir 720 2
op div dir dir 2
op rand dirac 10 2
op sub dirac dirac 5
op add dirac dir dirac
op sin ax dirac i
op mul ax ax i
op add ax ax re
op cos ay dirac dirac
op mul ay ay i
op add ay ay rebe
effect trail ax ay 15 %ffd8d829 
playsound true @sfx-windHowl 99 1 0 ax ay false
explosion t ax ay 5 10 true true false false
op add i2 i2 1
jump 11 always x false
op add i i 1
jump 8 always x false
control enabled switch1 0 0 0 0
set __ls_sugar "c2V0cmF0ZSA5OTk5OTk5OTk5OTk5OTk5OTk5OTk5OTkKc2Vuc29yIGUgc3dpdGNoMSBAZW5hYmxlZApzZW5zb3IgdCBAdGhpcyBAdGVhbQpvcCBzdWIgcmUgQHRoaXN4IDAuNQpvcCBhZGQgcmViZSBAdGhpc3kgMC41CmlmYmVnaW4gZSBub3RFcXVhbCBmYWxzZSA1Ngp3YWl0IDEuNQpmb3JiZWdpbiBpIDAgMSBsZXNzVGhhbkVxIDE1MCAyNApmb3JiZWdpbiBpMiAwIDEgbGVzc1RoYW5FcSA1MCAyMwpvcCByYW5kIGRpciA3MjAgMgpvcCBkaXYgZGlyIGRpciAyCm9wIHJhbmQgZGlyYWMgMTAgMgpvcCBzdWIgZGlyYWMgZGlyYWMgNQpvcCBhZGQgZGlyYWMgZGlyIGRpcmFjCm9wIHNpbiBheCBkaXJhYyBpCm9wIG11bCBheCBheCBpCm9wIGFkZCBheCBheCByZQpvcCBjb3MgYXkgZGlyYWMgZGlyYWMKb3AgbXVsIGF5IGF5IGkKb3AgYWRkIGF5IGF5IHJlYmUKZWZmZWN0IHRyYWlsIGF4IGF5IDE1ICVmZmQ4ZDgyOSAKcGxheXNvdW5kIHRydWUgQHNmeC13aW5kSG93bCA5OSAxIDAgYXggYXkgZmFsc2UKZXhwbG9zaW9uIHQgYXggYXkgNSAxMCB0cnVlIHRydWUgZmFsc2UgZmFsc2UKYmxvY2tlbmQKYmxvY2tlbmQKY29udHJvbCBlbmFibGVkIHN3aXRjaDEgMCAwIDAgMApibG9ja2VuZAo="
""";
    }
}
