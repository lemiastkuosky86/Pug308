package com.peugeot308.oem;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Serviço explícito para avisos enquanto Waze, Maps ou Android Auto estão na tela. */
public final class RadarForegroundService extends Service {
    private static final String CHANNEL="radar_pardal_sp";
    private static final int NOTIFICATION_ID=153;
    private static volatile RadarForegroundService running;
    private static volatile boolean homeVisible;
    static boolean isRunning(){return running!=null;}
    static void setHomeVisible(boolean foreground){
        homeVisible=foreground;
        RadarForegroundService service=running;
        if(service!=null)service.main.post(service::renderOverlay);
    }
    private PardalRadar radar;
    private LocationManager gps;
    private WindowManager windows;
    private View overlay;
    private TextView radarText;
    private TextView limitText;
    private TextView kindText;
    private TextView speedText;
    private ProgressBar distanceBar;
    private final Handler main=new Handler(Looper.getMainLooper());
    private int lastNotificationDistance=-999;
    private long lastNotificationTime=0L;
    private long lastBaseCheck=0L;
    private final LocationListener listener=new LocationListener(){
        @Override public void onLocationChanged(Location location){
            if(radar==null)return;
            radar.onLocation(location);
            renderOverlay();
            updateNotification(false);
        }
        @Override public void onStatusChanged(String provider,int status,Bundle extras){}
        @Override public void onProviderEnabled(String provider){}
        @Override public void onProviderDisabled(String provider){renderOverlay();}
    };
    private final Runnable refresh=new Runnable(){@Override public void run(){
        if(running!=RadarForegroundService.this)return;
        if(radar.mode()==PardalRadar.OFF){stopSelf();return;}
        renderOverlay();updateNotification(false);
        long now=SystemClock.elapsedRealtime();
        if(now-lastBaseCheck>=60000L){
            lastBaseCheck=now;
            try{
                ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo info=cm==null?null:cm.getActiveNetworkInfo();
                radar.checkDaily(info!=null&&info.isConnected()
                        &&info.getType()==ConnectivityManager.TYPE_WIFI);
            }catch(Exception ignored){}
        }
        main.postDelayed(this,1000L);
    }};
    @Override public void onCreate(){
        super.onCreate();
        running=this;
        radar=PardalRadar.shared(this,getSharedPreferences("MainActivity",MODE_PRIVATE));
        windows=(WindowManager)getSystemService(WINDOW_SERVICE);
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Radar Pardal",NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID,notification("Radar Pardal ativo • aguardando GPS"));
        main.post(refresh);
        subscribeGps();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(radar==null||radar.mode()==PardalRadar.OFF){stopSelf();return START_NOT_STICKY;}
        subscribeGps();
        return START_STICKY;
    }
    private void subscribeGps(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
        if(gps!=null)return;
        try{
            gps=(LocationManager)getSystemService(LOCATION_SERVICE);
            if(gps!=null)gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,900L,0f,listener,Looper.getMainLooper());
        }catch(Exception e){gps=null;}
    }
    private Notification notification(String message){
        Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,153,i,PendingIntent.FLAG_UPDATE_CURRENT|
                (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Peugeot 308 • Radar Pardal")
                .setContentText(message).setContentIntent(pending).setOngoing(true)
                .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    private void updateNotification(boolean force){
        if(radar==null)return;
        long now=SystemClock.elapsedRealtime();int d=radar.distance();
        if(!force&&now-lastNotificationTime<5000L&&Math.abs(d-lastNotificationDistance)<40)return;
        lastNotificationTime=now;lastNotificationDistance=d;
        String msg=radar.active()?radar.kind()+" a "+d+" m • "+radar.speed()+" km/h":radar.gpsStatus();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification(msg));
    }
    private int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        d.setStroke(dp(2),0xFF506D7F);return d;
    }
    private TextView label(String text,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    /** Cartao compacto para Waze/Maps, com a mesma hierarquia do aviso na Home. */
    private void makeOverlay(){
        if(overlay!=null)return;
        LinearLayout root=new LinearLayout(this);
        root.setGravity(Gravity.CENTER_VERTICAL);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(bg(0xF015202B,18));
        root.setPadding(dp(15),dp(10),dp(15),dp(10));

        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        limitText=label("60",28,0xFF121921,true);
        limitText.setGravity(Gravity.CENTER);
        GradientDrawable sign=new GradientDrawable();
        sign.setShape(GradientDrawable.OVAL);
        sign.setColor(0xFFF8FAFC);
        sign.setStroke(dp(7),0xFFDB3E43);
        limitText.setBackground(sign);
        row.addView(limitText,new LinearLayout.LayoutParams(dp(70),dp(70)));

        LinearLayout data=new LinearLayout(this);
        data.setOrientation(LinearLayout.VERTICAL);
        data.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dataParams=new LinearLayout.LayoutParams(0,dp(72),1f);
        dataParams.leftMargin=dp(14);
        row.addView(data,dataParams);

        kindText=label("RADAR FIXO",13,0xFF9FB5C3,true);
        radarText=label("350 m",27,Color.WHITE,true);
        speedText=label("ATUAL  60 km/h",13,0xFF80D7C9,false);
        data.addView(kindText);
        data.addView(radarText);
        data.addView(speedText);
        root.addView(row);

        distanceBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        distanceBar.setIndeterminate(false);
        distanceBar.setMax(350);
        distanceBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF344551));
        distanceBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(0xFF5ABBC9));
        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(4));
        barParams.topMargin=dp(8);
        root.addView(distanceBar,barParams);
        overlay=root;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(
                Math.max(dp(220),Math.min(dp(410),
                        getResources().getDisplayMetrics().widthPixels-dp(28))),
                dp(116),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(22);
        try{windows.addView(overlay,p);}catch(Exception e){overlay=null;}
    }
    private void removeOverlay(){
        if(overlay!=null){try{windows.removeView(overlay);}catch(Exception ignored){}overlay=null;}
    }
    private void renderOverlay(){
        if(radar==null||!radar.active()||homeVisible||!Settings.canDrawOverlays(this)){
            removeOverlay();return;
        }
        makeOverlay();if(overlay==null)return;
        limitText.setText(radar.limit()>0?String.valueOf(radar.limit()):"?");
        kindText.setText(radar.kind());
        radarText.setText(radar.distance()+" m");
        boolean speeding=radar.over();
        speedText.setText("ATUAL  "+radar.speed()+" km/h"
                +(speeding?"  •  REDUZA":""));
        speedText.setTextColor(speeding?0xFFFF918B:0xFF80D7C9);
        distanceBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                speeding?0xFFF27670:0xFF5ABBC9));
        distanceBar.setProgress(350-Math.max(0,Math.min(350,radar.distance())));
    }
    @Override public void onDestroy(){
        running=null;main.removeCallbacks(refresh);removeOverlay();
        if(gps!=null){try{gps.removeUpdates(listener);}catch(Exception ignored){}gps=null;}
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
