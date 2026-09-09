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
import arc.math.geom.Rect;
import arc.scene.ui.layout.Scl;
import arc.struct.FloatSeq;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.pooling.Pools;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.core.GameState;
import mindustry.game.EventType;
import mindustry.gen.Groups;
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
 * itself is drawn straight from the executor's live state every frame, and both the wait
 * arc and the failure text are culled against the camera viewport.</p>
 *
 * <p>Breakpoints (ported from upstream v0.8.2) pause the game at the instruction, center
 * the camera on the processor, optionally detach the camera, and freeze every processor's
 * accumulator for the remainder of the frame. Failed assertions can be routed through the
 * same path with the {@code assertsAreBreakpoints} setting.</p>
 */
public final class ProcessorStatus{
    /** Slider steps for the processor scan rate (index -> scans per frame), ported from
     *  upstream v0.8.2. The stored setting is the index, not the value. */
    public static final int[] UPDATES_PER_TICK = {1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    /** Waits shorter than this (ms) are not indicated; 0 disables wait indication. */
    public static volatile int minWaitMillis = 1000;
    /** Max blocks inspected per frame while distributing the scan. */
    public static volatile int scanPerTick = 50;
    /** < 0: never spawn warning effects; 0: once when a failure appears; n: every n seconds. */
    public static volatile int warnEffectFrequency = 0;
    /** Breakpoint instructions do nothing when set. */
    public static volatile boolean disableBreakpoints = false;
    /** Failed assertions pause the game at the failing instruction instead of looping. */
    public static volatile boolean assertsAreBreakpoints = false;
    /** Center the camera on the processor and temporarily detach it when a breakpoint hits. */
    public static volatile boolean detachCameraOnBreakpoint = true;

    // wait is a special case, recognized by comparison to this instance
    static final String WAIT = new String("W");

    // Color of the displayed text
    static final Color color = Color.coral;

    static final float layer = Layer.darkness + 1;
    static final float waitLayer = Layer.turret + 1;
    static final float textWidth = 110f;

    // Camera bounds used to skip processors that cannot be seen
    static final Rect wideBounds = new Rect();
    static final Rect narrowBounds = new Rect();
    static final Rect hitbox = new Rect();

    // Active messages
    static final ObjectMap<LogicBuild, String> blocks = new ObjectMap<>();

    // All blocks
    static final Seq<LogicBuild> allBlocks = new Seq<>();

    // Invalid blocks
    static final Seq<LogicBuild> invalidBlocks = new Seq<>();

    // The breakpoint context: the paused processor's message is drawn even though the
    // overlay scan is suspended while the game is paused
    static LogicBuild breakpointProc;
    static String breakpointMessage;
    static final FloatSeq accumulators = new FloatSeq();
    static final Seq<LogicBuild> accumulatorBlocks = new Seq<>();

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
        scanPerTick = updatesPerTick(scanStep());
        warnEffectFrequency = Core.settings.getInt("logicsugar.warnEffect", warnEffectFrequency);
        disableBreakpoints = Core.settings.getBool("logicsugar.disableBreakpoints", false);
        assertsAreBreakpoints = Core.settings.getBool("logicsugar.assertsAreBreakpoints", false);
        detachCameraOnBreakpoint = Core.settings.getBool("logicsugar.detachCameraOnBreakpoint", true);
    }

    /** The scan slider stores a step index. Pre-v0.8.2 builds stored the raw per-frame
     *  count under the same key, so a one-time migration rewrites it to the closest step
     *  (a stored 10 must become step 2, not step 10 = 5000/frame). */
    static int scanStep(){
        if(!Core.settings.getBool("logicsugar.processorScanMigrated", false)){
            int raw = Core.settings.getInt("logicsugar.processorScan", 50);
            Core.settings.put("logicsugar.processorScan", closestStep(raw));
            Core.settings.put("logicsugar.processorScanMigrated", true);
        }
        return Core.settings.getInt("logicsugar.processorScan", 4);
    }

    /** Index of the step whose value is closest to {@code raw}. */
    static int closestStep(int raw){
        int best = 0;
        for(int i = 1; i < UPDATES_PER_TICK.length; i++){
            if(Math.abs(UPDATES_PER_TICK[i] - raw) < Math.abs(UPDATES_PER_TICK[best] - raw)) best = i;
        }
        return best;
    }

    /** Slider index -> scans per frame. Out-of-range values defensively fall back to the
     *  closest lower step (normalized settings never hit this path). */
    public static int updatesPerTick(int index){
        if(index >= 0 && index < UPDATES_PER_TICK.length) return UPDATES_PER_TICK[index];
        for(int i = UPDATES_PER_TICK.length - 1; i >= 0; i--){
            if(UPDATES_PER_TICK[i] <= index) return UPDATES_PER_TICK[i];
        }
        return UPDATES_PER_TICK[0];
    }

