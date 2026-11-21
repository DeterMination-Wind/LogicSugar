package mindustry.logic;

import arc.scene.ui.layout.Table;
import mindustry.logic.LExecutor.LInstruction;

/**
 * Stand-in for a debug statement owned by another mod (MDT's {@code assert}/{@code assertBounds}
 * classes), used by the headless tests that prove LogicSugar recognises debug lines by their
 * written text instead of by its own {@link SugarDebugLines.Line} class.
 *
 * <p>Deliberately independent of MDT: the production contract is only "an {@link LStatement}
 * whose {@code write()} emits a debug opcode line", and a fake keeps the tests runnable without
 * that mod installed.</p>
 */
final class ForeignDebugStatement extends LStatement{
    private final String text;

    ForeignDebugStatement(String text){
        this.text = text;
    }

    @Override
    public void build(Table table){
        // Headless: the stand-in never creates editor UI.
    }

    @Override
    public LInstruction build(LAssembler builder){
        // Inert, like LogicSugar's own passthrough line. Production never assembles this class:
        // when MDT is installed it owns the opcode and its parser produces MDT's own statement.
        return new LExecutor.NoopI();
    }

    @Override
    public LCategory category(){
        return LCategory.unknown;
    }

    @Override
    public void write(StringBuilder out){
        out.append(text);
    }
}
