package logicsugar;

import arc.Core;
import arc.files.Fi;
import arc.files.ZipFi;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.Icon;
import mindustry.mod.Mods;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.ModBrowserDialog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * First-launch recommendation that hands the player over to Mlog Dev Tools (MDT).
 *
 * <p>LogicSugar no longer ships MDT's debugger (see {@code docs/upstream.md}), so the two mods are
 * meant to be installed side by side. This dialog is the one-time pointer to that mod:</p>
 *
 * <ul>
 *   <li><b>Download</b> - in mainland China (detected by IP, with the system locale as the fallback
 *       when every probe fails) the jar is fetched through a GitHub mirror pool and the game is
 *       restarted, matching what the in-game mod importer does. Outside China the same button opens
 *       the mod browser with "Mlog Dev Tools" already typed in, because GitHub is reachable there
 *       and the browser is the normal update path.</li>
 *   <li><b>Mod browser / GitHub release</b> - manual routes that work no matter what the probes
 *       said, and the escape hatch when the newest MDT needs a newer game than this one.</li>
 * </ul>
 *
 * <p>The prompt is standalone-only ({@code !bekBundled}) and shows at most once, even if it is
 * dismissed with Esc: the flag is written when the dialog is actually shown, not when it is
 * answered. A host that already ships MDT is detected up front and never sees it.</p>
 */
public final class MdtRecommendation{
    /** Repository that publishes MDT (renamed from MlogAssertions at v0.12.0). */
    static final String repo = "cardillan/MlogDevTools";
    /** Display name used by MDT's own {@code mod.hjson}. */
    static final String displayName = "Mlog Dev Tools";
    /** Internal mod name (meta.name) of MDT; older builds used the same {@code mlog-assertions} stem. */
    static final String internalName = "mlog-assertions-8";
    /** Search text typed into the mod browser when the player is outside mainland China. */
    static final String browserQuery = "Mlog Dev Tools";

    static final String releasePage = "https://github.com/" + repo + "/releases/latest";
    static final String latestJarUrl = "https://github.com/" + repo + "/releases/latest/download/MlogDevTools.jar";
    static final String latestApiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";

    /** Set once the prompt has been shown, so it never nags on later launches. */
    static final String settingShown = "logicsugar.mdt.recommendShown";

    /**
     * GitHub proxy prefixes, in the order they are tried. Taken from Xenon's
     * {@code MirrorDownloader.MIRRORS} (the launcher's measured mainland pool) and reordered by a
     * 2026-11 probe of this repo's own release asset: the first two serve both the API and the jar,
     * the rest serve the jar only. Dead entries are kept at the tail - the download budget stops the
     * chain, and a mirror that is down for one network is often up for another.
     */
    static final String[] mirrors = {
        "https://gh-proxy.com",
        "https://github.chenc.dev",
        "https://ghfast.top",
        "https://gh.tinylake.top",
        "https://gh.monlor.com",
        "https://gh.dpik.top",
        "https://ghpxy.hwinzniej.top",
        "https://ghfile.geekertao.top",
        "https://gh.llkk.cc",
        "https://ghproxy.cc"
    };

    /** IP geolocation probes. Cloudflare's trace is plain text, ip.sb is JSON, ipapi.co is a bare code. */
    static final String[] geoServices = {
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://api.ip.sb/geoip",
        "https://ipapi.co/country/"
    };

    private static final String logTag = "[LogicSugar/MDT]";
    private static final int probeConnectTimeout = 3500;
    private static final int probeReadTimeout = 4000;
    private static final long probeBudgetMs = 5000L;
    private static final int connectTimeout = 5000;
    private static final int readTimeout = 15000;
    /** Metadata gets its own budget: a dead API route must not eat the jar's download window. */
    private static final long metadataBudgetMs = 25_000L;
    /** Total budget for the jar transfer; the mirror chain can be long, but never unbounded. */
    private static final long downloadBudgetMs = 90_000L;
    private static final int maxDeferrals = 20;

    private static boolean expired(){
        return deadline > 0 && System.currentTimeMillis() > deadline;
    }

    /** Progress of the active download, read by the loading bar on the render thread. */
    private static volatile float progress;
    private static volatile long deadline;

    private static boolean shownThisSession;
    private static int deferrals;

