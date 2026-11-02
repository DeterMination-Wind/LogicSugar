package logicsugar;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.EventType;
import mindustry.mod.Mods;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Anonymous end-of-session usage reporting and user feedback for LogicSugar.
 *
 * <p>The mod reports one {@code logicsugar_session_end} event after enough in-game minutes have
 * accumulated, and a {@code logicsugar_feedback} event when the player submits the feedback
 * dialog. Country is not sent by the mod: PostHog derives {@code $geoip_country_name} from the
 * request IP on ingestion.</p>
 *
 * <p>Nothing here touches the saved processor program: reporting is local-only bookkeeping plus
 * one HTTP POST at shutdown (session) or on demand (feedback), so it cannot change multiplayer
 * compatibility.</p>
 */
public final class PostHogReporter{
    static final String eventSessionEnd = "logicsugar_session_end";
    static final String eventFeedback = "logicsugar_feedback";
    /** Sessions shorter than this roll into the next launch instead of uploading noise. */
    static final int minFlushMinutes = 2;

    private static final String logTag = "[LogicSugar/PostHog]";
    private static final String apiKey = "phc_ry3vePCjmUFcy9oQ765tTDA2FagUXVrMcw3DWPsVCkn9";
    private static final String endpoint = "https://us.i.posthog.com/i/v0/e/";
    private static final String keyPendingMinutes = "logicsugar.posthog.pendingMinutes";
    private static final String keyStablePlayerId = "logicsugar.posthog.playerId";

    private static final PostHogReporter instance = new PostHogReporter();

    private boolean installed;
    private volatile boolean flushed;
    private volatile double inGameSeconds;
    private Thread shutdownHook;
    private PlayerId cachedPlayerId;

    private PostHogReporter(){}

    /** Installs the session reporter once per client process. Headless servers report nothing. */
    public static void install(){
        instance.onClientLoad();
    }

    private void onClientLoad(){
        if(installed || Vars.headless) return;
        installed = true;

        Core.settings.defaults(keyPendingMinutes, 0);
        Core.settings.defaults(keyStablePlayerId, "");
        Log.info(logTag + " initialized. thresholdMinutes=" + minFlushMinutes
            + ", pendingMinutes=" + Math.max(0, Core.settings.getInt(keyPendingMinutes, 0)));

        Events.run(EventType.Trigger.update, this::onUpdate);
        Events.on(EventType.DisposeEvent.class, e -> flush("dispose-event"));
        installShutdownHook();

        cachedPlayerId = resolvePlayerId();
        Log.info(logTag + " player id ready. source=" + cachedPlayerId.source);
    }

    private void onUpdate(){
        if(flushed) return;
        if(Vars.state != null && Vars.state.isGame()){
            // Time.delta is already measured in seconds. Do not divide by 60 twice: the field is
            // seconds, and sessionMinutes() performs the only conversion.
            inGameSeconds += Time.delta;
        }
    }

    private synchronized void flush(String source){
        if(flushed || Vars.headless) return;
        flushed = true;

        int sessionMinutes = sessionMinutes(inGameSeconds);
        int pendingMinutes = Math.max(0, Core.settings.getInt(keyPendingMinutes, 0));
        int nextPending = nextPending(pendingMinutes, sessionMinutes);
        Log.info(logTag + " flush start. source=" + source + ", sessionMinutes=" + sessionMinutes
            + ", pendingBefore=" + pendingMinutes + ", pendingAfter=" + nextPending);

        // Persist before sending so a failed request (or a very short session) is retried next run.
        persistPendingMinutes(nextPending);
        if(nextPending < minFlushMinutes){
            Log.info(logTag + " skip upload: pendingAfter=" + nextPending + " < threshold=" + minFlushMinutes);
            return;
        }

        PlayerId id = cachedPlayerId == null ? resolvePlayerId() : cachedPlayerId;
        ModMeta meta = resolveModMeta();
        String payload = sessionPayload(id.value, playerName(), gameVersion(), meta.version,
            nextPending, utcTimestamp(), LogicSugarMod.bekBundled, localeName(), platformName());
        Log.info(logTag + " send prepared. distinct_id=" + id.value + ", source=" + id.source
            + ", game_version=" + gameVersion() + ", mod_version=" + meta.version
            + ", usage_minutes=" + nextPending + ", bundled=" + LogicSugarMod.bekBundled);

        if(send(payload)){
            persistPendingMinutes(0);
            Log.info(logTag + " upload success. pending reset to 0.");
        }else{
            Log.warn(logTag + " upload failed. keep pendingMinutes=" + nextPending + " for next run.");
        }
    }

