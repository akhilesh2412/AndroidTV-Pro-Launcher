package com.kulwant.atvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the phone control page after the launcher is updated, without bringing Home to the front. */
public class PhoneStarter extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        c.startForegroundService(new Intent(c, PhoneService.class));
    }
}
