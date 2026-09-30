package logicsugar.assist;

import mindustry.gen.Iconc;

public final class EscapePreviewSelfTest{
    /** 假图标名：注册到游戏自己的图标表（Iconc.codes，UI.formatIcons 的第一个查表处）。 */
    private static final char ICON = '★';
    private static final String NAME = "ls-preview-test";

    public static void main(String[] args){
        // ===== 转义（原有覆盖面） =====
        expect(EscapePreview.Status.preview, quoted("你好"), true, false,
            EscapePreview.analyze(quoted("\\u4F60\\u597D"), true));
        expect(EscapePreview.Status.preview, quoted("line\nnext"), true, false,
            EscapePreview.analyze(quoted("line\\nnext"), false));
        String quoteSlash = new String(new char[]{'"', '\\'});
        expect(EscapePreview.Status.preview, quoted(quoteSlash), true, false,
            EscapePreview.analyze(quoted(new String(new char[]{'\\', '"', '\\', '\\'})), true));
        expect(EscapePreview.Status.unsupported, quoted("你"), true, false,
            EscapePreview.analyze(quoted("\\u4F60"), false));
        expect(EscapePreview.Status.invalid, "\\u requires four hex digits", false, false,
            EscapePreview.analyze(quoted("\\u12xz"), true));
        expect(EscapePreview.Status.none, "", false, false, EscapePreview.analyze("plain", true));
        expect(EscapePreview.Status.none, "", false, false, EscapePreview.analyze("\"plain\\q\"", true));
        expect(EscapePreview.Status.none, "", false, false, EscapePreview.analyze(quoted("\\"), true));

        // ===== `:name:` 图标（游戏在 print 输出与留言板上做的同一件事） =====
        String icon = String.valueOf(ICON);
        boolean added = !Iconc.codes.containsKey(NAME);
        Iconc.codes.put(NAME, ICON);
        try{
            expect(EscapePreview.Status.preview, quoted("hello " + icon), false, true,
                EscapePreview.analyze(quoted("hello :" + NAME + ":"), true));
            // 游戏只看前导冒号：`x:name` 在游戏里同样替换，预览必须与游戏一致而不是更严
            expect(EscapePreview.Status.preview, quoted("x" + icon), false, true,
                EscapePreview.analyze(quoted("x:" + NAME), true));
            // 图标表不认识的 `:name:`：游戏原样保留，所以没有预览
            expect(EscapePreview.Status.none, "", false, false,
                EscapePreview.analyze(quoted("hello :nope:"), true));
            // 转义 + 图标同时命中：文本两者都替换（前缀按“转义优先”）
            expect(EscapePreview.Status.preview, quoted(icon + "\n!"), true, true,
                EscapePreview.analyze(quoted(":" + NAME + ":\\n!"), true));
            // 图标命中但现代转义不支持：状态仍是 unsupported，文本里图标照样替换
            expect(EscapePreview.Status.unsupported, quoted(icon + "你"), true, true,
                EscapePreview.analyze(quoted(":" + NAME + ":\\u4F60"), false));
        }finally{
            if(added) Iconc.codes.remove(NAME);
        }

        // ===== 运行时能力探测 =====
        boolean modern = EscapePreview.modernEscapesSupported();
        String escapedQuote = new String(new char[]{'\\', '"'});
        String decodedQuote = new String(new char[]{'"'});
        expect(modern ? EscapePreview.Status.preview : EscapePreview.Status.unsupported,
            quoted(decodedQuote), true, false, EscapePreview.analyze(quoted(escapedQuote), modern));
        System.out.println("EscapePreviewSelfTest passed");
    }

    private static String quoted(String body){
        return "\"" + body + "\"";
    }

    private static void expect(EscapePreview.Status status, String text, boolean escapes, boolean icons,
                               EscapePreview.Result actual){
        if(actual.status() != status || !actual.text().equals(text)
            || actual.escapes() != escapes || actual.icons() != icons){
            throw new AssertionError("expected " + status + " / " + text + " / escapes=" + escapes
                + " icons=" + icons + ", got " + actual);
        }
    }
}