    public static synchronized void init(){
        if(initialized) return;
        initialized = true;

        Events.on(EventType.ResetEvent.class, e -> {
            blocks.clear();
            allBlocks.clear();
            invalidBlocks.clear();
            breakpointProc = null;
            nextWarnEffect = 0;
        });

        Events.on(EventType.WorldLoadEndEvent.class, e -> {
            blocks.clear();
            allBlocks.clear();
            invalidBlocks.clear();
            nextWarnEffect = 0;

            // Groups.build is the live building list: no full-map tile sweep needed
            Groups.build.each(b -> {
                if(b instanceof LogicBuild build && blocks.put(build, "") == null){
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

        // Leaving the paused state ends the breakpoint context
        Events.on(EventType.StateChangeEvent.class, e -> {
            if(e.from == GameState.State.paused){
                breakpointProc = null;
                reattachCamera();
            }
        });

        Events.run(EventType.Trigger.drawOver, () -> {
            checkBlocks();

            Core.camera.bounds(narrowBounds);
            wideBounds.set(narrowBounds);
            narrowBounds.grow(tilesize * 2f);
            wideBounds.grow(tilesize * 10f);

            blocks.each(ProcessorStatus::draw);
            if(breakpointProc != null){
                draw(breakpointProc, breakpointMessage);
            }

            invalidBlocks.each(blocks::remove);
            allBlocks.removeAll(invalidBlocks);
            invalidBlocks.clear();
        });

        // Restore the camera if the game was closed while paused at a breakpoint
        reattachCamera();
    }

    /** Pauses the game at a breakpoint: centers the camera on {@code processor}, freezes
     *  every processor's accumulator for this frame (restored right after the update), and
     *  keeps {@code message} drawn above the processor while the game is paused.
     *
     * <p>Port of upstream MlogAssertions v0.8.2, with one correction: the vanilla
     *  {@code detach-camera} setting is restored to its previous value instead of always
     *  being cleared, so a user who runs with the camera detached keeps that preference.</p> */
    public static void breakpoint(LogicBuild processor, String message){
        if(disableBreakpoints) return;

        Vars.state.set(GameState.State.paused);

        if(detachCameraOnBreakpoint){
            Core.settings.put("logicsugar.breakpointCameraSaved", true);
            Core.settings.put("logicsugar.breakpointCameraPrevious", Core.settings.getBool("detach-camera", false));
            Core.settings.put("detach-camera", true);
        }
        Core.camera.position.set(processor.getX(), processor.getY());

        // Stop every processor for the rest of this frame...
        accumulators.clear();
        accumulatorBlocks.clear();
        allBlocks.each(b -> {
            accumulatorBlocks.add(b);
            accumulators.add(b.accumulator);
            b.accumulator = 0;
        });

        // ...and give their accumulated time back right after the update
        Core.app.post(() -> {
            for(int i = 0; i < accumulators.size && i < accumulatorBlocks.size; i++){
                accumulatorBlocks.get(i).accumulator += accumulators.get(i);
            }
        });

        breakpointProc = processor;
        breakpointMessage = message;
        blocks.remove(processor);
    }

    /** Restores the vanilla {@code detach-camera} value saved when the breakpoint hit. */
    private static void reattachCamera(){
        if(Core.settings.getBool("logicsugar.breakpointCameraSaved", false)){
            Core.settings.put("detach-camera", Core.settings.getBool("logicsugar.breakpointCameraPrevious", false));
            Core.settings.put("logicsugar.breakpointCameraSaved", false);
        }
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

    /** Frame budget advance for the throttled scan (ported from upstream v0.8.2): the
     *  budget scales with frame time ({@code delta * 60} = 1 at 60 FPS) so the scan rate
     *  is frame-rate independent, and is capped at 5x per frame so a stutter (or a very
     *  low FPS) cannot force a full-map scan burst on top of the lag. Sub-unit remainders
     *  still accumulate, which keeps fractional per-frame rates from losing scans. */
    static double advanceBudget(double previous, double delta, int perTick){
        return previous + Math.min(delta * 60, 5) * perTick;
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
            if(instruction instanceof AssertInstructions.AssertInstruction){
                // assertion instructions own their message lifecycle; a scan that saw
                // "not a stop/wait" would wipe the failure message every frame
                return;
            }
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

        // No drawing for processors outside the (grown) viewport
        block.hitbox(hitbox);
        if(!wideBounds.overlaps(hitbox)) return;

        // This is a wait indication
        if(message == WAIT){
            if(narrowBounds.overlaps(hitbox)){
                drawWait(block);
            }
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
