package logicsugar;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.TextArea;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Scl;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Icon;

/**
 * Feedback entry: writes one free-form message and submits it as a dedicated PostHog event.
 *
 * <p>The message is the only user-authored payload. Game/mod version and player name are attached
 * automatically, matching the anonymous usage event; save files and processor programs are never
 * read or uploaded.</p>
 */
public class FeedbackDialog extends Dialog{
    private static final int maxLength = 2000;

    private final TextArea area;
    private final Label status;
    private final TextButton sendButton;

    public FeedbackDialog(){
        super("@logicsugar.feedback.title");
        float width = Math.min(520f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));

        cont.add("@logicsugar.feedback.hint").width(width).wrap().pad(12f);
        cont.row();

        area = new TextArea("");
        area.setMessageText(Core.bundle.get("logicsugar.feedback.placeholder", ""));
        area.setMaxLength(maxLength);
        area.setPrefRows(6);
        cont.add(area).width(width).height(150f).pad(6f);
        cont.row();

        status = cont.add("").width(width).wrap().pad(4f).get();
        status.setAlignment(Align.center);
        cont.row();

        cont.add("@logicsugar.feedback.privacy").width(width).wrap().color(Color.lightGray).pad(4f);
        cont.row();

        buttons.defaults().size(Math.min(200f, Core.graphics.getWidth() / 2.4f / Scl.scl(1f)), 50f).pad(4f);
        sendButton = buttons.button("@logicsugar.feedback.send", Icon.chat, this::submit).get();
        buttons.button("@cancel", Icon.left, this::hide);

        shown(() -> {
            if(Core.scene != null){
                Core.scene.setKeyboardFocus(area);
            }
        });
    }

    private void submit(){
        String message = area.getText() == null ? "" : area.getText().trim();
        if(message.isEmpty()){
            status.setText("@logicsugar.feedback.empty");
            return;
        }

        sendButton.setDisabled(true);
        status.setText("@logicsugar.feedback.sending");
        PostHogReporter.sendFeedback(message, success -> {
            if(success){
                hide();
                if(Vars.ui != null){
                    Vars.ui.showInfoFade("@logicsugar.feedback.sent");
                }
            }else{
                sendButton.setDisabled(false);
                status.setText("@logicsugar.feedback.failed");
            }
        });
    }
}
