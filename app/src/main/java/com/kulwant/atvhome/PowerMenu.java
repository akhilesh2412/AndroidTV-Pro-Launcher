package com.kulwant.atvhome;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Glass dropdown under the Power pill: Restart, Sleep, Shut down. An ordinary app cannot power
 * the TV off itself, so the chosen shell command runs through the TV's own ADB (KeyInjector's
 * connection, the same channel the phone remote uses).
 */
final class PowerMenu {

    private static final String[] LABELS = {"Restart", "Sleep", "Shut down"};
    private static final String[] COMMANDS = {
            "reboot",
            "input keyevent KEYCODE_SLEEP",
            "reboot -p || svc power shutdown",
    };

    final FrameLayout panel;
    private final LinearLayout list;
    private final Context c;
    private final List<TextView> rows = new ArrayList<>();
    private int sel;

    PowerMenu(Context c) {
        this.c = c;
        panel = new FrameLayout(c);
        panel.setBackground(Ui.rounded(0xEB1C1C1E, Ui.dp(c, 16), Ui.dp(c, 1), 0x24FFFFFF));
        panel.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        panel.setElevation(Ui.dp(c, 12));
        panel.setVisibility(View.GONE);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        panel.addView(list);
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void open(int leftPx, int topPx) {
        list.removeAllViews();
        rows.clear();
        for (String label : LABELS) {
            TextView t = Ui.text(c, 400, 15, 0xFFFFFFFF);
            t.setText(label);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
            list.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, 188), Ui.dp(c, 38)));
            rows.add(t);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = leftPx;
        lp.topMargin = topPx;
        panel.setLayoutParams(lp);
        sel = 0;
        paint();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setPivotY(0);
        panel.setScaleY(0.94f);
        panel.animate().alpha(1f).scaleY(1f).setDuration(160).start();
    }

    void close() {
        panel.animate().alpha(0f).setDuration(110).withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    void move(int d) {
        int n = sel + d;
        if (n < 0 || n >= LABELS.length) return;
        sel = n;
        paint();
    }

    String selectedCommand() {
        return COMMANDS[sel];
    }

    private void paint() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setBackground(i == sel ? Ui.rounded(0x2EFFFFFF, Ui.dp(c, 11), Ui.dp(c, 1), 0x40FFFFFF) : null);
        }
    }
}
