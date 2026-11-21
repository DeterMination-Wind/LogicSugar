package logicsugar;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

/**
 * The MDT recommendation prompt is one-time, network-dependent UI, so the parts that can be pinned
 * headlessly are pinned here: the identity check that keeps the prompt away from hosts that already
 * ship MDT, the release/asset resolution that decides which jar is downloaded, the country verdict
 * that chooses between the mirror path and the mod browser, and the flow wiring (standalone only,
 * version gate, restart) as source nails.
 *
 * <p>The failure this guards against is not a crash: it is a prompt that shows up for a mod that is
 * already installed, or a "Download" button that downloads the wrong asset / installs a jar the game
 * cannot run. Both are silent in a headless run and only surface to players.</p>
 */
public final class MdtRecommendationTest{
    private MdtRecommendationTest(){}

    public static void main(String[] args) throws IOException{
        releaseIdentity();
        versionComparator();
        mirrorChain();
        assetResolution();
        countryVerdict();
        installedDetection();
        zipHead();
        bundleContract();
        promptIsStandaloneAndOneShot();
        downloadFlowUsesMirrorsAndRestart();

        System.out.println("MdtRecommendationTest: all checks passed");
    }

    /** The repo and its two release URLs must stay in one place: they are the only hardcoded endpoints. */
    private static void releaseIdentity(){
        check("cardillan/MlogDevTools".equals(MdtRecommendation.repo),
            "MDT's repository is cardillan/MlogDevTools; the launcher and this prompt must agree");
        check(MdtRecommendation.latestJarUrl.equals(
                "https://github.com/cardillan/MlogDevTools/releases/latest/download/MlogDevTools.jar"),
            "the fallback jar URL must point at MDT's release asset, got " + MdtRecommendation.latestJarUrl);
        check(MdtRecommendation.latestApiUrl.equals(
                "https://api.github.com/repos/cardillan/MlogDevTools/releases/latest"),
            "the metadata URL must ask the same repo, got " + MdtRecommendation.latestApiUrl);
        check(MdtRecommendation.releasePage.equals("https://github.com/cardillan/MlogDevTools/releases/latest"),
            "the GitHub button must land on the latest release page");
    }

    /**
     * Every mirror entry becomes {@code <mirror>/<github-url>}, and the direct origin is tried last.
     * A missing direct route would leave non-proxy users unable to install MDT at all.
     */
    private static void mirrorChain(){
        String github = "https://github.com/cardillan/MlogDevTools/releases/latest/download/MlogDevTools.jar";
        List<String> urls = MdtRecommendation.mirrorUrls(github);

        check(urls.size() == MdtRecommendation.mirrors.length + 1,
            "one URL per mirror plus the direct origin, got " + urls.size());
        check(urls.get(urls.size() - 1).equals(github), "the direct origin must be the last route");
        for(int i = 0; i < MdtRecommendation.mirrors.length; i++){
            check(urls.get(i).equals(MdtRecommendation.stripSlash(MdtRecommendation.mirrors[i]) + "/" + github),
                "mirror route " + i + " is built wrong: " + urls.get(i));
            check(MdtRecommendation.mirrors[i].startsWith("https://"),
                "mirrors must be https: Android blocks cleartext outside the manifest, got " + MdtRecommendation.mirrors[i]);
        }
        check(MdtRecommendation.stripSlash("https://a/").equals("https://a"), "stripSlash removes exactly one slash");
        check(MdtRecommendation.stripSlash("https://a").equals("https://a"), "stripSlash keeps a URL that has none");
    }

    /** The release jar is chosen from the API body, preferring the known name over any other .jar. */
    private static void assetResolution(){
        String body = "{\"tag_name\":\"v0.12.1\",\"assets\":["
            + "{\"name\":\"sources.zip\",\"browser_download_url\":\"https://x/sources.zip\"},"
            + "{\"name\":\"MlogDevTools.jar\",\"browser_download_url\":\"https://x/MlogDevTools.jar\"}]}";
        check("https://x/MlogDevTools.jar".equals(MdtRecommendation.parseJarAsset(body)),
            "the named release jar must win over an earlier .jar");

        String renamed = "{\"assets\":[{\"name\":\"MlogAssertions.jar\",\"browser_download_url\":\"https://x/old.jar\"}]}";
        check("https://x/old.jar".equals(MdtRecommendation.parseJarAsset(renamed)),
            "a renamed release jar must still be accepted: the asset was MlogAssertions.jar before v0.12.0");

        check(MdtRecommendation.parseJarAsset("{\"assets\":[]}") == null, "no assets means no URL");
        check(MdtRecommendation.parseJarAsset("{\"assets\":[{\"name\":\"README.md\","
            + "\"browser_download_url\":\"https://x/README.md\"}]}") == null, "non-jar assets are not installable");
        check(MdtRecommendation.parseJarAsset("not json") == null, "a proxy error page must not parse");
        check(MdtRecommendation.parseJarAsset(null) == null, "null body");
    }

