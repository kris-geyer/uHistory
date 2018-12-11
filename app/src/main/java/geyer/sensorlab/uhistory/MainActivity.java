package geyer.sensorlab.uhistory;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.Settings;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    Button startUpload, changeP;
    ProgressBar progressBar;
    SharedPreferences prefs;
    SharedPreferences.Editor editor;
    TextView tv;

    private static final int MY_PERMISSION_REQUEST_PACKAGE_USAGE_STATS = 101;

    private final static String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

            //initialize components
        initializeVisibleComponent();
        initializeSharedPreferences();
            //gain permissions

        permissionStatus();
    }

    private void initializeVisibleComponent() {
        tv = findViewById(R.id.tvStatus);

        progressBar = findViewById(R.id.pb);
        progressBar.setVisibility(View.INVISIBLE);

        startUpload = findViewById(R.id.btnStart);
        changeP = findViewById(R.id.btnChangePassword);

        startUpload.setOnClickListener(this);
        changeP.setOnClickListener(this);
        startUpload.setEnabled(false);
    }


    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.btnStart:
                progressBar.setVisibility(View.VISIBLE);
                startUpload.setEnabled(false);
                startUpload();
                break;
            case R.id.btnChangePassword:
                requestPassword();
                break;
        }
    }
    private void initializeSharedPreferences() {
        prefs = getSharedPreferences("general preferences", MODE_PRIVATE);
        editor = prefs.edit();
        editor.apply();
    }

    private void permissionStatus() {
        handlePermissions hp = new handlePermissions();
        AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);

        if(hp.hasPermission(appOpsManager, this)){
            if(prefs.getBoolean("password generated", false)){
                startUpload.setEnabled(true);
                tv.setText(R.string.upload_ready);
            }else{
                requestPassword();
            }
        }else{
            requestPackagePermission();
        }
    }

    private void requestPassword() {
        LayoutInflater inflater = this.getLayoutInflater();
        //create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle("password")
                .setMessage("The file that is generated with your data and later exported must be password protected. Please enter a password that is at least 8 characters in length.")
                .setView(inflater.inflate(R.layout.request_passwrd_screen, null))
                .setNeutralButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Dialog d = (Dialog) dialogInterface;
                        EditText password = d.findViewById(R.id.etPassword);
                        if (checkPassword(password.getText())) {
                            editor.putBoolean("password generated", true);
                            editor.putString("pdfPassword", String.valueOf(password.getText()));
                            editor.apply();
                            permissionStatus();
                        } else {
                            Toast.makeText(MainActivity.this, "The password entered was not long enough", Toast.LENGTH_SHORT).show();
                            permissionStatus();
                        }
                    }
                    private boolean checkPassword(Editable text) {
                        return text.length() > 7;
                    }
                });
        builder.create()
                .show();
    }
    //request usage statistics permission

    private void requestPackagePermission() {
        AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(this);

        builder.setTitle("Usage permission")
                .setMessage("To participate in this experiment you must enable usage stats permission")
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            startActivityForResult(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), MY_PERMISSION_REQUEST_PACKAGE_USAGE_STATS);
                        }
                    }
                })
                .show();
    }

    //detects the results of requesting permission from the usage statistics

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode){
            case MY_PERMISSION_REQUEST_PACKAGE_USAGE_STATS:
                permissionStatus();
        }
    }

    private void startUpload() {
        @SuppressLint("WrongConstant") UsageStatsManager usm = (UsageStatsManager) this.getSystemService("usagestats");
        uploadHistoryData upload = new uploadHistoryData();
        upload.execute(usm, this, getApplicationContext(),getBaseContext());
    }

    private class uploadHistoryData extends AsyncTask<Object, Integer, Void>{

        int numTaskComplete;

        Context context;
        Context applicationContext;
        Context baseContext;
        HashMap<String , Integer> uninstalledApps;
        UsageStatsManager usm;
        PackageManager pm;
        long startDate;

        ArrayList<String> databaseEvent;
        ArrayList <Long> databaseTimestamp;
        ArrayList <Integer> databaseEventType;

        @Override
        protected Void doInBackground(Object... objects) {

            numTaskComplete = 0;

            Log.i(TAG, "upload history operations called");

            context = (Context) objects[1];
            applicationContext = (Context) objects[2];
            baseContext = (Context) objects[3];

            uninstalledApps = new HashMap<>();
            usm = (UsageStatsManager) objects[0];
            pm = applicationContext.getPackageManager();

            documentStatistic(recordUsageStatistics());
            insertAppsAndPermissions(recordInstalledApps());
            recordUseageEvent();
            insertEventsIntoPdf();
            insertUninstalledIntoPdf();

             return null;
         }

        private void documentStatistic(HashMap<Long, UsageStats> stats) {

            //creates document
            Document document = new Document();
            //getting destination
            File path = context.getFilesDir();
            File file = new File(path, Constants.USAGE_STATISCS);
            // Location to save
            PdfWriter writer = null;
            try {
                writer = PdfWriter.getInstance(document, new FileOutputStream(file));
            } catch (DocumentException e) {
                Log.e(TAG, "document exception: " + e);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "File not found exception: " + e);
            }
            try {
                if (writer != null) {
                    writer.setEncryption("concretepage".getBytes(), prefs.getString("pdfPassword", "sensorlab").getBytes(), PdfWriter.ALLOW_COPY, PdfWriter.ENCRYPTION_AES_128);
                }
            } catch (DocumentException e) {
                Log.e(TAG, "document exception: " + e);
            }
            if (writer != null) {
                writer.createXmpMetadata();
            }
            // Open to write
            document.open();

            PdfPTable table = new PdfPTable(2);
            //attempts to add the columns
            table.addCell("start date");
            table.addCell(String.valueOf(startDate));
            try {
                int count = 0;
                for (Map.Entry<Long, UsageStats> app : stats.entrySet()) {

                    table.addCell("££$" + app.getValue().getPackageName());
                    table.addCell( "$$£"+ String.valueOf(app.getKey()));
                    int currentProgress = (count * 100) / stats.size();
                    count++;
                    publishProgress(currentProgress);
                }
            }catch (Exception e) {
                Log.e("file construct", "error " + e);
            }

            //add to document
            document.setPageSize(PageSize.A4);
            document.addCreationDate();
            try {
                document.add(table);
            } catch (DocumentException e) {
                Log.e(TAG, "Document exception: " + e);
            }
            document.addAuthor("Kris");
            document.close();
            numTaskComplete++;

        }

        private HashMap<Long, UsageStats> recordUsageStatistics() {
            @SuppressLint("WrongConstant") UsageStatsManager usageStatsManager = (UsageStatsManager) context.getSystemService("usagestats");

            List<UsageStats> appList;
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_YEAR, -365);
            long start = calendar.getTimeInMillis();
            startDate = start;

            appList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, start, System.currentTimeMillis());
            HashMap<Long, UsageStats> mySortedMap = new HashMap<>();

            if(appList != null && appList.size() > 0){

                for(UsageStats usageStats : appList){
                    mySortedMap.put(usageStats.getTotalTimeInForeground(), usageStats);
                    if(usageStats.getFirstTimeStamp() < startDate){
                      startDate = usageStats.getFirstTimeStamp();
                    }
                }
            }

            Log.i("start date", ""+startDate);

            return mySortedMap;

        }

        private HashMap<String, ArrayList> recordInstalledApps() {
            HashMap<String, ArrayList> appPermissions = new HashMap<>();

            final List <PackageInfo> appinstall=pm.getInstalledPackages(PackageManager.GET_PERMISSIONS|PackageManager.GET_RECEIVERS|
                    PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS);

            for(PackageInfo pInfo:appinstall) {
                String[] reqPermission = pInfo.requestedPermissions;
                int[] reqPermissionFlag = pInfo.requestedPermissionsFlags;

                ArrayList<String> permissions = new ArrayList<>();
                if (reqPermission != null){
                    for (int i = 0; i < reqPermission.length; i++){
                        String tempPermission = reqPermission[i];
                        int tempPermissionFlag = reqPermissionFlag[i];
                        //Log.i(TAG, "permission flag: " + tempPermissionFlag);

                        boolean approved = tempPermissionFlag == 3;
                        permissions.add("*&^"+tempPermission + " - " + approved);
                    }
                }
                appPermissions.put("!@€"+pInfo.applicationInfo.loadLabel(pm), permissions);
            }
            return appPermissions;
        }

        private void insertAppsAndPermissions(HashMap<String, ArrayList> stringArrayListHashMap) {
            //creates document
            Document document = new Document();
            //getting destination
            File path = context.getFilesDir();
            File file = new File(path, Constants.APPS_FILE);
            // Location to save
            PdfWriter writer = null;
            try {
                writer = PdfWriter.getInstance(document, new FileOutputStream(file));
            } catch (DocumentException e) {
                Log.e(TAG, "document exception: " + e);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "File not found exception: " + e);
            }
            try {
                if (writer != null) {
                    writer.setEncryption("concretepage".getBytes(), prefs.getString("pdfPassword", "sensorlab").getBytes(), PdfWriter.ALLOW_COPY, PdfWriter.ENCRYPTION_AES_128);
                }
            } catch (DocumentException e) {
                Log.e(TAG, "document exception: " + e);
            }
            if (writer != null) {
                writer.createXmpMetadata();
            }
            // Open to write
            document.open();

            PdfPTable table = new PdfPTable(1);
            //attempts to add the columns
            try {
                int count = 0;
                for (Map.Entry<String, ArrayList> item : stringArrayListHashMap.entrySet()) {
                    String key = item.getKey();
                    table.addCell(key);
                    ArrayList value = item.getValue();
                    for (int i = 0; i < value.size(); i++){
                        table.addCell(String.valueOf(value.get(i)));
                    }
                    count++;
                    int currentProgress = (count * 100) / stringArrayListHashMap.size();
                    publishProgress(currentProgress);
                }

            } catch (Exception e) {
                Log.e("file construct", "error " + e);
            }

            //add to document
            document.setPageSize(PageSize.A4);
            document.addCreationDate();
            try {
                document.add(table);
            } catch (DocumentException e) {
                Log.e(TAG, "Document exception: " + e);
            }
            document.addAuthor("Kris");
            document.close();
            numTaskComplete++;

        }


        private void insertUninstalledIntoPdf() {

            if(!uninstalledApps.isEmpty()){
                Document documentU = new Document();
                //getting destination
                File pathU = context.getFilesDir();
                File fileU = new File(pathU, Constants.UNINSTALLED_APPS);
                // Location to save
                PdfWriter writerU = null;
                try {
                    writerU = PdfWriter.getInstance(documentU, new FileOutputStream(fileU));
                } catch (DocumentException e) {
                    Log.e(TAG, "document exception: " + e);
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "File not found exception: " + e);
                }
                try {
                    if (writerU != null) {
                        writerU.setEncryption("concretepage".getBytes(), prefs.getString("pdfPassword", "sensorlab").getBytes(), PdfWriter.ALLOW_COPY, PdfWriter.ENCRYPTION_AES_128);
                    }
                } catch (DocumentException e) {
                    Log.e(TAG, "document exception: " + e);
                }
                if (writerU != null) {
                    writerU.createXmpMetadata();
                }
                // Open to write
                documentU.open();

                PdfPTable tableU = new PdfPTable(2);
                //attempts to add the columns
                try {
                    for (Map.Entry<String, Integer> item: uninstalledApps.entrySet()){
                        tableU.addCell(item.getKey());
                        tableU.addCell(String.valueOf(item.getValue()));
                    }

                } catch (Exception e) {
                    Log.e("file construct", "error " + e);
                }

                //add to document
                documentU.setPageSize(PageSize.A4);
                documentU.addCreationDate();
                try {
                    documentU.add(tableU);
                } catch (DocumentException e) {
                    Log.e(TAG, "Document exception: " + e);
                }
                documentU.addAuthor("Kris");
                documentU.close();
                numTaskComplete++;
            }

        }

        private void insertEventsIntoPdf() {
            //creates document
            Document document = new Document();
            //getting destination
            File path = context.getFilesDir();
            File file = new File(path, Constants.EVENTS_FILE);
            // Location to save
            PdfWriter writer = null;
            try {
                writer = PdfWriter.getInstance(document, new FileOutputStream(file));
            } catch (DocumentException e) {
                Log.e(TAG, "document exception: " + e);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "File not found exception: " + e);
            }
            try {
                if (writer != null) {
                    writer.setEncryption("concretepage".getBytes(), prefs.getString("pdfPassword", "sensorlab").getBytes(), PdfWriter.ALLOW_COPY, PdfWriter.ENCRYPTION_AES_128);
                }
            } catch (DocumentException e) {
                Log.e(TAG, "document exception: " + e);
            }
            if (writer != null) {
                writer.createXmpMetadata();
            }
            // Open to write
            document.open();

            PdfPTable table = new PdfPTable(3);
            //attempts to add the columns
            try {
                for (int i = 0; i < databaseEvent.size(); i++) {
                    table.addCell("@€£" + (databaseTimestamp.get(i)/1000));
                    table.addCell("#£$" + databaseEvent.get(i));
                    table.addCell("^&*" + databaseEventType.get(i));
                    publishProgress((int) ((i / (float) databaseEvent.size()) * 100));
                    if (i != 0) {
                        int currentProgress = (i * 100) / databaseEvent.size();
                        publishProgress(currentProgress);
                    }
                }
            } catch (Exception e) {
                Log.e("file construct", "error " + e);
            }

            //add to document
            document.setPageSize(PageSize.A4);
            document.addCreationDate();
            try {
                document.add(table);
            } catch (DocumentException e) {
                Log.e(TAG, "Document exception: " + e);
            }
            document.addAuthor("Kris");
            document.close();
            numTaskComplete++;
        }

        private void recordUseageEvent() {
            UsageEvents usageEvents = null;

            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_YEAR, -5);
            long start = calendar.getTimeInMillis();

            if (usm != null) {
                usageEvents =  usm.queryEvents(start, System.currentTimeMillis());
            }else{
                Log.e(TAG, "usm equals null");
            }
                    
            databaseEvent = new ArrayList<>();
            databaseTimestamp = new ArrayList<>();
            databaseEventType = new ArrayList<>();

            int count = 0;
            if (usageEvents != null) {
                while(usageEvents.hasNextEvent()){
                    //Log.i(TAG, "number: " + count);
                    UsageEvents.Event e = new UsageEvents.Event();
                    usageEvents.getNextEvent(e);
                    count++;
                }

                usageEvents =  usm.queryEvents(start, System.currentTimeMillis());


                int newCount = 0;
                while(usageEvents.hasNextEvent()){

                    UsageEvents.Event e = new UsageEvents.Event();
                    usageEvents.getNextEvent(e);

                    try {
                        String appName = (String) pm.getApplicationLabel(pm.getApplicationInfo(e.getPackageName(), PackageManager.GET_META_DATA));
                        appName = appName.replace(" ", "-");
                        databaseEvent.add(appName);
                        //Log.i("app name", appName );
                    } catch (PackageManager.NameNotFoundException e1) {

                        String packageName = e.getPackageName();
                        packageName = packageName.replace(" ", "-");
                        if(uninstalledApps.containsKey(packageName)){
                            databaseEvent.add("app" +uninstalledApps.get(packageName));
                        }else{
                            databaseEvent.add("app"+uninstalledApps.size());
                            uninstalledApps.put(packageName, uninstalledApps.size());
                        }
                        Log.e("usageHistory","Error in identify package name: " + e);
                    }

                    databaseTimestamp.add(e.getTimeStamp());
                    databaseEventType.add(e.getEventType());

                    newCount++;
                    publishProgress((newCount*100)/count);
                    if(newCount%100==0){
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                    }
                }
            }else{
                Log.e(TAG, "usage event equals null");
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);

            //documenting intent to send multiple
            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("text/plain");

            //getting directory for internal files
            String directory = (String.valueOf(context.getFilesDir()) + File.separator);
            Log.i("Directory", directory);

            //initializing files reference
            File eventsFile = new File(directory + File.separator + Constants.EVENTS_FILE);
            File appFile = new File(directory + File.separator + Constants.APPS_FILE);
            File uninstalled = new File(directory + File.separator + Constants.UNINSTALLED_APPS);
            File usageStats = new File(directory + File.separator + Constants.USAGE_STATISCS);

            //list of files to be uploaded
            ArrayList<Uri> files = new ArrayList<>();

            //if target files are identified to exist then they are packages into the attachments of the email
            try {
                if (eventsFile.exists()) {
                    files.add(FileProvider.getUriForFile(context, "geyer.sensorlab.uhistory.fileprovider", eventsFile));
                } else {
                    Log.i(TAG, "events file doesn't exist");
                }

                if (appFile.exists()) {
                    Log.i("toExportFileError", "true");
                    files.add(FileProvider.getUriForFile(context, "geyer.sensorlab.uhistory.fileprovider", appFile));
                } else {
                    Log.i(TAG, "app file doesn't exist");
                }

                if(uninstalled.exists()){
                    Log.i("toExportFileError", "true");
                    files.add(FileProvider.getUriForFile(context, "geyer.sensorlab.uhistory.fileprovider", uninstalled));
                } else {
                    Log.i(TAG, "uninstall file doesn't exist");
                }

                if(usageStats.exists()){
                    Log.i("toExportFileError", "true");
                    files.add(FileProvider.getUriForFile(context, "geyer.sensorlab.uhistory.fileprovider", usageStats));
                } else {
                    Log.i(TAG, "usageStats doesn't exist");
                }

                //adds the file to the intent to send multiple data points
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(intent);
            } catch (Exception e) {
                Log.e("File upload error1", "Error:" + e);
            }

            progressBar.setProgress(0);
            progressBar.setVisibility(View.INVISIBLE);
            startUpload.setEnabled(true);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            progressBar.setProgress(values[0]);
            String toReplay = "operations carried out: " + numTaskComplete;
            tv.setText(toReplay);
        }
    }
}