    private MdtRecommendation(){}

    /**
     * Entry point from {@code ClientLoadEvent}. Deferred by a second and retried while another
     * dialog owns the screen - the editor-conflict prompt is shown from the same event, and a
     * second modal would cover the one the player is answering.
     */
    static void maybeShow(){
        if(shownThisSession || Vars.headless || Vars.ui == null) return;
        if(LogicSugarMod.bekBundled) return;
        if(Core.settings.getBool(settingShown, false)) return;

        // Installed but disabled still counts: downloading another copy would not help, and the
        // mods dialog is where that state is fixed.
        if(installed()){
            Core.settings.put(settingShown, true);
            return;
        }

        if(Core.scene != null && Core.scene.hasDialog()){
            if(++deferrals <= maxDeferrals){
                Time.run(120f, MdtRecommendation::maybeShow);
            }else{
                // Give up for this launch without writing the flag: the next launch tries again at
                // a point where the screen is more likely to be free.
                Log.info(logTag + " another dialog stayed open; the recommendation waits for the next launch.");
            }
            return;
        }

        showPrompt();
    }

    /** Whether MDT is present, enabled or not. */
    static boolean installed(){
        if(Vars.mods == null) return false;
        for(Mods.LoadedMod mod : Vars.mods.list()){
            if(mod == null) continue;
            String name = mod.name;
            String display = mod.meta == null ? null : mod.meta.displayName;
            String repoName = null;
            try{
                repoName = mod.getRepo();
            }catch(Throwable ignored){
                // A headless test host has no settings; identity by name is enough there.
            }
            if(isMdtMod(name, display, repoName)) return true;
        }
        return false;
    }

    /** Pure identity check: MDT across its old and new names, its display name and its repo. */
    static boolean isMdtMod(String internalName, String display, String repoName){
        if(repoName != null && repoName.trim().equalsIgnoreCase(repo)) return true;
        if(internalName != null){
            String name = internalName.trim().toLowerCase(Locale.ROOT);
            if(name.startsWith("mlog-assertions") || name.startsWith("mlogdevtools")) return true;
        }
        return display != null && display.trim().equalsIgnoreCase(displayName);
    }

    // ===== prompt =====

    private static void showPrompt(){
        shownThisSession = true;

        BaseDialog dialog = new BaseDialog(Core.bundle.get("logicsugar.mdt.title", "Note"));
        float textWidth = Math.min(560f, Core.graphics.getWidth() / 1.25f / Scl.scl(1f));
        dialog.cont.pane(p -> p.add(Core.bundle.get("logicsugar.mdt.recommend"))
                .width(textWidth).wrap().labelAlign(Align.left).pad(8f))
            .growX().maxHeight(Core.graphics.getHeight() / 2.1f / Scl.scl(1f)).scrollX(false);

        float buttonWidth = Math.min(240f, Core.graphics.getWidth() / 2.4f / Scl.scl(1f));
        dialog.buttons.defaults().size(buttonWidth, 58f).pad(3f);

        // Same order as the recommended-actions list in the prompt text: declining, GitHub, browser,
        // then the one-click action. The long two share a row so neither label has to be abbreviated.
        dialog.buttons.button("@logicsugar.mdt.no", Icon.left, dialog::hide);
        dialog.buttons.button("@logicsugar.mdt.github", MdtRecommendation::openReleasePage);
        dialog.buttons.row();
        dialog.buttons.button("@logicsugar.mdt.browser", MdtRecommendation::openBrowserSearch);
        dialog.buttons.button("@logicsugar.mdt.download", Icon.download, () -> beginDownload(dialog));

        dialog.shown(() -> Core.settings.put(settingShown, true));
        dialog.show();
    }

    private static void openReleasePage(){
        Core.app.openURI(releasePage);
    }

