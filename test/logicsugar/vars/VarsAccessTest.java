package logicsugar.vars;

import logicsugar.vars.ui.TripleTap;
import logicsugar.vars.ui.VarsAccess;
import mindustry.gen.Building;

/**
 * 变量界面的「入口侧」纯逻辑：三击状态机、快照类型 token（线格式与 {@code snapshot} 卡片一致）、
 * 以及设置项读入的运行期字段默认值。
 *
 * <p>对话框本身需要活的 scene/world，无法无头构造（见 {@code VarsUiLogicTest} 的说明）；这里只钉
 * 那些不需要画面的部分——三击状态机是最容易写错且错在「多开一次/少开一次」的地方。</p>
 */
public class VarsAccessTest{
    private static final int WINDOW = 500;

    public static void main(String[] args){
        tripleTapTriggersOnTheThirdTapOnly();
        windowExpiryRestartsTheCount();
        anotherBlockRestartsTheCount();
        disabledOrEmptyTapsNeverTrigger();
        snapshotTypeTokensStayWireStable();
        processorRowOrderStaysWireAligned();
        viewPreferencesPersist();
        defaultsAreSane();
        bundleCoversEveryKey();
        System.out.println("LogicSugar vars access self-test passed.");
    }

    private static void tripleTapTriggersOnTheThirdTapOnly(){
        TripleTap taps = new TripleTap();
        Building build = block();

        check(!taps.tap(build, 1000, WINDOW), "first tap must not trigger");
        check(!taps.tap(build, 1200, WINDOW), "second tap must not trigger");
        check(taps.tap(build, 1400, WINDOW), "third tap must trigger");
        // 触发后立刻复位：第四次点击是新一轮的第一次
        check(!taps.tap(build, 1500, WINDOW), "a fourth tap must start a new sequence");
    }

    private static void windowExpiryRestartsTheCount(){
        TripleTap taps = new TripleTap();
        Building build = block();

        check(!taps.tap(build, 1000, WINDOW), "first tap must not trigger");
        check(!taps.tap(build, 2000, WINDOW), "a tap outside the window must restart the count");
        check(!taps.tap(build, 2200, WINDOW), "second tap of the new sequence must not trigger");
        check(taps.tap(build, 2400, WINDOW), "third tap of the new sequence must trigger");
    }

    private static void anotherBlockRestartsTheCount(){
        TripleTap taps = new TripleTap();
        Building first = block(), second = block();

        check(!taps.tap(first, 1000, WINDOW), "first tap must not trigger");
        check(!taps.tap(first, 1100, WINDOW), "second tap must not trigger");
        check(!taps.tap(second, 1200, WINDOW), "tapping another block must restart the count");
        check(!taps.tap(second, 1300, WINDOW), "second tap on the other block must not trigger");
        check(taps.tap(second, 1400, WINDOW), "third tap on the other block must trigger");
    }

    private static void disabledOrEmptyTapsNeverTrigger(){
        TripleTap taps = new TripleTap();
        Building build = block();

        for(int i = 0; i < 6; i++){
            check(!taps.tap(build, 1000 + i * 100, 0), "a disabled window (0) must never trigger");
        }
        // 点空（无方块）不会攒计数，也不会把上一处方块的计数带过来
        check(!taps.tap(null, 1000, WINDOW), "tapping nothing must not trigger");
        check(!taps.tap(build, 1100, WINDOW), "a tap after tapping nothing must be the first of a sequence");
        check(!taps.tap(build, 1200, WINDOW), "second tap must not trigger");
        check(taps.tap(build, 1300, WINDOW), "third tap must trigger");
    }

    private static void snapshotTypeTokensStayWireStable(){
        // 这些 token 就是 mlog 行里写的字（snapshot 卡与解析器共用），改名字等于改存档格式
        check(SnapshotType.isolated.name().equals("isolated"), "isolated token drifted");
        check(SnapshotType.connected.name().equals("connected"), "connected token drifted");
        check(SnapshotType.recording.name().equals("recording"), "recording token drifted (upstream v0.11.2)");
        check(SnapshotType.global.name().equals("global"), "global token drifted");
        check(SnapshotType.all.length == 4, "unexpected snapshot type count: " + SnapshotType.all.length);
        // 显示名有英文兜底（bundle 缺键时也要能看）；图标来自游戏图集，无头环境下拿不到，不断言
        for(SnapshotType type : SnapshotType.all){
            check(type.display() != null && !type.display().isEmpty(), "no display name for " + type);
        }
    }

    private static void defaultsAreSane(){
        check(VarsAccess.tripleTapMillis == 500, "triple-tap default must be 500 ms: " + VarsAccess.tripleTapMillis);
        check(Snapshots.maxSnapshots == 20, "snapshot limit default must be 20: " + Snapshots.maxSnapshots);
        check(VarsOptions.significantDigits >= 1 && VarsOptions.significantDigits <= 16,
            "significant digits default out of range: " + VarsOptions.significantDigits);
        check(VarsOptions.updateFrequency >= 1, "update frequency default must be >= 1: " + VarsOptions.updateFrequency);
    }

