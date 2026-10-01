package logicsugar.vars.ui;

import arc.Core;
import arc.math.Interp;
import arc.scene.Group;
import arc.scene.actions.Actions;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Log;
import logicsugar.assist.L10n;
import logicsugar.vars.Snapshots;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.input.InputHandler;
import mindustry.ui.Styles;
import mindustry.ui.fragments.BlockConfigFragment;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MemoryBlock.MemoryBuild;

import java.lang.reflect.Field;

/**
 * 方块配置面板上的变量/快照入口（上游 MlogAssertions 的 {@code BuildConfiguration}）。
 *
 * <p>做法：把 {@code InputHandler.config}（原版 {@link BlockConfigFragment}）换成一个包装了原
 * 实现的子类，在 {@link BlockConfigFragment#showConfig(Building)} 里给内存块 / 处理器 / 任意
 * 方块接上「快照、变量界面」按钮。原版这两个类的私有字段（{@code InputHandler.config}、
 * {@code BlockConfigFragment.table/selected}）只能反射访问——跨类加载器规则见 AGENTS.md。</p>
 *
 * <p><b>与 MindustryX 共存</b>：MindustryX 自己给内存块（内存网格）与处理器（编辑/信息/重置链接/
 * 取代码）写了 {@code buildConfiguration}，上游那份「替换式」面板会把它们整块顶掉。所以这里探测
 * 到 fork 自带逻辑工具时<b>先调一次 {@code buildConfiguration} 再追加</b>本 mod 的按钮（处理器
 * 上也不再重复画编辑铅笔）；原版客户端则按上游做法自建按钮（原版处理器的 {@code
 * buildConfiguration} 只有一个编辑铅笔，重复调用会出现两个）。</p>
 *
 * <p>另外：v160 起内存块默认不可配置（{@code configurable == false}），必须先打开它，否则配置
 * 面板根本不会出现。</p>
 */
public final class BlockConfigAccess{
    private static Field configField;
    private static Field tableField;
    private static Field selectedField;

    /** fork 是否自带逻辑工具（MindustryX 的 {@code LogicSupport}）；null = 尚未探测。 */
    private static Boolean forkLogicTools;

    private BlockConfigAccess(){
    }

    public static void init(){
        try{
            configField = InputHandler.class.getDeclaredField("config");
            configField.setAccessible(true);
            tableField = BlockConfigFragment.class.getDeclaredField("table");
            tableField.setAccessible(true);
            selectedField = BlockConfigFragment.class.getDeclaredField("selected");
            selectedField.setAccessible(true);
        }catch(ReflectiveOperationException e){
            // 面板入口是可选功能：接不上就照旧用原版面板，绝不能因此让 mod 初始化失败
            Log.warn("LogicSugar: cannot access the block config fragment fields, the vars/snapshot block buttons are disabled: @", e);
            return;
        }

        try{
            Object current = configField.get(Vars.control.input);
            if(current instanceof BlockConfigFragment delegate && !(current instanceof VarsConfigFragment)){
                configField.set(Vars.control.input, new VarsConfigFragment(delegate));
            }
            // 内存块在 v160 默认不可配置：先让它可配置，菜单按钮才有地方出现
            Vars.content.blocks().each(b -> b instanceof MemoryBlock, b -> b.configurable = true);
        }catch(Throwable t){
            Log.warn("LogicSugar: failed to install the vars/snapshot block buttons: @", t);
        }
    }

    /** 探测 fork 是否自带了逻辑/内存面板（MindustryX 的 {@code LogicSupport}）。 */
    static boolean forkLogicTools(){
        if(forkLogicTools == null){
            boolean found;
            try{
                Class.forName("mindustryX.features.ui.LogicSupport");
                found = true;
            }catch(Throwable ignored){
                found = false;
            }
            forkLogicTools = found;
        }
        return forkLogicTools;
    }

