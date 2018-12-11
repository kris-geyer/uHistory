package geyer.sensorlab.uhistory;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

public class handlePermissions {


    Context context;

    public boolean hasPermission(AppOpsManager appOpsManager, Context mc) {
        this.context = mc;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            if (appOpsManager != null) {
                int mode = appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), "geyer.sensorlab.uhistory");
                Log.i("From mainActivity", "Permission given: " + String.valueOf(mode == AppOpsManager.MODE_ALLOWED));
                return mode == AppOpsManager.MODE_ALLOWED;
            }else{
                return false;
            }
        }
        else {
            return false;
        }
    }
}
