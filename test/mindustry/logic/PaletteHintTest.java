package mindustry.logic;

import arc.Core;
import arc.func.Prov;
import arc.struct.ObjectMap;
import logicsugar.LogicSugarMod;
import logicsugar.SourceNails;
import mindustry.gen.LogicIO;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * 「添加积木」调色板的悬浮提示键（issue #24）。
 *
 * <p>用户报告：装了 LogicSugar 后，原版积木（输入&输出 / 控制方块 / 操作 / 控制顺序 / 控制单位）
 * 悬停不再显示任何提示，糖积木却正常。原因就在这条键规则上 —— 原版把提示键写成
 * {@code "lst." + statementKey()}（没有该访问器的老客户端是 {@code "lst." + name()}），而让
 * {@code statementKey()} 改走反射的那次改动把 {@code "lst."} 前缀丢了：每个原版积木都去查
 * {@code sensor} 而不是 {@code lst.sensor}，查不到就静默不显示。糖积木的键
 * （{@code logicsugar.lst.<typeName>}）走的是另一条分支，根本用不上这个回退 —— 于是失声的只有
 * 原版积木，正是报告描述的样子（截图里悬停 Get Link 没有提示，悬停 While Begin 却有）。</p>
 *
 * <p>自检钉住三件事：① 键规则本身（v160 的 statementKey、无该访问器的老客户端用 name、糖积木
 * 不通吃原版命名空间）；② 真实 bundle + 真实调色板注册表下，每个可见原版积木都能解析出游戏
 * {@code lst.*} 里真实存在的条目，糖积木只取自己的命名空间；③ 源码钉子：调色板必须走这条规则，
 * 不许再自己拼键。</p>
 */
public final class PaletteHintTest{
    private PaletteHintTest(){
    }

    public static void main(String[] args) throws Exception{
        keyRule();
        realPalette();
        paletteWiring();

        System.out.println("LogicSugar palette hint self-test passed.");
    }

    // ===== 键规则 =====

    /**
     * 用一批受控条目单独跑键规则：每个分支都刻意同时放进「不该被取的那条」，让错取也变红，
     * 而不只是「取不到」。
     */
    private static void keyRule(){
        bundle("sensor", "裸 statementKey()：原版要加 \"lst.\" 前缀才查得到");
        bundle("lst.sensor", "Get data from a building or unit.");
        bundle("lst.getlink", "Get a processor link by index. Starts at 0.");
        bundle("lst.set", "Set a variable.");
        bundle("lst.unknown", "Unrelated vanilla statement.");
        bundle("logicsugar.lst.uset", "Unordered set declaration card.");

        // v160 系：原版拼的是 "lst." + statementKey()。裸键就在这个 bundle 里，但绝不能被取到。
        checkEquals("lst.sensor", SugarLogicDialog.paletteHintKey("Sensor", "sensor", "Sensor", false),
            "v160 系原版提示键是 \"lst.\" + statementKey()");
        // 没有 statementKey() 的老客户端（v159 / Neon 的原版 classpath）：原版用 name()，空格与大小写由查表归一。
        checkEquals("lst.getlink", SugarLogicDialog.paletteHintKey("GetLink", null, "Get Link", false),
            "无 statementKey() 的客户端必须回落到 \"lst.\" + name()");
        // 糖积木用自己的键，就算原版恰好有同名语句（Set 声明卡 vs 原版 lst.set）也不许借用。
        checkEquals("logicsugar.lst.uset", SugarLogicDialog.paletteHintKey("USet", "uset", "Set", true),
            "Set 声明卡说的必须是自己，不是原版 lst.set");
        // 自己的译文不在时宁可没有提示，也不能显示一张无关原版积木的说明。
        checkEquals(null, SugarLogicDialog.paletteHintKey("Unknown", null, "Unknown", true),
            "糖积木没有自己的键时必须没有提示");
        // 什么都没有：没有提示（原行为保持不变）。
        checkEquals(null, SugarLogicDialog.paletteHintKey("NoSuch", "nosuch", "No Such", false),
            "两侧都查不到时不得编造提示");
        checkEquals(null, SugarLogicDialog.paletteHintKey("NoSuch", null, "No Such", false),
            "老客户端上查不到时同样没有提示");
    }

    // ===== 真实 bundle + 真实调色板注册表 =====

    /**
     * 用游戏自己的 bundle 与模组三份 bundle 解析真实注册表里的每一条：原版积木一条都不能丢提示
     * （issue #24 的完整回归面，而不是只测截图里的 Sensor/Get Link），糖积木则只能落在自己的
     * 命名空间里。
     */
    private static void realPalette() throws IOException{
        Set<String> gameKeys = loadGameBundle();
        for(String key : gameKeys) bundle(key, "game");
        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties properties = new Properties();
            try(Reader reader = Files.newBufferedReader(
                SourceNails.root().toPath().resolve("assets/bundles").resolve(name), StandardCharsets.UTF_8)){
                properties.load(reader);
            }
            properties.forEach((key, value) -> bundle((String)key, (String)value));
        }