    /** 这些方块的面板由本类接管（其余block/建筑的配置面板原样透传）。 */
    private static boolean takeOver(Building build){
        if(build instanceof MemoryBuild) return true;
        return build instanceof LogicBuild logic && logic.executor != null && logic.executor.vars.length > 0;
    }

    /** 追加按钮。{@code fork} 为真时调用者已经把方块自己的面板画好了。 */
    private static void buttons(Table table, Building build, boolean fork){
        if(build instanceof MemoryBuild memory){
            if(Snapshots.maxSnapshots > 0){
                table.button(Icon.box, Styles.cleari, () -> createSnapshot(memory)).size(40f);
            }
            table.button(Icon.menu, Styles.cleari, () -> openVars(memory)).size(40f);
        }else if(build instanceof LogicBuild logic){
            if(Snapshots.maxSnapshots > 0){
                table.button(Icon.box, Styles.cleari, () -> createSnapshot(logic)).size(40f);
            }
            // fork 的处理器的编辑按钮由它自己的工具条提供，不重复画
            if(!fork) table.button(Icon.pencil, Styles.cleari, logic::showEditDialog).size(40f);
            table.button(Icon.menu, Styles.cleari, () -> openVars(logic)).size(40f);
        }else{
            table.button(Icon.zoom, Styles.cleari, () -> openVars(build)).size(40f);
        }
    }

    private static void createSnapshot(Building build){
        Snapshots.create(build, L10n.text("logicsugar.vars.usersnapshot", "User snapshot"));
    }

    private static void openVars(Building build){
        new VarsDialog(build).show();
    }

    /** 包装原版面板：只在内存块/处理器的面板上接管绘制，其余全部转发。 */
    private static class VarsConfigFragment extends BlockConfigFragment{
        private final BlockConfigFragment delegate;
        private final Table table;

        VarsConfigFragment(BlockConfigFragment delegate) throws ReflectiveOperationException{
            this.delegate = delegate;
            this.table = (Table)tableField.get(delegate);
        }

        private Building selected(){
            try{
                return (Building)selectedField.get(delegate);
            }catch(IllegalAccessException e){
                Log.err("LogicSugar: cannot read the selected block of the config fragment", e);
                return null;
            }
        }

        @Override
        public void showConfig(Building build){
            if(!takeOver(build)){
                delegate.showConfig(build);
                return;
            }

            try{
                Building previous = selected();
                if(previous != null && previous != build) previous.onConfigureClosed();
                if(!build.configTapped()) return;

                selectedField.set(delegate, build);
                table.visible = true;
                table.clear();
                table.background(null);

                boolean fork = forkLogicTools();
                if(fork){
                    // MindustryX 自带面板：追加而非替换（见类注释）
                    build.buildConfiguration(table);
                }
                buttons(table, build, fork);

                table.pack();
                table.setTransform(true);
                table.actions(Actions.scaleTo(0f, 1f), Actions.visible(true),
                    Actions.scaleTo(1f, 1f, 0.07f, Interp.pow3Out));

                table.update(() -> {
                    Building selected = selected();
                    if(selected != null && selected.shouldHideConfigure(Vars.player)){
                        hideConfig();
                        return;
                    }

                    table.setOrigin(Align.center);
                    if(selected == null || selected.block == Blocks.air || !selected.isValid()){
                        hideConfig();
                    }else{
                        selected.updateTableAlign(table);
                    }
                });
            }catch(IllegalAccessException e){
                Log.err("LogicSugar: cannot write the selected block of the config fragment", e);
            }
        }

        @Override
        public void build(Group parent){
            delegate.build(parent);
        }

        @Override
        public void forceHide(){
            delegate.forceHide();
        }

        @Override
        public boolean isShown(){
            return delegate.isShown();
        }

        @Override
        public Building getSelected(){
            return delegate.getSelected();
        }

        @Override
        public boolean hasConfigMouse(){
            return delegate.hasConfigMouse();
        }

        @Override
        public void hideConfig(){
            delegate.hideConfig();
        }
    }
}