    /**
     * Sends the feedback text as a dedicated PostHog event. HTTP runs on a daemon thread so the
     * settings dialog never blocks the render thread; {@code callback} is delivered on the app
     * thread with the request result.
     */
    public static void sendFeedback(String message, Cons<Boolean> callback){
        String text = message == null ? "" : message.trim();
        if(text.isEmpty()){
            postResult(callback, false);
            return;
        }

        // Read every game-side value on the caller (UI/app) thread; the worker only does HTTP.
        PlayerId id = instance.cachedPlayerId == null ? instance.resolvePlayerId() : instance.cachedPlayerId;
        ModMeta meta = resolveModMeta();
        String payload = feedbackPayload(id.value, playerName(), gameVersion(), meta.version,
            text, utcTimestamp(), LogicSugarMod.bekBundled, localeName(), platformName());
        Log.info(logTag + " feedback prepared. length=" + text.length() + ", distinct_id=" + id.value);

        Thread thread = new Thread(() -> {
            boolean ok = false;
            try{
                ok = send(payload);
            }catch(Throwable t){
                Log.warn(logTag + " feedback request exception: " + t);
            }
            postResult(callback, ok);
        }, "logicsugar-posthog-feedback");
        thread.setDaemon(true);
        thread.start();
    }

    static String sessionPayload(String distinctId, String playerName, String gameVersion, String modVersion,
                                 int usageMinutes, String timestamp, boolean bundled, String locale, String platform){
        return "{"
            + "\"api_key\":\"" + escapeJson(apiKey) + "\","
            + "\"event\":\"" + eventSessionEnd + "\","
            + "\"distinct_id\":\"" + escapeJson(distinctId) + "\","
            + "\"timestamp\":\"" + escapeJson(timestamp) + "\","
            + "\"properties\":{"
            + "\"player_id\":\"" + escapeJson(distinctId) + "\","
            + "\"player_name\":\"" + escapeJson(playerName) + "\","
            + "\"game_version\":\"" + escapeJson(gameVersion) + "\","
            + "\"mod_version\":\"" + escapeJson(modVersion) + "\","
            + "\"usage_minutes\":" + Math.max(0, usageMinutes) + ","
            + "\"bundled\":" + bundled + ","
            + "\"locale\":\"" + escapeJson(locale) + "\","
            + "\"platform\":\"" + escapeJson(platform) + "\""
            + "}}";
    }

    static String feedbackPayload(String distinctId, String playerName, String gameVersion, String modVersion,
                                   String message, String timestamp, boolean bundled, String locale, String platform){
        return "{"
            + "\"api_key\":\"" + escapeJson(apiKey) + "\","
            + "\"event\":\"" + eventFeedback + "\","
            + "\"distinct_id\":\"" + escapeJson(distinctId) + "\","
            + "\"timestamp\":\"" + escapeJson(timestamp) + "\","
            + "\"properties\":{"
            + "\"message\":\"" + escapeJson(message) + "\","
            + "\"player_id\":\"" + escapeJson(distinctId) + "\","
            + "\"player_name\":\"" + escapeJson(playerName) + "\","
            + "\"game_version\":\"" + escapeJson(gameVersion) + "\","
            + "\"mod_version\":\"" + escapeJson(modVersion) + "\","
            + "\"bundled\":" + bundled + ","
            + "\"locale\":\"" + escapeJson(locale) + "\","
            + "\"platform\":\"" + escapeJson(platform) + "\""
            + "}}";
    }

    /** Whole minutes represented by a raw seconds accumulator. */
    static int sessionMinutes(double seconds){
        if(!(seconds > 0) || Double.isInfinite(seconds)) return 0;
        return (int)(seconds / 60.0);
    }

    /** Rounds nothing: short sessions deliberately carry their remainder to the next launch. */
    static int nextPending(int pendingMinutes, int sessionMinutes){
        return Math.max(0, pendingMinutes) + Math.max(0, sessionMinutes);
    }

    private static boolean send(String json){
        HttpURLConnection conn = null;
        try{
            conn = (HttpURLConnection)new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "LogicSugar-PostHog");
            conn.setDoOutput(true);

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out = conn.getOutputStream()){
                out.write(bytes);
            }