        LogicSugarMod.registerStatements();

        int vanilla = 0, modCards = 0, keyless = 0;
        for(Prov<LStatement> prov : LogicIO.allStatements){
            LStatement example = prov.get();
            if(example instanceof LStatements.InvalidStatement || example.hidden()) continue;

            String key = SugarLogicDialog.paletteHintKey(example);
            if(example instanceof SugarStatements.SugarStatement){
                modCards++;
                String own = "logicsugar.lst." + example.typeName().toLowerCase(Locale.ROOT);
                checkEquals(bundle().containsKey(own) ? own : null, key,
                    "糖积木只能取自己命名空间的键："
                        + example.getClass().getSimpleName() + " / " + example.typeName());
                if(key == null) keyless++;
            }else{
                vanilla++;
                check(key != null, "原版积木在调色板里必须仍有提示："
                    + example.getClass().getSimpleName() + "（issue #24 的回归面）");
                check(gameKeys.contains(key), "原版积木的提示键必须是游戏自己的条目："
                    + example.getClass().getSimpleName() + " -> " + key);
            }
        }

        // 循环没真跑起来的护栏（注册表被别人清空、或原版积木被误判成糖积木时立刻变红）
        check(vanilla >= 50, "调色板必须仍然列出全部原版积木，只走到 " + vanilla + " 条");
        check(modCards >= 50, "调色板必须仍然列出糖积木，只走到 " + modCards + " 条");

        // 尚未写译文的糖积木（当前 9 张：单位四卡、Mem、Assert 条件卡与 v0.11.3 的 snapshot/
        // profile/restart）今天也不显示提示：这里是既有事实的度量，不是许可 —— 数量变大说明
        // 新卡漏了 logicsugar.lst.<typeName>，该在 bundle 里补上。
        System.out.println("palette: " + vanilla + " vanilla + " + modCards + " mod cards, "
            + keyless + " mod cards without their own hint key");
    }

    /**
     * 游戏自己的 bundle（桌面 jar 根下的 {@code bundles/bundle.properties}）。提示键解析的就是这些
     * 条目，所以必须是真实文本而不是测试拼出来的替身；jar 里没有它说明 classpath 上不是游戏 jar
     * （例如 {@code -PmindustryJar} 指错），此时用例的结论没有意义，直接报错。
     */
    private static Set<String> loadGameBundle() throws IOException{
        InputStream stream = PaletteHintTest.class.getClassLoader().getResourceAsStream("bundles/bundle.properties");
        if(stream == null){
            throw new AssertionError("test classpath 上找不到游戏的 bundles/bundle.properties"
                + "（检查 -PmindustryJar / mindustryJar 是否指向桌面游戏 jar）");
        }
        try(Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)){
            Properties properties = new Properties();
            properties.load(reader);
            Set<String> keys = new LinkedHashSet<>(properties.stringPropertyNames());
            check(keys.size() > 3000, "游戏 bundle 条目太少（" + keys.size() + "），不像游戏自己的 bundle");
            return keys;
        }
    }

    // ===== 接线钉子 =====

    /** 调色板必须走这条规则：issue #24 就是调色板自己拼键拼错的，别让第二条拼键路径再长出来。 */
    private static void paletteWiring() throws IOException{
        String dialog = SourceNails.readSource("src/mindustry/logic/SugarLogicDialog.java");
        String palette = SourceNails.methodBody(dialog, "void showAddDialog(int position)");
        check(palette.contains("paletteHintKey(example)"),
            "调色板按钮的提示必须走 paletteHintKey()，不要自己拼键");
        check(!palette.contains("\"lst.\" +"),
            "调色板不得自己拼原版提示键：issue #24 正是这样丢掉 \"lst.\" 前缀的");
        check(!dialog.contains("statementBundleKey"),
            "丢掉 \"lst.\" 前缀的 statementBundleKey() 必须保持删除");
    }

    // ===== 工具 =====

    /** 自检里 {@code Core.bundle} 是空的 {@code createEmptyBundle()}，条目表可以直接写。 */
    private static void bundle(String key, String text){
        bundle().put(key, text);
    }

    private static ObjectMap<String, String> bundle(){
        check(Core.bundle != null, "无头自检里 Core.bundle 必须存在（Arc 的静态空 bundle）");
        var properties = Core.bundle.getProperties();
        check(properties != null, "Core.bundle 必须提供可写的条目表");
        return properties;
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static void checkEquals(String expected, String actual, String message){
        if(expected == null ? actual != null : !expected.equals(actual)){
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
