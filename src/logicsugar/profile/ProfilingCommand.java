package logicsugar.profile;

/**
 * The three commands of the {@code profile} instruction (upstream MlogAssertions v0.11.3).
 * The names double as the wire tokens written by {@code SugarAsserts.ProfileCard}, so they
 * must not change.
 */public enum ProfilingCommand{
    start, stop, clear;

    public static final ProfilingCommand[] all = values();

    /** 选择按钮的本地化显示名；卡片写出的 token 仍是 {@link #name()}。 */
    public String display(){
        return logicsugar.assist.L10n.text("logicsugar.asserts.profilecommand." + name(), name());
    }
}
