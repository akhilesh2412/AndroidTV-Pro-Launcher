package com.kulwant.atvhome;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Allow this phone to control the TV?" shown over whatever is on screen, answered with the TV
 * remote. Pairing therefore needs someone in the room: there is no code to guess from the network.
 * "Don't allow" has focus first, so a stray OK press never lets a phone in.
 */
final class PairingPrompt {

    interface Answer {
        void onAnswer(boolean allowed);
    }

    private final Context c;
    private final WindowManager wm;
    private FrameLayout root;
    private Answer answer;
    private final TextView[] buttons = new TextView[2];
    private int focus;   // 0 = Don't allow, 1 = Allow

    PairingPrompt(Context c) {
        this.c = c.getApplicationContext();
        wm = (WindowManager) this.c.getSystemService(Context.WINDOW_SERVICE);
    }

    static boolean canShow(Context c) {
        return Settings.canDrawOverlays(c);
    }

    boolean isShowing() {
        return root != null;
    }

    /** Call on the main thread. */
    void show(String device, Answer a) {
        dismiss(false);
        answer = a;
        root = new FrameLayout(c) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        setFocus(0);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        setFocus(1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        dismiss(focus == 1);
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        dismiss(false);
                        return true;
                    default:
                        return true;   // the prompt owns the remote while it is up
                }
            }
        };
        root.setBackgroundColor(0x99000000);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(0xF21C1C1E, Ui.dp(c, 22), Ui.dp(c, 1), 0x24FFFFFF));
        card.setPadding(Ui.dp(c, 30), Ui.dp(c, 26), Ui.dp(c, 30), Ui.dp(c, 24));

        TextView title = Ui.text(c, 600, 22, 0xFFFFFFFF);
        title.setText("Allow this phone to control the TV?");
        card.addView(title);
        TextView sub = Ui.text(c, 400, 15, 0x99FFFFFF);
        sub.setText(device);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = Ui.dp(c, 8);
        card.addView(sub, slp);
        TextView note = Ui.text(c, 400, 13, 0x73FFFFFF);
        note.setText("It will be able to open apps, press keys, type and change the photos.");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = Ui.dp(c, 4);
        card.addView(note, nlp);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        String[] labels = {"Don't allow", "Allow"};
        for (int i = 0; i < 2; i++) {
            buttons[i] = Ui.text(c, 500, 16, 0xFFFFFFFF);
            buttons[i].setText(labels[i]);
            buttons[i].setGravity(Gravity.CENTER);
            buttons[i].setPadding(Ui.dp(c, 22), 0, Ui.dp(c, 22), 0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, Ui.dp(c, 42));
            if (i > 0) blp.leftMargin = Ui.dp(c, 10);
            row.addView(buttons[i], blp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(c, 24);
        card.addView(row, rlp);

        root.addView(card, new FrameLayout.LayoutParams(Ui.dp(c, 520), -2, Gravity.CENTER));
        setFocus(0);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,   // focusable: takes the remote's keys
                PixelFormat.TRANSLUCENT);
        wm.addView(root, lp);
        root.requestFocus();
    }

    /** Closes the prompt; a prompt closed without an answer counts as "Don't allow". */
    void dismiss(boolean allowed) {
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
        }
        Answer a = answer;
        answer = null;
        if (a != null) a.onAnswer(allowed);
    }

    private void setFocus(int i) {
        focus = i;
        for (int k = 0; k < 2; k++) {
            boolean on = k == i;
            buttons[k].setBackground(on ? Ui.rounded(0xFFFFFFFF, Ui.dp(c, 12), 0, 0)
                    : Ui.rounded(0x26FFFFFF, Ui.dp(c, 12), 0, 0));
            buttons[k].setTextColor(on ? 0xFF000000 : 0xFFFFFFFF);
        }
    }
}
