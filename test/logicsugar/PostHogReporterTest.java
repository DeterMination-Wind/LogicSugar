package logicsugar;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * PostHog reporting contract: the event/property names the dashboard already queries, JSON escaping
 * for free-form feedback, the minute accumulator, and the dual-form feedback entry.
 *
 * <p>No HTTP request is made here. The wire shape is pinned as text so a rename cannot silently
 * break the existing LogicSugar dashboard ({@code logicsugar_session_end} with
 * {@code usage_minutes}, {@code game_version}, {@code mod_version}); country is enriched by PostHog
 * from the request IP and therefore must never appear in our payload.</p>
 */
public final class PostHogReporterTest{
    private PostHogReporterTest(){}

    public static void main(String[] args) throws IOException{
        escaping();
        sessionPayloadContract();
        feedbackPayloadContract();
        minuteAccumulator();
        bundleContract();
        dualFormEntry();
        System.out.println("PostHogReporterTest: all checks passed");
    }

    private static void escaping(){
        check("plain".equals(PostHogReporter.escapeJson("plain")), "plain text must pass through");
        check("a\\\"b".equals(PostHogReporter.escapeJson("a\"b")), "quotes must be escaped");
        check("a\\\\b".equals(PostHogReporter.escapeJson("a\\b")), "backslashes must be escaped");
        check("a\\nb\\tc\\rd".equals(PostHogReporter.escapeJson("a\nb\tc\rd")),
            "control characters must use JSON escapes");
        check("\\u0001".equals(PostHogReporter.escapeJson("\u0001")),
            "other control characters must use \\u escapes");
        check("中文反馈".equals(PostHogReporter.escapeJson("中文反馈")),
            "non-ASCII text must stay readable UTF-8, not be escaped");
        check("".equals(PostHogReporter.escapeJson(null)), "null must become an empty string");
    }

    private static void sessionPayloadContract(){
        String json = PostHogReporter.sessionPayload("player-1", "name", "160.1", "5.9.2",
            17, "2026-11-02T12:00:00Z", true, "zh_CN", "desktop");
        require(json, "\"event\":\"logicsugar_session_end\"", "session event name");
        require(json, "\"distinct_id\":\"player-1\"", "distinct id");
        require(json, "\"timestamp\":\"2026-11-02T12:00:00Z\"", "timestamp");
        require(json, "\"player_id\":\"player-1\"", "player id property");
        require(json, "\"player_name\":\"name\"", "player name property");
        require(json, "\"game_version\":\"160.1\"", "game version property");
        require(json, "\"mod_version\":\"5.9.2\"", "mod version property");
        require(json, "\"usage_minutes\":17", "usage minutes must be a JSON number");
        require(json, "\"bundled\":true", "bundled flag");
        require(json, "\"locale\":\"zh_CN\"", "locale property");
        require(json, "\"platform\":\"desktop\"", "platform property");
        check(!json.contains("$geoip"), "country must be left to PostHog ingestion, not sent by the mod");
        check(!json.contains("usage_minutes\":\"17\""), "usage_minutes must not be quoted");
    }

    private static void feedbackPayloadContract(){
        String message = "第一行\n第二行 \"quoted\" \\ tail";
        String json = PostHogReporter.feedbackPayload("player-2", "name", "160.1", "180003",
            message, "2026-11-02T12:00:00Z", true, "en_US", "android");
        require(json, "\"event\":\"logicsugar_feedback\"", "feedback event name");
        require(json, "\"mod_version\":\"180003\"", "bundled host version must stay a string");
        require(json, "\"platform\":\"android\"", "platform property");
        require(json, "\"message\":\"第一行\\n第二行 \\\"quoted\\\" \\\\ tail\"",
            "feedback text must be JSON escaped");
        require(json, "\"distinct_id\":\"player-2\"", "feedback distinct id");
        check(!json.contains("\"usage_minutes\""), "feedback is not a usage event");
    }

    private static void minuteAccumulator(){
        check(PostHogReporter.sessionMinutes(0) == 0, "zero seconds is zero minutes");
        check(PostHogReporter.sessionMinutes(59.9) == 0, "partial minutes are carried over");
        check(PostHogReporter.sessionMinutes(60) == 1, "sixty seconds is one minute");
        check(PostHogReporter.sessionMinutes(119.9) == 1, "whole minutes only");
        check(PostHogReporter.sessionMinutes(120) == 2, "two minutes reach the upload threshold");
        check(PostHogReporter.sessionMinutes(-5) == 0, "negative time is clamped");
        check(PostHogReporter.sessionMinutes(Double.NaN) == 0, "NaN is clamped");
        check(PostHogReporter.sessionMinutes(Double.POSITIVE_INFINITY) == 0, "infinity is clamped");

        check(PostHogReporter.nextPending(0, 1) == 1, "short session accumulates");
        check(PostHogReporter.nextPending(1, 1) == 2, "pending rolls into the next launch");
        check(PostHogReporter.nextPending(-3, 2) == 2, "negative pending is clamped");
        check(PostHogReporter.nextPending(2, -5) == 2, "negative session is clamped");
        check(PostHogReporter.minFlushMinutes == 2, "dashboard expectations assume roughly 2-minute uploads");
    }

    private static void bundleContract() throws IOException{
        File root = SourceNails.root();
        String[] keys = {
            "logicsugar.feedback",
            "logicsugar.feedback.title",
            "logicsugar.feedback.hint",
            "logicsugar.feedback.placeholder",
            "logicsugar.feedback.privacy",
            "logicsugar.feedback.send",
            "logicsugar.feedback.sending",
            "logicsugar.feedback.sent",
            "logicsugar.feedback.failed",
            "logicsugar.feedback.empty"
        };
        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties bundle = load(root, name);
            for(String key : keys){
                require(bundle, name, key);
            }
        }
    }

    /** The settings page and the Neon aggregate form must both expose the feedback row. */
    private static void dualFormEntry() throws IOException{
        String settings = SourceNails.readSource("src/logicsugar/LogicSugarSettings.java");
        String mod = SourceNails.readSource("src/logicsugar/LogicSugarMod.java");
        check(SourceNails.methodBody(settings, "private static void build(").contains("new FeedbackButtonSetting()"),
            "standalone settings page must register the feedback row");
        check(SourceNails.methodBody(mod, "public void bekBuildSettings(")
                .contains("new LogicSugarSettings.FeedbackButtonSetting()"),
            "Neon aggregate settings page must register the feedback row");
        check(SourceNails.methodBody(mod, "public void init(").contains("PostHogReporter.install()"),
            "the reporter must be installed on client load");
    }

    private static Properties load(File root, String name) throws IOException{
        Properties properties = new Properties();
        try(Reader reader = new InputStreamReader(
            Files.newInputStream(root.toPath().resolve("assets/bundles").resolve(name)), StandardCharsets.UTF_8)){
            properties.load(reader);
        }
        return properties;
    }

    private static void require(Properties bundle, String name, String key){
        check(bundle.getProperty(key) != null, name + " is missing " + key);
    }

    private static void require(String json, String needle, String what){
        check(json.contains(needle), what + " missing from " + json);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