            int code = conn.getResponseCode();
            String body = readBody(code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream());
            boolean ok = code >= 200 && code < 300;
            if(ok){
                Log.info(logTag + " HTTP " + code + " response=" + snippet(body));
            }else{
                Log.warn(logTag + " HTTP " + code + " response=" + snippet(body));
            }
            return ok;
        }catch(Throwable t){
            Log.warn(logTag + " request exception: " + t);
            return false;
        }finally{
            if(conn != null) conn.disconnect();
        }
    }

    private PlayerId resolvePlayerId(){
        String cached = normalizeRaw(Core.settings.getString(keyStablePlayerId, ""));
        if(isUsableId(cached)){
            return new PlayerId(cached, "stable-cache");
        }

        if(Vars.player != null){
            String rawPlayerUuid = normalizeRaw(Vars.player.uuid());
            if(isUsableId(rawPlayerUuid)){
                persistStablePlayerId(rawPlayerUuid);
                return new PlayerId(rawPlayerUuid, "player.uuid");
            }
        }

        if(Vars.platform != null){
            String platformUuid = normalizeRaw(Vars.platform.getUUID());
            if(isUsableId(platformUuid)){
                persistStablePlayerId(platformUuid);
                return new PlayerId(platformUuid, "platform.uuid");
            }
        }

        // Offline singleplayer can expose "[LOCAL]" from both sources. A per-install UUID keeps
        // DAU usable without merging every offline player into one "unknown" person.
        String generated = "ls-" + UUID.randomUUID();
        persistStablePlayerId(generated);
        return new PlayerId(generated, "generated");
    }

    private static ModMeta resolveModMeta(){
        String modVersion = "unknown";
        if(Vars.mods != null){
            // Standalone: the loaded mod's main class is LogicSugarMod itself.
            Mods.LoadedMod mod = Vars.mods.getMod(LogicSugarMod.class);
            // Bundled: LogicSugarMod is not the host's main class. The dashboard's N/B version
            // breakdown expects the host release code (e.g. 180003 -> B18.3) in that case.
            if(mod == null && LogicSugarMod.bekBundled){
                ClassLoader loader = LogicSugarMod.class.getClassLoader();
                for(Mods.LoadedMod candidate : Vars.mods.list()){
                    if(candidate != null && candidate.meta != null && candidate.main != null
                        && candidate.main.getClass().getClassLoader() == loader){
                        mod = candidate;
                        break;
                    }
                }
            }
            if(mod == null){
                mod = Vars.mods.getMod("LogicSugar");
            }
            if(mod != null && mod.meta != null){
                modVersion = normalize(mod.meta.version, "unknown");
            }
        }
        return new ModMeta(modVersion);
    }

    private static String playerName(){
        if(Vars.player == null || Vars.player.name == null) return "";
        return normalize(Vars.player.name, "");
    }

    private static String gameVersion(){
        return normalize(Version.buildString(), "unknown");
    }

    private static String localeName(){
        try{
            if(Core.bundle != null) return Core.bundle.getLocale().toString();
        }catch(Throwable ignored){
        }
        return Locale.getDefault().toString();
    }

    private static String platformName(){
        return Vars.mobile ? "android" : "desktop";
    }

    private static boolean isUsableId(String value){
        if(value == null) return false;
        String normalized = value.trim();
        if(normalized.isEmpty()) return false;
        return !"[LOCAL]".equalsIgnoreCase(normalized) && !"LOCAL".equalsIgnoreCase(normalized);
    }

    private static String normalizeRaw(String value){
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value, String fallback){
        if(value == null) return fallback;
        String stripped = Strings.stripColors(value).trim();
        return stripped.isEmpty() ? fallback : stripped;
    }

    private static String utcTimestamp(){
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    static String escapeJson(String value){
        if(value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            switch(c){
                case '\"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if(c < 0x20){
                        out.append(String.format("\\u%04x", (int)c));
                    }else{
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static String readBody(InputStream stream){
        if(stream == null) return "";
        try(InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream(256)){
            byte[] buffer = new byte[1024];
            int len;
            while((len = in.read(buffer)) >= 0){
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }catch(Throwable ignored){
            return "";
        }
    }

    private static String snippet(String text){
        if(text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        if(oneLine.length() <= 220) return oneLine;
        return oneLine.substring(0, 220) + "...";
    }

    private static void postResult(Cons<Boolean> callback, boolean ok){
        if(callback == null) return;
        if(Core.app != null){
            Core.app.post(() -> callback.get(ok));
        }else{
            callback.get(ok);
        }
    }

    private static void persistPendingMinutes(int value){
        Core.settings.put(keyPendingMinutes, Math.max(0, value));
        try{
            Core.settings.forceSave();
        }catch(Throwable t){
            Log.warn(logTag + " forceSave failed", t);
        }
    }

    private static void persistStablePlayerId(String value){
        if(!isUsableId(value)) return;
        String normalized = normalizeRaw(value);
        if(normalized.equals(normalizeRaw(Core.settings.getString(keyStablePlayerId, "")))) return;
        Core.settings.put(keyStablePlayerId, normalized);
        try{
            Core.settings.forceSave();
        }catch(Throwable t){
            Log.warn(logTag + " stable player id forceSave failed", t);
        }
    }

    private void installShutdownHook(){
        if(shutdownHook != null) return;
        shutdownHook = new Thread(() -> {
            try{
                flush("shutdown-hook");
            }catch(Throwable t){
                Log.warn(logTag + " shutdown hook flush exception", t);
            }
        }, "logicsugar-posthog-shutdown");
        shutdownHook.setDaemon(true);
        try{
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }catch(Throwable t){
            Log.warn(logTag + " failed to install shutdown hook", t);
        }
    }

    private static final class PlayerId{
        final String value;
        final String source;

        PlayerId(String value, String source){
            this.value = value;
            this.source = source;
        }
    }

    private static final class ModMeta{
        final String version;

        ModMeta(String version){
            this.version = version;
        }
    }
}
