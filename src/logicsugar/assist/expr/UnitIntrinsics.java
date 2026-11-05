package logicsugar.assist.expr;

import logicsugar.assist.expr.ExprCompiler.Line;
import logicsugar.assist.expr.ExprCompiler.Node;
import logicsugar.assist.expr.ExprCompiler.OpLine;
import logicsugar.assist.expr.ExprCompiler.SensorLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Compile-time unit classification getters.
 *
 * <p>These are expression intrinsics, not real mlog sensors: the game has no {@code @family}
 * access. Each call expands to ordinary {@code sensor}/{@code op} instructions, so the saved
 * program is fully vanilla. The argument is expected to be a <b>unit</b>; passing a unit type
 * or a building is not meaningful (the game exposes no "this is a unit type" sensor to check at
 * runtime).</p>
 *
 * <p>Family means the vanilla factory / tech-tree family, not movement:</p>
 * <pre>
 *  0 unknown
 *  1 ground   Serpulo ground factory         logic id  0..14
 *  2 air      Serpulo air factory            logic id 15..24
 *  3 naval    Serpulo naval factory          logic id 25..34
 *  8 core     alpha / beta / gamma           logic id 35..37
 *  4 tank     Erekir tank fabricator         logic id 38..42
 *  5 mech     Erekir mech fabricator         logic id 43..47
 *  6 ship     Erekir ship fabricator         logic id 48..52
 *  7 support  Erekir core support            logic id 53..55
 * </pre>
 *
 * <p>This distinction matters: Erekir's {@code elude} is a ship-family unit but grounded;
 * {@code avert}/{@code obviate}/{@code quell}/{@code disrupt} are ship-family units and fly;
 * {@code evoke}/{@code incite}/{@code emanate} are flying support units. Movement flags cannot
 * reproduce these families, so the table is by logic id (the id {@code sensor x t @id} returns
 * for a unit type), not by {@code @flying} or {@code @naval}.</p>
 *
 * <p><b>Stability.</b> Logic ids come from the game's {@code logicids.dat}, which the packer
 * extends append-only (new content gets the next free id; existing ids do not move). The table
 * is intentionally frozen to the v160 unit list: modded units and future vanilla units return
 * {@code 0} (unknown) instead of being guessed. Do not widen a range in place; that changes the
 * instruction stream of already-saved condition/carrier source and fails
 * {@code SugarCompiler.verifyRestore}. Add a new intrinsic name/version instead.</p>
 *
 * <p><b>Cost.</b> {@code unit_family} is a range ladder (about 24 instructions because it also
 * guards null/out-of-range ids), {@code unit_tier} about 14, {@code unit_planet} about 11
 * (including one {@code sensor @type} + one {@code sensor @id}). The default processor budget is
 * small ({@code @ipt}), so cache the value when it is compared more than once:
 * {@code f = unit_family(u)} / {@code t = unit_tier(u)}.</p>
 *
 * <p>Usage: {@code if unit_family(u) == @__AIR__}, {@code if unit_tier(u) == @__T5__},
 * {@code if unit_planet(u) == @__EREKIR__}.</p>
 */
public final class UnitIntrinsics implements ExprIntrinsics.Provider{
    public static final String FAMILY = "unit_family";
    public static final String TIER = "unit_tier";
    public static final String PLANET = "unit_planet";

    private static final String[] NAMES = {FAMILY, TIER, PLANET};

    @Override
    public String[] callNames(){
        return NAMES;
    }

    @Override
    public int arity(String name){
        return 1;
    }

    @Override
    public List<Line> expandCall(String name, List<Node> args, ExprIntrinsics.Ctx ctx){
        if(args == null || args.size() != 1){
            throw ctx.error(name + " expects exactly 1 argument");
        }
        String unit = ctx.compile(args.get(0));
        switch(name){
            case FAMILY:
                return family(unit, ctx);
            case TIER:
                return tier(unit, ctx);
            case PLANET:
                return planet(unit, ctx);
            default:
                return null;
        }
    }

    @Override
    public boolean isMemberBase(Node base){
        // Deliberately no member sugar: `u.tier` on a unit and `t.tier` on a unit type would need
        // different expansions and the compiler cannot tell the two apart. Use the functions.
        return false;
    }

    @Override
    public List<Line> readMember(Node base, String prop, ExprIntrinsics.Ctx ctx){
        return null;
    }

    @Override
    public List<Line> writeMember(Node base, String prop, Node value, ExprIntrinsics.Ctx ctx){
        return null;
    }

