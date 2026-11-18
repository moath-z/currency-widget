package com.moath.currencywidget;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Only shows how to add the widget. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText("Currency Widget is installed.\n\n"
                + "To add it: long-press an empty spot on your home screen → Widgets → "
                + "Currency Widget, then drag it onto the screen.\n\n"
                + "Tap a currency at the top to choose what you're typing in, "
                + "then use the keypad. ↻ refreshes the rates.");
        t.setTextSize(18);
        t.setTextColor(0xFFE6EAF0);
        t.setBackgroundColor(0xFF10141A);
        t.setGravity(Gravity.CENTER);
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, pad);
        setContentView(t);
    }
}
