package logicsugar.assist;

import arc.Core;
import arc.Events;
import arc.func.Prov;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.scene.ui.layout.Scl;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.pooling.Pools;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.game.EventType;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.logic.LExecutor;
import mindustry.ui.Fonts;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import static mindustry.Vars.tilesize;

/**
 * Indicates stopped and long-waiting logic processors directly on the map, and shows
 * runtime failure messages above the processor they occurred on. Ported from the upstream
 * MlogAssertions mod (ui/Assertions), with bundle-localized texts and settings wired into
 * LogicSugar's settings entries.
 *
 * <p>Failed-assertion reporting ({@link #setMessage}) is a public service for runtime
 * instruction classes; the vanilla instruction set never calls it, so without the
 * assertion instruction set only stopped/waiting states are reported.</p>
 *
 * <p>Performance: the whole-map scan is throttled (fractional update budget, round-robin
 * over the processor list, {@link #scanPerTick} blocks per frame at most); the wait arc
 * itself is drawn straight from the executor's live state every frame.</p>
 */
public final class ProcessorStatus{
    /** Waits shorter than this (ms) are not indicated; 0 disables wait indication. */
    public static volatile int minWaitMillis = 1000;
    /** Max blocks inspected per frame while distributing the scan. */
    public static volatile int scanPerTick = 50;
    /** < 0: never spawn warning effects; 0: once when a failure appears; n: every n seconds. */
    public static volatile int warnEffectFrequency = 0;

    // wait is a special case, recognized by comparison to this instance
    static final String WAIT = new String("W");

    // Color of the displayed text
    static final Color color = Color.coral;

    static final float layer = Layer.darkness + 1;
    static final float waitLayer = Layer.turret + 1;
    static final float textWidth = 110f;

    // Active messages
    static final ObjectMap<LogicBuild, String> blocks = new ObjectMap<>();

    // All blocks
    static final Seq<LogicBuild> allBlocks = new Seq<>();

    // Invalid blocks
    static final Seq<LogicBuild> invalidBlocks = new Seq<>();

    // The next time the effect should be run (game time)
    static double nextWarnEffect = 0;

    static int checkIndex;
    static double totalUpdates = 0;
    static boolean runWarnEffect;

    private static boolean initialized;

    private ProcessorStatus(){}

    /** Reads the stored settings into the scan fields (settings UI may not have been built). */
    public static void applySettings(){
        if(Core.settings == null) return;
        minWaitMillis = Core.settings.getInt("logicsugar.waitIndication", minWaitMillis);
        scanPerTick = Core.settings.getInt("logicsugar.processorScan", scanPerTick);
        warnEffectFrequency = Core.settings.getInt("logicsugar.warnEffect", warnEffectFrequency);
    }