    /**
     * Opens the game's own mod browser with MDT already filtered in.
     *
     * <p>Order matters: the filter is the backing {@code searchtxt} field, and only {@code show()}
     * re-runs the browser's {@code rebuildBrowser()}. Typing into the dialog's {@code TextField}
     * does not help on its own - Arc's {@code setText()} only fires change listeners when
     * {@code programmaticChangeEvents} is enabled, which the browser leaves off - so the field is
     * written first (reflection, with a vanilla/MindustryX field name), the visible text box is
     * filled for the player, and the dialog is shown last.</p>
     */
    static void openBrowserSearch(){
        if(Vars.ui == null || Vars.ui.mods == null || Vars.ui.mods.browser == null){
            toast("logicsugar.mdt.browserfailed");
            return;
        }
        ModBrowserDialog browser = Vars.ui.mods.browser;
        boolean filtered = fillSearchFieldReflective(browser, browserQuery);
        fillSearchField(browser, browserQuery);
        browser.show();
        if(!filtered){
            toast("logicsugar.mdt.browserfailed");
            return;
        }
        toast("logicsugar.mdt.opened");
    }

    /** Types into the browser's TextField, whose change listener also rebuilds the list. */
    static boolean fillSearchField(Group group, String text){
        if(group == null) return false;
        for(Element child : group.getChildren().toArray()){
            if(child instanceof TextField field){
                field.setText(text);
                return true;
            }
            if(child instanceof Group nested && fillSearchField(nested, text)) return true;
        }
        return false;
    }

    /** Fallback for a browser whose field was never built: set the value rebuildBrowser() reads. */
    static boolean fillSearchFieldReflective(Object browser, String text){
        try{
            Class<?> type = browser.getClass();
            while(type != null){
                try{
                    var field = type.getDeclaredField("searchtxt");
                    field.setAccessible(true);
                    field.set(browser, text);
                    return true;
                }catch(NoSuchFieldException ignored){
                    type = type.getSuperclass();
                }
            }
        }catch(Throwable t){
            Log.warn(logTag + " could not prefill the mod browser search", t);
        }
        return false;
    }

    // ===== download =====

    /**
     * Starts the download flow. Country detection and the transfer both run on a worker thread; only
     * the loading fragment and dialogs are touched from {@code Core.app.post}.
     */
    private static void beginDownload(BaseDialog dialog){
        if(Vars.ui == null) return;
        dialog.hide();

        AtomicBoolean cancelled = new AtomicBoolean();
        if(Vars.ui.loadfrag != null){
            Vars.ui.loadfrag.show(Core.bundle.get("logicsugar.mdt.detecting", displayName));
            Vars.ui.loadfrag.setButton(() -> {
                cancelled.set(true);
                Vars.ui.loadfrag.hide();
                Log.info(logTag + " download cancelled by the player.");
                // The prompt is one-shot, so cancelling must not be a dead end: bring it back, with
                // the manual routes still on screen. Unless the import already finished, in which
                // case MDT is installed and re-recommending it would be nonsense.
                Core.app.post(() -> {
                    if(!installed()) showPrompt();
                });
            });
        }

        Thread worker = new Thread(() -> downloadFlow(cancelled), "logicsugar-mdt-download");
        worker.setDaemon(true);
        worker.start();
    }

