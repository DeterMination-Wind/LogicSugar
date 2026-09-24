package logicsugar.assist;

import arc.func.Floatf;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins the line-breaking rule behind {@link SugarTooltip}. The measurement is a plain character
 * count here, so the expectations are exact and the test runs without a graphics context.
 */
public class TextWrapTest{
    /** What {@link SugarTooltip} measures in the real UI, replaced by a character count. */
    private static final Floatf<String> chars = text -> text.length();

    public static void main(String[] args){
        shortTextIsReturnedUnchanged();
        breaksOnSpacesWithNoLineOverTheLimit();
        overlongWordIsHardBrokenWithoutLosingCharacters();
        markupTagIsNeverSplit();
        existingNewlinesSurvive();
        wrappingIsIdempotent();
        System.out.println("LogicSugar TextWrap test passed.");
    }

    private static void shortTextIsReturnedUnchanged(){
        String text = "Array name used with subscripts";
        check(TextWrap.wrap(text, 200f, chars) == text, "a fitting string must be returned as-is");
        check(TextWrap.wrap("", 10f, chars).isEmpty(), "empty input must survive");
        check(TextWrap.wrap(null, 10f, chars) == null, "null input must survive");
    }

    private static void breaksOnSpacesWithNoLineOverTheLimit(){
        // "aaa bbb" is exactly the limit (7), so it stays; adding "ccc" would make 11
        String wrapped = TextWrap.wrap("aaa bbb ccc ddd", 7f, chars);
        check(wrapped.equals("aaa bbb\nccc ddd"), "unexpected wrap: " + wrapped);

        String longText = "one two three four five six seven eight nine ten eleven twelve";
        List<String> lines = lines(TextWrap.wrap(longText, 20f, chars));
        for(String line : lines){
            check(line.length() <= 20, "line over the limit: '" + line + "'");
        }
        // no hard breaks were needed, so the words are all still there in order
        check(String.join(" ", lines).equals(longText), "words lost or reordered: " + lines);
    }

    private static void overlongWordIsHardBrokenWithoutLosingCharacters(){
        String wrapped = TextWrap.wrap("abcdefghij", 4f, chars);
        check(wrapped.equals("abcd\nefgh\nij"), "unexpected hard break: " + wrapped);
        check(wrapped.replace("\n", "").equals("abcdefghij"), "characters lost in the hard break");

        // a word that fits exactly on a line of its own must not be broken
        check(TextWrap.wrap("abcd", 4f, chars).equals("abcd"), "exact fit must not break");
    }

    private static void markupTagIsNeverSplit(){
        // A tag must travel with the text it colours, so the break has to fall after ']'
        String wrapped = TextWrap.wrap("[red]abcdef", 6f, chars);
        List<String> lines = lines(wrapped);
        check(lines.get(0).startsWith("[red]"), "tag was split away from its text: " + wrapped);
        for(String line : lines){
            int open = line.indexOf('[');
            if(open >= 0) check(line.indexOf(']', open) > open, "unclosed tag on line: " + line);
        }
        check(String.join("", lines).equals("[red]abcdef"), "characters lost around the tag");
    }

    private static void existingNewlinesSurvive(){
        String text = "short line\nanother line here";
        check(TextWrap.wrap(text, 100f, chars).equals(text), "hard newline was not preserved");
        // the hard break still counts as a limit: neither paragraph may exceed the width
        List<String> lines = lines(TextWrap.wrap(text, 12f, chars));
        check(lines.size() == 3, "expected both paragraphs wrapped, got " + lines);
        check(lines.get(0).equals("short line"), "first paragraph changed: " + lines.get(0));
        check(lines.get(1).equals("another line"), "second paragraph not wrapped: " + lines.get(1));
    }

    private static void wrappingIsIdempotent(){
        // the settings re-flow runs more than once, so a wrapped string must re-wrap to itself
        String[] samples = {
            "aaa bbb ccc ddd",
            "abcdefghij",
            "[red]abcdef",
            "one two three four five six seven eight nine ten",
            "short line\nanother line here"
        };
        float[] widths = {7f, 4f, 6f, 20f, 12f};
        for(int i = 0; i < samples.length; i++){
            String once = TextWrap.wrap(samples[i], widths[i], chars);
            String twice = TextWrap.wrap(once, widths[i], chars);
            check(once.equals(twice), "not idempotent for '" + samples[i] + "': " + once + " -> " + twice);
        }
    }

    private static List<String> lines(String text){
        List<String> result = new ArrayList<>();
        for(String line : text.split("\n", -1)) result.add(line);
        return result;
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
