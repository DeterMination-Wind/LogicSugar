package lab;

import arc.Core;
import arc.files.Fi;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.struct.StringMap;
import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.io.SaveOptions;
import mindustry.logic.GlobalVars;
import mindustry.logic.SugarCompiler;
import mindustry.maps.Map;
import mindustry.net.Net;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.logic.LogicBlock.LogicLink;
import mindustry.world.blocks.logic.MessageBlock;
import mindustry.world.blocks.logic.MessageBlock.MessageBuild;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 生成 LogicSugar 功能展厅地图：100x100、5x5 共 25 个处理器展台，每个展台演示一个
 * LogicSugar 辅助功能，旁边一块信息板写说明、一块输出板显示程序的运行结果。
 *
 * <p>产物里的程序是「原版 mlog + __ls_sugar 载体」：原版客户端能解析、能运行，装了
 * LogicSugar 的客户端打开编辑器看到的是积木卡片。唯一的例外是断言展台（调试构建，
 * emit 模式），信息板上写明了后果。</p>
 *
 * <pre>
 *   ./build.ps1                      # 编译 + 生成地图
 *   java -cp ... lab.Lab --check     # 只编译自检，不出地图
 *   java -cp ... lab.Lab --dump for  # 打印某个展台的糖码 ⇒ 画布文本 ⇒ 产物
 * </pre>
 */
public final class Lab{
    /** 地图尺寸：100x100（用户要求的方阵尺寸）。 */
    static final int W = 100, H = 100;
    /** 展台方阵：5 行 x 5 列；格 18x17，格间 1 格走廊。 */
    static final int CELL_W = 18, CELL_H = 17, GAP = 1;
    static final int GRID_X = 3, GRID_Y = 2;
    /** 格内固定摆件：处理器 2x2、信息板、输出板。 */
    static final int PROC_DX = 2, PROC_DY = 13;
    static final int INFO_DX = 7, INFO_DY = 15;
    static final int BOARD_DX = 7, BOARD_DY = 13;
    /** 顶部标题条：large-logic-display 6x6 + 一块处理器。
     *  多格方块按「锚点 - (size-1)/2」铺开（Tile.setBlock 的 offset），所以 6x6 的锚点写在中心：
     *  (49,93) 实际覆盖 x 47..52 / y 91..96，正好是标题条。 */
    static final int TITLE_DISPLAY_X = 49, TITLE_DISPLAY_Y = 93;
    static final int TITLE_PROC_X = 57, TITLE_PROC_Y = 92;

    static final String MAP_NAME = "LogicSugar 功能展厅";
    static final String MAP_AUTHOR = "LogicSugar";
    static final String MAP_DESCRIPTION =
        "25 个处理器展台，每个演示一个 LogicSugar 功能（控制流 / 表达式 / 函数 / 数据结构 / 调试视图）。"
        + "点开处理器看糖码积木，点信息板看说明，点输出板看运行结果。";

    static final double TILESIZE = 8.0;