    public static synchronized void init(){
        if(initialized) return;
        initialized = true;

        Events.on(EventType.ResetEvent.class, e -> {
            blocks.clear();
            allBlocks.clear();
            invalidBlocks.clear();
            nextWarnEffect = 0;
        });

        Events.on(EventType.WorldLoadEndEvent.class, e -> {
            blocks.clear();
            allBlocks.clear();
            invalidBlocks.clear();
            nextWarnEffect = 0;

            Vars.world.tiles.eachTile(tile -> {
                if(tile.build instanceof LogicBuild build && blocks.put(build, "") == null){
                    allBlocks.add(build);
                }
            });

            blocks.clear(32);
        });

        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            if(e.tile.build instanceof LogicBuild build){
                allBlocks.add(build);
            }
        });

        Events.on(EventType.ConfigEvent.class, e -> {
            if(e.tile instanceof LogicBuild build){
                reset(build);
            }
        });

        Events.run(EventType.Trigger.drawOver, () -> {
            checkBlocks();
            blocks.each(ProcessorStatus::draw);

            invalidBlocks.each(blocks::remove);
            allBlocks.removeAll(invalidBlocks);
            invalidBlocks.clear();
        });
    }

    /** Marks a processor as failed, showing {@code message} above it until it recovers. */
    public static void setMessage(LogicBuild block, Prov<String> message){
        String prev = blocks.get(block);
        if(prev == null || prev == WAIT){
            String str = message.get();
            blocks.put(block, str == null ? "<error>" : str);

            // Just this once
            if(warnEffectFrequency == 0) effect(block);
        }
    }

    /** Clears a processor's stopped/wait/failed indication. */
    public static void reset(LogicBuild block){
        blocks.remove(block);
    }

    private static void effect(LogicBuild block){
        Fx.unitCapKill.at(block.getX(), block.getY(), 10f, color);
    }

    public static void setWait(LogicBuild block){
        if(blocks.get(block) != WAIT){
            blocks.put(block, WAIT);
        }
    }

    /** Whether a wait of {@code waitSeconds} is long enough to be indicated (threshold is
     *  inclusive; a threshold of 0 disables wait indication entirely). Pure decision,
     *  extracted for the self-test. */
    static boolean isLongWait(double waitSeconds){
        return minWaitMillis > 0 && 1000 * waitSeconds >= minWaitMillis;
    }

    /** Frame budget advance for the throttled scan (ported verbatim from upstream): delta
     *  is clamped from BELOW at 1.5, so every frame delivers at least {@code 1.5 * perTick}
     *  scans (a live minimum, not a catch-up clamp) — the whole map is re-scanned on
     *  normal maps and the cap only engages on very processor-heavy ones. Sub-unit
     *  remainders still accumulate when {@code 1.5 * perTick} is fractional
     *  (e.g. 7.5/frame at perTick=5), which is what the fractional budget preserves. */
    static double advanceBudget(double previous, double delta, int perTick){
        return previous + Math.max(delta, 1.5) * perTick;
    }

    static void checkBlocks(){
        // Do not lose fractional values of updates at high FPS
        totalUpdates = advanceBudget(totalUpdates, Core.graphics.getDeltaTime(), scanPerTick);
        long updates = (long)totalUpdates;
        totalUpdates -= updates;

        if(allBlocks.size <= updates){
            allBlocks.each(ProcessorStatus::check);
        }else{
            for(int i = 0; i < updates; i++){
                if(checkIndex >= allBlocks.size) checkIndex = 0;
                check(allBlocks.get(checkIndex++));
            }
        }

        // Should spawn warning effects during this update?
        if(warnEffectFrequency > 0 && nextWarnEffect < Vars.state.tick){
            runWarnEffect = true;
            nextWarnEffect = Vars.state.tick + 60 * warnEffectFrequency;
        }else{
            runWarnEffect = false;
        }
    }

    private static void check(LogicBuild block){
        if(block.tile.build != block){
            invalidBlocks.add(block);
            return;
        }else if(block.executor == null || block.executor.counter == null){
            // Not yet ready
            reset(block);
            return;
        }

        int ix = (int)block.executor.counter.numval;
        LExecutor.LInstruction[] instructions = block.executor.instructions;

        if(ix >= 0 && ix < instructions.length){
            LExecutor.LInstruction instruction = instructions[ix];
            if(instruction instanceof LExecutor.StopI){
                setMessage(block, () -> Core.bundle.format("logicsugar.stoppedAt", ix));
                return;
            }
            if(instruction instanceof LExecutor.WaitI w && isLongWait(w.value.num())){
                setWait(block);
                return;
            }
        }

        reset(block);
    }

    private static void draw(LogicBuild block, String message){
        if(block.tile.build != block){
            invalidBlocks.add(block);
            return;
        }

        // This is a wait indication
        if(message == WAIT){
            drawWait(block);
            return;
        }

        if(runWarnEffect){
            effect(block);
        }

        float x = block.getX();
        float y = block.getY() + (block.block.size * tilesize/2f + 1.5f);

        Draw.z(layer);
        float z = Drawf.text();

        Font font = Fonts.outline;
        GlyphLayout l = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
        boolean ints = font.usesIntegerPositions();
        font.getData().setScale(1 / 4f / Scl.scl(1f));
        font.setUseIntegerPositions(false);

        l.setText(font, message, color, textWidth, Align.left, true);

        Draw.color();
        font.setColor(color);
        font.draw(message, x - l.width/2f, y + l.height, textWidth, Align.left, true);
        font.setUseIntegerPositions(ints);
        font.getData().setScale(1f);
        Draw.z(z);

        Pools.free(l);
    }

    private static void drawWait(LogicBuild block){
        int sides = 60;
        int ix = (int)block.executor.counter.numval;
        LExecutor.LInstruction[] instructions = block.executor.instructions;
        if(ix >= 0 && ix < instructions.length && instructions[ix] instanceof LExecutor.WaitI w){
            float total = (float)w.value.num();
            float current = w.curTime;
            float arc = current / total;

            float x = block.getX();
            float y = block.getY();

            Draw.z(waitLayer);
            float z = Drawf.text();
            int blockSize = block.tile.block().size;
            if(arc > 0.01){
                Draw.color(Color.white);
                Fill.arc(x, y, Scl.scl(blockSize * 2.1f - 0.5f), arc, 90 - 360f * arc, sides);
            }
            Draw.color(Color.white);
            Lines.stroke(Scl.scl((blockSize + 1) * 0.25f));
            Lines.poly(x, y, sides, Scl.scl(block.tile.block().size * 2.5f));
            Draw.z(z);
        }
    }
}