    /**
     * Family getter. The arithmetic is a branch-free range ladder:
     * <ul>
     *   <li>Serpulo (id &lt; 38): {@code s = id / 5}; {@code 1 + (s>=3) + (s>=5) + 5*(s>=7)}
     *       gives ground 1, air 2, naval 3, core 8.</li>
     *   <li>Erekir (id &gt;= 38): {@code 4 + (id - 38) / 5} gives tank 4, mech 5, ship 6,
     *       support 7.</li>
     *   <li>Unknown (modded id -1, null unit, id &gt; 55) is forced to 0.</li>
     * </ul>
     */
    private static List<Line> family(String unit, ExprIntrinsics.Ctx ctx){
        List<Line> out = new ArrayList<>();
        String type = ctx.temp();
        out.add(new SensorLine(type, unit, "@type"));
        String id = ctx.temp();
        out.add(new SensorLine(id, type, "@id"));

        String s = ctx.temp();
        op(out, "idiv", s, id, "5");
        String ge3 = ctx.temp();
        op(out, "greaterThanEq", ge3, s, "3");
        String ge5 = ctx.temp();
        op(out, "greaterThanEq", ge5, s, "5");
        String ge7 = ctx.temp();
        op(out, "greaterThanEq", ge7, s, "7");

        String small = ctx.temp();
        op(out, "add", small, "1", ge3);
        op(out, "add", small, small, ge5);
        String five = ctx.temp();
        op(out, "mul", five, ge7, "5");
        op(out, "add", small, small, five);

        String e = ctx.temp();
        op(out, "sub", e, id, "38");
        op(out, "idiv", e, e, "5");
        String big = ctx.temp();
        op(out, "add", big, "4", e);

        String erekir = ctx.temp();
        op(out, "greaterThanEq", erekir, id, "38");
        String diff = ctx.temp();
        op(out, "sub", diff, big, small);
        op(out, "mul", diff, diff, erekir);
        String family = ctx.temp();
        op(out, "add", family, small, diff);

        // Unknown guards: modded id -1, null unit (id object -> num 0), future id > 55.
        String nonneg = ctx.temp();
        op(out, "greaterThanEq", nonneg, id, "0");
        String upper = ctx.temp();
        op(out, "lessThanEq", upper, id, "55");
        String valid = ctx.temp();
        op(out, "and", valid, nonneg, upper);
        String isNull = ctx.temp();
        op(out, "strictEqual", isNull, id, "null");
        String nonNull = ctx.temp();
        op(out, "sub", nonNull, "1", isNull);
        String guarded = ctx.temp();
        op(out, "mul", guarded, family, valid);
        String result = ctx.temp();
        op(out, "mul", result, guarded, nonNull);
        return out;
    }

    /**
     * Tier getter: Serpulo {@code id % 5 + 1}, Erekir {@code (id - 38) % 5 + 1}; 0 for
     * unknown. The {@code 0 <= id <= 55} and null guards keep modded/future/null units at 0
     * (without the non-negative check, another negative id would produce a negative tier).
     */
    private static List<Line> tier(String unit, ExprIntrinsics.Ctx ctx){
        List<Line> out = new ArrayList<>();
        String type = ctx.temp();
        out.add(new SensorLine(type, unit, "@type"));
        String id = ctx.temp();
        out.add(new SensorLine(id, type, "@id"));

        String isErekir = ctx.temp();
        op(out, "greaterThanEq", isErekir, id, "38");
        String offset = ctx.temp();
        op(out, "mul", offset, isErekir, "38");
        String base = ctx.temp();
        op(out, "sub", base, id, offset);
        op(out, "mod", base, base, "5");
        String tier = ctx.temp();
        op(out, "add", tier, base, "1");

        String isNull = ctx.temp();
        op(out, "strictEqual", isNull, id, "null");
        String nonNull = ctx.temp();
        op(out, "sub", nonNull, "1", isNull);
        String nonneg = ctx.temp();
        op(out, "greaterThanEq", nonneg, id, "0");
        String upper = ctx.temp();
        op(out, "lessThanEq", upper, id, "55");
        String valid = ctx.temp();
        op(out, "and", valid, nonneg, upper);
        String guarded = ctx.temp();
        op(out, "mul", guarded, tier, nonNull);
        String result = ctx.temp();
        op(out, "mul", result, guarded, valid);
        return out;
    }

    /**
     * Planet getter: 1 Serpulo (0..37), 2 Erekir (38..55), 0 unknown. The explicit
     * {@code id >= 0} check also rejects modded units (logic id -1); the null check rejects a
     * missing unit, whose id reads as the numeric value 0.
     */
    private static List<Line> planet(String unit, ExprIntrinsics.Ctx ctx){
        List<Line> out = new ArrayList<>();
        String type = ctx.temp();
        out.add(new SensorLine(type, unit, "@type"));
        String id = ctx.temp();
        out.add(new SensorLine(id, type, "@id"));

        String nonneg = ctx.temp();
        op(out, "greaterThanEq", nonneg, id, "0");
        String upper = ctx.temp();
        op(out, "lessThanEq", upper, id, "55");
        String valid = ctx.temp();
        op(out, "and", valid, nonneg, upper);
        String erekir = ctx.temp();
        op(out, "greaterThanEq", erekir, id, "38");
        String planet = ctx.temp();
        op(out, "add", planet, erekir, "1");
        String inRange = ctx.temp();
        op(out, "mul", inRange, planet, valid);
        String isNull = ctx.temp();
        op(out, "strictEqual", isNull, id, "null");
        String nonNull = ctx.temp();
        op(out, "sub", nonNull, "1", isNull);
        String result = ctx.temp();
        op(out, "mul", result, inRange, nonNull);
        return out;
    }

    private static void op(List<Line> out, String op, String dest, String a, String b){
        out.add(new OpLine(op, dest, a, b));
    }

    // ===== table helpers (tests + documentation; the expansions above must stay in sync) =====

    /** Expected family for a vanilla logic id; 0 for unknown/modded/out-of-range. */
    static int familyOfId(int id){
        if(id < 0 || id > 55) return 0;
        if(id <= 14) return 1;   // ground
        if(id <= 24) return 2;   // air
        if(id <= 34) return 3;   // naval
        if(id <= 37) return 8;   // core
        if(id <= 42) return 4;   // tank
        if(id <= 47) return 5;   // mech
        if(id <= 52) return 6;   // ship
        return 7;                // support
    }

    /** Expected tier for a vanilla logic id; 0 for unknown/modded/out-of-range. */
    static int tierOfId(int id){
        if(id < 0 || id > 55) return 0;
        int base = id >= 38 ? id - 38 : id;
        return base % 5 + 1;
    }

    /** Expected planet for a vanilla logic id; 0 for unknown/modded/out-of-range. */
    static int planetOfId(int id){
        if(id < 0 || id > 55) return 0;
        return id < 38 ? 1 : 2;
    }
}