    /** All three probe shapes are recognised, and an unusable answer stays "unknown" instead of China. */
    private static void countryVerdict(){
        String trace = "fl=1f2\nh=www.cloudflare.com\nip=1.2.3.4\nts=1\nvisit_scheme=https\nloc=CN\ncolo=HKG\n";
        check(Boolean.TRUE.equals(MdtRecommendation.parseCountry(trace)), "Cloudflare loc=CN must count as China");
        check(Boolean.FALSE.equals(MdtRecommendation.parseCountry(trace.replace("loc=CN", "loc=US"))),
            "loc=US must count as not China");

        String ipSb = "{\"country\":\"China\",\"country_code\":\"CN\",\"region\":\"Zhejiang\"}";
        check(Boolean.TRUE.equals(MdtRecommendation.parseCountry(ipSb)), "ip.sb country_code must be read");

        check(Boolean.TRUE.equals(MdtRecommendation.parseCountry("CN\n")), "ipapi.co returns a bare code");
        check(Boolean.FALSE.equals(MdtRecommendation.parseCountry("DE")), "a bare non-CN code");

        check(MdtRecommendation.parseCountry("{\"message\":\"Resource not found.\"}") == null,
            "an error body must stay unknown, not be misread as China");
        check(MdtRecommendation.parseCountry("") == null, "empty body");
        check(MdtRecommendation.parseCountry(null) == null, "null body");

        for(String china : new String[]{"CN", "cn", "CHN", "China", "中国", "中国大陆", " 中国 "}){
            check(MdtRecommendation.isChinaCountry(china), china + " must be recognised as China");
        }
        for(String other : new String[]{"US", "HK", "TW", "", null, "Chinese Taipei"}){
            check(!MdtRecommendation.isChinaCountry(other), String.valueOf(other) + " must not count as China");
        }

        check(MdtRecommendation.jsonStringValue("{\"a\":\"b\"}", "a").equals("b"), "quoted value");
        check(MdtRecommendation.jsonStringValue("{\"a\":1}", "a") == null, "non-string value is not a country");
        check(MdtRecommendation.jsonStringValue("{}", "a") == null, "missing key");
    }

    /** The prompt must not appear for MDT under any of the names it has shipped with. */
    private static void installedDetection(){
        check(MdtRecommendation.internalName.equals("mlog-assertions-8"),
            "the documented internal name must match MDT's mod.hjson; rename it only with a probe");
        check(MdtRecommendation.isMdtMod(MdtRecommendation.internalName, null, null), "the documented name");
        check(MdtRecommendation.isMdtMod("mlog-assertions-8", null, null), "current internal name");
        check(MdtRecommendation.isMdtMod("mlog-assertions", null, null), "pre-rename internal name");
        check(MdtRecommendation.isMdtMod("mlogdevtools", null, null), "repo-derived name");
        check(MdtRecommendation.isMdtMod("whatever", "Mlog Dev Tools", null), "display name");
        check(MdtRecommendation.isMdtMod("whatever", null, "cardillan/MlogDevTools"), "repo");
        check(MdtRecommendation.isMdtMod("whatever", null, "CARDILLAN/mlogdevtools"),
            "the repo check must ignore case: the game lowercases the setting key, not the value");

        check(!MdtRecommendation.isMdtMod("logicsugar", "Logic Sugar", null), "LogicSugar itself");
        check(!MdtRecommendation.isMdtMod("mlog-sugar", "Mlog Sugar", null), "a similarly named mod");
        check(!MdtRecommendation.isMdtMod(null, null, null), "nothing installed");
        check(!MdtRecommendation.isMdtMod("", "", ""), "empty metadata");
    }

    /** Proxy error pages are HTML; the first bytes catch them before the importer sees a bad zip. */
    private static void zipHead(){
        check(MdtRecommendation.looksLikeZipHead(new byte[]{'P', 'K', 3, 4}, 4), "a normal jar starts with PK\\3\\4");
        check(MdtRecommendation.looksLikeZipHead(new byte[]{'P', 'K', 5, 6}, 4), "an empty archive is still a zip");
        check(!MdtRecommendation.looksLikeZipHead("<htm".getBytes(StandardCharsets.US_ASCII), 4),
            "an HTML error page must be rejected");
        check(!MdtRecommendation.looksLikeZipHead(new byte[]{'P', 'K'}, 2), "a truncated head is not enough");
        check(!MdtRecommendation.looksLikeZipHead(null, 4), "null head");
    }

