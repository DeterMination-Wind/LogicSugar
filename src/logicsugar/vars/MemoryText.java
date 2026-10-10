package logicsugar.vars;

import arc.util.Strings;

import java.util.regex.Pattern;

/**
 * 变量表 ↔ 剪贴板文本：导出 {@link #write} 与导入 {@link #read}/{@link #validate}。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 ({@code cardillan.mlogassertions.data.MemoryText}),
 * verbatim（含表头文本与全部转义规则）。表是制表符分隔的三列：地址（十进制）、类型
 * （{@link ValueType#title}）、值（mlog 字面量）；没有字面量的非有限数写成
 * {@code null}，字符串带引号并用反斜杠转义（{@code \n}、{@code \t}、引号、反斜杠，
 * 控制字符与代理对写成 unicode 转义）。
 * <b>表头与类型名不是可翻译文本</b>：导入时按原样匹配，改了就无法解析（见
 * {@link ValueType} 的说明）。</p>
 *
 * <p>导入是两段式（上游 UI 的调用顺序）：先用 {@link #validate} 检查整段文本，只有
 * 没有错误时才用 {@link #read} 真正写入，因此「有坏行就不改目标」由调用方保证。
 * 无法还原的类型（unit/building/content/link 等引用）会被静默跳过。</p>
 *
 * <p>全部方法都是纯函数式的静态方法，只依赖 {@link VariableValues} 接口，不需要活的
 * 世界（{@link VarsDataTest} 用一个纯数组替身直接验证往返）。</p>
 */
public final class MemoryText{
    private static final String header = "Slot\tType\tValue";
    private static final Pattern headerPattern = Pattern.compile("^\\s*slot\\s*type\\s*value\\s*$", Pattern.CASE_INSENSITIVE);

    /** @return true for the type names whose values can be restored. */
    private static boolean isImportable(ValueType type){
        return type == ValueType.color || type == ValueType.integer || type == ValueType.number ||
                type == ValueType.string || type == ValueType.zero || type == ValueType.nothing;
    }

    /** @return the literal of a string value, so that it can be parsed back. */
    private static String string(String value){
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');

        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            switch(c){
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                default -> {
                    //the characters which would either be lost or break the table are encoded
                    //using escape sequences. The two halves of a character outside the basic
                    //multilingual plane are encoded separately, keeping the literal in ASCII.
                    if(c < ' ' || c == 0x7f || Character.isSurrogate(c)){
                        String hex = Integer.toHexString(c);
                        result.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    }else{
                        result.append(c);
                    }
                }
            }
        }

