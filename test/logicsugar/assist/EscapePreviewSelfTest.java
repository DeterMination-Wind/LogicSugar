package logicsugar.assist;

public final class EscapePreviewSelfTest{
    public static void main(String[] args){
        expect(EscapePreview.Status.preview, quoted("你好"),
            EscapePreview.analyze(quoted("\\u4F60\\u597D"), true));
        expect(EscapePreview.Status.preview, quoted("line\nnext"),
            EscapePreview.analyze(quoted("line\\nnext"), false));
        String quoteSlash = new String(new char[]{'"', '\\'});
        expect(EscapePreview.Status.preview, quoted(quoteSlash),
            EscapePreview.analyze(quoted(new String(new char[]{'\\', '"', '\\', '\\'})), true));
        expect(EscapePreview.Status.unsupported, quoted("你"),
            EscapePreview.analyze(quoted("\\u4F60"), false));
        expect(EscapePreview.Status.invalid, "\\u requires four hex digits",
            EscapePreview.analyze(quoted("\\u12xz"), true));
        expect(EscapePreview.Status.none, "", EscapePreview.analyze("plain", true));
        expect(EscapePreview.Status.none, "", EscapePreview.analyze("\"plain\\q\"", true));
        expect(EscapePreview.Status.none, "", EscapePreview.analyze(quoted("\\"), true));
        boolean modern = EscapePreview.modernEscapesSupported();
        String escapedQuote = new String(new char[]{'\\', '"'});
        String decodedQuote = new String(new char[]{'"'});
        expect(modern ? EscapePreview.Status.preview : EscapePreview.Status.unsupported,
            quoted(decodedQuote), EscapePreview.analyze(quoted(escapedQuote), modern));
        System.out.println("EscapePreviewSelfTest passed");
    }

    private static String quoted(String body){
        return "\"" + body + "\"";
    }

    private static void expect(EscapePreview.Status status, String text, EscapePreview.Result actual){
        if(actual.status() != status || !actual.text().equals(text)){
            throw new AssertionError("expected " + status + " / " + text + ", got " + actual);
        }
    }
}