    /**
     * The minimum-version gate. MDT 0.12.2 and older crash the LogicSugar editor on resize, so the
     * comparator must be exact about the boundary and must never treat unknown text as new enough.
     */
    private static void versionComparator(){
        check("0.12.3".equals(MdtRecommendation.minMdtVersion),
            "the minimum must be the MDT fix release (PR #11), got " + MdtRecommendation.minMdtVersion);

        check(MdtRecommendation.versionAtLeast("0.12.3", "0.12.3"), "equal versions are at least");
        check(MdtRecommendation.versionAtLeast("0.12.3", "0.12.2"),
            "0.12.3 must pass a 0.12.2 requirement");
        check(!MdtRecommendation.versionAtLeast("0.12.2", "0.12.3"),
            "0.12.2 must fail the 0.12.3 requirement");
        check(!MdtRecommendation.versionAtLeast("0.12.1", "0.12.2"),
            "0.12.1 is older than 0.12.2");
        check(MdtRecommendation.versionAtLeast("0.13", "0.12.3"), "a newer minor wins");
        check(MdtRecommendation.versionAtLeast("1", "0.99.99"), "a newer major wins");
        check(MdtRecommendation.versionAtLeast("0.12.3.1", "0.12.3"),
            "missing required segments count as 0, extra actual segments count");
        check(!MdtRecommendation.versionAtLeast("0.12", "0.12.3"),
            "missing actual segments count as 0");
        check(MdtRecommendation.versionAtLeast("0.12.3-dev", "0.12.3"),
            "a pre-release suffix after '-' is ignored");
        check(MdtRecommendation.versionAtLeast("0.12.3+build7", "0.12.3"),
            "a build suffix after '+' is ignored");
        check(MdtRecommendation.versionAtLeast("v0.12.3", "0.12.3"), "a v prefix is tolerated");
        check(MdtRecommendation.versionAtLeast(" 0.12.3 ", "0.12.3"), "whitespace is trimmed");

        for(String garbage : new String[]{null, "", " ", "latest", "0.12.x", "0..3", "0.12.3.4.5a",
            "v", "-0.12.3"}){
            check(!MdtRecommendation.versionAtLeast(garbage, "0.12.3"),
                "garbage must never count as new enough: " + garbage);
        }
        check(!MdtRecommendation.versionAtLeast("0.12.3", null),
            "a null requirement has no comparison and must fail");
    }