    private static void downloadFlow(AtomicBoolean cancelled){
        try{
            boolean china = likelyChina();
            if(cancelled.get()) return;

            if(!china){
                // Outside mainland China the mirror pool is the slow route and GitHub itself works;
                // the in-game browser also keeps future updates on the normal path.
                Core.app.post(() -> {
                    if(cancelled.get()) return;
                    hideLoading();
                    openBrowserSearch();
                });
                return;
            }

            Core.app.post(() -> {
                if(cancelled.get()) return;
                setLoadingText("logicsugar.mdt.downloading", displayName);
                if(Vars.ui != null && Vars.ui.loadfrag != null){
                    Vars.ui.loadfrag.setProgress(() -> progress);
                }
            });

            Fi file = downloadJar(cancelled);
            if(cancelled.get()) return;
            if(file == null){
                Core.app.post(() -> {
                    if(cancelled.get()) return;
                    hideLoading();
                    showFailure("logicsugar.mdt.failed");
                });
                return;
            }

            Mods.ModMeta meta = readMeta(file);
            if(meta == null){
                Log.warn(logTag + " the downloaded file is not a mod archive; discarding it.");
                file.delete();
                Core.app.post(() -> {
                    if(cancelled.get()) return;
                    hideLoading();
                    showFailure("logicsugar.mdt.invalid");
                });
                return;
            }

            if(!Version.isAtLeast(meta.minGameVersion)){
                Log.warn(logTag + " MDT @ needs game @, this game is @; not importing it.",
                    meta.version, meta.minGameVersion, Version.buildString());
                String required = meta.minGameVersion;
                String current = Version.buildString();
                file.delete();
                Core.app.post(() -> {
                    if(cancelled.get()) return;
                    hideLoading();
                    showFailure(Core.bundle.format("logicsugar.mdt.incompatible", required, current));
                });
                return;
            }

            Mods.LoadedMod loaded;
            try{
                loaded = Vars.mods.importMod(file, true);
                loaded.setRepo(repo);
            }catch(Throwable t){
                Log.err(logTag + " could not import the downloaded MDT jar", t);
                file.delete();
                Core.app.post(() -> {
                    if(cancelled.get()) return;
                    hideLoading();
                    showFailure("logicsugar.mdt.failed");
                });
                return;
            }finally{
                file.delete();
            }

            Log.info(logTag + " imported MDT @; exiting so the next launch loads it.", meta.version);
            Core.app.post(() -> {
                hideLoading();
                if(Vars.ui != null){
                    Vars.ui.showInfoOnHidden(Core.bundle.get("logicsugar.mdt.downloaded", displayName),
                        () -> Core.app.exit());
                }
            });
        }catch(Throwable t){
            Log.err(logTag + " download flow failed", t);
            Core.app.post(() -> {
                if(cancelled.get()) return;
                hideLoading();
                showFailure("logicsugar.mdt.failed");
            });
        }
    }

    /** Resolves the current release jar (through the mirrors), then fetches it. */
    static Fi downloadJar(AtomicBoolean cancelled){
        // Metadata first: it names the versioned asset, which keeps working even after a rename of
        // the release jar (the asset was called MlogAssertions.jar before v0.12.0).
        deadline = System.currentTimeMillis() + metadataBudgetMs;
        String asset = resolveLatestAssetUrl();
        deadline = System.currentTimeMillis() + downloadBudgetMs;

        List<String> candidates = mirrorUrls(asset == null ? latestJarUrl : asset);

        Fi target = Vars.tmpDirectory.child("logicsugar-mdt.jar");
        target.delete();

        for(String url : candidates){
            if(cancelled.get() || expired()) break;
            target.delete();
            progress = 0f;
            Log.info(logTag + " trying @", url);
            if(fetch(url, target, cancelled)) return target;
        }
        target.delete();
        return null;
    }

    /** GitHub API release metadata, read through the mirror pool; null when every route fails. */
    private static String resolveLatestAssetUrl(){
        for(String url : mirrorUrls(latestApiUrl)){
            if(expired()) return null;
            String body = fetchString(url);
            String asset = parseJarAsset(body);
            if(asset != null) return asset;
        }
        return null;
    }

    /** Picks the release jar out of a GitHub release JSON body. */
    static String parseJarAsset(String body){
        if(body == null || body.isEmpty()) return null;
        try{
            Jval root = Jval.read(body);
            if(root == null || !root.isObject()) return null;
            Jval assets = root.get("assets");
            if(assets == null || !assets.isArray()) return null;

            String fallback = null;
            for(Jval asset : assets.asArray()){
                String name = asset.getString("name", "");
                String url = asset.getString("browser_download_url", null);
                if(url == null || !name.endsWith(".jar")) continue;
                if(name.equalsIgnoreCase("MlogDevTools.jar")) return url;
                if(fallback == null) fallback = url;
            }
            return fallback;
        }catch(Throwable t){
            return null;
        }
    }

    /** Mirror-prefixed copies of a GitHub URL, with the direct origin as the last route. */
    static List<String> mirrorUrls(String githubUrl){
        List<String> urls = new ArrayList<>(mirrors.length + 1);
        for(String mirror : mirrors){
            urls.add(stripSlash(mirror) + "/" + githubUrl);
        }
        urls.add(githubUrl);
        return urls;
    }

