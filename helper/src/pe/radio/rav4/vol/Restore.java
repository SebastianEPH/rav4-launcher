package pe.radio.rav4.vol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class Restore extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) { Quiet.put(c, 0); }
}