    /**
     * Every literal key the dialog uses must exist in all three bundles. */
    private static void bundleContract() throws IOException{
        File root = SourceNails.root();
        String[] keys = {
            "logicsugar.mdt.title", "logicsugar.mdt.recommend", "logicsugar.mdt.no",
            "logicsugar.mdt.github", "logicsugar.mdt.browser", "logicsugar.mdt.download",
            "logicsugar.mdt.detecting", "logicsugar.mdt.downloading", "logicsugar.mdt.downloaded",
            "logicsugar.mdt.invalid", "logicsugar.mdt.incompatible", "logicsugar.mdt.failed",
            "logicsugar.mdt.opened", "logicsugar.mdt.browserfailed",
            "logicsugar.mdt.update", "logicsugar.mdt.tooold"
        };
        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties bundle = load(root, name);
            for(String key : keys){
                check(bundle.getProperty(key) != null, name + " is missing " + key);
            }
            // The old "running both together stays bug-free" claim is exactly what 0.12.2 broke:
            // the prompt must state the minimum version instead of promising compatibility.
            String recommend = bundle.getProperty("logicsugar.mdt.recommend");
            check(!recommend.toLowerCase(java.util.Locale.ROOT).contains("bug-free")
                    && !recommend.contains("不会有 Bug") && !recommend.contains("不會有 Bug"),
                name + ": the prompt must not claim the combination is bug-free");
            check(recommend.contains("0.12.3"),
                name + ": the prompt must state the MDT minimum version");
            // The button labels are literal (no format args); the failure texts are formatted.
            for(String key : new String[]{"logicsugar.mdt.no", "logicsugar.mdt.github",
                "logicsugar.mdt.browser", "logicsugar.mdt.download"}){
                check(!bundle.getProperty(key).contains("{0}"), name + ": " + key + " must not contain {0}");
            }
            String incompatible = bundle.getProperty("logicsugar.mdt.incompatible");
            check(incompatible.contains("{0}") && incompatible.contains("{1}"),
                name + ": the incompatible text must name both versions");
            check(bundle.getProperty("logicsugar.mdt.update").contains("{0}")
                    && bundle.getProperty("logicsugar.mdt.update").contains("{1}"),
                name + ": the update notice must name the required and installed versions");
            check(bundle.getProperty("logicsugar.mdt.tooold").contains("{0}")
                    && bundle.getProperty("logicsugar.mdt.tooold").contains("{1}"),
                name + ": the too-old refusal must name the required and downloaded versions");
        }
    }

    /** Standalone only, one-shot, and never stacked on another modal. */
    private static void promptIsStandaloneAndOneShot() throws IOException{
        String source = SourceNails.readSource("src/logicsugar/MdtRecommendation.java");
        String mod = SourceNails.readSource("src/logicsugar/LogicSugarMod.java");

        check(mod.contains("MdtRecommendation::maybeShow"),
            "init() must schedule the prompt; otherwise the feature is unreachable");
        check(mod.contains("Core.app.post(() -> Time.run(60f, MdtRecommendation::maybeShow))"),
            "the prompt must be deferred: the editor-conflict prompt is queued from the same event");

        String maybe = SourceNails.methodBody(source, "static void maybeShow(){");
        check(maybe.contains("if(LogicSugarMod.bekBundled) return;"),
            "a bundled host (Neon) ships its own selection; do not point players at a separate mod");
        check(maybe.contains("Vars.headless"), "a headless server has no player to ask");
        check(maybe.contains("Core.settings.getBool(settingShown, false)"),
            "the prompt must be one-shot");
        check(maybe.contains("if(installed())"), "an installed MDT must suppress the download prompt");
        check(maybe.contains("isInstalledOutdated()"),
            "an installed but too-old MDT must be detected instead of being silently accepted");
        check(maybe.contains("settingUpdateNoticeShown"),
            "the update notice is one-shot, with its own key");
        check(!maybe.contains("showPrompt();"),
            "the download prompt must only run through the deferred helper; otherwise it can stack on the conflict prompt");
        String deferred = SourceNails.methodBody(source, "private static void deferOrShow(Runnable action){");
        check(deferred.contains("Core.scene.hasDialog()"),
            "two modals at once cover each other; defer while another dialog is open");
        check(deferred.contains("MdtRecommendation::maybeShow"),
            "the deferred retry must re-run the whole decision (download prompt vs update notice)");
        check(deferred.contains("action.run()"), "when the screen is free the chosen action runs");

        String notice = SourceNails.methodBody(source, "private static void showUpdateNotice(){");
        check(notice.contains("Core.bundle.format(\"logicsugar.mdt.update\""),
            "the update notice must tell the player both versions");
        check(notice.contains("\"@logicsugar.mdt.github\"") && notice.contains("\"@logicsugar.mdt.browser\""),
            "the update notice must offer both manual update routes");
        check(notice.contains("Core.settings.put(settingUpdateNoticeShown, true)"),
            "the update notice flag belongs to shown(), so Esc does not make it return next launch");
        check(!notice.contains("@logicsugar.mdt.download"),
            "the update notice must not offer Download: it would install a second copy next to the installed one");
        check(!notice.contains("showPrompt"),
            "the update notice must not fall back to the first-launch download prompt");

        check(source.contains("static String installedVersion()"),
            "the installed version must come from the loaded mod's metadata");
        check(source.contains("static boolean isInstalledOutdated()"),
            "the outdated check must exist as its own predicate");

        String prompt = SourceNails.methodBody(source, "private static void showPrompt(){");
        check(prompt.contains("dialog.shown(() -> Core.settings.put(settingShown, true))"),
            "the flag belongs to shown(), not to a button: Esc must not make the prompt return next launch");
        int previous = -1;
        for(String key : new String[]{"logicsugar.mdt.no", "logicsugar.mdt.github",
            "logicsugar.mdt.browser", "logicsugar.mdt.download"}){
            int at = prompt.indexOf("\"@" + key + "\"");
            check(at >= 0, "the prompt must offer " + key);
            check(at > previous, "the buttons must follow the order the prompt text refers to: " + key);
            previous = at;
        }
    }

    /** China: mirror download + restart. Anywhere else: the mod browser with the search prefilled. */
    private static void downloadFlowUsesMirrorsAndRestart() throws IOException{
        String source = SourceNails.readSource("src/logicsugar/MdtRecommendation.java");
        String flow = SourceNails.methodBody(source, "private static void downloadFlow(AtomicBoolean cancelled){");
        check(flow.contains("if(!china)"), "the IP verdict must choose the branch");
        check(flow.contains("openBrowserSearch()"), "outside China the agreed action is the mod browser");
        check(flow.contains("downloadJar(cancelled)"), "in China the jar is fetched immediately");
        check(flow.contains("Version.isAtLeast(meta.minGameVersion)"),
            "the newest MDT may need a newer game; installing it anyway would leave a broken mod");
        check(flow.contains("Core.bundle.format(\"logicsugar.mdt.incompatible\""),
            "the version refusal must tell the player which versions disagree");
        // A lagging mirror can serve an MDT older than the fix release. It must be refused before
        // importMod(), with the file deleted, exactly like the minGameVersion refusal.
        int tooOld = flow.indexOf("!versionAtLeast(meta.version, minMdtVersion)");
        check(tooOld >= 0, "the download flow must refuse an MDT older than minMdtVersion");
        check(tooOld < flow.indexOf("Vars.mods.importMod(file, true)"),
            "the minimum-version gate must run before the importer sees the jar");
        String gate = flow.substring(tooOld, flow.indexOf("Vars.mods.importMod(file, true)"));
        check(gate.contains("file.delete()"),
            "a too-old download must be deleted instead of left in the mods folder");
        check(gate.contains("Core.bundle.format(\"logicsugar.mdt.tooold\""),
            "the too-old refusal needs its own message (the incompatible one names a game version)");
        check(flow.contains("Vars.mods.importMod(file, true)"),
            "the download must go through the game's importer, which handles zip naming and enabling");
        check(flow.contains("loaded.setRepo(repo)"),
            "without the repo the mod browser shows MDT as not installed and cannot update it");
        check(flow.contains("Vars.ui.showInfoOnHidden"), "the restart notice must be readable before exiting");
        check(flow.contains("Core.app.exit()"), "mod changes need a restart");

        String jar = SourceNails.methodBody(source, "static Fi downloadJar(AtomicBoolean cancelled){");
        check(jar.contains("resolveLatestAssetUrl()"), "metadata resolves the versioned asset name");
        check(jar.contains("latestJarUrl"), "the fixed asset name stays as the fallback");
        check(jar.contains("mirrorUrls("), "every route must be mirror-prefixed");

        String fetch = SourceNails.methodBody(source, "static boolean fetch(String url, Fi target, AtomicBoolean cancelled){");
        check(fetch.contains("looksLikeZip(target)"), "a proxy error page must be retried on the next route");
        check(fetch.contains("read != total"),
            "a mirror can answer 200 and stop early (measured); a truncated jar still starts with PK, "
                + "so the byte count has to be compared with Content-Length before the importer sees it");
        check(fetch.contains("cancelled.get()"), "the cancel button must actually stop the transfer");

        String begin = SourceNails.methodBody(source, "private static void beginDownload(BaseDialog dialog){");
        check(begin.contains("showPrompt()"),
            "cancelling the transfer must return to the prompt: the prompt is one-shot, so otherwise "
                + "the manual routes would be gone for this launch");
        check(begin.contains("installed()"),
            "the cancel path must not re-recommend MDT after a successful import");

        String browser = SourceNails.methodBody(source, "static void openBrowserSearch(){");
        check(browser.indexOf("fillSearchFieldReflective(browser, browserQuery)") < browser.indexOf("browser.show()"),
            "the filter field must be written before show(): the browser only rebuilds its list from "
                + "shown(), and Arc's TextField.setText() does not fire the change listener");
        check(browser.contains("fillSearchField(browser, browserQuery)"),
            "the visible search box must show the query too, not just the hidden filter field");

        String geo = SourceNails.methodBody(source, "static boolean likelyChina(){");
        check(geo.contains("Locale.getDefault().getCountry()"),
            "when no probe answers, the system locale decides; the mirror path is the safer default");
        check(geo.contains("return byLocale;"), "the fallback must return the locale verdict");

        check(source.contains("\"https://gh-proxy.com\""), "the mirror pool must include the tested proxy");
        check(geo.contains("chinaByNetwork()"), "the IP path must actually be consulted");
    }

    private static Properties load(File root, String name) throws IOException{
        Properties properties = new Properties();
        try(Reader reader = new InputStreamReader(
            Files.newInputStream(root.toPath().resolve("assets/bundles").resolve(name)), StandardCharsets.UTF_8)){
            properties.load(reader);
        }
        return properties;
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
