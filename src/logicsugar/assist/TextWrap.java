package logicsugar.assist;

import arc.func.Floatf;

/**
 * Greedy word wrap for the two places LogicSugar shows text that vanilla leaves on one line:
 * statement hover hints and settings descriptions.
 *
 * <p>Both are bundle strings with no length limit, and an Arc {@code Tooltip} only clamps its
 * <em>position</em>, never its width, so the longest settings descriptions (~500 characters) run
 * off both edges of the screen and are unreadable. Inserting real newlines is the only fix that
 * works for every label style: once the text has line breaks, the label's preferred width is the
 * widest line, and the tooltip containers size themselves from that.</p>
 *
 * <p>The width measurement is injected instead of being hard-wired to
 * {@link arc.graphics.g2d.GlyphLayout}, so the line-breaking rule can be pinned by a headless test
 * with a cheap stand-in measurer; the glyph-accurate one lives in {@link SugarTooltip}.</p>
 */
public final class TextWrap{

    private TextWrap(){}

    /**
     * Inserts newlines into {@code text} so that no line measures wider than {@code maxWidth}.
     * Existing newlines are kept as hard breaks.
     *
     * <p>Idempotent: text that already fits is returned unchanged, and re-wrapping an already
     * wrapped string reproduces it (every existing line fits and is therefore kept whole). That is
     * what makes it safe to run the settings re-flow more than once.</p>
     */
    public static String wrap(String text, float maxWidth, Floatf<String> measure){
        if(text == null || text.isEmpty() || maxWidth <= 0f) return text;

        String[] paragraphs = text.split("\n", -1);
        if(paragraphs.length == 1 && measure.get(text) <= maxWidth) return text;

        StringBuilder out = new StringBuilder(text.length() + 16);
        for(int i = 0; i < paragraphs.length; i++){
            if(i > 0) out.append('\n');
            wrapParagraph(out, paragraphs[i], maxWidth, measure);
        }
        return out.toString();
    }

    private static void wrapParagraph(StringBuilder out, String paragraph, float maxWidth, Floatf<String> measure){
        StringBuilder line = new StringBuilder();
        // Split on single spaces: these are bundle strings holding prose, and collapsing runs of
        // whitespace is what keeps the greedy test (line + " " + word) an honest predictor of the
        // result. Colour markup tags are ordinary tokens here and stay attached to their word.
        for(String word : paragraph.split(" ")){
            if(word.isEmpty()) continue;
            if(line.length() > 0 && measure.get(line + " " + word) <= maxWidth){
                line.append(' ').append(word);
                continue;
            }
            if(line.length() > 0){
                out.append(line).append('\n');
                line.setLength(0);
            }
            if(measure.get(word) <= maxWidth){
                line.append(word);
                continue;
            }
            // A single token wider than the box on its own (a long identifier, a pasted path):
            // fill whole lines up to the limit and keep the remainder on the current line, never
            // dropping a character. A "[tag]" is copied whole so colour markup survives the break.
            int start = 0;
            while(start < word.length()){
                int fit = Math.max(1, fittingPrefix(word, start, maxWidth, measure));
                if(start + fit >= word.length()){
                    line.append(word, start, word.length());
                    break;
                }
                out.append(word, start, start + fit).append('\n');
                start += fit;
            }
        }
        if(line.length() > 0) out.append(line);
    }

    /** Length of the longest prefix of {@code word} starting at {@code start} that still fits. */
    private static int fittingPrefix(String word, int start, float maxWidth, Floatf<String> measure){
        int next = start;
        int lastFit = 0;
        while(next < word.length()){
            int end = next + 1;
            if(word.charAt(next) == '['){
                int close = word.indexOf(']', next);
                if(close >= 0) end = close + 1;
            }
            if(measure.get(word.substring(start, end)) > maxWidth) break;
            lastFit = end - start;
            next = end;
        }
        return lastFit;
    }
}