    /**
     * {@link ProcessorVars} 的合成行序与 {@code start} 的位置是一份「隐式契约」：
     * {@code sum of rows} 与 profiler/快照靠名字对齐，且 {@code start} 之前的行不过滤、不参与
     * 「用户变量」排序。无头环境里造不出一块处理器（{@code LogicBlock} 的构造会读
     * {@code Vars.content}），所以这里扫源码把行序钉住：
     * {@code Text buffer → Time waited → Accumulator → counter → unit → ipt}，
     * 并且 {@code start = length} 必须在所有合成行之后。
     */
    private static void processorRowOrderStaysWireAligned(){
        String source = readSource("src/logicsugar/vars/ProcessorVars.java");

        int textBuffer = source.indexOf("logicsugar.vars.var.textbuffer");
        int timeWaited = source.indexOf("logicsugar.vars.var.timewaited");
        int accumulator = source.indexOf("logicsugar.vars.var.accumulator");
        int counter = source.indexOf("store(executor.counter);");
        int unit = source.indexOf("store(executor.unit);");
        int ipt = source.indexOf("store(executor.ipt);");
        int start = source.indexOf("start = length;");

        check(textBuffer >= 0 && timeWaited > textBuffer, "Text buffer 必须是第一个合成行");
        check(accumulator > timeWaited, "Accumulator 必须紧随 Time waited（上游 v0.11.2 的行序）");
        check(counter > accumulator && unit > counter && ipt > unit, "@counter/@unit/@ipt 的行序漂移了");
        check(start > ipt, "start = length 必须在所有合成行之后（首个用户变量下标）");

        // 合成行多了一行，数组容量必须同步 +5（textbuffer/timewaited/accumulator + counter/unit/ipt）
        check(source.contains("new LVar[executor.vars.length + 5"),
            "ProcessorVars 的行数组容量没给 Accumulator 留位置");
    }

    /**
     * P6：五个视图开关（hex / sorted / hide-temps / hide-links / full-precision）是持久化偏好。
     * 键名写错不会报错——读回默认值，表现为「重启后又变回默认」；所以三处必须对得上：
     * {@code VarsOptions} 定义并读写、{@code VarsDialog} 的每个显示开关经
     * {@code refreshView}/{@code updateView} 写盘、{@code VarsAccess.applySettings} 启动时读回。
     * 键前缀必须是本 mod 的（不跟上游 {@code mlogdevtools-*} 身份前缀）。
     */
    private static void viewPreferencesPersist(){
        String options = readSource("src/logicsugar/vars/VarsOptions.java");
        String dialog = readSource("src/logicsugar/vars/ui/VarsDialog.java");
        String access = readSource("src/logicsugar/vars/ui/VarsAccess.java");

        String[][] keys = {
            {"keyHex", "logicsugar.varsHex"},
            {"keySorted", "logicsugar.varsSorted"},
            {"keyHideTemps", "logicsugar.varsHideTemps"},
            {"keyHideLinks", "logicsugar.varsHideLinks"},
            {"keyFullPrecision", "logicsugar.varsFullPrecision"}
        };

        String load = logicsugar.SourceNails.methodBody(options, "public static void load(){");
        String save = logicsugar.SourceNails.methodBody(options, "public static void save(){");

        for(String[] key : keys){
            check(options.contains("public static final String " + key[0] + " = \"" + key[1] + "\";"),
                "VarsOptions 缺少键常量 " + key[0] + " = " + key[1]);
            check(load.contains(key[0]), "VarsOptions.load() 必须读回 " + key[1]);
            check(save.contains(key[0]), "VarsOptions.save() 必须写入 " + key[1]);
        }
        // 键前缀必须是本 mod 的：扫常量声明本身（不扫注释，注释里会提到上游的 mlogdevtools-*）
        java.util.regex.Matcher declared = java.util.regex.Pattern.compile(
            "public static final String (\\w+) = \"([^\"]+)\";").matcher(options);
        int count = 0;
        while(declared.find()){
            count++;
            check(declared.group(2).startsWith("logicsugar."),
                "视图偏好的键必须用本 mod 的键前缀，不能跟上游身份键: " + declared.group(2));
        }
        check(count == 5, "VarsOptions 的持久化键常量应为 5 个，实际 " + count);
        check(load.contains("Core.settings == null") && save.contains("Core.settings == null"),
            "Core.settings 为空（无头自检）时 load/save 必须静默跳过");

        // 标题栏的 hex/全位数与 Options 面板的所有开关都经由这两个入口
        for(String signature : new String[]{"private void refreshView(boolean update){", "private void updateView(boolean update){"}){
            String body = logicsugar.SourceNails.methodBody(dialog, signature);
            check(body.contains("VarsOptions.save()"), signature + " 必须写回视图偏好");
        }

        check(logicsugar.SourceNails.methodBody(access, "public static void applySettings(){").contains("VarsOptions.load()"),
            "VarsAccess.applySettings 必须读回视图偏好（启动时）");
    }