        return result.append('"').toString();
    }

    /** @return the value of a string literal (quotes already stripped). */
    private static String unescape(String value){
        StringBuilder result = new StringBuilder(value.length());

        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c != '\\' || i + 1 >= value.length()){
                result.append(c);
                continue;
            }

            char next = value.charAt(++i);
            switch(next){
                case 'n' -> result.append('\n');
                case 't' -> result.append('\t');
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case 'u' -> {
                    int code = i + 4 < value.length() ? Strings.parseInt(value, 16, -1, i + 1, i + 5) : -1;
                    if(code >= 0){
                        result.append((char)code);
                        i += 4;
                    }else{
                        result.append("\\u");
                    }
                }
                default -> result.append('\\').append(next);
            }
        }

        return result.toString();
    }

    /** @return true for a string enclosed in quotes, with all the inner quotes escaped. */
    private static boolean isStringLiteral(String literal){
        if(literal.length() < 2 || !literal.startsWith("\"") || !literal.endsWith("\"")) return false;

        //the quote at the end must not be escaped
        int escapes = 0;
        for(int i = literal.length() - 2; i > 0 && literal.charAt(i) == '\\'; i--){
            escapes++;
        }
        return escapes % 2 == 0;
    }

    /** @return the value of a numeric literal, or null when the literal cannot be parsed. */
    private static Double doubleValue(String literal){
        if(literal.isEmpty()) return null;

        try{
            if(literal.startsWith("%")){
                if(literal.length() != 7 && literal.length() != 9) return null;

                //colors are stored using the bits of their rgba value, see LAssembler.parseColor
                long
                        r = Long.parseLong(literal.substring(1, 3), 16),
                        g = Long.parseLong(literal.substring(3, 5), 16),
                        b = Long.parseLong(literal.substring(5, 7), 16),
                        a = literal.length() == 9 ? Long.parseLong(literal.substring(7, 9), 16) : 255;
                return Double.longBitsToDouble((r << 24) | (g << 16) | (b << 8) | a);
            }else if(literal.startsWith("0x") || literal.startsWith("0b")){
                return radix(literal, 2, literal.charAt(1) == 'x' ? 16 : 2);
            }else if(literal.startsWith("+0x") || literal.startsWith("+0b")){
                return radix(literal, 3, literal.charAt(2) == 'x' ? 16 : 2);
            }else if(literal.startsWith("-0x") || literal.startsWith("-0b")){
                Double value = radix(literal, 3, literal.charAt(2) == 'x' ? 16 : 2);
                //mlog interprets the bits of a hexadecimal number, but a hexadecimal floating
                //point literal carries its own sign
                return value == null ? null : literal.indexOf('.') >= 0 || literal.indexOf('p') >= 0 ? value : -value;
            }else{
                return Double.parseDouble(literal);
            }
        }catch(NumberFormatException e){
            return null;
        }
    }

    /** @return the value of a hexadecimal or binary literal with the radix prefix at the given index.
     * The dialog writes decimal values using the hexadecimal floating point syntax of the Java
     * language, which cannot be interpreted as the bits of a number. */
    private static Double radix(String literal, int index, int radix){
        String digits = literal.substring(index);

        //mlog interprets the bits of a hexadecimal or binary number, so values above Long.MAX_VALUE wrap around
        try{
            return (double)Long.parseUnsignedLong(digits, radix);
        }catch(NumberFormatException e){
            try{
                //the sign is part of the value of a hexadecimal floating point literal
                return Double.parseDouble(literal);
            }catch(NumberFormatException e2){
                return null;
            }
        }
    }

    /** @return the table with the values of a variable source, ready to be put on the clipboard.
     * It has the same columns as the table shown by the dialog, but the values are written
     * using the literals of the mlog language, so that they can be parsed back. */
    public static String write(VariableValues data, boolean hex){
        StringBuilder result = new StringBuilder(500).append(header).append('\n');

        for(int index = 0; index < data.size(); index++){
            ValueType type = data.type(index);
            String value =
                    type == ValueType.string && data.obj(index) instanceof String str ? string(str) :
                    //non finite values are not representable in memory blocks
                    !data.isObj(index) && !Double.isFinite(data.num(index)) ? "null" :
                    //the formatted value isn't a literal for the values in the color range
                    type == ValueType.color ? "%" + Long.toHexString(Double.doubleToRawLongBits(data.num(index)) | 0x100000000L).substring(1) :
                    data.formatted(index, hex, 20);

            result.append(data.label(index, false).trim()).append('\t')
                    .append(type.title)
                    .append('\t').append(value)
                    .append('\n');
        }

        return result.toString();
    }

    /** Checks the table without modifying the variable source.
     * @return an error message listing the lines which could not be parsed, or null. */
    public static String validate(String text, int capacity){
        return read(text, capacity, null);
    }

    /** Restores the values of a variable source from the table. Values which cannot be
     * restored (references to units, buildings and contents) are skipped.
     *
     * <p>上游 v0.11.6：字符串值超过 65535 UTF-8 字节（{@code writeUTF} 的上限）时按坏行拒绝，
     * 不再写进内存块——否则内存块序列化时会抛异常，整块内存都留不下来。</p>
     *
     * @param data the variable source to be filled, or null to only check the table.
     * @return an error message listing the lines which could not be parsed, or null. */
    public static String read(String text, int capacity, VariableValues data){
        if(text == null || text.isBlank()) return null;

        StringBuilder errors = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");

        for(int i = 0; i < lines.length; i++){
            String row = lines[i].trim();
            //the header of the table may be preceded by empty lines
            if(row.isEmpty() || headerPattern.matcher(row).matches()) continue;

            String[] columns = row.split("\t");
            if(columns.length != 3){
                error(errors, i);
                continue;
            }

            int address = address(columns[0].trim());
            ValueType type = type(columns[1].trim());
            String literal = columns[2];

            if(address < 0 || address >= capacity || type == null){
                error(errors, i);
                continue;
            }

            if(!isImportable(type)){
                //references to units, buildings and contents cannot be restored
                continue;
            }

            if(type == ValueType.string){
                //when the literal isn't quoted, take the text as it is
                String value;
                if(isStringLiteral(literal)){
                    value = unescape(literal.substring(1, literal.length() - 1));
                }else if(literal.startsWith("\"")){
                    error(errors, i);
                    value = null;
                }else{
                    value = literal;
                }
                if(value != null){
                    //上游 v0.11.6：内存块用 writeUTF 写字符串，超过 65535 字节的文本会让它抛
                    //异常（导入整块内存因此失败）。按上游口径在这里拒绝，并给出坏行行号。
                    if(utf8size(value) > 65535){
                        error(errors, i);
                    }else{
                        set(data, address, value);
                    }
                }
            }else if(literal.equals("null")){
                //the values which are not representable in memory blocks are written as null
                set(data, address, null);
            }else{
                Double value = doubleValue(literal);
                if(value == null){
                    error(errors, i);
                }else{
                    set(data, address, value);
                }
            }
        }

        return errors.isEmpty() ? null : errors.toString();
    }

    /** 字符串写进内存块时的 UTF-8 字节数（{@code writeUTF} 口径）：NUL 记 2、{@code <0x800} 记 2、
     *  其余记 3，代理对自然记 6。上游 v0.11.6 用它对导入字符串施加 65535 字节的上限。 */
    private static int utf8size(String str){
        int size = 0;
        for(int i = 0; i < str.length(); i++){
            char c = str.charAt(i);
            size += c != 0 && c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
        }
        return size;
    }

    private static void set(VariableValues data, int address, Object value){
        if(data == null) return;

        if(value instanceof Number num){
            data.set(address, num.doubleValue());
        }else{
            data.set(address, value);
        }
    }

    private static int address(String value){
        try{
            return Integer.parseInt(value);
        }catch(NumberFormatException e){
            return -1;
        }
    }

    private static ValueType type(String title){
        for(ValueType type : ValueType.values()){
            if(type.title.equals(title)) return type;
        }
        return null;
    }

    private static void error(StringBuilder errors, int line){
        if(!errors.isEmpty()) errors.append(", ");
        errors.append(line + 1);
    }
}