    static String stripSlash(String url){
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** One download attempt. Returns false for an unreachable mirror, a bad status or a truncated file. */
    static boolean fetch(String url, Fi target, AtomicBoolean cancelled){
        HttpURLConnection connection = null;
        try{
            connection = open(url, connectTimeout, readTimeout);
            int code = connection.getResponseCode();
            if(code != 200){
                Log.info(logTag + " HTTP @ from @", code, url);
                return false;
            }

            // getContentLengthLong() is API 24; Mindustry declares minSdk 21 and the jar is far below
            // the int overflow point, so the int accessor is the portable one here.
            long total = connection.getContentLength();
            long read = 0;
            try(InputStream in = connection.getInputStream(); OutputStream out = target.write(false)){
                byte[] buffer = new byte[8192];
                int length;
                while((length = in.read(buffer)) >= 0){
                    if(cancelled.get() || expired()) return false;
                    out.write(buffer, 0, length);
                    read += length;
                    if(total > 0) progress = Math.min(0.99f, read / (float)total);
                }
            }

            // A mirror answered with HTTP 200 but stopped early (measured on 2026-11); the truncated
            // file still starts with PK, so the size check has to happen here, before readMeta().
            if(total > 0 && read != total){
                Log.info(logTag + " @ returned @ of @ bytes; trying the next route", url, read, total);
                return false;
            }

            if(!looksLikeZip(target)){
                Log.info(logTag + " @ did not return a jar; trying the next route", url);
                return false;
            }
            progress = 1f;
            return true;
        }catch(Throwable t){
            Log.info(logTag + " @ failed: @", url, t.toString());
            return false;
        }finally{
            if(connection != null) connection.disconnect();
        }
    }

    /** First four bytes of the jar: proxy error pages are HTML, and readMeta() would reject them too late to retry. */
    static boolean looksLikeZip(Fi file){
        byte[] head = new byte[4];
        int read = 0;
        try(InputStream in = file.read()){
            while(read < head.length){
                int count = in.read(head, read, head.length - read);
                if(count <= 0) break;
                read += count;
            }
        }catch(Throwable t){
            return false;
        }
        return looksLikeZipHead(head, read);
    }

    static boolean looksLikeZipHead(byte[] head, int length){
        return head != null && length >= 4 && head[0] == 'P' && head[1] == 'K'
            && (head[2] == 3 || head[2] == 5 || head[2] == 7);
    }

    /** Opens a jar as a zip and reads its metadata; null when it is not a mod archive. */
    static Mods.ModMeta readMeta(Fi file){
        ZipFi zip = null;
        try{
            zip = new ZipFi(file);
            return Vars.mods == null ? null : Vars.mods.findMeta(zip);
        }catch(Throwable t){
            return null;
        }finally{
            if(zip != null) zip.delete();
        }
    }

    private static HttpURLConnection open(String url, int connect, int read) throws Exception{
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(connect);
        connection.setReadTimeout(read);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "LogicSugar-MDT");
        connection.setRequestProperty("Accept", "application/octet-stream, application/json, */*");
        // Progress is byte-based; a gzip body would make Content-Length lie about it.
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static String fetchString(String url){
        HttpURLConnection connection = null;
        try{
            connection = open(url, probeConnectTimeout, probeReadTimeout);
            if(connection.getResponseCode() != 200) return null;
            try(InputStream in = connection.getInputStream()){
                ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
                byte[] buffer = new byte[4096];
                int length;
                int total = 0;
                while((length = in.read(buffer)) >= 0){
                    out.write(buffer, 0, length);
                    total += length;
                    // Release metadata is a few KB; anything this large is not the API response.
                    if(total > 512 * 1024) return null;
                }
                return out.toString("UTF-8");
            }
        }catch(Throwable t){
            return null;
        }finally{
            if(connection != null) connection.disconnect();
        }
    }

    // ===== country detection =====

    /**
     * Whether this connection is in mainland China. Probes run in parallel and answer as soon as any
     * of them does; a failed probe set falls back to the system locale, and a system that cannot be
     * identified either is treated as China because that path ends with the direct GitHub route.
     */
    static boolean likelyChina(){
        Boolean byNetwork = chinaByNetwork();
        if(byNetwork != null) return byNetwork;

        String country = Locale.getDefault().getCountry();
        boolean byLocale = country != null && country.equalsIgnoreCase("CN");
        Log.info(logTag + " no geo probe answered; falling back to the system locale (@): @",
            country, byLocale ? "China" : "not China");
        return byLocale;
    }

    /** {@code null} when no probe produced a verdict. Any China answer wins over a non-China one. */
    static Boolean chinaByNetwork(){
        AtomicReference<Boolean> verdict = new AtomicReference<>();
        if(geoServices.length > 0){
            CountDownLatch latch = new CountDownLatch(geoServices.length);
            for(String service : geoServices){
                Thread probe = new Thread(() -> {
                    try{
                        Boolean answer = parseCountry(fetchString(service));
                        if(answer != null){
                            if(answer){
                                verdict.set(Boolean.TRUE);
                            }else{
                                verdict.compareAndSet(null, Boolean.FALSE);
                            }
                        }
                    }finally{
                        latch.countDown();
                    }
                }, "logicsugar-mdt-geo");
                probe.setDaemon(true);
                probe.start();
            }
            try{
                latch.await(probeBudgetMs, TimeUnit.MILLISECONDS);
            }catch(InterruptedException ignored){
                Thread.currentThread().interrupt();
            }
        }
        return verdict.get();
    }

    /** Parses a probe body: Cloudflare's {@code loc=} line, a JSON body, or a bare country code. */
    static Boolean parseCountry(String body){
        if(body == null) return null;
        String text = body.trim();
        if(text.isEmpty()) return null;

        for(String line : text.split("\n")){
            int equals = line.indexOf('=');
            if(equals > 0 && line.substring(0, equals).trim().equalsIgnoreCase("loc")){
                return isChinaCountry(line.substring(equals + 1));
            }
        }

        String code = jsonStringValue(text, "country_code");
        if(code == null) code = jsonStringValue(text, "countryCode");
        if(code != null) return isChinaCountry(code);

        if(text.length() <= 3) return isChinaCountry(text);
        return null;
    }

    static boolean isChinaCountry(String value){
        if(value == null) return false;
        String text = value.trim().toLowerCase(Locale.ROOT);
        return text.equals("cn") || text.equals("chn") || text.equals("china")
            || text.equals("中国") || text.equals("中国大陆") || text.equals("中华人民共和国");
    }

    /** Minimal quoted-value read; the probe bodies are tiny and this avoids a JSON dependency there. */
    static String jsonStringValue(String body, String key){
        if(body == null || key == null) return null;
        int at = body.indexOf("\"" + key + "\"");
        if(at < 0) return null;
        int colon = body.indexOf(':', at + key.length() + 2);
        if(colon < 0) return null;
        int start = body.indexOf('"', colon + 1);
        if(start < 0) return null;
        int end = body.indexOf('"', start + 1);
        return end < 0 ? null : body.substring(start + 1, end);
    }

    // ===== UI plumbing =====

    private static void hideLoading(){
        if(Vars.ui != null && Vars.ui.loadfrag != null) Vars.ui.loadfrag.hide();
    }

    private static void setLoadingText(String key, String fallback){
        if(Vars.ui != null && Vars.ui.loadfrag != null){
            Vars.ui.loadfrag.setText(Core.bundle.get(key, fallback));
        }
    }

    private static void toast(String key){
        if(Vars.ui != null) Vars.ui.showInfoFade(Core.bundle.get(key));
    }

    private static void showFailure(String bundleKeyOrText){
        showFailureWith(Core.bundle.get(bundleKeyOrText, bundleKeyOrText));
    }

    /** Failure dialog that keeps both manual routes one click away. */
    private static void showFailureWith(String message){
        if(Vars.ui == null) return;

        BaseDialog dialog = new BaseDialog(Core.bundle.get("logicsugar.mdt.title", "Note"));
        float width = Math.min(520f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));
        dialog.cont.add(message).width(width).wrap().pad(12f).get().setAlignment(Align.center, Align.center);
        dialog.buttons.defaults().size(Math.min(220f, Core.graphics.getWidth() / 2.4f / Scl.scl(1f)), 54f).pad(3f);
        dialog.buttons.button("@logicsugar.mdt.browser", Icon.menu, () -> {
            dialog.hide();
            openBrowserSearch();
        });
        dialog.buttons.button("@logicsugar.mdt.github", Icon.github, () -> {
            dialog.hide();
            openReleasePage();
        });
        dialog.buttons.button("@ok", Icon.left, dialog::hide);
        dialog.show();
    }
}