    private static Building block(){
        return new Building(){
        };
    }

    private static String readSource(String file){
        try{
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file)),
                java.nio.charset.StandardCharsets.UTF_8);
        }catch(java.io.IOException e){
            throw new AssertionError("cannot read " + file + " (working directory: "
                + System.getProperty("user.dir") + ")", e);
        }
    }


    /**
     * 代码里用到的每个 {@code logicsugar.vars.*}/{@code logicsugar.asserts.*} 键都必须在英文
     * bundle 里有条目。
     *
     * <p>为什么需要这条：文案取词一律「键存在就用键，否则退回英文 fallback」，键名写错时界面
     * 照样显示英文原文，编译与游戏内都不会报错——只有对着 bundle 逐键核对才看得出来。扫描的是
     * 取词调用的第一个字符串（{@code L10n.text}/{@code text}/{@code cardText}/{@code tr}），
     * 以 {@code .} 结尾的键是动态家族前缀（如 {@code logicsugar.vars.align.}），跳过；这些家族
     * 用枚举全量核对。</p>
     */
    private static void bundleCoversEveryKey(){
        java.util.Set<String> keys = new java.util.TreeSet<>();
        java.util.regex.Pattern call = java.util.regex.Pattern.compile(
            "(?:L10n\\.text|text|cardText|tr)\\(\\s*\"([^\"]+)\"");
        // 只认带点号的文案键：logicsugar.varsAlignment/logicsugar.assertsAreBreakpoints 这类
        // 是 Core.settings 的设置项键，不是 bundle 键
        java.util.regex.Pattern direct = java.util.regex.Pattern.compile(
            "\"(logicsugar\\.(?:vars|asserts)\\.[A-Za-z0-9_.]*)\"");
        for(String file : sourceFiles()){
            String source;
            try{
                source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file)),
                    java.nio.charset.StandardCharsets.UTF_8);
            }catch(java.io.IOException e){
                throw new AssertionError("cannot read " + file + " (working directory: "
                    + System.getProperty("user.dir") + ")", e);
            }
            // 取词调用（键可能不带 logicsugar. 前缀，由 SugarAsserts 的 text/cardText 补齐）
            java.util.regex.Matcher matcher = call.matcher(source);
            while(matcher.find()){
                String key = matcher.group(1);
                if(key.endsWith(".")) continue;                       // 动态家族前缀
                if(key.startsWith("asserts.") || key.startsWith("vars.")) key = "logicsugar." + key;
                // 取词调用的第一个参数也可能本来就是完整键或原版 @ 键（那一类不扫）
                if(!key.startsWith("logicsugar.")) continue;
                keys.add(key);
            }
            // 直接写在代码里的完整键（Core.bundle.get/format、枚举字段等）
            matcher = direct.matcher(source);
            while(matcher.find()){
                String key = matcher.group(1);
                if(!key.endsWith(".")) keys.add(key);
            }
        }

        // 动态家族：枚举名拼进键里，扫描不到，逐个补上
        for(SnapshotType type : SnapshotType.all) keys.add("logicsugar.vars.snapshottype." + type.name());
        check(keys.size() > 50, "the key scan found suspiciously few keys: " + keys.size());

        String bundle;
        try{
            bundle = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/bundles/bundle.properties")),
                java.nio.charset.StandardCharsets.UTF_8);
        }catch(java.io.IOException e){
            throw new AssertionError("cannot read assets/bundles/bundle.properties (working directory: "
                + System.getProperty("user.dir") + ")", e);
        }

        java.util.List<String> missing = new java.util.ArrayList<>();
        for(String key : keys){
            if(!java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote(key) + "\\s*=").matcher(bundle).find()){
                missing.add(key);
            }
        }
        check(missing.isEmpty(), "keys used by the code but missing from bundle.properties: " + missing);
    }

    /** 参与取词扫描的源文件（新增界面/卡片文件时一并加进来）。 */
    private static String[] sourceFiles(){
        java.util.List<String> files = new java.util.ArrayList<>();
        for(String dir : new String[]{"src/logicsugar/vars", "src/logicsugar/profile"}){
            try(java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(java.nio.file.Paths.get(dir))){
                stream.filter(p -> p.toString().endsWith(".java")).sorted()
                    .forEach(p -> files.add(p.toString().replace('\\', '/')));
            }catch(java.io.IOException e){
                throw new AssertionError("cannot walk " + dir, e);
            }
        }
        files.add("src/logicsugar/assist/AssertInstructions.java");
        files.add("src/logicsugar/LogicSugarSettings.java");
        files.add("src/mindustry/logic/SugarAsserts.java");
        return files.toArray(new String[0]);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