    public static void main(String[] args){
        Path demos = Paths.get("demos");
        Path out = Paths.get("out", "LogicSugar-Lab.msav");
        String dump = null;
        String preview = null;
        String verifyOnly = null;
        boolean checkOnly = false, install = false;

        for(int i = 0; i < args.length; i++){
            switch(args[i]){
                case "--demos" -> demos = Paths.get(args[++i]);
                case "--out" -> out = Paths.get(args[++i]);
                case "--dump" -> dump = args[++i];
                case "--check" -> checkOnly = true;
                case "--preview" -> preview = args[++i];
                case "--install" -> install = true;
                case "--verify-only" -> verifyOnly = args[++i];
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if(verifyOnly != null){
            // 只读校验：用当前 classpath 上的游戏 jar 读一张已生成的地图，确认这个客户端能不能加载它。
            bootstrap();
            System.out.println("游戏 jar: " + gameJar());
            verifyOnly(Paths.get(verifyOnly));
            return;
        }

        bootstrap();
        System.out.println("游戏 jar: " + gameJar());
        List<LabStations.Station> stations = LabStations.all();

        if(dump != null){
            dumpStation(demos, stations, dump);
            return;
        }

        java.util.Map<String, LabCompiler.Result> compiled = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for(LabStations.Station station : stations){
            ids.add(station.demo());
            for(LabStations.Extra extra : station.extras()) ids.add(extra.demo());
        }
        ids.add(TITLE_DEMO);

        System.out.println("=== 编译 " + stations.size() + " 个展台（含 " + (ids.size() - stations.size())
            + " 个附件程序）===");
        for(String id : ids){
            LabStations.Station owner = ownerOf(stations, id);
            try{
                LabCompiler.Result result = compileDemo(demos, owner, id);
                compiled.put(id, result);

                if(owner != null && owner.vanillaCode()){
                    System.out.println("  [反编译推断] " + result.restored.replace('\n', '|'));
                }

                List<String> notes = new ArrayList<>();
                if(!result.verified) notes.add("verifyRestore FAILED");
                if(!result.strategyStable) notes.add("switch strategy changed the product");
                if(!result.libraryEmbedded) notes.add("library not embedded");
                if(owner != null && owner.assertEmit() && !"stored".equals(result.openingMode)) notes.add("opening mode " + result.openingMode);
                if(owner != null && owner.vanillaCode() && !"inferred".equals(result.openingMode)) notes.add("expected inferred, got " + result.openingMode);
                if(!notes.isEmpty()) failures.add(id + ": " + String.join("; ", notes));

                System.out.printf("  %-14s %3d 条指令  open=%-8s verify=%-5s strategy=%-5s %s%n",
                    id, result.instructions, result.openingMode,
                    result.verified, result.strategyStable ? "same" : "DIFF",
                    notes.isEmpty() ? "" : "  << " + String.join("; ", notes));
            }catch(Throwable t){
                failures.add(id + ": " + t);
                System.out.println("  " + id + " FAILED: " + t);
            }
        }
        for(LabStations.Station station : stations){
            if(station.info().length() > 400){
                failures.add(station.demo() + ": 说明板文本 " + station.info().length() + " 字 > 400");
            }
        }

        if(!failures.isEmpty()){
            System.out.println();
            System.out.println("!!! " + failures.size() + " 个问题：");
            for(String failure : failures) System.out.println("    " + failure);
            if(checkOnly) System.exit(1);
        }else{
            System.out.println("全部展台通过：载体可还原、开编辑器看到的是存储在载体里的糖码。");
        }
        if(checkOnly){
            return;
        }

        buildMap(stations, compiled, out);
        verifyMap(out, stations, compiled);
        if(preview != null) writePreview(Paths.get(preview));
        System.out.println();
        System.out.println("地图已生成: " + out.toAbsolutePath());
        if(install) install(out);
    }

    // ------------------------------------------------------------------ 编译辅助

    /** 标题牌程序：不属于任何展台，但和展台一起编译。 */
    static final String TITLE_DEMO = "title";

    /** 谁拥有这个程序 id（展台主程序或它的附加处理器；标题牌没有宿主）。 */
    private static LabStations.Station ownerOf(List<LabStations.Station> stations, String id){
        for(LabStations.Station station : stations){
            if(station.demo().equals(id)) return station;
            for(LabStations.Extra extra : station.extras()){
                if(extra.demo().equals(id)) return station;
            }
        }
        return null;
    }

    /** 按宿主展台的开关编译一段演示源码（断言 emit / 纯原版 / 函数库）。 */
    private static LabCompiler.Result compileDemo(Path demos, LabStations.Station owner, String id){
        String friendly = read(demos.resolve(id + ".ls"));
        if(owner == null) return LabCompiler.compile(friendly, false, null);
        if(owner.vanillaCode()) return LabCompiler.vanilla(friendly);
        return LabCompiler.compile(friendly, owner.assertEmit(),
            owner.library() == null ? null : read(demos.resolve(owner.library())));
    }

    private static void dumpStation(Path demos, List<LabStations.Station> stations, String id){
        LabStations.Station owner = ownerOf(stations, id);
        if(owner == null && !TITLE_DEMO.equals(id)){
            throw new IllegalArgumentException("No such station: " + id);
        }
        LabCompiler.Result result = compileDemo(demos, owner, id);
        java.util.Map<String, String> layers = new LinkedHashMap<>();
        layers.put("sugar text", result.friendly);
        layers.put("canvas text", result.canvas);
        layers.put("compiled product", result.product);
        layers.put("restored from carrier", result.restored);
        layers.put("opening mode", result.openingMode);
        layers.put("verifyRestore", String.valueOf(result.verified));
        layers.put("strategy stable", String.valueOf(result.strategyStable));
        layers.put("instructions", String.valueOf(result.instructions));
        for(java.util.Map.Entry<String, String> entry : layers.entrySet()){
            System.out.println("---------- " + entry.getKey() + " ----------");
            System.out.println(entry.getValue());
        }
    }

    // ------------------------------------------------------------------ 地图构建

    private static void buildMap(List<LabStations.Station> stations, java.util.Map<String, LabCompiler.Result> compiled, Path out){
        Vars.state.set(GameState.State.playing);
        Rules rules = new Rules();
        Gamemode.editor.apply(rules);
        rules.canGameOver = false;
        rules.defaultTeam = Team.sharded;
        Vars.state.rules = rules;
        Vars.state.map = new Map(mapTags());

        Vars.world.resize(W, H);
        Vars.world.tiles.fill();
        paintFloors();

        for(LabStations.Station station : stations){
            buildStation(station, compiled);
        }
        buildTitle(compiled.get("title"));

        SaveOptions options = new SaveOptions();
        options.extraTags = mapTags();
        SaveIO.write(new Fi(out.toFile()), options);

        System.out.printf("世界：%dx%d，处理器 %d 块，信息板 %d 块，单位 %d 只%n",
            Vars.world.width(), Vars.world.height(), logicBuilds(), messageBlocks(), Groups.unit.size());
    }

    private static StringMap mapTags(){
        StringMap tags = new StringMap();
        tags.put("name", MAP_NAME);
        tags.put("author", MAP_AUTHOR);
        tags.put("description", MAP_DESCRIPTION);
        tags.put("width", String.valueOf(W));
        tags.put("height", String.valueOf(H));
        return tags;
    }

    /** 地面：整体深色板 + 每行一个主题色 + 展台格 + 走廊。 */
    private static void paintFloors(){
        for(int y = 0; y < H; y++){
            for(int x = 0; x < W; x++){
                Vars.world.tile(x, y).setFloor((Floor)Blocks.darkPanel4);
            }
        }
        // 外框两格压深，内部走廊留深色
        for(int y = 0; y < H; y++){
            for(int x = 0; x < W; x++){
                if(x < 2 || y < 2 || x >= W - 2 || y >= H - 2){
                    Vars.world.tile(x, y).setFloor((Floor)Blocks.darkPanel2);
                }
            }
        }
        Floor[] rowFloors = {(Floor)Blocks.metalFloor3, (Floor)Blocks.metalFloor4, (Floor)Blocks.metalFloor5,
            (Floor)Blocks.metalFloor2, (Floor)Blocks.metalFloor};
        for(int row = 0; row < 5; row++){
            for(int col = 0; col < 5; col++){
                int ox = stationX(col), oy = stationY(row);
                for(int dy = 0; dy < CELL_H; dy++){
                    for(int dx = 0; dx < CELL_W; dx++){
                        Vars.world.tile(ox + dx, oy + dy).setFloor(rowFloors[row]);
                    }
                }
            }
        }
        // 标题条：整段深色板，压出「展馆地面」的感觉
        for(int y = TITLE_DISPLAY_Y; y < TITLE_DISPLAY_Y + 6; y++){
            for(int x = 3; x < W - 3; x++){
                Vars.world.tile(x, y).setFloor((Floor)Blocks.darkPanel3);
            }
        }
    }

    static int stationX(int col){
        return GRID_X + col * (CELL_W + GAP);
    }

    static int stationY(int row){
        return GRID_Y + row * (CELL_H + GAP);
    }

    private static void buildStation(LabStations.Station station, java.util.Map<String, LabCompiler.Result> compiled){
        int ox = stationX(station.col()), oy = stationY(station.row());

        // 说明板：静态文本，点方块就能看到
        Tile infoTile = place(ox + INFO_DX, oy + INFO_DY, block("message"), Team.sharded, 0);
        message(infoTile).message.append(station.info());

        // 输出板：程序用 printflush board 写，链接名 board
        Tile boardTile = place(ox + BOARD_DX, oy + BOARD_DY, block("message"), Team.sharded, 0);
        message(boardTile).message.append(LabStations.outputPlaceholder());

        // 道具：放进世界并记下链接名
        List<LogicLink> links = new ArrayList<>();
        links.add(new LogicLink(boardTile.x, boardTile.y, "board", true));
        for(LabStations.Prop prop : station.props()){
            Tile tile = place(ox + prop.dx(), oy + prop.dy(), block(prop.block()), Team.sharded, 0);
            if(prop.link() != null){
                links.add(new LogicLink(tile.x, tile.y, prop.link(), true));
            }
        }

        // 主处理器
        LabCompiler.Result result = compiled.get(station.demo());
        if(result == null) throw new IllegalStateException("no compiled code for " + station.demo());
        Tile procTile = place(ox + PROC_DX, oy + PROC_DY, block(station.processor()), Team.sharded, 0);
        LogicBuild proc = (LogicBuild)procTile.build;
        proc.links.clear();
        for(LogicLink link : links) proc.links.add(link);
        proc.updateCode(result.product, false, null);

        // 附加处理器（自带输出板）
        for(LabStations.Extra extra : station.extras()){
            LabCompiler.Result extraResult = compiled.get(extra.demo());
            if(extraResult == null) throw new IllegalStateException("no compiled code for " + extra.demo());
            Tile extraBoard = place(ox + extra.boardDx(), oy + extra.boardDy(), block("message"), Team.sharded, 0);
            message(extraBoard).message.append(LabStations.outputPlaceholder());
            Tile extraProc = place(ox + extra.dx(), oy + extra.dy(), block(extra.processor()), Team.sharded, 0);
            LogicBuild build = (LogicBuild)extraProc.build;
            build.links.clear();
            build.links.add(new LogicLink(extraBoard.x, extraBoard.y, "board", true));
            build.updateCode(extraResult.product, false, null);
        }

        // 展台里的展示单位
        for(LabStations.ExtraUnit unit : station.units()){
            UnitType type = Vars.content.unit(unit.type());
            if(type == null) throw new IllegalStateException("unknown unit type " + unit.type());
            type.spawn(Team.sharded, (ox + unit.dx()) * (float)TILESIZE, (oy + unit.dy()) * (float)TILESIZE);
        }
    }

    /** 顶部标题条：大逻辑显示屏 + 绘制它的处理器（顺带演示 draw / printflush 到显示屏）。 */
    private static void buildTitle(LabCompiler.Result compiled){
        Tile displayTile = place(TITLE_DISPLAY_X, TITLE_DISPLAY_Y, block("large-logic-display"), Team.sharded, 0);
        Tile center = centerTile(displayTile, 6);

        Tile procTile = place(TITLE_PROC_X, TITLE_PROC_Y, block("logic-processor"), Team.sharded, 0);
        LogicBuild proc = (LogicBuild)procTile.build;
        proc.links.clear();
        proc.links.add(new LogicLink(center.x, center.y, "display", true));
        proc.updateCode(compiled == null ? "" : compiled.product, false, null);
    }

    // ------------------------------------------------------------------ 写回校验

    private static void verifyMap(Path out, List<LabStations.Station> stations, java.util.Map<String, LabCompiler.Result> compiled){
        System.out.println();
        System.out.println("=== 读回校验（用游戏自己的 SaveIO 重新加载）===");
        SaveIO.load(new Fi(out.toFile()));

        List<String> problems = new ArrayList<>();
        if(Vars.world.width() != W || Vars.world.height() != H){
            problems.add("尺寸 " + Vars.world.width() + "x" + Vars.world.height());
        }

        for(LabStations.Station station : stations){
            int ox = stationX(station.col()), oy = stationY(station.row());
            Tile procTile = Vars.world.tile(ox + PROC_DX, oy + PROC_DY);
            if(procTile == null || !(procTile.build instanceof LogicBuild proc)){
                problems.add(station.demo() + ": 处理器不见了");
                continue;
            }
            LabCompiler.Result result = compiled.get(station.demo());
            if(!sameCode(proc.code, result.product)){
                problems.add(station.demo() + ": 读回的代码与写入不一致");
            }
            for(LogicLink link : proc.links){
                // 读回后链接的 valid 标志可能还停在写入时的状态：LogicBuild.update() 在第一次 tick
                // 会重新校验并修正（“check for previously invalid links” 那段循环），因为 map 区域是
                // 按格子顺序读的，指向后面格子的链接在处理器被读到时目标还不存在。所以这里不读
                // valid 标志，而是按 validLink 的条件自己判一遍（确定性，与读取顺序无关）。
                Building target = Vars.world.build(link.x, link.y);
                if(target == null){
                    problems.add(station.demo() + ": 链接 " + link.name + " 指向空位 (" + link.x + "," + link.y + ")");
                }else if(!linkUsable(proc, target)){
                    problems.add(station.demo() + ": 链接 " + link.name + " 不可用（" + target.block.name
                        + " team=" + target.team + " distance=" + (int)target.dst(proc) + "）");
                }
            }
            Tile infoTile = Vars.world.tile(ox + INFO_DX, oy + INFO_DY);
            if(!(infoTile.build instanceof MessageBuild info) || info.message.length() == 0){
                problems.add(station.demo() + ": 说明板文本空了");
            }else if(!info.message.toString().equals(station.info())){
                problems.add(station.demo() + ": 说明板文本与写入不一致");
            }
        }

        // 断言展台读回后仍然是可还原的载体（emit 形状由 verifyRestore 的两种形状覆盖）
        for(LabStations.Station station : stations){
            int ox = stationX(station.col()), oy = stationY(station.row());
            Tile procTile = Vars.world.tile(ox + PROC_DX, oy + PROC_DY);
            if(!(procTile.build instanceof LogicBuild proc)) continue;
            if(!SugarCompiler.verifyRestore(proc.code, SugarCompiler.restore(proc.code))){
                problems.add(station.demo() + ": 读回后 verifyRestore 失败");
            }
        }

        System.out.printf("  读回：%d 处理器 / %d 信息板 / %d 单位，问题 %d 个%n",
            logicBuilds(), messageBlocks(), Groups.unit.size(), problems.size());
        printMeta(out);
        for(String problem : problems) System.out.println("    ! " + problem);
        if(!problems.isEmpty()) throw new IllegalStateException("map verification failed");
        printLayout(stations);
    }

    /**
     * 示意预览图（不是地图产物）：按方块类型上色的方格图，用来肉眼核对摆位。
     * 游戏自己的 {@code MapIO.writeImage} 渲的是真实贴图缩略图，深色地板上几乎全黑，
     * 排布问题看不出来，所以这里自己画一张。
     */
    private static void writePreview(Path path){
        int scale = 5;
        arc.graphics.Pixmap pixmap = new arc.graphics.Pixmap(W * scale, H * scale);
        pixmap.fill(arc.graphics.Color.rgba8888(0.06f, 0.07f, 0.09f, 1f));
        for(int y = 0; y < H; y++){
            for(int x = 0; x < W; x++){
                Tile tile = Vars.world.tile(x, y);
                int color = floorColor(tile.floor().name);
                if(tile.block() != Blocks.air) color = blockColor(tile.block().name);
                int px = x * scale, py = (H - 1 - y) * scale;
                for(int dx = 0; dx < scale; dx++){
                    for(int dy = 0; dy < scale; dy++){
                        pixmap.set(px + dx, py + dy, color);
                    }
                }
            }
        }
        new Fi(path.toFile()).writePng(pixmap);
        pixmap.dispose();
        System.out.println("预览图已写出: " + path.toAbsolutePath());
    }

    private static int floorColor(String name){
        return switch(name){
            case "dark-panel-2" -> arc.graphics.Color.rgba8888(0.16f, 0.17f, 0.20f, 1f);
            case "dark-panel-3" -> arc.graphics.Color.rgba8888(0.13f, 0.16f, 0.22f, 1f);
            case "dark-panel-4" -> arc.graphics.Color.rgba8888(0.10f, 0.11f, 0.13f, 1f);
            case "metal-floor" -> arc.graphics.Color.rgba8888(0.30f, 0.30f, 0.33f, 1f);
            case "metal-floor-2" -> arc.graphics.Color.rgba8888(0.24f, 0.33f, 0.30f, 1f);
            case "metal-floor-3" -> arc.graphics.Color.rgba8888(0.24f, 0.29f, 0.38f, 1f);
            case "metal-floor-4" -> arc.graphics.Color.rgba8888(0.34f, 0.30f, 0.22f, 1f);
            case "metal-floor-5" -> arc.graphics.Color.rgba8888(0.32f, 0.24f, 0.32f, 1f);
            default -> arc.graphics.Color.rgba8888(0.20f, 0.20f, 0.22f, 1f);
        };
    }

    private static int blockColor(String name){
        return switch(name){
            case "logic-processor" -> arc.graphics.Color.rgba8888(1f, 0.85f, 0.25f, 1f);
            case "hyper-processor" -> arc.graphics.Color.rgba8888(1f, 0.55f, 0.15f, 1f);
            case "micro-processor" -> arc.graphics.Color.rgba8888(1f, 0.95f, 0.65f, 1f);
            case "message" -> arc.graphics.Color.rgba8888(0.35f, 0.85f, 1f, 1f);
            case "memory-cell" -> arc.graphics.Color.rgba8888(0.45f, 1f, 0.45f, 1f);
            case "memory-bank" -> arc.graphics.Color.rgba8888(0.25f, 0.85f, 0.45f, 1f);
            case "large-logic-display" -> arc.graphics.Color.rgba8888(0.55f, 0.55f, 1f, 1f);
            case "logic-display" -> arc.graphics.Color.rgba8888(0.45f, 0.45f, 0.95f, 1f);
            case "switch" -> arc.graphics.Color.rgba8888(0.9f, 0.4f, 0.9f, 1f);
            default -> arc.graphics.Color.rgba8888(1f, 0.25f, 0.25f, 1f);
        };
    }

    /** 读回后的地图元数据（游戏地图列表看到的就是这些）。 */
    private static void printMeta(Path out){
        SaveMeta meta = SaveIO.getMeta(new Fi(out.toFile()));
        System.out.println("  meta: name=" + meta.tags.get("mapname") + " / author=" + meta.tags.get("author")
            + " / " + meta.tags.get("width") + "x" + meta.tags.get("height") + " / saveVersion=" + meta.version
            + " / mods=" + meta.tags.get("mods"));
        System.out.println("  rules: " + meta.tags.get("rules"));
    }

    /** 展台摆位清单：处理器 / 说明板 / 输出板 / 道具的绝对坐标，方便对照地图。 */
    private static void printLayout(List<LabStations.Station> stations){
        System.out.println();
        System.out.println("=== 摆位清单（格 " + CELL_W + "x" + CELL_H
            + "，格间走廊 " + GAP + "；坐标是地图格子坐标，原点在左下角）===");
        for(LabStations.Station station : stations){
            int ox = stationX(station.col()), oy = stationY(station.row());
            StringBuilder line = new StringBuilder();
            line.append(String.format("  %-14s 格(%d,%d) 处理器(%d,%d) 说明板(%d,%d) 输出板(%d,%d)",
                station.demo(), ox, oy, ox + PROC_DX, oy + PROC_DY, ox + INFO_DX, oy + INFO_DY,
                ox + BOARD_DX, oy + BOARD_DY));
            for(LabStations.Prop prop : station.props()){
                line.append(" | ").append(prop.block()).append('(').append(ox + prop.dx()).append(',')
                    .append(oy + prop.dy()).append(')');
                if(prop.link() != null) line.append("=").append(prop.link());
            }
            for(LabStations.Extra extra : station.extras()){
                line.append(" | ").append(extra.demo()).append('(').append(ox + extra.dx()).append(',')
                    .append(oy + extra.dy()).append(") 板(").append(ox + extra.boardDx()).append(',')
                    .append(oy + extra.boardDy()).append(')');
            }
            for(LabStations.ExtraUnit unit : station.units()){
                line.append(" | 单位").append(unit.type()).append('(').append(ox + unit.dx()).append(',')
                    .append(oy + unit.dy()).append(')');
            }
            System.out.println(line);
        }
    }

    /** 与 {@code LogicBuild.validLink} 同口径的可链接判定（不带读取顺序的副作用）。 */
    private static boolean linkUsable(LogicBuild proc, Building target){
        Block block = target.block;
        return target.isValid() && !block.privileged && target.team == proc.team
            && target.within(proc, ((LogicBlock)proc.block).range + block.size * (float)TILESIZE / 2f)
            && !(target instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild);
    }

    private static boolean sameCode(String a, String b){
        if(a == null) a = "";
        if(b == null) b = "";
        return a.replace("\r\n", "\n").equals(b.replace("\r\n", "\n"));
    }

    // ------------------------------------------------------------------ 通用工具

    private static int logicBuilds(){
        final int[] count = {0};
        Vars.world.tiles.eachTile(tile -> {
            if(tile != null && tile.isCenter() && tile.build instanceof LogicBuild) count[0]++;
        });
        return count[0];
    }

    private static int messageBlocks(){
        final int[] count = {0};
        Vars.world.tiles.eachTile(tile -> {
            if(tile != null && tile.isCenter() && tile.build instanceof MessageBuild) count[0]++;
        });
        return count[0];
    }

    private static Tile place(int x, int y, Block block, Team team, int rotation){
        Tile tile = Vars.world.tile(x, y);
        if(tile == null) throw new IllegalArgumentException("tile out of bounds: " + x + "," + y);
        // 摆件互不重叠：多格方块按下表铺开，重叠会把上一块静默吃掉（说明板被显示屏盖掉过一次）
        int offset = block.isMultiblock() ? -(block.size - 1) / 2 : 0;
        for(int dx = 0; dx < Math.max(block.size, 1); dx++){
            for(int dy = 0; dy < Math.max(block.size, 1); dy++){
                Tile other = Vars.world.tile(x + offset + dx, y + offset + dy);
                if(other == null) throw new IllegalStateException("block outside the map: " + block.name);
                if(other.block() != Blocks.air){
                    throw new IllegalStateException("tile already occupied by " + other.block().name
                        + " at " + other.x + "," + other.y + " (placing " + block.name + ")");
                }
            }
        }
        tile.setBlock(block, team, rotation);
        if(tile.build == null) throw new IllegalStateException("block not placed: " + block.name + " at " + x + "," + y);
        return tile;
    }

    private static MessageBuild message(Tile tile){
        if(!(tile.build instanceof MessageBuild build)){
            throw new IllegalStateException("not a message block at " + tile.x + "," + tile.y);
        }
        build.message.setLength(0);
        return build;
    }

    /** 多格方块的“中心”格（按 isCenter 找，绝不猜坐标）。 */
    private static Tile centerTile(Tile origin, int size){
        int offset = (size - 1) / 2;
        for(int dy = -offset; dy <= offset; dy++){
            for(int dx = -offset; dx <= offset; dx++){
                Tile tile = Vars.world.tile(origin.x + dx, origin.y + dy);
                if(tile != null && tile.isCenter() && tile.build == origin.build) return tile;
            }
        }
        throw new IllegalStateException("no center tile for " + origin.block() + " at " + origin.x + "," + origin.y);
    }

    private static Block block(String name){
        Block block = contentBlock(name);
        if(block == null) throw new IllegalArgumentException("unknown block: " + name);
        return block;
    }

    private static Block contentBlock(String name){
        for(Block block : Vars.content.blocks()){
            if(block.name.equals(name)) return block;
        }
        return null;
    }

    /**
     * 读一份演示源码（UTF-8）：**行尾统一归成 LF**。
     *
     * <p>原版 {@code LParser} 只在语句开头吞掉 {@code \r\n}，token 扫描器不把 {@code \r}
     * 当分隔符：CRLF 检出的 demo 文件会让 {@code ifbegin x equal 1 999\r} 的最后一个 token 变成
     * {@code "999\r"}（destination index 解析失败）或直接把字符串尾部带进 token。游戏里处理器的
     * 代码永远是 LF，所以这里读进来就归一，Windows 上 core.autocrlf 检出也能直接跑。</p>
     */
    private static String read(Path path){
        try{
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }catch(IOException e){
            throw new RuntimeException("cannot read " + path, e);
        }
    }

    private static void install(Path out){
        String appData = System.getenv("APPDATA");
        if(appData == null || appData.isEmpty()){
            System.out.println("APPDATA 不存在，跳过安装（手动把地图放进游戏 maps 目录即可）");
            return;
        }
        Path maps = Paths.get(appData, "Mindustry", "maps");
        try{
            Files.createDirectories(maps);
            Path target = maps.resolve(out.getFileName().toString());
            Files.copy(out, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("已复制到游戏地图目录: " + target);
        }catch(IOException e){
            throw new RuntimeException("cannot install to " + maps, e);
        }
    }

    /** 当前进程加载的游戏 jar（决定写出的存档格式，排查“老客户端读不动”时先看这一行）。 */
    private static String gameJar(){
        try{
            java.net.URL source = Vars.class.getProtectionDomain().getCodeSource().getLocation();
            return new java.io.File(source.toURI()).getAbsolutePath();
        }catch(Throwable t){
            return "unknown (" + t + ")";
        }
    }

    /**
     * 只读校验一张已生成的地图：用当前 jar 的 SaveIO 读一遍并统计内容。
     *
     * <p>跟 {@link #verifyMap} 不同，这里不比对写入时的期望值，所以可以拿**另一个**游戏 jar
     * （例如更新或更老的客户端）来跑，专门确认存档格式是否被那个客户端接受：“新 reader 认
     * 老格式，老 reader 不认新格式”。</p>
     */
    private static void verifyOnly(Path path){
        SaveIO.load(new Fi(path.toFile()));
        int logic = 0, carrier = 0, message = 0, memory = 0, display = 0;
        for(Tile tile : Vars.world.tiles){
            if(tile == null || !tile.isCenter() || tile.build == null) continue;
            if(tile.build instanceof LogicBlock.LogicBuild build){
                logic++;
                if(SugarLookup.hasCarrier(build.code)) carrier++;
            }
            if(tile.build instanceof MessageBuild build && build.message.length() > 0) message++;
            if(tile.block().name.startsWith("memory")) memory++;
            if(tile.block().name.endsWith("logic-display")) display++;
        }
        System.out.printf("读回成功：%dx%d 名字=%s 规则 editor=%s 默认队=%s%n",
            Vars.world.width(), Vars.world.height(), Vars.state.map.name(),
            Vars.state.rules.editor, Vars.state.rules.defaultTeam);
        System.out.printf("  处理器 %d（含糖载体 %d）/ 信息板 %d / 内存块 %d / 显示屏 %d / 单位 %d%n",
            logic, carrier, message, memory, display, Groups.unit.size());
    }

    /** 判读一段处理器代码里有没有 LogicSugar 载体（不依赖模组类，方便用原版 jar 复验）。 */
    private static final class SugarLookup{
        static boolean hasCarrier(String code){
            if(code == null) return false;
            for(String line : code.replace("\r\n", "\n").split("\n", -1)){
                if(line.startsWith("set __ls_sugar") && line.endsWith("\"")) return true;
            }
            return false;
        }
    }

    private static void bootstrap(){        Core.app = new MockApplication();
        Core.files = new MockFiles();
        Core.settings = new MockSettings();
        Core.graphics = new MockGraphics();

        Vars.headless = true;
        Vars.platform = new mindustry.core.Platform(){
        };
        Vars.net = new Net(null);
        Vars.tree = new mindustry.core.FileTree();
        // Vars.init() 会重建 ContentLoader / World / GameState，所以它必须先跑；
        // 在它之前 createBaseContent() 构造出来的方块会全部落在被丢弃的旧注册表里
        // （方块 id 保持 -1，写出的地图读回来全是 air）。
        Vars.init();
        // Logic 由客户端启动流程 add() 进去，headless 这里要自己建：SaveIO.load 会调 logic.reset()
        Vars.logic = new Logic();

        Vars.content.createBaseContent();
        Vars.content.createModContent();
        Vars.content.init();
        Vars.logicVars = new GlobalVars();
        // 注册 LogicSugar 的积木/解析器/数据模块：与游戏里加载模组后调用的是同一个入口。
        // 只做 --verify-only 时允许没有模组类（只用游戏的 SaveIO 读地图，不需要模组）。
        try{
            LogicSugarMod.registerStatements();
        }catch(Throwable t){
            System.out.println("（未加载 LogicSugar 类，跳过糖码解析器注册：" + t.getClass().getSimpleName() + "）");
        }

        if(Vars.content.blocks().size < 100){
            throw new IllegalStateException("base content not registered: " + Vars.content.blocks().size);
        }
    }
}
