package com.peugeot308.oem;

import android.app.*;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.media.session.*;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.*;
import android.bluetooth.*;
import android.provider.Settings;
import java.net.*;
import java.io.*;
import org.json.*;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;

/**
 * Peugeot 308 OEM Launcher v1.17
 * - Visual "concessionária": superfícies planas, ícones vetoriais, tipografia em hierarquia
 * - Tema automático dia/noite (nascer/pôr do sol), escuro ou claro
 * - Estados de toque, escala uniforme (sem distorcer), cache de ícones, aceleração por hardware
 * - Velocidade/rumo/altitude via GPS quando não há CANBUS; sem valores inventados
 */
public class MainActivity extends Activity {
    private static final String DEFAULT_SERVER = "http://192.168.15.30:8080/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private ToneGenerator tone;
    private MediaSessionManager mediaManager;
    private MediaController mediaCtl;
    private String songTitle = "Nenhuma música em reprodução", songArtist = "Abra o Spotify para começar", mediaSource = "Spotify";
    private String userLyrics="", userLyricsSong="";
    private boolean lyricsOn=false;
    private Bitmap albumArt;
    private boolean mediaPlaying = false, mediaIsSpotify = true, mediaPermission = false;
    private long songDuration = 0, songPosBase = 0, songPosStamp = 0;
    private float songSpeed = 1f;
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { runOnUiThread(() -> updateMedia()); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { runOnUiThread(() -> updateMedia()); }
    };

    private Dashboard dashboard;
    private JancarBridge jancarBridge;
    private int accentBase = Color.rgb(0, 174, 255), accent = accentBase, onAccent = Color.WHITE;
    private boolean beep;
    private boolean volumeVisible;
    private boolean wifiConnected, btConnected;

    // ---- Tema ----
    private int themeMode = 0; // 0 automático, 1 escuro, 2 claro
    private boolean night = true;
    private int cBg0, cBg1, cHead, cSurfTop, cSurfBot, cSurf2, cHair, cText, cMuted, cDim, cShadow, cTrack, cOk, cBad;

    // ---- v1.11: temas prontos, brilho, fade, modo direção, atalhos ----
    // 32 temas: 0-4 fundo PRETO (luz âmbar estilo painel Peugeot e variações), 5-11 cinza CHUMBO, 12-31 coloridos (marinho)
    private static final String[] PRESET_NAMES = {"Peugeot Âmbar", "Preto Vermelho", "Preto Verde", "Preto Branco", "Preto Azul",
        "Chumbo Âmbar", "Chumbo Laranja", "Chumbo Vermelho", "Chumbo Verde", "Chumbo Azul", "Chumbo Ciano", "Chumbo Branco",
        "Azul Peugeot", "Coral", "Menta", "Âmbar claro", "Violeta", "Aço", "Esmeralda", "Ciano", "Turquesa", "Lima",
        "Amarelo", "Laranja", "Vermelho", "Rosa", "Magenta", "Índigo", "Safira", "Dourado", "Bordô", "Gelo"};
    private static final int[] PRESET_ACCENTS = {0xffFFA21A, 0xffFF4B4B, 0xff3FD68B, 0xffEFE9DC, 0xff4DA3FF,
        0xffFFB454, 0xffFF8C42, 0xffFF6B6B, 0xff5FD39A, 0xff62AFFF, 0xff4CD0D8, 0xffE4E7EB,
        0xff00aeff, 0xfffa5965, 0xff29d6a4, 0xffffae46, 0xffae86ff, 0xff8fa3b8, 0xff2ecc71, 0xff22d3ee, 0xff14b8a6, 0xffa3e635,
        0xfffacc15, 0xfffb923c, 0xffef4444, 0xfff472b6, 0xffd946ef, 0xff6366f1, 0xff3b82f6, 0xffd4a24c, 0xffc2415b, 0xffbae6fd};
    private static final int NAVY_START = 12;
    private static int baseOf(int preset) { return preset < 5 ? 1 : preset < 12 ? 2 : 0; } // 1 preto, 2 chumbo, 0 marinho
    private int presetIndex = 0;
    private boolean albumBg = true;
    private int artTarget = 0, artFrom = 0, artCur = 0;
    private long artBlendStart = 0;
    private String artKey = "";
    private boolean autoBrightness = true;
    private int brightDay = 100, brightNight = 40;
    private boolean fadeBoot = true, fadePending = false, wasPlaying = false;
    private long fadeWindowUntil = 0, screenOffAt = 0;
    private static boolean bootArmed = false;
    private BroadcastReceiver screenReceiver;
    private boolean drivingEnabled = true, drivingMode = false;
    private int drivingThreshold = 10, drvAbove = 0, drvBelow = 0, drvLost = 0;
    private long drivingTestUntil = 0;
    private Dialog activeDialog;
    private SoundEngine snd;      // v1.14: motor de som (equalizador, loudness, volume por velocidade)
    private Dialog eqDialog;
    private final int[] topSlot = {0, 4, 9}; // mini cards do topo: id do indicador (-1 = vazio)
    private boolean alertSound = true, radarEnabled = false, overNow = false;
    private long lastOverAlert = 0;
    private String alertTitle = "", alertSub = "";
    private int alertKind = 0;
    private long alertUntil = 0, alertStart = 0;
    private ToneGenerator alertTone;
    private final ArrayList<double[]> radars = new ArrayList<>(); // {lat, lon, limite (0 = desconhecido), id}
    private double radarFetchLat = 999, radarFetchLon = 999, gpsLat = Double.NaN, gpsLon = Double.NaN;
    private long radarFetchAt = 0;
    private boolean radarFetching = false;
    private final HashMap<Long, Long> radarAlerted = new HashMap<>();
    private static final long SHINE_PERIOD = 3200L, SHINE_MS = 850L;
    private static final float SET_SCALE = 1.5f; // tamanho dos menus de configuração
    private boolean speedAlert = false;
    private int speedLimit = 110;
    private static final String DEFAULT_VEHICLE_ACTION = "com.peugeot308.oem.VEHICLE_COMMAND";
    private String vehicleAction = DEFAULT_VEHICLE_ACTION;
    private final String[] vbtnLabel = {"MENU", "PC BORDO", "VOLTAR"};
    private final String[] vbtnCmd = {"MENU", "TRIP", "BACK"};
    private String homeAddr = "", workAddr = "";
    private final ArrayList<String> recentPackages = new ArrayList<>();
    private String lastCandPkg = null;
    private long lastCandStamp = 0;

    // A Jancar ainda não fornece protocolo CANBUS conhecido. Nunca indicar conectado sem telemetria válida.
    private boolean canConnected = false;
    private long lastCanPacket = 0;
    private final boolean[] vehicleVisible = {true, true, true, true, true, true, true, true, true, true, false};
    private boolean vehicleMenuEnabled = false; // menu do veículo (3 botões + dados) desligado por padrão
    private boolean topWidgetEnabled = true;    // mini widget na barra superior
    private final String[] vehicleNames = {"Velocidade", "Autonomia", "Temperatura externa", "Combustível", "RPM do motor", "Portas", "Rumo (GPS)", "Altitude (GPS)", "Consumo instantâneo", "Temperatura do motor", "Limite de velocidade"};
    private final String[] vehicleShort = {"VELOCIDADE", "AUTONOMIA", "EXTERNA", "COMBUSTÍVEL", "RPM", "PORTAS", "RUMO", "ALTITUDE", "CONSUMO", "TEMP. MOTOR", "LIMITE"};
    private final String[] vehicleUnits = {"km/h", "km", "°C", "%", "rpm", "", "", "m", "km/l", "°C", "km/h"};
    private final String[] vehicleValues = {"--", "--", "--", "--", "--", "--", "--", "--", "--", "--", "--"};
    private boolean hasCanData() { return canConnected && SystemClock.elapsedRealtime() - lastCanPacket < 10000; }

    // ---- GPS ----
    private LocationManager locationManager;
    private boolean gpsRunning = false;
    private float gpsSpeedKmh = -1, gpsBearing = -1;
    private double gpsAlt = Double.NaN;
    private long gpsLastFix = 0;
    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            gpsLastFix = SystemClock.elapsedRealtime();
            float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
            gpsSpeedKmh = kmh < 2f ? 0f : kmh;
            if (l.hasBearing() && kmh >= 4f) gpsBearing = l.getBearing();
            gpsAlt = l.hasAltitude() ? l.getAltitude() : Double.NaN;
            gpsLat = l.getLatitude(); gpsLon = l.getLongitude();
            if (weatherAuto) tryAutoWeather();
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };
    private boolean needGps() { return weatherAuto || vehicleMenuEnabled || topWidgetEnabled || drivingEnabled || radarEnabled || speedAlert || (snd != null && snd.autoVolOn); }
    private boolean gpsFresh() { return gpsLastFix > 0 && SystemClock.elapsedRealtime() - gpsLastFix < 6000; }
    private void startGps() {
        if (gpsRunning || !needGps()) return;
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener);
            gpsRunning = true;
        } catch (Exception ignored) {}
    }
    private void stopGps() {
        try { if (locationManager != null) locationManager.removeUpdates(gpsListener); } catch (Exception ignored) {}
        gpsRunning = false;
    }
    private String compass(float b) {
        String[] d = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};
        return d[(int) (((b + 22.5f) % 360f) / 45f) % 8];
    }
    /** Valor real do indicador, ou null quando não há fonte de dados válida. */
    private String vehicleValue(int id) {
        if (id == 0) {
            if (hasCanData()) return vehicleValues[0];
            return gpsFresh() && gpsSpeedKmh >= 0 ? String.valueOf(Math.round(gpsSpeedKmh)) : null;
        }
        if (id == 6) return gpsFresh() && gpsBearing >= 0 ? compass(gpsBearing) + " " + Math.round(gpsBearing) + "°" : null;
        if (id == 7) return gpsFresh() && !Double.isNaN(gpsAlt) ? String.valueOf(Math.round(gpsAlt)) : null;
        if (id == 10) return String.valueOf(speedLimit);
        return hasCanData() ? vehicleValues[id] : null;
    }
    private float speedNow() { String sv = vehicleValue(0); if (sv == null) return -1; try { return Float.parseFloat(sv); } catch (Exception e) { return -1; } }

    // ---- Clima ----
    private String weatherCity = "", weatherLine = "Configure a cidade", weatherDetail = "";
    private boolean weatherAuto = false;
    private double weatherAutoLat = Double.NaN, weatherAutoLon = Double.NaN;
    private long weatherAutoChecked = 0;
    private String brandTitle = "PEUGEOT 308";
    private boolean minimalIcons = true;
    private final HashSet<String> originalAppIcons = new HashSet<>();
    private boolean standaloneVehicleButton = true;
    private int vehicleSelectedPage = 0;
    private String weatherCondition = "--", weatherHigh = "--", weatherLow = "--", weatherWind = "--", weatherRain = "--", weatherHumidity = "--";
    private int weatherCode = -1, sunriseMin = -1, sunsetMin = -1;
    private long lastWeather = 0, lastWeatherSuccess = 0;
    private boolean weatherLoading = false, weatherFailed = false, netOnline = false;

    // ---- Dock ----
    // ids: 0 Waze, 1 Rádio, 2 Spotify, 3 Telefone, 4 Bluetooth, 5 Apps, 6 Config. (oculto), 7 Casa, 8 Trabalho, 9 Último app
    private static final int N_DOCK = 10;
    private final int[] dockOrder = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    private final boolean[] dockEnabled = {true, true, true, true, false, true, true, true, true, true};
    private final ArrayList<String> pinnedPackages = new ArrayList<>();
    private final String[] names = {"Waze", "Rádio", "Spotify", "Telefone", "Bluetooth", "Apps", "Config.", "Casa", "Trabalho", "Último app"};
    private final String[] packages = {"com.waze", "com.jancar.radio", "com.spotify.music", "com.jancar.bluetooth", "", "", "", "", "", ""};

    private long volumeUntil;
    private int previousVolume = 5;
    private int tickCount = 0;
    private Runnable rampTask;
    private ConnectivityManager.NetworkCallback netCallback;
    private String updateBase = DEFAULT_SERVER;
    private String ghRepo = "lemiastkuosky86/Pug308", notifiedVersion = ""; private int updateSource = 0; private boolean autoUpdate = true, updateChecking = false;
    private long updateDownloadId = -1; private Uri pendingInstall = null; private BroadcastReceiver downloadReceiver;

    private final Runnable tick = new Runnable() {
        public void run() {
            updateDrivingMode();
            pushSoundState();
            checkAlerts();
            if (computeNight() != night) applyTheme();
            if (tickCount % 3 == 0) { refreshConnections(); updateMedia(); }
            if (tickCount % 600 == 599) maybeAutoUpdate();
            tickCount++;
            if (dashboard != null) dashboard.invalidate();
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- tema
    private boolean computeNight() {
        if (themeMode == 1) return true;
        if (themeMode == 2) return false;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (sunriseMin >= 0 && sunsetMin >= 0) return now < sunriseMin || now >= sunsetMin;
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h < 6 || h >= 18;
    }
    private void applyTheme() {
        night = computeNight();
        int base = baseOf(presetIndex);
        if (night && base == 1) { // preto puro: baixo ofuscamento, luz âmbar
            cBg0 = 0xff000000; cBg1 = 0xff050505; cHead = 0xff000000;
            cSurfTop = 0xff171717; cSurfBot = 0xff0E0E0E; cSurf2 = 0xff202020;
            cHair = 0x24FFFFFF; cText = 0xffF1EDE5; cMuted = 0xffA8A49D; cDim = 0xff77746E;
            cShadow = 0x99000000; cTrack = 0x2AFFFFFF; cOk = 0xff3DDC84; cBad = 0xffFF5C6C;
        } else if (night && base == 2) { // cinza chumbo
            cBg0 = 0xff16191D; cBg1 = 0xff1F2328; cHead = 0xff13161A;
            cSurfTop = 0xff2C3137; cSurfBot = 0xff24282E; cSurf2 = 0xff353B43;
            cHair = 0x20FFFFFF; cText = 0xffECEEF1; cMuted = 0xffA2A8B0; cDim = 0xff737A83;
            cShadow = 0x66000000; cTrack = 0x2AFFFFFF; cOk = 0xff3DDC84; cBad = 0xffFF5C6C;
        } else if (night) {
            cBg0 = 0xff080C12; cBg1 = 0xff10161F; cHead = 0xff0D131B;
            cSurfTop = 0xff1A222D; cSurfBot = 0xff121820; cSurf2 = 0xff202A37;
            cHair = 0x1FFFFFFF; cText = 0xffF2F5F9; cMuted = 0xffA0ACBC; cDim = 0xff6F7C8D;
            cShadow = 0x66000000; cTrack = 0x26FFFFFF; cOk = 0xff3DDC84; cBad = 0xffFF5C6C;
        } else if (base != 0) { // dia: cinza neutro (sem tingimento)
            cBg0 = 0xffE4E5E7; cBg1 = 0xffF1F2F3; cHead = 0xffF8F8F9;
            cSurfTop = 0xffFFFFFF; cSurfBot = 0xffF3F4F5; cSurf2 = 0xffE8E9EB;
            cHair = 0x1F0F1720; cText = 0xff0F1720; cMuted = 0xff566272; cDim = 0xff8793A2;
            cShadow = 0x221E2A3C; cTrack = 0x1F0F1720; cOk = 0xff14A44D; cBad = 0xffE5384F;
        } else {
            cBg0 = 0xffE3E8EE; cBg1 = 0xffF0F3F7; cHead = 0xffF8FAFC;
            cSurfTop = 0xffFFFFFF; cSurfBot = 0xffF3F5F8; cSurf2 = 0xffE7EBF0;
            cHair = 0x1F0F1720; cText = 0xff0F1720; cMuted = 0xff566272; cDim = 0xff8793A2;
            cShadow = 0x221E2A3C; cTrack = 0x1F0F1720; cOk = 0xff14A44D; cBad = 0xffE5384F;
        }
        if (base == 0 && presetIndex > NAVY_START) { // tema colorido: tinge levemente as superfícies com a cor do tema
            float k = night ? .12f : .07f; int t = PRESET_ACCENTS[presetIndex];
            cBg0 = tintC(cBg0, t, k); cBg1 = tintC(cBg1, t, k); cHead = tintC(cHead, t, k);
            cSurfTop = tintC(cSurfTop, t, k); cSurfBot = tintC(cSurfBot, t, k); cSurf2 = tintC(cSurf2, t, k);
        }
        accent = night ? accentBase : lightAccent(accentBase);
        onAccent = lum(accent) > .6f ? 0xff0B1015 : Color.WHITE;
        applyBrightness();
        if (dashboard != null) dashboard.invalidate();
    }
    private static int tintC(int base, int tint, float k) { return mix(base, 0xff000000 | (tint & 0xffffff), k); }
    private static float lum(int c) { return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f; }
    private static int lightAccent(int c) { return lum(c) > .55f ? mix(c, 0xff000000, .38f) : c; } // contraste no tema claro
    private void setPreset(int i) {
        presetIndex = Math.max(0, Math.min(PRESET_NAMES.length - 1, i));
        accentBase = PRESET_ACCENTS[presetIndex];
        getPreferences(0).edit().putInt("theme2", presetIndex).apply();
        applyTheme();
    }
    /** Brilho automático: dia/noite. Na janela do launcher sempre; no sistema só se o Android permitir (WRITE_SETTINGS). */
    private void applyBrightness() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (autoBrightness) {
                int pct = Math.max(5, Math.min(100, night ? brightNight : brightDay));
                lp.screenBrightness = pct / 100f;
                if (Settings.System.canWrite(this)) {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(13, pct * 255 / 100));
                }
            } else lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }
    private void setThemeMode(int mode) {
        themeMode = mode;
        getPreferences(0).edit().putInt("theme", mode).apply();
        applyTheme();
    }
    private static int mix(int a, int b, float t) {
        return Color.argb((int) (Color.alpha(a) * (1 - t) + Color.alpha(b) * t), (int) (Color.red(a) * (1 - t) + Color.red(b) * t),
                (int) (Color.green(a) * (1 - t) + Color.green(b) * t), (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    // ---------------------------------------------------------------- ciclo de vida
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        snd = SoundEngine.get(this); snd.start(); // reaplica o equalizador salvo ao ligar o carro
        SharedPreferences prefs = getPreferences(0);
        presetIndex = Math.max(0, Math.min(PRESET_NAMES.length - 1, prefs.getInt("theme2", 0))); // padrão: Peugeot Âmbar (preto + âmbar)
        accentBase = PRESET_ACCENTS[presetIndex];
        albumBg = prefs.getBoolean("album_bg", true);
        autoBrightness = prefs.getBoolean("auto_bright", true);
        brightDay = Math.max(10, Math.min(100, prefs.getInt("bright_day", 100)));
        brightNight = Math.max(5, Math.min(100, prefs.getInt("bright_night", 40)));
        fadeBoot = prefs.getBoolean("fade_boot", true);
        drivingEnabled = prefs.getBoolean("driving_mode", true);
        drivingThreshold = Math.max(5, Math.min(60, prefs.getInt("driving_kmh", 10)));
        speedAlert = prefs.getBoolean("speed_alert", false);
        speedLimit = Math.max(30, Math.min(220, prefs.getInt("speed_limit", 110)));
        int[] defSlots = {0, 4, 9};
        for (int i = 0; i < topSlot.length; i++) topSlot[i] = Math.max(-1, Math.min(10, prefs.getInt("top_slot_" + i, defSlots[i])));
        alertSound = prefs.getBoolean("alert_sound", true);
        radarEnabled = prefs.getBoolean("radar_alert", false);
        vehicleAction = prefs.getString("vehicle_action", DEFAULT_VEHICLE_ACTION);
        for (int i = 0; i < 3; i++) { vbtnLabel[i] = prefs.getString("vbtn_label_" + i, vbtnLabel[i]); vbtnCmd[i] = prefs.getString("vbtn_cmd_" + i, vbtnCmd[i]); }
        homeAddr = prefs.getString("home_addr", ""); workAddr = prefs.getString("work_addr", "");
        for (String pkg : prefs.getString("recent_apps", "").split(",")) if (!pkg.isEmpty() && !recentPackages.contains(pkg)) recentPackages.add(pkg);
        beep = prefs.getBoolean("beep", false);
        themeMode = Math.max(0, Math.min(2, prefs.getInt("theme", 0)));
        updateBase = prefs.getString("server", DEFAULT_SERVER);
        ghRepo = prefs.getString("gh_repo", "lemiastkuosky86/Pug308"); if (ghRepo.trim().isEmpty()) ghRepo = "lemiastkuosky86/Pug308"; updateSource = prefs.getInt("upd_source", 0); autoUpdate = prefs.getBoolean("upd_auto", true); notifiedVersion = prefs.getString("upd_notified", "");
        vehicleMenuEnabled = prefs.getBoolean("vehicle_menu", prefs.getBoolean("vehicle_card", false));
        topWidgetEnabled = prefs.getBoolean("top_widget", true);
        for (int i = 0; i < vehicleVisible.length; i++) vehicleVisible[i] = prefs.getBoolean("vehicle_visible_" + i, i != 10);
        String savedOrder = prefs.getString("dock_order", "0,1,2,3,4,5,6,7,8,9");
        try {
            String[] parts = savedOrder.split(",");
            if (parts.length == 7) parts = (savedOrder + ",7,8,9").split(","); // migração da v1.10
            boolean[] seen = new boolean[N_DOCK];
            if (parts.length != N_DOCK) throw new Exception();
            for (int i = 0; i < N_DOCK; i++) { int id = Integer.parseInt(parts[i].trim()); if (id < 0 || id >= N_DOCK || seen[id]) throw new Exception(); seen[id] = true; dockOrder[i] = id; }
        } catch (Exception ignored) { for (int i = 0; i < N_DOCK; i++) dockOrder[i] = i; }
        for (int i = 0; i < N_DOCK; i++) dockEnabled[i] = i != 6 && prefs.getBoolean("dock_enabled_" + i, i != 4);
        for (String pkg : prefs.getString("pinned_apps", "").split(",")) if (!pkg.isEmpty() && !pinnedPackages.contains(pkg)) pinnedPackages.add(pkg);
        weatherCity = prefs.getString("city", "");
        weatherAuto = prefs.getBoolean("weather_auto", false);
        brandTitle = prefs.getString("brand_title", "PEUGEOT 308");
        minimalIcons = prefs.getBoolean("minimal_icons", true);
        lyricsOn=prefs.getBoolean("lyrics_on",false);
        userLyrics=prefs.getString("lyrics_text","");
        userLyricsSong=prefs.getString("lyrics_song","");
        for(String packageName:prefs.getString("original_app_icons","").split(","))
            if(!packageName.isEmpty()) originalAppIcons.add(packageName);
        standaloneVehicleButton = prefs.getBoolean("standalone_vehicle_button", true);
        applyTheme();
        dashboard = new Dashboard();
        jancarBridge = new JancarBridge(this, () -> handler.post(() -> { if (dashboard != null) dashboard.invalidate(); }));
        jancarBridge.start();
        setContentView(dashboard);
        mediaManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        updateMedia();
        refreshConnections();
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) need.add("android.permission.BLUETOOTH_CONNECT");
        if (needGps() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 14); else startGps();
        registerNetworkWatcher();
        if (!weatherCity.isEmpty() && !weatherAuto) fetchWeather(); else if (weatherAuto) tryAutoWeather();
        registerScreenReceiver();
        handler.post(shineTick);
        if (!bootArmed) { bootArmed = true; if (SystemClock.elapsedRealtime() < 6 * 60 * 1000L) armStartupFade(); } // ligou o carro (boot recente)
        handler.post(tick);
        handler.postDelayed(this::maybeAutoUpdate, 25000);
    }
    @Override protected void onResume() { super.onResume(); if (pendingInstall != null && getPackageManager().canRequestPackageInstalls()) { Uri pu = pendingInstall; pendingInstall = null; installApk(pu); } startGps(); if (snd != null) snd.start(); applyBrightness(); lastCandStamp = 0; if (dashboard != null) { dashboard.clearIcons(); dashboard.invalidate(); } }
    @Override protected void onPause() { if (snd == null || !snd.autoVolOn) stopGps(); super.onPause(); }
    @Override protected void onDestroy() {
        try { if (eqDialog != null) eqDialog.dismiss(); } catch (Exception ignored) {}
        if (snd != null) snd.saveNow();
        if (jancarBridge != null) jancarBridge.stop();
        try { if (downloadReceiver != null) unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        if (mediaCtl != null) mediaCtl.unregisterCallback(mediaCallback);
        handler.removeCallbacksAndMessages(null);
        try { if (netCallback != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        stopGps();
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (alertTone != null) alertTone.release();
        if (tone != null) tone.release();
        super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 14) { refreshConnections(); startGps(); }
    }

    // ================================================================ v1.12
    private final Runnable shineTick = new Runnable() {
        public void run() {
            long m = SystemClock.uptimeMillis() % SHINE_PERIOD;
            if (dashboard != null && m < SHINE_MS && !volumeVisible) dashboard.invalidateHeader();
            handler.postDelayed(this, m < SHINE_MS ? 40 : Math.min(400, SHINE_PERIOD - m));
        }
    };

    // ---- Alertas: limite de velocidade e radar (popup + som opcional) ----
    private void showAlert(int kind, String title, String sub, long ms, boolean sound) {
        alertKind = kind; alertTitle = title; alertSub = sub; alertStart = SystemClock.uptimeMillis(); alertUntil = alertStart + ms;
        if (sound && alertSound) alertBeep();
        if (dashboard != null) dashboard.invalidate();
    }
    private void alertBeep() {
        try { if (alertTone == null) alertTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); alertTone.startTone(alertKind == 0 ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, alertKind == 0 ? 600 : 350); } catch (Exception ignored) {}
    }
    private static double haversine(double la1, double lo1, double la2, double lo2) {
        double dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
    private static double bearingTo(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2), dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(p2), x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
    private void checkAlerts() {
        long nowEl = SystemClock.elapsedRealtime();
        float v = speedNow();
        if (speedAlert && v >= 0) {
            if (v > speedLimit) {
                if (!overNow || nowEl - lastOverAlert > 30000) {
                    overNow = true; lastOverAlert = nowEl;
                    showAlert(1, "VELOCIDADE ACIMA DO LIMITE", Math.round(v) + " km/h  •  limite " + speedLimit + " km/h", 4000, true);
                }
            } else if (v <= speedLimit - 2) overNow = false;
        } else overNow = false;
        if (!radarEnabled || !gpsFresh() || Double.isNaN(gpsLat)) return;
        boolean stale = radarFetchAt == 0 || nowEl - radarFetchAt > 1200000 || haversine(gpsLat, gpsLon, radarFetchLat, radarFetchLon) > 3500;
        if (!radarFetching && stale && (radarFetchAt == 0 || nowEl - radarFetchAt > 120000)) fetchRadars(gpsLat, gpsLon);
        if (v < 15 || gpsBearing < 0) return;
        double reach = Math.max(250, Math.min(700, v / 3.6 * 12)), best = 1e9; double[] bestR = null;
        for (double[] rd : radars) {
            double d = haversine(gpsLat, gpsLon, rd[0], rd[1]);
            if (d > reach || d >= best) continue;
            double diff = Math.abs(((bearingTo(gpsLat, gpsLon, rd[0], rd[1]) - gpsBearing + 540) % 360) - 180);
            if (diff > 45) continue; // só radares no sentido da viagem
            best = d; bestR = rd;
        }
        if (bestR == null) return;
        long id = (long) bestR[3]; Long last = radarAlerted.get(id);
        boolean first = last == null || nowEl - last > 300000;
        if (first) radarAlerted.put(id, nowEl);
        String lim = bestR[2] > 0 ? "  •  limite " + (int) bestR[2] + " km/h" : "";
        showAlert(0, "RADAR À FRENTE", (Math.round(best / 10) * 10) + " m" + lim, 3500, first);
    }
    /** Radares do OpenStreetMap (highway=speed_camera) num raio de 7 km, via Overpass. Experimental: depende da cobertura do mapa. */
    private void fetchRadars(final double lat, final double lon) {
        radarFetching = true; radarFetchAt = SystemClock.elapsedRealtime();
        new Thread(() -> {
            try {
                String q = "[out:json][timeout:20];node(around:7000," + lat + "," + lon + ")[\"highway\"=\"speed_camera\"];out;";
                JSONArray el = readJson("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")).getJSONArray("elements");
                final ArrayList<double[]> list = new ArrayList<>();
                for (int i = 0; i < el.length(); i++) {
                    JSONObject o = el.getJSONObject(i); double lim = 0;
                    JSONObject tags = o.optJSONObject("tags");
                    if (tags != null) { try { lim = Double.parseDouble(tags.optString("maxspeed", "").replaceAll("[^0-9.]", "")); } catch (Exception ignored) {} }
                    list.add(new double[]{o.getDouble("lat"), o.getDouble("lon"), lim, o.getLong("id")});
                }
                handler.post(() -> { radars.clear(); radars.addAll(list); radarFetchLat = lat; radarFetchLon = lon; radarFetching = false; });
            } catch (Exception e) { handler.post(() -> radarFetching = false); }
        }).start();
    }

    // ---- escala dos menus de configuração (tela do carro) ----
    private static final String SCALED = "scaled";
    private void scaleTree(View v, float k) {
        if (v == null) return;
        if (!SCALED.equals(v.getTag())) {
            if (v instanceof TextView) { TextView tv = (TextView) v; tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * k); }
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                if (lp.width > 0) lp.width = Math.round(lp.width * k);
                if (lp.height > 0) lp.height = Math.round(lp.height * k);
                if (lp instanceof ViewGroup.MarginLayoutParams) { ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp; m.setMargins(Math.round(m.leftMargin * k), Math.round(m.topMargin * k), Math.round(m.rightMargin * k), Math.round(m.bottomMargin * k)); }
                v.setLayoutParams(lp);
            }
            v.setPadding(Math.round(v.getPaddingLeft() * k), Math.round(v.getPaddingTop() * k), Math.round(v.getPaddingRight() * k), Math.round(v.getPaddingBottom() * k));
            v.setTag(SCALED);
        }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) scaleTree(g.getChildAt(i), k); }
    }
    private void choiceRow(LinearLayout box, String title, String[] opts, int index, java.util.function.IntConsumer onChange) {
        final int[] cur = {index};
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title, 15, cText, false), new LinearLayout.LayoutParams(0, dp(46), 1));
        final TextView val = label(opts[cur[0]], 15, cText, true); val.setGravity(Gravity.CENTER); val.setSingleLine(true);
        Button prev = smallButton("◀", () -> { cur[0] = (cur[0] + opts.length - 1) % opts.length; val.setText(opts[cur[0]]); onChange.accept(cur[0]); });
        Button next = smallButton("▶", () -> { cur[0] = (cur[0] + 1) % opts.length; val.setText(opts[cur[0]]); onChange.accept(cur[0]); });
        row.addView(prev, new LinearLayout.LayoutParams(dp(53), dp(44)));
        row.addView(val, new LinearLayout.LayoutParams(dp(170), dp(44)));
        row.addView(next, new LinearLayout.LayoutParams(dp(53), dp(44)));
        box.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    // ================================================================ v1.11 — novos recursos
    private List<String> dockPinned() { return drivingMode ? Collections.<String>emptyList() : pinnedPackages; }
    private void refreshGpsNeed() {
        if (needGps()) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 14);
            else startGps();
        } else stopGps();
        if (dashboard != null) dashboard.invalidate();
    }

    // ---- Fade de entrada ao ligar o carro (mesma rampa do mudo: 0 → volume anterior em 1,8 s) ----
    private void fadeFromZero() {
        try {
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v <= 0) return;
            previousVolume = v; cancelRamp();
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            rampVolume(v, 1800, null);
        } catch (Exception ignored) {}
    }
    /** Chamado ao ligar (boot recente) e ao acordar a tela depois de um tempo apagada. */
    private void armStartupFade() {
        if (!fadeBoot) return;
        fadePending = true; fadeWindowUntil = SystemClock.elapsedRealtime() + 120000L;
        fadeFromZero();
    }
    /** Se o Spotify só começar a tocar depois da rampa (ex.: retoma após o Bluetooth), repete o fade uma vez. */
    private void trackPlaybackFade() {
        boolean now = mediaPlaying;
        if (now && !wasPlaying && fadePending) {
            fadePending = false;
            if (fadeBoot && rampTask == null && SystemClock.elapsedRealtime() < fadeWindowUntil) fadeFromZero();
        }
        wasPlaying = now;
    }
    private void registerScreenReceiver() {
        try {
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(a) && snd != null) snd.start();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) screenOffAt = SystemClock.elapsedRealtime();
                    else if (Intent.ACTION_SCREEN_ON.equals(a) && screenOffAt > 0 && SystemClock.elapsedRealtime() - screenOffAt > 20000L) { screenOffAt = 0; armStartupFade(); }
                }
            };
            IntentFilter f = new IntentFilter(); f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenReceiver, f);
        } catch (Exception ignored) {}
    }

    // ---- Fundo com a cor da capa (sutil) ----
    private int dominantColor(Bitmap src) {
        try {
            Bitmap sm = Bitmap.createScaledBitmap(src, 24, 24, true);
            double r = 0, g = 0, b = 0, ws = 0; float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int c = sm.getPixel(x, y); Color.colorToHSV(c, hsv);
                double w = 0.05 + hsv[1] * hsv[2]; // pixels vivos pesam mais
                r += Color.red(c) * w; g += Color.green(c) * w; b += Color.blue(c) * w; ws += w;
            }
            if (sm != src) sm.recycle();
            Color.colorToHSV(Color.rgb((int) (r / ws), (int) (g / ws), (int) (b / ws)), hsv);
            if (hsv[1] >= .12f) hsv[1] = Math.max(.35f, Math.min(.85f, hsv[1] * 1.15f)); // capa cinza continua neutra
            hsv[2] = Math.max(.55f, Math.min(.95f, hsv[2]));
            return Color.HSVToColor(255, hsv);
        } catch (Exception e) { return 0; }
    }
    private void maybeUpdateArtColor() {
        if (!albumBg) return;
        if (albumArt == null) { if (mediaCtl == null) { artKey = ""; setArtTarget(0); } return; }
        String key = songTitle + "|" + songArtist;
        if (key.equals(artKey)) return;
        artKey = key;
        setArtTarget(dominantColor(albumArt));
    }
    private void setArtTarget(int c) {
        if (c == artTarget && (c != 0 || artCur == 0)) return;
        artFrom = artCur; artTarget = c; artBlendStart = SystemClock.uptimeMillis();
        handler.removeCallbacks(artAnim); handler.post(artAnim);
    }
    private final Runnable artAnim = new Runnable() {
        public void run() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - artBlendStart) / 1200f);
            int from = artFrom == 0 ? (artTarget & 0x00FFFFFF) : artFrom;
            int to = artTarget == 0 ? (artFrom & 0x00FFFFFF) : artTarget;
            artCur = mix(from, to, t);
            if (dashboard != null) dashboard.invalidate();
            if (t < 1f) handler.postDelayed(this, 50); else artCur = artTarget;
        }
    };

    // ---- Modo direção ----
    private void updateDrivingMode() {
        boolean before = drivingMode;
        if (drivingTestUntil > 0) {
            if (SystemClock.uptimeMillis() < drivingTestUntil) drivingMode = true;
            else { drivingTestUntil = 0; drivingMode = false; drvAbove = 0; drvBelow = 0; drvLost = 0; }
        } else if (!drivingEnabled) {
            drivingMode = false;
        } else {
            String sv = vehicleValue(0);
            if (sv == null) { if (++drvLost > 60) drivingMode = false; } // túnel/sem GPS: mantém por 1 min
            else {
                drvLost = 0; float v = 0;
                try { v = Float.parseFloat(sv); } catch (Exception ignored) {}
                if (v >= drivingThreshold) { drvAbove++; drvBelow = 0; if (drvAbove >= 2) drivingMode = true; }
                else if (v < Math.max(3f, drivingThreshold * 0.4f)) { drvBelow++; drvAbove = 0; if (drvBelow >= 6) drivingMode = false; } // 6 s parado
                else drvAbove = 0;
            }
        }
        if (drivingMode != before) {
            if (drivingMode && activeDialog != null) { try { activeDialog.dismiss(); } catch (Exception ignored) {} }
            if (dashboard != null) dashboard.invalidate();
        }
    }

    // ---- Waze: Casa / Trabalho ----
    private void openWazeDestination(boolean home) {
        String addr = (home ? homeAddr : workAddr).trim();
        if (addr.isEmpty()) { editAddress(home, null); return; }
        boolean coords = addr.matches("-?\\d{1,3}(\\.\\d+)?\\s*,\\s*-?\\d{1,3}(\\.\\d+)?");
        String query = coords ? "ll=" + addr.replaceAll("\\s+", "") : "q=" + Uri.encode(addr);
        String[] uris = {"waze://?" + query + "&navigate=yes", "https://waze.com/ul?" + query + "&navigate=yes"};
        for (String u : uris) {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                it.setPackage("com.waze"); it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it); noteRecent("com.waze"); return;
            } catch (Exception ignored) {}
        }
        snack("Waze não encontrado");
    }
    private void editAddress(boolean home, Runnable after) {
        promptText(home ? "Endereço de casa (Waze)" : "Endereço do trabalho (Waze)", "Rua, número, cidade  ou  -23.0305,-45.5551", home ? homeAddr : workAddr, val -> {
            if (home) homeAddr = val; else workAddr = val;
            getPreferences(0).edit().putString(home ? "home_addr" : "work_addr", val).apply();
            dashboard.invalidate(); if (after != null) after.run();
        });
    }

    // ---- Último app usado ----
    private void noteRecent(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return;
        recentPackages.remove(pkg); recentPackages.add(0, pkg);
        while (recentPackages.size() > 8) recentPackages.remove(recentPackages.size() - 1);
        getPreferences(0).edit().putString("recent_apps", android.text.TextUtils.join(",", recentPackages)).apply();
        lastCandStamp = 0;
    }
    /** Último app aberto pelo launcher que ainda não está no dock (fixados ou atalhos padrão ativos). */
    private String lastAppCandidate() {
        long now = SystemClock.elapsedRealtime();
        if (lastCandStamp != 0 && now - lastCandStamp < 3000) return lastCandPkg;
        ArrayList<String> inDock = new ArrayList<>(pinnedPackages);
        for (int id = 0; id < N_DOCK; id++) if (id != 9 && id != 6 && dockEnabled[id] && !packages[id].isEmpty()) inDock.add(packages[id]);
        String found = null;
        for (String pkg : recentPackages) {
            if (inDock.contains(pkg)) continue;
            try { if (getPackageManager().getLaunchIntentForPackage(pkg) == null) continue; } catch (Exception e) { continue; }
            found = pkg; break;
        }
        lastCandPkg = found; lastCandStamp = now;
        return found;
    }

    // Acesso ao aplicativo OEM sem duplicar funções nem enviar comandos ao CAN.
    private void openJancarApp(boolean carApp) {
        Intent list = new Intent(Intent.ACTION_MAIN); list.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps = getPackageManager().queryIntentActivities(list, 0);
        if (apps != null) for (android.content.pm.ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            String name = app.loadLabel(getPackageManager()).toString().toLowerCase(Locale.ROOT);
            boolean match = carApp ? (name.contains("veículo") || name.contains("veiculo") || name.contains("car info") || name.contains("canbus"))
                                   : (name.equals("rádio") || name.equals("radio") || name.contains("fm radio"));
            if (!match || getPackageName().equals(pkg)) continue;
            try {
                Intent target = new Intent(list);
                target.setClassName(pkg, app.activityInfo.name);
                startActivity(target);
                return;
            } catch (RuntimeException ignored) { }
        }
        snack(carApp ? "App original do veículo não localizado" : "Rádio original não localizado");
    }

    // ---- Botões do carro (RT4) ----
    /** Ponto de ligação com o CANBUS. Enquanto não houver protocolo conhecido da Jancar, nada é transmitido ao carro. */
    private boolean canSend(String command) { return false; }
    private void vehicleCommand(int i) {
        try {
            Intent it = new Intent(vehicleAction);
            it.putExtra("command", vbtnCmd[i]); it.putExtra("button", i); it.putExtra("label", vbtnLabel[i]);
            sendBroadcast(it); // para um app/serviço ponte (Tasker, serviço da Jancar etc.) que queira ouvir
        } catch (Exception ignored) {}
        if (!canSend(vbtnCmd[i])) snack("CANBUS não conectado: o comando não chegou ao carro");
    }
    private void vehicleOptionsMenu() {
        String[] options={"Escolher indicadores", "Próxima página de indicadores", "Abrir informações da Jancar", "Editar botões do veículo"};
        new AlertDialog.Builder(this).setTitle("Computador de bordo")
           .setItems(options,(d,which)->{
               if(which==0){settings(3);}
               else if(which==1){vehicleSelectedPage++;dashboard.invalidate();}
               else if(which==2){openJancarApp(true);}
               else {editVehicleButton(0,null);}
           }).setNegativeButton("Fechar",null).show();
    }
    private void openLyrics(){
        String now=songTitle == null?"":songTitle;
        boolean match=now.equalsIgnoreCase(userLyricsSong) && !userLyrics.trim().isEmpty();
        LinearLayout body=vertical();body.setPadding(dp(18),dp(14),dp(18),dp(12));
        body.addView(label(match?"Letra para: "+now:"Sem letra cadastrada para: "+now,17,cText,true));
        TextView text=label(match?userLyrics:"A sessão de mídia não fornece letras de músicas. Você pode inserir letras que tenha direito de usar.",15,cMuted,false);
        text.setGravity(Gravity.TOP);text.setPadding(0,dp(14),0,dp(14));body.addView(text);
        ScrollView wrap=new ScrollView(this);wrap.addView(body);
        new AlertDialog.Builder(this).setTitle("Spotify • Letras")
            .setView(wrap).setPositiveButton("Fechar",null)
            .setNeutralButton("Inserir texto",(d,i)->popupInput("Letras fornecidas por você","Cole texto de sua autoria ou autorizado",match?userLyrics:"",v->{
                userLyrics=v;userLyricsSong=now;getPreferences(0).edit().putString("lyrics_text",v).putString("lyrics_song",now).apply();
            })).show();
    }
    private void editVehicleButton(int i, Runnable after) {
        promptText("Nome do botão " + (i + 1), "Ex.: MENU", vbtnLabel[i], lb -> {
            if (!lb.isEmpty()) vbtnLabel[i] = lb;
            promptText("Comando do botão " + (i + 1), "Ex.: MENU, TRIP, BACK", vbtnCmd[i], cmd -> {
                if (!cmd.isEmpty()) vbtnCmd[i] = cmd;
                getPreferences(0).edit().putString("vbtn_label_" + i, vbtnLabel[i]).putString("vbtn_cmd_" + i, vbtnCmd[i]).apply();
                dashboard.invalidate(); if (after != null) after.run();
            });
        });
    }

    // ---- helpers de configuração ----
    private void promptText(String title, String hint, String current, java.util.function.Consumer<String> ok) { popupInput(title, hint, current, ok); }
    private Switch switchRow(LinearLayout box, String text, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        Switch sw = new Switch(this); sw.setText(text); sw.setTextSize(15); sw.setTextColor(cText); sw.setChecked(checked); sw.setPadding(0, dp(8), 0, dp(8));
        sw.setOnCheckedChangeListener(l); box.addView(sw); return sw;
    }
    private Button smallButton(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title); b.setTextColor(cText); b.setAllCaps(false); b.setTextSize(12);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(cSurf2));
        b.setOnClickListener(v -> { feedback(); action.run(); });
        return b;
    }
    private void stepperRow(LinearLayout box, String title, int value, int min, int max, int step, String unit, java.util.function.IntConsumer onChange) {
        final int[] cur = {value};
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title, 15, cText, false), new LinearLayout.LayoutParams(0, dp(46), 1));
        final TextView val = label(cur[0] + unit, 16, cText, true); val.setGravity(Gravity.CENTER);
        Button minus = smallButton("−", () -> { cur[0] = Math.max(min, cur[0] - step); val.setText(cur[0] + unit); onChange.accept(cur[0]); });
        Button plus = smallButton("+", () -> { cur[0] = Math.min(max, cur[0] + step); val.setText(cur[0] + unit); onChange.accept(cur[0]); });
        minus.setTextSize(18); plus.setTextSize(18);
        row.addView(minus, new LinearLayout.LayoutParams(dp(53), dp(44)));
        row.addView(val, new LinearLayout.LayoutParams(dp(80), dp(44)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(53), dp(44)));
        box.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    private void presetGrid(LinearLayout box, Runnable redraw) {
        int[][] groups = {{0, 5}, {5, 12}, {12, 22}, {22, 32}};
        String[] titles = {"PRETO  ·  luz âmbar, ótimo para a noite", "CINZA CHUMBO  ·  suave para os olhos", "CORES", null};
        for (int gI = 0; gI < groups.length; gI++) {
            if (titles[gI] != null) box.addView(label(titles[gI], 11, cDim, true), new LinearLayout.LayoutParams(-1, dp(24)));
            LinearLayout row = new LinearLayout(this); box.addView(row, new LinearLayout.LayoutParams(-1, dp(72)));
            for (int col = 0; col < 10; col++) {
                final int idx = groups[gI][0] + col;
                if (idx >= groups[gI][1]) { row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1)); continue; }
                final boolean sel = idx == presetIndex;
                LinearLayout cell = vertical(); cell.setGravity(Gravity.CENTER_HORIZONTAL);
                View dot = new View(this) {
                    final Paint preview = new Paint(3);
                    @Override protected void onDraw(Canvas cv) {
                        float w=getWidth(),h=getHeight(); int bg=idx<5?0xff0b0d11:idx<12?0xff292c30:0xff0b1b2d;
                        preview.setColor(bg);cv.drawRoundRect(0,0,w,h,dp(5),dp(5),preview);
                        int light=PRESET_ACCENTS[idx];
                        preview.setColor(light);
                        cv.drawRoundRect(w*.06f,h*.12f,w*.30f,h*.17f,dp(2),dp(2),preview);
                        preview.setColor(idx<12?0xff44494e:0xff18334a);
                        for(int z=0;z<3;z++)cv.drawRoundRect(w*(.06f+.30f*z),h*.30f,w*(.29f+.30f*z),h*.84f,dp(2),dp(2),preview);
                        preview.setColor(light);cv.drawRoundRect(w*.12f,h*.53f,w*.23f,h*.57f,dp(1),dp(1),preview);
                        cv.drawRoundRect(w*.42f,h*.67f,w*.52f,h*.71f,dp(1),dp(1),preview);
                        cv.drawCircle(w*.76f,h*.53f,dp(2),preview);
                        preview.setColor(sel?cText:cHair);preview.setStyle(Paint.Style.STROKE);preview.setStrokeWidth(dp(sel?2:1));
                        cv.drawRoundRect(1,1,w-1,h-1,dp(5),dp(5),preview);preview.setStyle(Paint.Style.FILL);
                    }
                };
                cell.addView(dot, new LinearLayout.LayoutParams(dp(63), dp(39)));
                TextView nm = label(PRESET_NAMES[idx], 10, sel ? cText : cMuted, sel); nm.setGravity(Gravity.CENTER); nm.setSingleLine(true);
                cell.addView(nm, new LinearLayout.LayoutParams(-1, dp(22)));
                row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
                cell.setOnClickListener(v -> { feedback(); setPreset(idx); redraw.run(); });
            }
        }
    }
    private void addressRow(LinearLayout box, boolean home, Runnable redraw) {
        String a = home ? homeAddr : workAddr;
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = label((home ? "Casa: " : "Trabalho: ") + (a.isEmpty() ? "não definido" : a), 15, cText, false);
        tv.setSingleLine(true); tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0, dp(46), 1));
        row.addView(smallButton("Editar", () -> editAddress(home, redraw)), new LinearLayout.LayoutParams(dp(100), dp(44)));
        box.addView(row);
    }

    // ---------------------------------------------------------------- áudio
    private void feedback() {
        dashboard.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (beep) try { if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 10); tone.startTone(ToneGenerator.TONE_PROP_BEEP, 24); } catch (Exception ignored) {}
    }
    private void adjustVolume(int delta) {
        try {
            cancelRamp();
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, v + delta));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
            if (snd != null) snd.volumeChanged();
            if (next > 0) previousVolume = next;
            if (v == 0 && next > 0) { volumeVisible = false; dashboard.invalidate(); } else showVolume();
        } catch (Exception e) { snack("Volume indisponível"); }
    }
    /** Mudo: desce rápido até 0; ao desmutar sobe aos poucos até o volume anterior (sem susto). */
    private void toggleMute() {
        try {
            cancelRamp();
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v > 0) {
                previousVolume = v;
                rampVolume(0, 450, () -> showVolume());
            } else {
                volumeVisible = false; dashboard.invalidate();
                rampVolume(Math.max(1, previousVolume), 1800, null);
            }
        } catch (Exception ignored) {}
    }
    private void rampVolume(final int target, final int totalMs, final Runnable done) {
        cancelRamp();
        final int from = audio.getStreamVolume(AudioManager.STREAM_MUSIC), steps = Math.abs(target - from);
        if (steps == 0) { if (done != null) done.run(); return; }
        final int dir = target > from ? 1 : -1;
        final double e = dir > 0 ? 1.6 : 1.0; // subida em curva suave: começa devagar
        rampTask = new Runnable() {
            int k = 0;
            public void run() {
                k++;
                try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, from + dir * k, 0); if (snd != null) snd.volumeChanged(); } catch (Exception ignored) {}
                if (k >= steps) { rampTask = null; if (done != null) done.run(); return; }
                double tNow = totalMs * Math.pow((double) k / steps, 1.0 / e), tNext = totalMs * Math.pow((double) (k + 1) / steps, 1.0 / e);
                handler.postDelayed(this, Math.max(30, (long) (tNext - tNow)));
            }
        };
        handler.postDelayed(rampTask, 30);
    }
    private void cancelRamp() { if (rampTask != null) { handler.removeCallbacks(rampTask); rampTask = null; } }
    private void showVolume() { volumeVisible = true; volumeUntil = SystemClock.uptimeMillis() + 3200; dashboard.invalidate(); }
    private boolean isMuted() { return audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0; }

    // ---------------------------------------------------------------- mídia (Spotify primeiro; senão qualquer sessão ativa)
    private void updateMedia() {
        if (mediaManager == null) return;
        try {
            ComponentName component = new ComponentName(this, SpotifyNotificationListener.class);
            List<MediaController> sessions = mediaManager.getActiveSessions(component);
            mediaPermission = true;
            MediaController found = null;
            for (MediaController c : sessions) if ("com.spotify.music".equals(c.getPackageName())) { found = c; break; }
            if (found == null) for (MediaController c : sessions) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { found = c; break; }
            }
            if (found == null && !sessions.isEmpty() && sessions.get(0).getMetadata() != null) found = sessions.get(0);
            if (mediaCtl != found) {
                if (mediaCtl != null) mediaCtl.unregisterCallback(mediaCallback);
                mediaCtl = found;
                if (found != null) found.registerCallback(mediaCallback);
            }
            if (found != null) {
                mediaIsSpotify = "com.spotify.music".equals(found.getPackageName());
                mediaSource = mediaIsSpotify ? "Spotify" : appLabel(found.getPackageName());
                MediaMetadata m = found.getMetadata();
                if (m != null) {
                    songTitle = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                    songArtist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (songTitle == null || songTitle.isEmpty()) songTitle = mediaSource;
                    if (songArtist == null) songArtist = "";
                    albumArt = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (albumArt == null) albumArt = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    songDuration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                }
                PlaybackState state = found.getPlaybackState();
                mediaPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                if (state != null) { songPosBase = Math.max(0, state.getPosition()); songPosStamp = state.getLastPositionUpdateTime(); songSpeed = state.getPlaybackSpeed(); }
                else { songPosBase = 0; songPosStamp = 0; songSpeed = 0; }
            } else {
                mediaSource = "Spotify"; mediaIsSpotify = true;
                songTitle = "Nenhuma música em reprodução";
                songArtist = "Abra o Spotify para começar";
                albumArt = null; mediaPlaying = false; songDuration = 0; songPosBase = 0; songPosStamp = 0;
            }
            trackPlaybackFade(); maybeUpdateArtColor();
        } catch (SecurityException ex) {
            mediaPermission = false; mediaCtl = null;
            songTitle = "Ative o acesso às notificações";
            songArtist = "Para mostrar a música em reprodução";
        } catch (Exception ignored) {}
        if (dashboard != null) dashboard.invalidate();
    }
    /** Posição interpolada: avança sozinha entre as atualizações do Android. */
    private long livePosition() {
        long pos = songPosBase;
        if (mediaPlaying && songPosStamp > 0) pos += (long) ((SystemClock.elapsedRealtime() - songPosStamp) * songSpeed);
        return songDuration > 0 ? Math.max(0, Math.min(pos, songDuration)) : Math.max(0, pos);
    }
    private void media(int key) {
        try {
            if (mediaCtl != null) {
                MediaController.TransportControls controls = mediaCtl.getTransportControls();
                if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) controls.skipToPrevious();
                else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) controls.skipToNext();
                else if (mediaPlaying) controls.pause(); else controls.play();
            } else if (!mediaPermission) openNotificationAccess();
            else launch(2);
        } catch (Exception e) { snack("Controle de mídia indisponível"); }
        handler.postDelayed(this::updateMedia, 450);
    }
    private void openNotificationAccess() {
        try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (Exception e) { snack("Abra o acesso às notificações nas configurações"); }
    }
    private String timeString(long ms) { long sec = Math.max(0, ms) / 1000; return String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60); }

    // ---------------------------------------------------------------- atalhos / apps
    private void launch(int i) {
        if (i == 6) { settings(); return; }
        if (i == 5) { picker(); return; }
        if (i == 4) { openSystemSetting(Settings.ACTION_BLUETOOTH_SETTINGS); return; }
        if (i == 7 || i == 8) { openWazeDestination(i == 7); return; }
        if (i == 9) { String last = lastAppCandidate(); if (last != null) launchPackage(last); else picker(); return; }
        try { Intent it = getPackageManager().getLaunchIntentForPackage(packages[i]); if (it != null) { noteRecent(packages[i]); startActivity(it); return; } } catch (Exception ignored) {}
        picker();
    }
    private void savePinned() {
        getPreferences(0).edit().putString("pinned_apps", android.text.TextUtils.join(",", pinnedPackages)).apply();
        lastCandStamp = 0; dashboard.invalidate();
    }
    private String appLabel(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception ex) { return pkg; }
    }
    private void launchPackage(String pkg) {
        try { Intent intent = getPackageManager().getLaunchIntentForPackage(pkg); if (intent == null) throw new Exception(); noteRecent(pkg); startActivity(intent); }
        catch (Exception ex) { snack("Não foi possível abrir " + appLabel(pkg)); }
    }
    private ArrayList<Integer> dockBuiltins() {
        ArrayList<Integer> list = new ArrayList<>();
        for (int id : dockOrder) {
            if (id == 6 || !dockEnabled[id]) continue;
            if (drivingMode && !(id <= 3 || id == 7 || id == 8)) continue; // modo direção: só o essencial
            if (id == 9 && lastAppCandidate() == null) continue;
            list.add(id);
        }
        return list;
    }
    private void openSystemSetting(String action) { try { startActivity(new Intent(action)); } catch (Exception e) { snack("Configuração indisponível"); } }

    // ================================================================ popups padronizados (v1.13)
    // Todos os popups seguem o tema, abrem centralizados, com tamanho proporcional à tela (1280x720 = 1,0), fundo escurecido
    // e sem trazer a barra do sistema de volta (modo imersivo mantido).
    private float popU() { android.util.DisplayMetrics m = getResources().getDisplayMetrics(); return Math.min(m.widthPixels / 1280f, m.heightPixels / 720f); }
    private int px(float v) { return Math.round(v); }
    private GradientDrawable solid(int color, float rad) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(rad); return g; }
    private GradientDrawable popBg(float rad) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{cSurfTop, cSurfBot});
        g.setCornerRadius(rad); g.setStroke(Math.max(1, px(rad / 18f)), cHair); return g;
    }
    private TextView popText(String s, float sizePx, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL); return t;
    }
    private TextView popButton(String text, boolean primary, float U, View.OnClickListener l) {
        TextView b = popText(text, 22 * U, primary ? onAccent : cText, true); b.setGravity(Gravity.CENTER);
        b.setBackground(solid(primary ? accent : cSurf2, 18 * U)); b.setPadding(px(30 * U), 0, px(30 * U), 0); b.setMinWidth(px(150 * U)); b.setClickable(true);
        b.setOnTouchListener((v, e) -> { int a = e.getActionMasked(); if (a == MotionEvent.ACTION_DOWN) v.setAlpha(.7f); else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.setAlpha(1f); return false; });
        b.setOnClickListener(l); return b;
    }
    /** Mostra um diálogo sem deixar o Android re-exibir a barra de navegação (que deslocava a janela). */
    private void immersiveFix(Window w) {
        if (w == null) return;
        w.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }
    /** Janelas grandes (Configurações, Apps): ocupam uma fração da tela, centralizadas. */
    private void showBig(Dialog d, float wFrac, float hFrac) {
        Window w = d.getWindow();
        if (w != null) w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        d.show();
        if (w != null) {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            w.setLayout(Math.round(m.widthPixels * wFrac), Math.round(m.heightPixels * hFrac));
            w.setGravity(Gravity.CENTER); w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = w.getAttributes(); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); lp.dimAmount = .62f; w.setAttributes(lp);
            immersiveFix(w);
        }
    }
    /** Popup padrão: título, mensagem opcional, campo de texto opcional e botões. */
    private void popup(final String title, final String message, final String hint, final String current, final String okLabel, final String cancelLabel, final java.util.function.Consumer<String> ok) {
        final float U = popU(); final boolean hasInput = hint != null;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = vertical(); card.setBackground(popBg(26 * U)); card.setPadding(px(34 * U), px(30 * U), px(34 * U), px(26 * U));
        card.addView(popText(title, 30 * U, cText, true));
        if (message != null) {
            TextView m = popText(message, 22 * U, cMuted, false); m.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = px(14 * U); card.addView(m, lp);
        }
        final EditText input = hasInput ? new EditText(this) : null;
        if (hasInput) {
            input.setSingleLine(true); input.setText(current == null ? "" : current); input.setHint(hint); input.setTextColor(cText); input.setHintTextColor(cDim);
            input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * U); input.setPadding(px(20 * U), 0, px(20 * U), 0); input.setBackground(solid(cSurf2, 16 * U));
            input.setSelectAllOnFocus(true); input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(70 * U)); lp.topMargin = px(18 * U); card.addView(input, lp);
        }
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.topMargin = px(26 * U); card.addView(row, rp);
        final Runnable doOk = () -> { String t = hasInput ? input.getText().toString().trim() : null; dialog.dismiss(); if (ok != null) ok.accept(t); };
        if (cancelLabel != null) { LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, px(64 * U)); bl.rightMargin = px(14 * U); row.addView(popButton(cancelLabel, false, U, v -> dialog.dismiss()), bl); }
        if (okLabel != null) row.addView(popButton(okLabel, true, U, v -> { feedback(); doOk.run(); }), new LinearLayout.LayoutParams(-2, px(64 * U)));
        if (hasInput) input.setOnEditorActionListener((v, a, e) -> { doOk.run(); return true; });
        dialog.setContentView(card);
        activeDialog = dialog; dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
        Window w = dialog.getWindow();
        if (w != null) { w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE); if (hasInput) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE); }
        dialog.show();
        if (w != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(Math.min(px((hasInput ? 700 : 620) * U), px(dm.widthPixels * .94f)), ViewGroup.LayoutParams.WRAP_CONTENT);
            WindowManager.LayoutParams lp = w.getAttributes(); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); lp.dimAmount = .62f;
            if (hasInput) { lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = px(80 * U); } else lp.gravity = Gravity.CENTER; // com teclado: sobe, para não ficar atrás dele
            w.setAttributes(lp); immersiveFix(w);
            if (hasInput) { input.requestFocus(); handler.postDelayed(() -> { try { ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input, 0); } catch (Exception ignored) {} }, 200); }
        }
    }
    private void popupInput(String title, String hint, String current, java.util.function.Consumer<String> ok) { popup(title, null, hint, current, "Salvar", "Cancelar", ok); }
    private void popupMessage(String title, String msg, String okLabel, Runnable ok, String cancelLabel) { popup(title, msg, null, null, okLabel, cancelLabel, t -> { if (ok != null) ok.run(); }); }
    private void popupInfo(String title, String msg) { popupMessage(title, msg, "OK", null, null); }
    /** Aviso rápido no mesmo estilo (substitui o Toast padrão do Android). */
    private void snack(String msg) {
        try {
            float U = popU();
            TextView tv = popText(msg, 21 * U, cText, false); tv.setGravity(Gravity.CENTER); tv.setPadding(px(30 * U), px(16 * U), px(30 * U), px(16 * U)); tv.setMaxWidth(px(780 * U));
            tv.setBackground(popBg(30 * U));
            Toast t = new Toast(this); t.setView(tv); t.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, px(150 * U));
            t.setDuration(msg.length() > 40 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT); t.show();
        } catch (Exception e) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    }

    // ---------------------------------------------------------------- helpers de UI (diálogos)
    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    private GradientDrawable panel(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp)); g.setStroke(dp(1), cHair);
        return g;
    }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextColor(color); t.setTextSize(size);
        t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL); t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void divider(LinearLayout l) {
        View v = new View(this); v.setBackgroundColor(cHair);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1)); p.setMargins(0, dp(10), 0, dp(10)); l.addView(v, p);
    }
    private void section(LinearLayout root, String name, String description) {
        root.addView(label(name, 16, accent, true)); root.addView(label(description, 12, cMuted, false));
        View gap = new View(this); root.addView(gap, new LinearLayout.LayoutParams(1, dp(9)));
    }
    private void actionButton(LinearLayout row, String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title); b.setTextColor(cText); b.setAllCaps(false); b.setTextSize(12);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(cSurf2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(45), 1); lp.setMargins(0, 0, dp(8), 0); row.addView(b, lp);
        b.setOnClickListener(v -> { feedback(); action.run(); });
    }
    private void sizeDialog(AlertDialog dialog, float maxW, float maxH) {
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(30), dp(maxW)), Math.min(getResources().getDisplayMetrics().heightPixels - dp(20), dp(maxH)));
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    // ---------------------------------------------------------------- seletor de aplicativos
    private void picker() {
        feedback();
        ArrayList<android.content.pm.ResolveInfo> apps = new ArrayList<>();
        Intent query = new Intent(Intent.ACTION_MAIN); query.addCategory(Intent.CATEGORY_LAUNCHER);
        apps.addAll(getPackageManager().queryIntentActivities(query, 0));
        Collections.sort(apps, (a, b) -> a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        LinearLayout root = vertical(); root.setPadding(dp(20), dp(14), dp(20), dp(16)); root.setBackground(panel(cBg1, 20));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("APLICATIVOS", 22, cText, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView close = label("✕", 22, cText, true); close.setGravity(Gravity.CENTER); close.setBackground(panel(cSurf2, 12));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44))); root.addView(header);
        divider(root);
        root.addView(label("Toque para abrir  •  ☆ fixar  •  MIN/ORIG alterna o ícone", 13, cMuted, false), new LinearLayout.LayoutParams(-1, dp(33)));
        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(16);
        search.setTextColor(cText); search.setHintTextColor(cDim); search.setHint("Pesquisar aplicativos...");
        search.setPadding(dp(14), 0, dp(14), 0); search.setBackground(panel(cSurf2, 12));
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(47)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout rows = vertical(); rows.setPadding(0, dp(12), 0, 0); scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews(); int shown = 0;
            String filter = search.getText().toString().trim().toLowerCase(Locale.getDefault());
            for (android.content.pm.ResolveInfo info : apps) {
                String pkg = info.activityInfo.packageName;
                String name = info.loadLabel(getPackageManager()).toString();
                if (!name.toLowerCase(Locale.getDefault()).contains(filter)) continue;
                shown++;
                LinearLayout item = new LinearLayout(this); item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(12), dp(5), dp(8), dp(5)); item.setBackground(panel(cSurfTop, 14));
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(64)); ip.setMargins(0, 0, 0, dp(8)); rows.addView(item, ip);
                ImageView icon = new ImageView(this); icon.setImageDrawable(info.loadIcon(getPackageManager()));
                item.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
                TextView title = label(name, 17, cText, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1); tp.setMargins(dp(13), 0, dp(8), 0); item.addView(title, tp);
                boolean pinned = pinnedPackages.contains(pkg);
                TextView star = label(pinned ? "★" : "☆", 28, pinned ? cOk : cDim, true);
                star.setGravity(Gravity.CENTER); star.setBackground(panel(cSurf2, 12));
                item.addView(star, new LinearLayout.LayoutParams(dp(55), dp(48)));
                if(pinned){
                    TextView style=label(originalAppIcons.contains(pkg)?"ORIG":"MIN",11,accent,true);
                    style.setGravity(Gravity.CENTER);style.setBackground(panel(cSurf2,12));
                    LinearLayout.LayoutParams styleParams=new LinearLayout.LayoutParams(dp(57),dp(48));styleParams.setMargins(dp(6),0,0,0);
                    item.addView(style,styleParams);
                    style.setOnClickListener(v->{
                        if(!originalAppIcons.remove(pkg)) originalAppIcons.add(pkg);
                        getPreferences(0).edit().putString("original_app_icons",android.text.TextUtils.join(",",originalAppIcons)).apply();
                        dashboard.invalidate();render[0].run();
                    });
                }
                icon.setOnClickListener(v -> { dialog.dismiss(); launchPackage(pkg); });
                title.setOnClickListener(v -> { dialog.dismiss(); launchPackage(pkg); });
                star.setOnClickListener(v -> {
                    feedback();
                    if (pinnedPackages.contains(pkg)) pinnedPackages.remove(pkg);
                    else if (pinnedPackages.size() >= 8) { snack("Máximo de 8 aplicativos extras"); return; }
                    else pinnedPackages.add(pkg);
                    savePinned(); render[0].run();
                });
            }
            if (shown == 0) rows.addView(label("Nenhum aplicativo encontrado", 16, cMuted, false));
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { render[0].run(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        close.setOnClickListener(v -> dialog.dismiss());
        showBig(dialog, 0.82f, 0.88f); render[0].run();
    }

    // Relatório de diagnóstico seguro: somente estados da aplicação; não registra coordenadas,
    // credenciais, músicas, endereços, conteúdo de notificações nem parâmetros privados da MCU.
    private String diagnosticReport() {
        StringBuilder b = new StringBuilder("PEUGEOT LAUNCHER - DIAGNÓSTICO DE CAMPO\n");
        b.append("Versão: ").append(BuildConfig.VERSION_NAME).append(" / ").append(BuildConfig.VERSION_CODE).append('\n');
        b.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        b.append("Hardware: ").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL).append('\n');
        b.append("Pacote: ").append(getPackageName()).append('\n');
        b.append("Wi-Fi: ").append(wifiConnected ? "ativo" : "inativo").append('\n');
        b.append("Bluetooth: ").append(btConnected ? "ativo" : "inativo").append('\n');
        b.append("Rede: ").append(netOnline ? "online" : "offline").append('\n');
        b.append("Localização: ").append(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ? "permitida" : "sem permissão").append('\n');
        b.append("GPS: ").append(gpsFresh() ? "posição recente" : "sem posição recente").append('\n');
        b.append("Clima: ").append(lastWeatherSuccess > 0 ? "consulta concluída" : "sem consulta concluída").append('\n');
        b.append("Clima GPS: ").append(weatherAuto ? "ligado" : "desligado").append('\n');
        b.append("CAN telemetria: ").append(hasCanData() ? "válida" : "não disponível").append('\n');
        b.append("Widget veículo: ").append(vehicleMenuEnabled ? "ligado" : "desligado").append('\n');
        b.append("Indicadores habilitados: ");
        int count = 0;for (boolean visible : vehicleVisible) if (visible) count++;
        b.append(count).append('\n');
        b.append("Atalhos favoritos: ").append(pinnedPackages.size()).append('\n');
        b.append("Áudio equalizador: ").append(snd != null ? (snd.effectOk ? "efeito criado" : "efeito não confirmado") : "motor ausente").append('\n');
        b.append("Áudio loudness: ").append(snd != null && snd.loudOn ? "configurado" : "desligado").append('\n');
        b.append("Áudio balanço/fader: sem API OEM confirmada\n");
        b.append("Spotify: ").append(mediaPermission ? "acesso à sessão permitido" : "acesso à sessão não confirmado").append('\n');
        b.append("Atualização: ").append(updateSource == 0 ? "GitHub" : "servidor local").append('\n');
        b.append("--- SERVIÇO JANCAR ---\n");
        b.append(jancarBridge == null ? "Não iniciado" : jancarBridge.diagnostic()).append('\n');
        b.append("Nota: vínculo com IVI Car não confirma valores CAN, e estado do EQ não confirma efeito audível.\n");
        return b.toString();
    }
    private void copyTestReport() {
        android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clip == null) { snack("Área de transferência indisponível"); return; }
        clip.setPrimaryClip(ClipData.newPlainText("Relatório Peugeot Launcher", diagnosticReport()));
        snack("Relatório copiado: pode enviar ao ChatGPT");
    }
    private void showTestReport() {
        if (jancarBridge != null) jancarBridge.start();
        ScrollView scroll = new ScrollView(this);
        TextView report = label(diagnosticReport(), 13, cText, false);
        report.setTextIsSelectable(true);report.setPadding(dp(18),dp(14),dp(18),dp(14));
        scroll.addView(report);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Diagnóstico • Peugeot Launcher")
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Copiar", (d,w) -> copyTestReport())
            .setNegativeButton("Atualizar", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> report.setText(diagnosticReport())));
        dialog.show();
    }

    // ---------------------------------------------------------------- configurações (abas, sem rolagem)
    private void settings() { settings(0); }
    private void settings(int initialTab) {
        feedback();
        LinearLayout root = vertical(); root.setPadding(dp(20), dp(16), dp(20), dp(16)); root.setBackground(panel(cBg1, 20));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("CONFIGURAÇÕES", 22, cText, true), new LinearLayout.LayoutParams(0, dp(53), 1));
        TextView close = label("✕", 24, cText, true); close.setGravity(Gravity.CENTER); close.setBackground(panel(cSurf2, 12));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44))); root.addView(header);
        View headerLine = new View(this); headerLine.setBackgroundColor(cHair); root.addView(headerLine, new LinearLayout.LayoutParams(-1, dp(1)));
        root.addView(label("PEUGEOT 308  /  PERSONALIZAÇÃO DO SISTEMA", 12, cDim, false), new LinearLayout.LayoutParams(-1, dp(32)));
        LinearLayout tabs = new LinearLayout(this); root.addView(tabs);
        LinearLayout page = vertical(); page.setPadding(0, dp(14), 0, dp(10));
        ScrollView pageScroll = new ScrollView(this); pageScroll.addView(page);
        root.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        String[] titles = {"Aparência", "Clima", "Atalhos", "Veículo", "Alertas", "Som", "Sistema"};
        final int[] current = {Math.max(0, Math.min(6, initialTab))}, shownTab = {-1};
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            final int keepY = shownTab[0] == current[0] ? pageScroll.getScrollY() : 0;
            shownTab[0] = current[0];
            tabs.removeAllViews(); page.removeAllViews();
            for (int i = 0; i < titles.length; i++) {
                final int index = i; Button tab = new Button(this);
                tab.setText(titles[i]); tab.setAllCaps(false); tab.setTextSize(11); tab.setMinWidth(0); tab.setMinimumWidth(0); tab.setPadding(0, 0, 0, 0);
                tab.setTextColor(i == current[0] ? onAccent : cText);
                tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(i == current[0] ? accent : cSurf2));
                LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, dp(46), 1); tabParams.setMargins(dp(2), 0, dp(2), 0); tabs.addView(tab, tabParams);
                tab.setOnClickListener(v -> { feedback(); current[0] = index; redraw[0].run(); });
            }
            switch (current[0]) {
                case 0: {
                    section(page, "APARÊNCIA", "32 temas prontos (preto, cinza chumbo e cores), claro/escuro, capa do álbum e brilho");
                    page.addView(label("Cor do tema: " + PRESET_NAMES[presetIndex], 15, cText, true));
                    LinearLayout titleRow=new LinearLayout(this);page.addView(titleRow);
                    titleRow.addView(label("Texto superior: "+brandTitle,14,cText,false),new LinearLayout.LayoutParams(0,dp(45),1));
                    titleRow.addView(smallButton("Alterar",()->popupInput("Texto do cabeçalho","Até 18 caracteres",brandTitle,v->{
                        brandTitle=v.trim().isEmpty()?"PEUGEOT 308":v.trim().substring(0,Math.min(18,v.trim().length()));
                        getPreferences(0).edit().putString("brand_title",brandTitle).apply();dashboard.invalidate();redraw[0].run();
                    })),new LinearLayout.LayoutParams(dp(110),dp(45)));
                    page.addView(label("Miniaturas dos painéis (prévia de cor)",12,cMuted,false));
                    presetGrid(page, () -> redraw[0].run());
                    divider(page);
                    page.addView(label("Tema atual: " + (themeMode == 0 ? "Automático (nascer e pôr do sol)" : themeMode == 1 ? "Escuro" : "Claro"), 15, cText, true));
                    LinearLayout themeButtons = new LinearLayout(this); themeButtons.setPadding(0, dp(10), 0, dp(4)); page.addView(themeButtons);
                    actionButton(themeButtons, "Automático", () -> { setThemeMode(0); redraw[0].run(); });
                    actionButton(themeButtons, "Escuro", () -> { setThemeMode(1); redraw[0].run(); });
                    actionButton(themeButtons, "Claro", () -> { setThemeMode(2); redraw[0].run(); });
                    page.addView(label("Aplicado imediatamente. No automático, o tema usa o horário do sol da cidade configurada.", 12, cMuted, false));
                    divider(page);
                    switchRow(page, "Fundo sutil com a cor da capa do álbum", albumBg, (v, checked) -> {
                        albumBg = checked; getPreferences(0).edit().putBoolean("album_bg", checked).apply(); artKey = "";
                        if (checked) maybeUpdateArtColor(); else setArtTarget(0);
                    });
                    switchRow(page, "Brilho automático (acompanha dia/noite)", autoBrightness, (v, checked) -> {
                        autoBrightness = checked; getPreferences(0).edit().putBoolean("auto_bright", checked).apply(); applyBrightness();
                    });
                    stepperRow(page, "Brilho de dia", brightDay, 10, 100, 10, "%", val -> { brightDay = val; getPreferences(0).edit().putInt("bright_day", val).apply(); applyBrightness(); });
                    stepperRow(page, "Brilho de noite", brightNight, 5, 100, 5, "%", val -> { brightNight = val; getPreferences(0).edit().putInt("bright_night", val).apply(); applyBrightness(); });
                    if (!Settings.System.canWrite(this)) {
                        LinearLayout permRow = new LinearLayout(this); permRow.setGravity(Gravity.CENTER_VERTICAL);
                        TextView note = label("Brilho só dentro do launcher. Para valer também no Waze/outros apps:", 12, cMuted, false);
                        permRow.addView(note, new LinearLayout.LayoutParams(0, dp(46), 1));
                        permRow.addView(smallButton("Permitir", () -> {
                            try { startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); }
                            catch (Exception e) { snack("Permissão indisponível nesta central"); }
                        }), new LinearLayout.LayoutParams(dp(110), dp(44)));
                        page.addView(permRow);
                    }
                    break;
                }
                case 1: {
                    section(page, "PREVISÃO DO TEMPO", "Cidade e atualização dos dados meteorológicos");
                    page.addView(label("Cidade: " + (weatherAuto ? "Automático (GPS)" : weatherCity.isEmpty() ? "Não configurada" : weatherCity), 20, cText, true));
                    switchRow(page, "Usar minha localização (atualizar ao viajar)", weatherAuto, (v, checked) -> {
                        weatherAuto = checked; getPreferences(0).edit().putBoolean("weather_auto", checked).apply();
                        refreshGpsNeed();
                        if (checked) { weatherAutoChecked = 0; tryAutoWeather(); }
                        else if (!weatherCity.isEmpty()) fetchWeather();
                    });
                    page.addView(label("No modo GPS, a previsão usa as coordenadas atuais. Consulta a cada 30 min ou ao mudar mais de 15 km; requer sinal GPS e internet.", 12, cMuted, false));
                    page.addView(label("Condição: " + weatherCondition + "   •   " + (weatherCity.isEmpty() ? "--" : weatherLine), 15, cMuted, false));
                    LinearLayout weatherButtons = new LinearLayout(this); weatherButtons.setPadding(0, dp(18), 0, 0); page.addView(weatherButtons);
                    actionButton(weatherButtons, "Alterar cidade", () -> editCity());
                    actionButton(weatherButtons, "Atualizar agora", () -> fetchWeather());
                    break;
                }
                case 2: {
                    section(page, "ATALHOS FIXOS", "Organize o dock; para adicionar YouTube, abra Apps e toque ☆");
                    switchRow(page,"Ícones minimalistas nos aplicativos fixados",minimalIcons,(v,checked)->{
                        minimalIcons=checked;getPreferences(0).edit().putBoolean("minimal_icons",checked).apply();dashboard.invalidate();
                    });
                    LinearLayout rows = vertical(); page.addView(rows); populateDockRows(rows);
                    divider(page);
                    page.addView(label("Destinos no Waze (aparecem no dock como Casa e Trabalho)", 14, accent, true));
                    addressRow(page, true, () -> redraw[0].run());
                    addressRow(page, false, () -> redraw[0].run());
                    page.addView(label("Endereço completo ou coordenadas (-23.03,-45.55). O Waze já abre navegando.", 12, cMuted, false));
                    page.addView(label("O LED verde identifica o último aplicativo aberto pelo launcher. Não confirma que ainda está executando em segundo plano.", 12, cMuted, false));
                    break;
                }
                case 3: {
                    section(page, "VEÍCULO E PAINEL", "Menu do veículo, dados no topo e modo direção");
                    LinearLayout box = page;
                    box.addView(label("CANBUS: " + (hasCanData() ? "CONECTADO" : "NÃO CONECTADO") + "     GPS: " + (gpsFresh() ? "SINAL OK" : "SEM SINAL"), 15, hasCanData() || gpsFresh() ? cOk : cMuted, true));
                    // Diagnóstico adicional sem modificar nenhum dos cartões ou botões existentes.
                    divider(box);
                    box.addView(label("INTEGRAÇÃO JANCAR (SOMENTE LEITURA)", 14, accent, true));
                    box.addView(label("Conecta ao serviço IVI Car do firmware quando o Android permite. A conexão não significa que valores CAN estejam disponíveis.", 12, cMuted, false));
                    LinearLayout jancarActions = new LinearLayout(this);
                    box.addView(jancarActions, new LinearLayout.LayoutParams(-1, dp(54)));
                    actionButton(jancarActions, "Diagnóstico Jancar", () -> {
                        if (jancarBridge != null) {
                            jancarBridge.start();
                            AlertDialog d = new AlertDialog.Builder(this).setTitle("Jancar • Diagnóstico CAN")
                                .setMessage(jancarBridge.diagnostic())
                                .setPositiveButton("Fechar", null)
                                .setNeutralButton("Atualizar", (di, which) -> {
                                    new AlertDialog.Builder(this).setTitle("Jancar • Diagnóstico CAN")
                                        .setMessage(jancarBridge.diagnostic()).setPositiveButton("OK", null).show();
                                }).show();
                            handler.postDelayed(() -> { if (d.isShowing()) d.setMessage(jancarBridge.diagnostic()); }, 1000);
                        }
                    });
                    actionButton(jancarActions, "Abrir app do veículo", () -> openJancarApp(true));
                    actionButton(jancarActions, "Rádio original", () -> openJancarApp(false));
                    box.addView(label("Rádio e volante continuam pelo sistema original até que os contratos da MCU/CAN sejam confirmados.", 12, cMuted, false));
                    divider(box);
                    switchRow(box, "Menu do veículo (3 botões + dados na 3ª coluna)", vehicleMenuEnabled, (v, checked) -> {
                        vehicleMenuEnabled = checked; getPreferences(0).edit().putBoolean("vehicle_menu", checked).apply(); refreshGpsNeed();
                    });
                    box.addView(label("Desligado (padrão): relógio/clima e Spotify ocupam a tela inteira, o próprio launcher se ajusta.", 12, cMuted, false));
                    switchRow(box,"Mostrar acesso ao computador de bordo sem widget",standaloneVehicleButton,(v,checked)->{
                        standaloneVehicleButton=checked;getPreferences(0).edit().putBoolean("standalone_vehicle_button",checked).apply();dashboard.invalidate();
                    });
                    LinearLayout vehicleNav=new LinearLayout(this);box.addView(vehicleNav);
                    actionButton(vehicleNav,"Selecionar próximo grupo de indicadores",()->{vehicleSelectedPage++;dashboard.invalidate();});
                    divider(box);
                    box.addView(label("Botões do carro (ex.: rádio RT4) — segure o botão na tela para renomear", 14, accent, true));
                    for (int bi = 0; bi < 3; bi++) {
                        final int b = bi;
                        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
                        row.addView(label((b + 1) + ".  " + vbtnLabel[b] + "   →   " + vbtnCmd[b], 15, cText, false), new LinearLayout.LayoutParams(0, dp(46), 1));
                        row.addView(smallButton("Editar", () -> editVehicleButton(b, () -> redraw[0].run())), new LinearLayout.LayoutParams(dp(100), dp(44)));
                        box.addView(row);
                    }
                    LinearLayout actRow = new LinearLayout(this); actRow.setGravity(Gravity.CENTER_VERTICAL);
                    TextView actTv = label("Broadcast: " + vehicleAction, 12, cDim, false); actTv.setSingleLine(true); actTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    actRow.addView(actTv, new LinearLayout.LayoutParams(0, dp(46), 1));
                    actRow.addView(smallButton("Editar", () -> promptText("Ação do broadcast", DEFAULT_VEHICLE_ACTION, vehicleAction, val -> {
                        vehicleAction = val.isEmpty() ? DEFAULT_VEHICLE_ACTION : val; getPreferences(0).edit().putString("vehicle_action", vehicleAction).apply(); redraw[0].run();
                    })), new LinearLayout.LayoutParams(dp(100), dp(44)));
                    box.addView(actRow);
                    box.addView(label("Sem CANBUS, os botões só emitem o broadcast ao sistema; nada chega ao carro (veja o README).", 12, cMuted, false));
                    divider(box);
                    box.addView(label("Mini cards no topo (ao lado do 308)", 14, accent, true));
                    switchRow(box, "Mostrar mini cards no topo", topWidgetEnabled, (v, checked) -> {
                        topWidgetEnabled = checked; getPreferences(0).edit().putBoolean("top_widget", checked).apply(); refreshGpsNeed();
                    });
                    String[] topOpts = new String[vehicleNames.length + 1]; topOpts[0] = "Nenhum";
                    for (int oi = 0; oi < vehicleNames.length; oi++) topOpts[oi + 1] = vehicleNames[oi];
                    for (int si = 0; si < topSlot.length; si++) {
                        final int slot = si;
                        choiceRow(box, "Card " + (si + 1), topOpts, topSlot[si] + 1, idx -> { topSlot[slot] = idx - 1; getPreferences(0).edit().putInt("top_slot_" + slot, topSlot[slot]).apply(); dashboard.invalidate(); });
                    }
                    box.addView(label("Temperatura/RPM/consumo dependem do CANBUS; sem ele o card mostra --. O limite de velocidade fica em Alertas.", 12, cMuted, false));
                    divider(box);
                    box.addView(label("Modo direção", 14, accent, true));
                    switchRow(box, "Esconder apps e configurações em movimento", drivingEnabled, (v, checked) -> {
                        drivingEnabled = checked; getPreferences(0).edit().putBoolean("driving_mode", checked).apply(); refreshGpsNeed();
                    });
                    stepperRow(box, "Ativa acima de", drivingThreshold, 5, 40, 5, " km/h", val -> { drivingThreshold = val; getPreferences(0).edit().putInt("driving_kmh", val).apply(); });
                    LinearLayout testRow = new LinearLayout(this); testRow.setPadding(0, dp(6), 0, dp(6)); box.addView(testRow);
                    actionButton(testRow, "Testar modo direção (15 s)", () -> { drivingTestUntil = SystemClock.uptimeMillis() + 15000L; updateDrivingMode(); });
                    box.addView(label("Fica ativo ao passar do limite (via GPS) e sai após 6 s parado. Mantém Waze, Rádio, Spotify, Telefone, Casa e Trabalho.", 12, cMuted, false));
                    divider(box);
                    box.addView(label("Indicadores do menu do veículo", 14, accent, true));
                    LinearLayout grid = vertical(); box.addView(grid);
                    for (int rowIndex = 0; rowIndex < (vehicleNames.length + 1) / 2; rowIndex++) {
                        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); grid.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
                        for (int col = 0; col < 2; col++) {
                            final int id = rowIndex * 2 + col;
                            if (id >= vehicleNames.length) { row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(44), 1)); continue; }
                            Switch sw = new Switch(this); sw.setText(vehicleNames[id]); sw.setTextSize(14); sw.setTextColor(cText);
                            sw.setChecked(vehicleVisible[id]); sw.setPadding(dp(6), 0, dp(12), 0);
                            row.addView(sw, new LinearLayout.LayoutParams(0, dp(44), 1));
                            sw.setOnCheckedChangeListener((v, checked) -> { vehicleVisible[id] = checked; getPreferences(0).edit().putBoolean("vehicle_visible_" + id, checked).apply(); dashboard.invalidate(); });
                        }
                        if(rowIndex==4) {
                            grid.addView(label("—  Indicadores adicionais  —",12,cDim,true));
                        }
                    }
                    break;
                }
                case 4: {
                    section(page, "ALERTAS", "Limite de velocidade e radares, com popup e som");
                    switchRow(page, "Alerta ao passar do limite de velocidade", speedAlert, (v, checked) -> { speedAlert = checked; getPreferences(0).edit().putBoolean("speed_alert", checked).apply(); overNow = false; refreshGpsNeed(); });
                    stepperRow(page, "Limite de velocidade", speedLimit, 30, 220, 10, " km/h", val -> { speedLimit = val; getPreferences(0).edit().putInt("speed_limit", val).apply(); dashboard.invalidate(); });
                    switchRow(page, "Avisar radares à frente (experimental, usa internet)", radarEnabled, (v, checked) -> {
                        radarEnabled = checked; getPreferences(0).edit().putBoolean("radar_alert", checked).apply();
                        if (!checked) { radars.clear(); radarFetchAt = 0; } refreshGpsNeed();
                    });
                    switchRow(page, "Som nos alertas (desligado: só o popup na tela)", alertSound, (v, checked) -> { alertSound = checked; getPreferences(0).edit().putBoolean("alert_sound", checked).apply(); });
                    LinearLayout alertTests = new LinearLayout(this); alertTests.setPadding(0, dp(8), 0, dp(4)); page.addView(alertTests);
                    actionButton(alertTests, "Testar radar", () -> { drivingTestUntil = 0; showAlert(0, "RADAR À FRENTE", "350 m  •  limite 60 km/h", 5000, true); });
                    actionButton(alertTests, "Testar velocidade", () -> showAlert(1, "VELOCIDADE ACIMA DO LIMITE", (speedLimit + 12) + " km/h  •  limite " + speedLimit + " km/h", 5000, true));
                    page.addView(label("O popup aparece com o launcher na tela. Com outro app na frente (ex.: Waze) estes alertas não aparecem; para isso deixe o Radarbot ou o próprio Waze avisando em segundo plano.", 12, cMuted, false));
                    page.addView(label("Radares vêm do OpenStreetMap e a cobertura varia por região; não substitui um app de radares dedicado.", 12, cMuted, false));
                    break;
                }
                case 5: {
                    section(page, "SONS E TOQUES", "Feedback ao tocar e volume ao ligar");
                    Switch sound = new Switch(this); sound.setText("Bip suave nos botões"); sound.setTextSize(18); sound.setTextColor(cText); sound.setChecked(beep);
                    sound.setOnCheckedChangeListener((v, checked) -> { beep = checked; getPreferences(0).edit().putBoolean("beep", beep).apply(); feedback(); });
                    page.addView(sound);
                    page.addView(label("O feedback tátil funciona apenas se a central tiver hardware compatível.", 13, cMuted, false));
                    divider(page);
                    switchRow(page, "Fade de entrada ao ligar o carro", fadeBoot, (v, checked) -> { fadeBoot = checked; getPreferences(0).edit().putBoolean("fade_boot", checked).apply(); });
                    page.addView(label("Ao ligar (ou acordar a central), o volume sobe de 0 até o nível anterior em 1,8 s, a mesma rampa do mudo. Se o Spotify retomar depois, o fade se repete uma vez.", 12, cMuted, false));
                    LinearLayout fadeTest = new LinearLayout(this); fadeTest.setPadding(0, dp(8), 0, 0); page.addView(fadeTest);
                    actionButton(fadeTest, "Testar fade agora", () -> fadeFromZero());
                    actionButton(fadeTest, "Abrir equalizador", () -> openEq());
                    break;
                }
                default: {
                    section(page, "SISTEMA E ATUALIZAÇÕES", "Versão instalada e atualização pelo GitHub");
                    page.addView(label("Peugeot 308 OEM Launcher", 21, cText, true));
                    page.addView(label("Versão instalada: " + BuildConfig.VERSION_NAME + "  •  Código " + BuildConfig.VERSION_CODE, 16, cMuted, false));
                    choiceRow(page, "Fonte da atualização", new String[]{"GitHub", "Servidor local"}, updateSource, src -> { updateSource = src; getPreferences(0).edit().putInt("upd_source", src).apply(); redraw[0].run(); });
                    if (updateSource == 0) page.addView(label("Repositório: " + (ghRepo.isEmpty() ? "não definido" : ghRepo), 15, cText, false));
                    else page.addView(label("Servidor: " + updateBase, 14, cText, false));
                    LinearLayout updateButtons = new LinearLayout(this); updateButtons.setPadding(0, dp(12), 0, dp(6)); page.addView(updateButtons);
                    actionButton(updateButtons, "Buscar atualização", () -> checkForUpdate(false));
                    if (updateSource == 0) actionButton(updateButtons, "Definir repositório", () -> editRepo(() -> redraw[0].run()));
                    else actionButton(updateButtons, "Alterar servidor", () -> editServer(() -> redraw[0].run()));
                    switchRow(page, "Verificar automaticamente (a cada 12 h, com internet)", autoUpdate, (v, checked) -> { autoUpdate = checked; getPreferences(0).edit().putBoolean("upd_auto", checked).apply(); });
                    page.addView(label("O repositório precisa ser público, com uma Release que tenha o arquivo .apk (tag como v1.13). O download vem direto do GitHub e a instalação pede sua confirmação no Android.", 12, cMuted, false));
                    divider(page);
                    section(page,"DIAGNÓSTICO PARA TESTE NA JANCAR","Confere serviços, permissões e recursos sem alterar dados do veículo");
                    LinearLayout diagnosticActions = new LinearLayout(this); diagnosticActions.setPadding(0,dp(8),0,0);page.addView(diagnosticActions);
                    actionButton(diagnosticActions, "Ver relatório", () -> showTestReport());
                    actionButton(diagnosticActions, "Copiar relatório", () -> copyTestReport());
                    page.addView(label("Após testar no carro, copie o relatório e envie na conversa. Ele não inclui coordenadas, músicas nem dados pessoais.", 12, cMuted, false));
                    break;
                }
            }
            scaleTree(tabs, SET_SCALE); scaleTree(page, SET_SCALE);
            pageScroll.post(() -> pageScroll.scrollTo(0, keepY));
        };
        redraw[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
        close.setOnClickListener(v -> { feedback(); dialog.dismiss(); dashboard.invalidate(); });
        scaleTree(root, SET_SCALE);
        showBig(dialog, 0.95f, 0.93f);
    }
    private void populateDockRows(LinearLayout container) {
        container.removeAllViews();
        for (int pos = 0; pos < N_DOCK; pos++) {
            final int position = pos, id = dockOrder[pos];
            if (id == 6) continue;
            int prevPos = -1, nextPos = -1;
            for (int q = pos - 1; q >= 0; q--) if (dockOrder[q] != 6) { prevPos = q; break; }
            for (int q = pos + 1; q < N_DOCK; q++) if (dockOrder[q] != 6) { nextPos = q; break; }
            final int pp = prevPos, np = nextPos;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); container.addView(row, new LinearLayout.LayoutParams(-1, dp(49)));
            Switch toggle = new Switch(this); toggle.setText(names[id]); toggle.setTextSize(14); toggle.setTextColor(cText); toggle.setChecked(dockEnabled[id]);
            row.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1));
            toggle.setOnCheckedChangeListener((b, checked) -> { feedback(); dockEnabled[id] = checked; saveDock(); });
            Button up = new Button(this); up.setText("↑"); up.setTextSize(18); up.setTextColor(cText); up.setAllCaps(false); up.setEnabled(pp >= 0); row.addView(up, new LinearLayout.LayoutParams(dp(53), dp(45)));
            Button down = new Button(this); down.setText("↓"); down.setTextSize(18); down.setTextColor(cText); down.setAllCaps(false); down.setEnabled(np >= 0); row.addView(down, new LinearLayout.LayoutParams(dp(53), dp(45)));
            up.setOnClickListener(v -> { feedback(); int tmp = dockOrder[pp]; dockOrder[pp] = dockOrder[position]; dockOrder[position] = tmp; saveDock(); populateDockRows(container); });
            down.setOnClickListener(v -> { feedback(); int tmp = dockOrder[np]; dockOrder[np] = dockOrder[position]; dockOrder[position] = tmp; saveDock(); populateDockRows(container); });
        }
        scaleTree(container, SET_SCALE);
    }
    private void saveDock() {
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < N_DOCK; i++) { if (i > 0) order.append(','); order.append(dockOrder[i]); }
        SharedPreferences.Editor e = getPreferences(0).edit().putString("dock_order", order.toString());
        for (int i = 0; i < N_DOCK; i++) e.putBoolean("dock_enabled_" + i, i != 6 && dockEnabled[i]);
        e.apply(); lastCandStamp = 0; dashboard.invalidate();
    }
    private void editCity() {
        popupInput("Cidade para previsão do tempo", "Ex.: Taubaté", weatherCity, v -> {
            weatherCity = v; lastWeatherSuccess = 0; sunriseMin = -1; sunsetMin = -1;
            getPreferences(0).edit().putString("city", weatherCity).apply(); fetchWeather();
        });
    }
    private void editServer(final Runnable done) {
        popupInput("Servidor local de atualização", "http://192.168.15.30:8080/", updateBase, v -> {
            if (v.isEmpty()) v = DEFAULT_SERVER;
            if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
            if (!v.endsWith("/")) v += "/";
            updateBase = v; getPreferences(0).edit().putString("server", v).apply(); if (done != null) done.run();
        });
    }
    private void editRepo(final Runnable done) {
        popupInput("Repositório do GitHub", "usuario/repositorio", ghRepo, v -> {
            v = v.replace("https://github.com/", "").replace("http://github.com/", "").replaceAll("^/+|/+$", "").replaceAll("\\.git$", "");
            if (!v.isEmpty() && !v.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) { snack("Use o formato usuario/repositorio"); return; }
            ghRepo = v; notifiedVersion = ""; getPreferences(0).edit().putString("gh_repo", v).putString("upd_notified", "").apply();
            if (done != null) done.run();
        });
    }

    // ---------------------------------------------------------------- atualização (GitHub Releases; servidor local como alternativa)
    private static int[] parseVer(String v) {
        ArrayList<Integer> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(v == null ? "" : v);
        while (m.find() && out.size() < 4) { try { out.add(Integer.parseInt(m.group())); } catch (Exception e) { out.add(0); } }
        int[] r = new int[out.size()]; for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }
    /** Compara versões numéricas: "v1.13" > "1.12"; "1.10" > "1.9". */
    private static int cmpVer(String a, String b) {
        int[] x = parseVer(a), y = parseVer(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) { int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0; if (p != q) return p > q ? 1 : -1; }
        return 0;
    }
    private void maybeAutoUpdate() {
        if (!autoUpdate || !netOnline || drivingMode || activeDialog != null || updateChecking) return;
        if (updateSource == 0 && ghRepo.isEmpty()) return;
        if (System.currentTimeMillis() - getPreferences(0).getLong("upd_last", 0) < 12L * 3600 * 1000) return;
        checkForUpdate(true);
    }
    private void checkForUpdate(boolean silent) { if (updateSource == 1) checkLocalUpdate(silent); else checkGithubUpdate(silent); }
    private void offerUpdate(String version, String notes, long size, final String url) {
        String n = notes == null ? "" : notes.trim(); if (n.length() > 380) n = n.substring(0, 380) + "…";
        String msg = "Instalada: " + BuildConfig.VERSION_NAME + "\nNova versão: " + version + (size > 0 ? String.format(Locale.US, "  (%.1f MB)", size / 1048576.0) : "") + (n.isEmpty() ? "" : "\n\nNovidades:\n" + n);
        popupMessage("Atualização disponível", msg, "Baixar e instalar", () -> downloadUpdate(url), "Depois");
    }
    private void checkGithubUpdate(final boolean silent) {
        final String repo = ghRepo.trim();
        if (!repo.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) { if (!silent) editRepo(() -> checkGithubUpdate(false)); return; }
        if (updateChecking) return;
        updateChecking = true;
        if (!silent) snack("Procurando atualização no GitHub…");
        new Thread(() -> {
            try {
                JSONObject rel = readJson("https://api.github.com/repos/" + repo + "/releases/latest");
                final String tag = rel.optString("tag_name", ""), body = rel.optString("body", "");
                String apkUrl = null; long size = 0;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").toLowerCase(Locale.US).endsWith(".apk")) { apkUrl = a.optString("browser_download_url", ""); size = a.optLong("size", 0); break; }
                }
                getPreferences(0).edit().putLong("upd_last", System.currentTimeMillis()).apply();
                final boolean newer = !tag.isEmpty() && cmpVer(tag, BuildConfig.VERSION_NAME) > 0;
                final String url = apkUrl; final long sz = size;
                runOnUiThread(() -> {
                    if (!newer) { if (!silent) popupInfo("Sistema atualizado", "Versão instalada: " + BuildConfig.VERSION_NAME + "\nÚltima versão no GitHub: " + (tag.isEmpty() ? "--" : tag)); return; }
                    if (url == null || !url.startsWith("https://github.com/")) { if (!silent) popupInfo("Release sem APK", "A versão " + tag + " existe, mas não tem um arquivo .apk anexado."); return; }
                    if (silent && (tag.equals(notifiedVersion) || drivingMode || activeDialog != null)) return;
                    notifiedVersion = tag; getPreferences(0).edit().putString("upd_notified", tag).apply();
                    offerUpdate(tag, body, sz, url);
                });
            } catch (final Exception ex) {
                final String m = ex instanceof FileNotFoundException ? "Repositório ou release não encontrado. O repositório precisa ser público e ter ao menos uma Release com um arquivo .apk."
                    : String.valueOf(ex.getMessage()).contains("403") ? "O GitHub limitou as consultas por agora. Tente de novo em alguns minutos."
                    : "Confira a internet da central.\n\n" + ex.getMessage();
                if (!silent) runOnUiThread(() -> popupInfo("Não foi possível verificar", m));
            } finally { updateChecking = false; }
        }).start();
    }

    private String updateApkBase = DEFAULT_SERVER;
    private void checkLocalUpdate(final boolean silent) {
        if (updateChecking) return;
        updateChecking = true;
        if (!silent) snack("Verificando atualização no servidor local…");
        final String serverBase = updateBase;
        new Thread(() -> {
            try {
                ByteArrayOutputStream bytes = null;
                IOException lastError = null;
                String[] bases = {serverBase, serverBase + "downloads/"};
                for (String base : bases) {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(base + "update.json?t=" + System.currentTimeMillis()).openConnection();
                        c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setUseCaches(false);
                        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " em " + base + "update.json");
                        ByteArrayOutputStream candidate = new ByteArrayOutputStream();
                        try (InputStream in = c.getInputStream()) {
                            byte[] b = new byte[2048]; int n;
                            while ((n = in.read(b)) != -1) { candidate.write(b, 0, n); if (candidate.size() > 16384) throw new IOException("update.json muito grande"); }
                        }
                        bytes = candidate; updateApkBase = base; break;
                    } catch (IOException ex) { lastError = ex; }
                    finally { if (c != null) c.disconnect(); }
                }
                if (bytes == null) throw new IOException("Não encontrei update.json na raiz nem em /downloads/. Última resposta: " + lastError);
                JSONObject j = new JSONObject(bytes.toString("UTF-8"));
                final int remoteCode = j.getInt("versionCode");
                final String remoteName = j.optString("versionName", String.valueOf(remoteCode));
                final String apk = j.optString("apk", "app-debug.apk");
                if (!apk.matches("[A-Za-z0-9._-]+\\.apk")) throw new IOException("Nome de APK inválido");
                getPreferences(0).edit().putLong("upd_last", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    if (remoteCode <= BuildConfig.VERSION_CODE) { if (!silent) popupInfo("Sistema atualizado", "Versão instalada: " + BuildConfig.VERSION_NAME + "\nVersão no servidor: " + remoteName); return; }
                    if (silent && (remoteName.equals(notifiedVersion) || drivingMode || activeDialog != null)) return;
                    notifiedVersion = remoteName; getPreferences(0).edit().putString("upd_notified", remoteName).apply();
                    offerUpdate(remoteName, "", 0, updateApkBase + apk);
                });
            } catch (final Exception ex) {
                if (!silent) runOnUiThread(() -> popupInfo("Não foi possível verificar", "Confira se o PC e a central estão na mesma rede e se o servidor está aberto.\n\n" + ex.getMessage()));
            } finally { updateChecking = false; }
        }).start();
    }

    private void downloadUpdate(String url) {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null) { File old = new File(dir, "peugeot308-update.apk"); if (old.exists()) old.delete(); }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Peugeot 308 Launcher - atualização"); req.setDescription("Baixando a nova versão");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "peugeot308-update.apk");
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            updateDownloadId = dm.enqueue(req);
            if (downloadReceiver == null) {
                downloadReceiver = new BroadcastReceiver() { public void onReceive(Context c, Intent i) { onDownloadDone(i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)); } };
                registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            }
            snack("Baixando a atualização… o instalador abre sozinho quando terminar");
        } catch (Exception ex) { popupInfo("Falha no download", String.valueOf(ex.getMessage())); }
    }
    private void onDownloadDone(long id) {
        if (id < 0 || id != updateDownloadId) return;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        boolean ok = false; android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        try { if (c != null && c.moveToFirst()) ok = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL; } finally { if (c != null) c.close(); }
        if (!ok) { popupInfo("Download falhou", "Não consegui baixar a atualização. Tente de novo com a internet estável."); return; }
        Uri u = dm.getUriForDownloadedFile(id);
        if (u == null) { popupInfo("Download concluído", "O arquivo foi baixado em Downloads, mas não consegui abri-lo automaticamente."); return; }
        installApk(u);
    }
    private void installApk(final Uri uri) {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                pendingInstall = uri;
                popupMessage("Permitir instalação", "Na próxima tela, ative \"Permitir desta fonte\" para o launcher e volte. A instalação continua sozinha.", "Abrir", () -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception e) { snack("Ative 'fontes desconhecidas' nas configurações do Android"); }
                }, "Cancelar");
                return;
            }
            Intent it = new Intent(Intent.ACTION_VIEW); it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it);
        } catch (Exception e) { popupInfo("Instalação", "Não consegui abrir o instalador. Abra o arquivo em Downloads manualmente."); }
    }

    // ---------------------------------------------------------------- conexões
    private void refreshConnections() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork(); NetworkCapabilities cap = n == null ? null : cm.getNetworkCapabilities(n);
            wifiConnected = cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            netOnline = cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { wifiConnected = false; netOnline = false; }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            btConnected = adapter != null && adapter.isEnabled() && (adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED || adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED);
        } catch (Exception e) { btConnected = false; }
        if ((!weatherCity.isEmpty() || weatherAuto) && netOnline && !weatherLoading && weatherStale() && SystemClock.elapsedRealtime() - lastWeather > 15000) fetchWeather();
    }
    private boolean weatherStale() { return lastWeatherSuccess == 0 || SystemClock.elapsedRealtime() - lastWeatherSuccess > 30 * 60 * 1000L; }
    /** Avisa o launcher no instante em que a rede conecta (a central pode levar ~2 min para subir o Wi-Fi). */
    private void registerNetworkWatcher() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { handler.post(() -> onNetworkChanged()); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { handler.post(() -> onNetworkChanged()); }
                @Override public void onLost(Network n) { handler.post(() -> onNetworkChanged()); }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {}
    }
    private void onNetworkChanged() {
        boolean was = netOnline;
        refreshConnections();
        if (netOnline && (!weatherCity.isEmpty() || weatherAuto) && !weatherLoading && (!was || weatherStale() || weatherFailed)) fetchWeather();
        if (dashboard != null) dashboard.invalidate();
        if (netOnline && !was) handler.postDelayed(this::maybeAutoUpdate, 8000);
    }

    // ---------------------------------------------------------------- clima (Open-Meteo; coordenadas em cache)
    private void tryAutoWeather() {
        if (!weatherAuto || !netOnline || !gpsFresh() || Double.isNaN(gpsLat) || weatherLoading) return;
        long now = SystemClock.elapsedRealtime();
        boolean moved = Double.isNaN(weatherAutoLat) || haversine(gpsLat,gpsLon,weatherAutoLat,weatherAutoLon) > 15000;
        if ((moved && now - weatherAutoChecked > 60000) || (weatherStale() && now - weatherAutoChecked > 60000)) {
            weatherAutoLat = gpsLat; weatherAutoLon = gpsLon; weatherAutoChecked = now;
            fetchWeather();
        }
    }
    private static final class WeatherData {
        boolean ok; String line = "", detail = "", condition = "--", high = "--", low = "--", wind = "--", rain = "--", humidity = "--";
        int code = -1, sunrise = -1, sunset = -1;
    }
    private void fetchWeather() {
        final String city = weatherAuto ? "Localização atual" : weatherCity;
        if (city.isEmpty()) { weatherLine = "Configure a cidade"; dashboard.invalidate(); return; }
        lastWeather = SystemClock.elapsedRealtime();
        if (weatherLoading) return;
        weatherLoading = true; dashboard.invalidate();
        new Thread(() -> {
            WeatherData w = new WeatherData();
            try {
                SharedPreferences sp = getPreferences(0);
                double lat, lon; String place = city;
                if (weatherAuto && !Double.isNaN(weatherAutoLat)) {
                    lat = weatherAutoLat; lon = weatherAutoLon; place = "Localização atual (GPS)";
                } else if (city.equalsIgnoreCase(sp.getString("geo_city", "")) && sp.contains("geo_lat") && sp.contains("geo_lon")) {
                    lat = Double.parseDouble(sp.getString("geo_lat", "0")); lon = Double.parseDouble(sp.getString("geo_lon", "0")); place = sp.getString("geo_name", city);
                } else {
                    JSONObject geo = readJson("https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(city, "UTF-8") + "&count=1&language=pt&format=json");
                    JSONArray results = geo.optJSONArray("results");
                    if (results == null || results.length() == 0) throw new Exception("Cidade não encontrada");
                    JSONObject pl = results.getJSONObject(0);
                    lat = pl.getDouble("latitude"); lon = pl.getDouble("longitude"); place = pl.optString("name", city);
                    sp.edit().putString("geo_city", city).putString("geo_lat", String.valueOf(lat)).putString("geo_lon", String.valueOf(lon)).putString("geo_name", place).apply();
                }
                JSONObject forecast = readJson("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset&forecast_days=1&timezone=auto");
                JSONObject current = forecast.getJSONObject("current");
                int temp = (int) Math.round(current.getDouble("temperature_2m"));
                int humidity = current.optInt("relative_humidity_2m", -1);
                int code = current.optInt("weather_code", -1);
                w.line = temp + "°C"; w.condition = weatherDescription(code); w.code = code;
                w.wind = Math.round(current.optDouble("wind_speed_10m", 0)) + " km/h";
                w.humidity = humidity >= 0 ? humidity + "%" : "--";
                JSONObject daily = forecast.getJSONObject("daily");
                w.high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)) + "°";
                w.low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "°";
                w.rain = daily.getJSONArray("precipitation_probability_max").getInt(0) + "%";
                w.detail = place;
                try {
                    String sr = daily.getJSONArray("sunrise").getString(0), ss = daily.getJSONArray("sunset").getString(0);
                    w.sunrise = Integer.parseInt(sr.substring(11, 13)) * 60 + Integer.parseInt(sr.substring(14, 16));
                    w.sunset = Integer.parseInt(ss.substring(11, 13)) * 60 + Integer.parseInt(ss.substring(14, 16));
                } catch (Exception ignored) {}
                w.ok = true;
            } catch (Exception ex) { w.ok = false; }
            runOnUiThread(() -> {
                weatherLoading = false;
                if (city.equals(weatherAuto ? "Localização atual" : weatherCity)) weatherFailed = !w.ok;
                if (city.equals(weatherAuto ? "Localização atual" : weatherCity) && w.ok) {
                    lastWeatherSuccess = SystemClock.elapsedRealtime();
                    weatherLine = w.line; weatherDetail = w.detail; weatherCondition = w.condition; weatherHigh = w.high; weatherLow = w.low;
                    weatherWind = w.wind; weatherRain = w.rain; weatherHumidity = w.humidity; weatherCode = w.code;
                    if (w.sunrise >= 0) { sunriseMin = w.sunrise; sunsetMin = w.sunset; }
                    if (computeNight() != night) applyTheme();
                }
                dashboard.invalidate();
            });
        }).start();
    }
    private JSONObject readJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(7000); conn.setReadTimeout(7000); conn.setRequestProperty("User-Agent", "Peugeot308Launcher/1.12");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { conn.disconnect(); }
    }
    private String weatherDescription(int code) {
        if (code == 0) return "Céu limpo"; if (code <= 2) return "Poucas nuvens"; if (code == 3) return "Nublado"; if (code <= 48) return "Neblina";
        if (code <= 57) return "Garoa"; if (code <= 67) return "Chuva"; if (code <= 77) return "Neve";
        if (code <= 82) return "Pancadas de chuva"; if (code <= 86) return "Neve"; if (code >= 95) return "Trovoadas"; return "Tempo variável";
    }

    // ================================================================ DASHBOARD (desenho + toque)
    private static final class Hit {
        final int id; final float x, y, w, h;
        Hit(int id, float x, float y, float w, float h) { this.id = id; this.x = x; this.y = y; this.w = w; this.h = h; }
    }

    private class Dashboard extends View {
        static final int I_NAV = 0, I_RADIO = 1, I_NOTE = 2, I_PHONE = 3, I_BT = 4, I_GRID = 5, I_GEAR = 6, I_WIFI = 7,
                I_MINUS = 8, I_PLUS = 9, I_PREV = 10, I_NEXT = 11, I_PLAY = 12, I_PAUSE = 13, I_SPEAKER = 14, I_MUTE = 15, I_HOME = 16, I_WORK = 17, I_MENU = 18, I_TRIP = 19, I_BACK = 20, I_EQ = 21;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Path path = new Path();
        final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        final Typeface tfReg = Typeface.create("sans-serif", Typeface.NORMAL);
        final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        final Typeface tfBold = Typeface.create("sans-serif", Typeface.BOLD);
        final HashMap<String, android.graphics.drawable.Drawable> iconCache = new HashMap<>();
        final ArrayList<Hit> hits = new ArrayList<>();
        float sx = 1, sy = 1, ox = 0, oy = 0;
        int pressedId = -1;
        boolean noHit = false; // desenha sem registrar toque (ex.: modo direção)
        float seekX = 642, seekW = 220;
        Canvas canvas;

        Dashboard() { super(MainActivity.this); setHapticFeedbackEnabled(true); }
        void clearIcons() { iconCache.clear(); }
        int faint() { return (cDim & 0x00FFFFFF) | 0x99000000; } // aviso quase invisível

        // ---------- primitivas
        void fill(int c) { p.reset(); p.setAntiAlias(true); p.setColor(c); p.setStyle(Paint.Style.FILL); }
        void stroke(int c, float w) { p.reset(); p.setAntiAlias(true); p.setColor(c); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
        void rr(float x, float y, float w, float h, float rad, int c) { fill(c); r.set(x, y, x + w, y + h); canvas.drawRoundRect(r, rad, rad, p); }
        void rrs(float x, float y, float w, float h, float rad, int c, float sw) { stroke(c, sw); r.set(x + sw / 2, y + sw / 2, x + w - sw / 2, y + h - sw / 2); canvas.drawRoundRect(r, rad, rad, p); }
        void circle(float cx, float cy, float rad, int c) { fill(c); canvas.drawCircle(cx, cy, rad, p); }
        void ln(float x1, float y1, float x2, float y2, int c, float w) { stroke(c, w); canvas.drawLine(x1, y1, x2, y2, p); }
        void text(String s, float x, float y, float size, int c, Typeface tf, Paint.Align a, float ls) {
            fill(c); p.setTypeface(tf); p.setTextSize(size); p.setTextAlign(a); if (ls != 0) p.setLetterSpacing(ls); canvas.drawText(s, x, y, p);
        }
        void t(String s, float x, float y, float size, int c, Typeface tf) { text(s, x, y, size, c, tf, Paint.Align.LEFT, 0); }
        void tc(String s, float x, float y, float size, int c, Typeface tf) { text(s, x, y, size, c, tf, Paint.Align.CENTER, 0); }
        void tr(String s, float x, float y, float size, int c, Typeface tf) { text(s, x, y, size, c, tf, Paint.Align.RIGHT, 0); }
        float measure(String s, float size, Typeface tf, float ls) { mp.reset(); mp.setAntiAlias(true); mp.setTypeface(tf); mp.setTextSize(size); if (ls != 0) mp.setLetterSpacing(ls); return mp.measureText(s); }
        String fit(String s, float size, Typeface tf, float maxW) {
            if (s == null) return "";
            if (measure(s, size, tf, 0) <= maxW) return s;
            mp.reset(); mp.setAntiAlias(true); mp.setTypeface(tf); mp.setTextSize(size);
            float ell = mp.measureText("…");
            int n = mp.breakText(s, true, Math.max(0, maxW - ell), null);
            return s.substring(0, Math.max(0, n)).trim() + "…";
        }
        boolean hit(int id, float x, float y, float w, float h) { if (noHit) return false; hits.add(new Hit(id, x, y, w, h)); return pressedId == id; }
        void pushPress(boolean pr, float x, float y, float w, float h) { canvas.save(); if (pr) canvas.scale(.96f, .96f, x + w / 2, y + h / 2); }
        void popPress() { canvas.restore(); }
        android.graphics.drawable.Drawable iconOf(String pkg) {
            if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
            android.graphics.drawable.Drawable d = null;
            try { d = getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) {}
            iconCache.put(pkg, d);
            return d;
        }

        // ---------- cartão OEM: superfície plana, gradiente sutil, borda quase invisível
        void card(float x, float y, float w, float h) {
            fill(cShadow); r.set(x + 2, y + 10, x + w - 2, y + h + 8); canvas.drawRoundRect(r, 26, 26, p);
            p.reset(); p.setAntiAlias(true);
            p.setShader(new LinearGradient(x, y, x, y + h, cSurfTop, cSurfBot, Shader.TileMode.CLAMP));
            r.set(x, y, x + w, y + h); canvas.drawRoundRect(r, 26, 26, p); p.setShader(null);
            if (albumBg && Color.alpha(artCur) > 0) { // cor da capa, bem sutil
                int a = (int) (Color.alpha(artCur) * (night ? .085f : .06f));
                fill((artCur & 0x00FFFFFF) | (a << 24)); r.set(x, y, x + w, y + h); canvas.drawRoundRect(r, 26, 26, p);
            }
            rrs(x, y, w, h, 26, cHair, 1.2f);
        }
        void drawArtGlow() {
            float a = Color.alpha(artCur) / 255f * (night ? .30f : .22f);
            int core = (artCur & 0x00FFFFFF) | ((int) (a * 255) << 24), edge = artCur & 0x00FFFFFF;
            p.reset(); p.setShader(new RadialGradient(700, 330, 820, core, edge, Shader.TileMode.CLAMP)); canvas.drawRect(0, 0, 1280, 720, p); p.setShader(null);
        }

        // ---------- ícones vetoriais (traço uniforme, sem depender da fonte do sistema)
        void ic(int k, float cx, float cy, float s, int col) {
            canvas.save(); canvas.translate(cx, cy);
            float u = s / 2f, sw = Math.max(2f, s * 0.09f);
            switch (k) {
                case I_NAV: {
                    fill(col); path.reset(); path.moveTo(0, -u); path.lineTo(.8f * u, .92f * u); path.lineTo(0, .46f * u); path.lineTo(-.8f * u, .92f * u); path.close(); canvas.drawPath(path, p); break;
                }
                case I_RADIO: {
                    stroke(col, sw); r.set(-.92f * u, -.42f * u, .92f * u, .82f * u); canvas.drawRoundRect(r, .2f * u, .2f * u, p);
                    canvas.drawLine(.3f * u, -.42f * u, .78f * u, -.96f * u, p);
                    canvas.drawCircle(-.42f * u, .2f * u, .26f * u, p);
                    canvas.drawLine(.12f * u, 0, .64f * u, 0, p); canvas.drawLine(.12f * u, .4f * u, .64f * u, .4f * u, p); break;
                }
                case I_NOTE: {
                    stroke(col, sw);
                    canvas.drawLine(-.3f * u, .5f * u, -.3f * u, -.7f * u, p); canvas.drawLine(.52f * u, .36f * u, .52f * u, -.84f * u, p); canvas.drawLine(-.3f * u, -.7f * u, .52f * u, -.84f * u, p);
                    fill(col); canvas.drawCircle(-.52f * u, .58f * u, .24f * u, p); canvas.drawCircle(.3f * u, .44f * u, .24f * u, p); break;
                }
                case I_PHONE: {
                    stroke(col, .34f * u); path.reset(); path.moveTo(-.6f * u, -.55f * u); path.cubicTo(-.8f * u, .2f * u, -.2f * u, .8f * u, .58f * u, .62f * u); canvas.drawPath(path, p); break;
                }
                case I_BT: {
                    stroke(col, sw * 1.1f); path.reset(); path.moveTo(-.5f * u, -.4f * u); path.lineTo(.5f * u, .42f * u); path.lineTo(0, .92f * u); path.lineTo(0, -.92f * u); path.lineTo(.5f * u, -.42f * u); path.lineTo(-.5f * u, .4f * u); canvas.drawPath(path, p); break;
                }
                case I_GRID: {
                    fill(col); float q = .62f * u;
                    float[][] o = {{-.82f, -.82f}, {.2f, -.82f}, {-.82f, .2f}, {.2f, .2f}};
                    for (float[] pt : o) { r.set(pt[0] * u, pt[1] * u, pt[0] * u + q, pt[1] * u + q); canvas.drawRoundRect(r, .14f * u, .14f * u, p); } break;
                }
                case I_GEAR: {
                    stroke(col, .3f * u); canvas.drawCircle(0, 0, .5f * u, p);
                    p.setStrokeCap(Paint.Cap.BUTT);
                    for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; canvas.drawLine((float) (Math.cos(a) * .58f * u), (float) (Math.sin(a) * .58f * u), (float) (Math.cos(a) * .9f * u), (float) (Math.sin(a) * .9f * u), p); } break;
                }
                case I_WIFI: {
                    stroke(col, sw * 1.2f);
                    for (int i = 1; i <= 3; i++) { float rad = (.2f + .42f * i) * u; r.set(-rad, .72f * u - rad, rad, .72f * u + rad); canvas.drawArc(r, -135, 90, false, p); }
                    fill(col); canvas.drawCircle(0, .72f * u, .12f * u, p); break;
                }
                case I_MINUS: { stroke(col, sw * 1.3f); canvas.drawLine(-.6f * u, 0, .6f * u, 0, p); break; }
                case I_PLUS: { stroke(col, sw * 1.3f); canvas.drawLine(-.6f * u, 0, .6f * u, 0, p); canvas.drawLine(0, -.6f * u, 0, .6f * u, p); break; }
                case I_PREV: {
                    stroke(col, sw * 1.3f); canvas.drawLine(-.6f * u, -.55f * u, -.6f * u, .55f * u, p);
                    fill(col); path.reset(); path.moveTo(.62f * u, -.62f * u); path.lineTo(.62f * u, .62f * u); path.lineTo(-.36f * u, 0); path.close(); canvas.drawPath(path, p); break;
                }
                case I_NEXT: {
                    stroke(col, sw * 1.3f); canvas.drawLine(.6f * u, -.55f * u, .6f * u, .55f * u, p);
                    fill(col); path.reset(); path.moveTo(-.62f * u, -.62f * u); path.lineTo(-.62f * u, .62f * u); path.lineTo(.36f * u, 0); path.close(); canvas.drawPath(path, p); break;
                }
                case I_PLAY: {
                    fill(col); path.reset(); path.moveTo(-.38f * u, -.66f * u); path.lineTo(-.38f * u, .66f * u); path.lineTo(.68f * u, 0); path.close(); canvas.drawPath(path, p); break;
                }
                case I_PAUSE: {
                    fill(col); r.set(-.52f * u, -.6f * u, -.16f * u, .6f * u); canvas.drawRoundRect(r, .08f * u, .08f * u, p);
                    r.set(.16f * u, -.6f * u, .52f * u, .6f * u); canvas.drawRoundRect(r, .08f * u, .08f * u, p); break;
                }
                case I_HOME: {
                    stroke(col, sw * 1.1f);
                    path.reset(); path.moveTo(-.9f * u, -.05f * u); path.lineTo(0, -.85f * u); path.lineTo(.9f * u, -.05f * u); canvas.drawPath(path, p);
                    path.reset(); path.moveTo(-.6f * u, -.1f * u); path.lineTo(-.6f * u, .8f * u); path.lineTo(.6f * u, .8f * u); path.lineTo(.6f * u, -.1f * u); canvas.drawPath(path, p);
                    path.reset(); path.moveTo(-.18f * u, .8f * u); path.lineTo(-.18f * u, .3f * u); path.lineTo(.18f * u, .3f * u); path.lineTo(.18f * u, .8f * u); canvas.drawPath(path, p);
                    break;
                }
                case I_WORK: {
                    stroke(col, sw * 1.1f);
                    r.set(-.88f * u, -.3f * u, .88f * u, .72f * u); canvas.drawRoundRect(r, .16f * u, .16f * u, p);
                    path.reset(); path.moveTo(-.34f * u, -.3f * u); path.lineTo(-.34f * u, -.62f * u); path.lineTo(.34f * u, -.62f * u); path.lineTo(.34f * u, -.3f * u); canvas.drawPath(path, p);
                    canvas.drawLine(-.88f * u, .12f * u, .88f * u, .12f * u, p);
                    break;
                }
                case I_MENU: {
                    stroke(col, sw * 1.3f);
                    canvas.drawLine(-.8f * u, -.55f * u, .8f * u, -.55f * u, p); canvas.drawLine(-.8f * u, 0, .8f * u, 0, p); canvas.drawLine(-.8f * u, .55f * u, .8f * u, .55f * u, p);
                    break;
                }
                case I_TRIP: {
                    stroke(col, sw * 1.2f); r.set(-.9f * u, -.9f * u, .9f * u, .9f * u); canvas.drawArc(r, 150, 240, false, p);
                    canvas.drawLine(0, 0, .5f * u, -.5f * u, p); fill(col); canvas.drawCircle(0, 0, .12f * u, p);
                    break;
                }
                case I_EQ: {
                    stroke(col, sw); for (int q = -1; q <= 1; q++) canvas.drawLine(q * .62f * u, -.88f * u, q * .62f * u, .88f * u, p);
                    fill(col); canvas.drawCircle(-.62f * u, .28f * u, .27f * u, p); canvas.drawCircle(0, -.34f * u, .27f * u, p); canvas.drawCircle(.62f * u, .44f * u, .27f * u, p);
                    break;
                }
                case I_BACK: {
                    stroke(col, sw * 1.3f); canvas.drawLine(-.8f * u, 0, .8f * u, 0, p);
                    path.reset(); path.moveTo(-.2f * u, -.62f * u); path.lineTo(-.82f * u, 0); path.lineTo(-.2f * u, .62f * u); canvas.drawPath(path, p);
                    break;
                }
                default: { // alto-falante / mudo
                    canvas.scale(s / 30f, s / 30f);
                    fill(col); path.reset(); path.moveTo(-12, -5); path.lineTo(-7, -5); path.lineTo(0, -11); path.lineTo(0, 11); path.lineTo(-7, 5); path.lineTo(-12, 5); path.close(); canvas.drawPath(path, p);
                    stroke(col, 2.4f);
                    if (k == I_MUTE) { canvas.drawLine(6, -7, 16, 7, p); canvas.drawLine(16, -7, 6, 7, p); }
                    else { r.set(-4, -8, 12, 8); canvas.drawArc(r, -62, 124, false, p); r.set(-6, -13, 19, 13); canvas.drawArc(r, -58, 116, false, p); }
                }
            }
            canvas.restore();
        }
        void sunIcon(float cx, float cy, float s, int col) {
            float u = s / 2f; circle(cx, cy, .42f * u, col);
            stroke(col, Math.max(2f, s * .08f));
            for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; canvas.drawLine(cx + (float) Math.cos(a) * .68f * u, cy + (float) Math.sin(a) * .68f * u, cx + (float) Math.cos(a) * .95f * u, cy + (float) Math.sin(a) * .95f * u, p); }
        }
        void cloudIcon(float cx, float cy, float s, int col) {
            float u = s / 2f; canvas.save(); canvas.translate(cx, cy); fill(col); path.reset();
            path.addCircle(-.34f * u, .12f * u, .4f * u, Path.Direction.CW); path.addCircle(.1f * u, -.12f * u, .52f * u, Path.Direction.CW); path.addCircle(.56f * u, .14f * u, .34f * u, Path.Direction.CW);
            path.addRoundRect(-.74f * u, .1f * u, .9f * u, .48f * u, .2f * u, .2f * u, Path.Direction.CW);
            canvas.drawPath(path, p); canvas.restore();
        }
        void weatherIcon(int code, float cx, float cy, float s) {
            int sun = 0xffFFB84D, cl = night ? 0xffC9D3DF : 0xff9AA8BA;
            if (code == 0) { sunIcon(cx, cy, s * .8f, sun); return; }
            if (code == 1 || code == 2) { sunIcon(cx - s * .16f, cy - s * .16f, s * .56f, sun); cloudIcon(cx + s * .08f, cy + s * .1f, s * .82f, cl); return; }
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82), snow = (code >= 71 && code <= 77) || code == 85 || code == 86, storm = code >= 95, fog = code >= 45 && code <= 48;
            float shift = (rain || snow || storm || fog) ? -s * .14f : 0;
            cloudIcon(cx, cy + shift, s, cl);
            if (rain) { stroke(accent, Math.max(2.5f, s * .07f)); for (int i = 0; i < 3; i++) { float x = cx - s * .26f + i * s * .26f; canvas.drawLine(x, cy + s * .26f, x - s * .08f, cy + s * .46f, p); } }
            else if (snow) { fill(cText); for (int i = 0; i < 3; i++) canvas.drawCircle(cx - s * .26f + i * s * .26f, cy + s * .38f, s * .045f, p); }
            else if (storm) { fill(sun); float u = s / 2f; path.reset(); path.moveTo(cx + .12f * u, cy + .3f * u); path.lineTo(cx - .2f * u, cy + .78f * u); path.lineTo(cx + .02f * u, cy + .78f * u); path.lineTo(cx - .1f * u, cy + 1.14f * u); path.lineTo(cx + .3f * u, cy + .62f * u); path.lineTo(cx + .08f * u, cy + .62f * u); path.close(); canvas.drawPath(path, p); }
            else if (fog) { stroke(cl, Math.max(2.5f, s * .06f)); canvas.drawLine(cx - s * .3f, cy + s * .3f, cx + s * .3f, cy + s * .3f, p); canvas.drawLine(cx - s * .2f, cy + s * .44f, cx + s * .2f, cy + s * .44f, p); }
        }

        // ---------- componentes
        void iconBtn(int id, float x, float y, float w, float h, int kind, float isz) {
            boolean pr = hit(id, x, y, w, h); pushPress(pr, x, y, w, h);
            rr(x, y, w, h, 14, pr ? mix(cSurf2, accent, .3f) : cSurf2); ic(kind, x + w / 2, y + h / 2, isz, cText); popPress();
        }
        void chip(int id, float x, float y, float w, float h, int kind, String label, boolean ok) {
            boolean pr = hit(id, x, y, w, h); pushPress(pr, x, y, w, h);
            rr(x, y, w, h, 14, pr ? mix(cSurf2, accent, .3f) : cSurf2);
            ic(kind, x + 24, y + h / 2, 26, ok ? accent : cDim); t(label, x + 46, y + h / 2 + 6.5f, 18, ok ? cText : cMuted, tfMed); popPress();
        }
        void roundBtn(int id, float cx, float cy, float rad, int kind, float isz, int bg, int fg) {
            boolean pr = hit(id, cx - rad, cy - rad, rad * 2, rad * 2); pushPress(pr, cx - rad, cy - rad, rad * 2, rad * 2);
            circle(cx, cy, rad, pr ? (bg == accent ? mix(bg, Color.BLACK, .25f) : mix(bg, accent, .35f)) : bg); ic(kind, cx + (kind == I_PLAY ? isz * .04f : 0), cy, isz, fg); popPress();
        }
        void statCell(float x, float y, String label, String value) {
            text(label, x, y, 15, cDim, tfBold, Paint.Align.LEFT, .08f);
            t(fit(value, 24, tfMed, 136), x, y + 28, 24, cText, tfMed);
        }
        void tile(float x, float y, float w, float h, String label, String value, String unit) {
            rr(x, y, w, h, 16, cSurf2);
            text(label, x + w / 2, y + 27, 14, cDim, tfBold, Paint.Align.CENTER, .06f);
            float vs = h >= 100 ? 38 : 32, wu = unit.isEmpty() ? 0 : measure(unit, 16, tfReg, 0) + 5;
            while (vs > 22 && measure(value, vs, tfBold, 0) + wu > w - 18) vs -= 2; // reduz a fonte antes de cortar o texto
            String v = fit(value, vs, tfBold, w - 18 - wu);
            float wv = measure(v, vs, tfBold, 0), x0 = x + w / 2 - (wv + wu) / 2, base = h >= 100 ? y + h * .7f : y + h - 20;
            t(v, x0, base, vs, cText, tfBold);
            if (!unit.isEmpty()) t(unit, x0 + wv + 5, base, 16, cDim, tfReg);
        }

        // ---------- tela
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); canvas = c; hits.clear();
            c.drawColor(cBg0);
            float s = Math.min(getWidth() / 1280f, getHeight() / 720f);
            sx = sy = s; ox = (getWidth() - 1280 * s) / 2f; oy = (getHeight() - 720 * s) / 2f;
            c.save(); c.translate(ox, oy); c.scale(s, s);
            p.reset(); p.setShader(new LinearGradient(0, 0, 0, 720, cBg0, cBg1, Shader.TileMode.CLAMP)); c.drawRect(0, 0, 1280, 720, p); p.setShader(null);
            if (albumBg && Color.alpha(artCur) > 0) drawArtGlow();
            drawHeader(); drawClockWeather();
            if (vehicleMenuEnabled) { drawMedia(386, 508, false); drawVehicle(); }
            else {
                drawMedia(386, 862, true);
                if (standaloneVehicleButton) {
                    boolean pr=hit(43,1112,501,115,36);
                    rr(1112,501,115,36,12,pr?mix(cSurf2,accent,.27f):cSurf2);
                    tc("PC BORDO",1169,525,13,cText,tfMed);
                }
            }
            drawDock(); drawAlertPopup(); drawVolume();
            c.restore();
        }

        void shineText(String s, float x, float y, float size, int base, int hi, Typeface tf, float ls, float sweepX) {
            p.reset(); p.setAntiAlias(true); p.setTypeface(tf); p.setTextSize(size); p.setTextAlign(Paint.Align.LEFT); if (ls != 0) p.setLetterSpacing(ls);
            if (!Float.isNaN(sweepX)) p.setShader(new LinearGradient(sweepX - 36, 0, sweepX + 36, 0, new int[]{base, hi, base}, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
            else p.setColor(base);
            canvas.drawText(s, x, y, p); p.setShader(null);
        }
        void drawTopWidget(float startX) {
            if (!topWidgetEnabled) return;
            ArrayList<Integer> ids = new ArrayList<>();
            for (int sl : topSlot) if (sl >= 0 && sl < vehicleNames.length) ids.add(sl);
            if (ids.isEmpty()) return;
            float gap = 8, w = Math.min(122, (596 - startX - gap * (ids.size() - 1)) / ids.size());
            if (w < 70) return;
            float spd = speedNow();
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i); float x = startX + i * (w + gap), y = 10, h = 52;
                String val = vehicleValue(id); boolean live = val != null;
                boolean alert = speedAlert && spd > speedLimit && (id == 0 || id == 10);
                rr(x, y, w, h, 12, alert ? mix(cSurf2, cBad, .35f) : cSurf2);
                text(vehicleShort[id], x + 10, y + 17, 10, alert ? cBad : cDim, tfBold, Paint.Align.LEFT, .06f);
                String u = vehicleUnits[id], shown = live ? val : "--"; float uw = u.isEmpty() ? 0 : measure(u, 12, tfReg, 0) + 3, vs = 22;
                while (vs > 14 && measure(shown, vs, tfBold, 0) + uw > w - 18) vs -= 2;
                t(shown, x + 10, y + 43, vs, alert ? cBad : (live ? cText : cDim), tfBold);
                if (!u.isEmpty()) t(u, x + 10 + measure(shown, vs, tfBold, 0) + 3, y + 43, 12, cDim, tfReg);
            }
        }

        void drawHeader() {
            rr(0, 0, 1280, 72, 0, cHead); ln(0, 72, 1280, 72, cHair, 1.5f);
            float brandFont=25;
            while(brandFont>16 && measure(brandTitle,brandFont,tfBold,.025f)>270)brandFont--;
            float wm = measure(brandTitle, brandFont, tfBold, .025f), w308 = 0, gx = 32 + wm;
            long sm = SystemClock.uptimeMillis() % SHINE_PERIOD; // brilho que corre pelo logo a cada poucos segundos
            float sweep = sm < SHINE_MS ? 10 + (sm / (float) SHINE_MS) * (wm + w308 + 70) : Float.NaN;
            shineText(brandTitle, 32, 47, brandFont, cText, accent, tfBold, .025f, sweep);
            float dvx = gx + w308 + 14;
            ln(dvx, 22, dvx, 50, cHair, 1.5f);
            drawTopWidget(dvx + 14);
            iconBtn(1, 612, 12, 56, 48, I_MINUS, 22);
            iconBtn(2, 676, 12, 64, 48, I_MUTE, 26);
            iconBtn(3, 748, 12, 56, 48, I_PLUS, 22);
            ln(824, 22, 824, 50, cHair, 1.5f);
            noHit = drivingMode; // em movimento os chips viram só indicadores
            chip(4, 840, 14, 84, 44, I_BT, "BT", btConnected);
            chip(5, 930, 14, 102, 44, I_WIFI, "Wi-Fi", wifiConnected);
            noHit = false;
            chip(7, 1038, 14, 76, 44, I_EQ, "EQ", snd != null && snd.on); // v1.14: som/equalizador (continua tocável em movimento)
            if (drivingMode) {
                rr(1122, 12, 126, 48, 14, mix(cSurf2, accent, .25f));
                text("MODO DIREÇÃO", 1185, 41, 13, cText, tfBold, Paint.Align.CENTER, .04f);
            } else {
                boolean pr = hit(6, 1122, 12, 126, 48); pushPress(pr, 1122, 12, 126, 48);
                rr(1122, 14, 126, 44, 13, pr ? mix(cSurf2, accent, .3f) : cSurf2); ic(I_GEAR, 1150, 36, 24, cText); t("Config.", 1174, 42, 17, cText, tfMed); popPress();
            }
        }

        void drawClockWeather() {
            float x = 32, y = 96, w = 330, h = 456, cx = x + w / 2;
            card(x, y, w, h);
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            tc(time, cx, 202, 92, cText, tfLight);
            Locale br = new Locale("pt", "BR");
            String date = new SimpleDateFormat("EEEE, d 'de' MMMM", br).format(new Date());
            date = date.substring(0, 1).toUpperCase(br) + date.substring(1);
            tc(date, cx, 240, 20, cMuted, tfReg);
            ln(x + 24, 264, x + w - 24, 264, cHair, 1.5f);
            boolean hasWx = (weatherAuto || !weatherCity.isEmpty()) && weatherLine.endsWith("°C");
            if (!hasWx) {
                weatherIcon(3, cx, 372, 84);
                if (weatherCity.isEmpty() && !weatherAuto) { tc("Escolha sua cidade", cx, 450, 24, cText, tfMed); tc("Toque para configurar", cx, 482, 17, cMuted, tfReg); }
                else { tc(weatherLoading ? "Buscando previsão…" : "Previsão indisponível", cx, 450, 24, cText, tfMed); tc(fit(weatherCity, 17, tfReg, 280) + (weatherLoading ? "" : "  •  toque para tentar"), cx, 482, 17, cMuted, tfReg); }
            } else {
                boolean offline = !netOnline || weatherFailed;
                t(fit(weatherDetail.isEmpty() ? weatherCity : weatherDetail, 22, tfMed, offline ? 150 : 190), x + 24, 304, 22, cText, tfMed);
                if (offline) text("offline" + (lastWeatherSuccess > 0 ? " · há " + ((SystemClock.elapsedRealtime() - lastWeatherSuccess) / 60000) + " min" : ""), x + w - 24, 304, 13, faint(), tfReg, Paint.Align.RIGHT, 0);
                else {
                    String stamp = weatherLoading ? "Atualizando…" : (lastWeatherSuccess > 0 ? "há " + ((SystemClock.elapsedRealtime() - lastWeatherSuccess) / 60000) + " min" : "");
                    tr(stamp, x + w - 24, 304, 15, cDim, tfReg);
                }
                t(fit(weatherCondition, 18, tfReg, 282), x + 24, 332, 18, cMuted, tfReg);
                weatherIcon(weatherCode, x + 58, 388, 64);
                t(weatherLine, x + 112, 404, 56, cText, tfBold);
                statCell(x + 24, 438, "MÁX / MÍN", weatherHigh + " / " + weatherLow);
                statCell(x + 174, 438, "CHUVA", weatherRain);
                statCell(x + 24, 494, "VENTO", weatherWind);
                statCell(x + 174, 494, "UMIDADE", weatherHumidity);
            }
            hit(30, x, 268, w, y + h - 268);
        }

        void drawMedia(float x, float w, boolean wide) {
            float y = 96, h = 456;
            card(x, y, w, h);
            int srcCol = mediaIsSpotify ? 0xff1ED760 : accent;
            circle(x + 40, y + 42, 22, srcCol); ic(I_NOTE, x + 40, y + 42, 24, mediaIsSpotify ? 0xff06210F : onAccent);
            t(fit(mediaSource, 24, tfMed, 230), x + 76, y + 51, 24, cText, tfMed);
            if (mediaCtl != null) {
                float pw = 156, px = x + w - 24 - pw, py = y + 25;
                rr(px, py, pw, 34, 17, mediaPlaying ? mix(cSurf2, cOk, .22f) : cSurf2);
                text(mediaPlaying ? "REPRODUZINDO" : "PAUSADO", px + pw / 2, py + 23, 14, mediaPlaying ? cOk : cMuted, tfBold, Paint.Align.CENTER, .06f);
            }
            // capa grande e quadrada (recorte central, sem distorcer)
            float art = wide ? 300 : 216, ax = x + 32, ay = wide ? 168 : 172;
            rr(ax, ay, art, art, 24, cSurf2);
            if (albumArt != null) {
                p.reset(); p.setAntiAlias(true); p.setFilterBitmap(true);
                canvas.save(); path.reset(); r.set(ax, ay, ax + art, ay + art); path.addRoundRect(r, 24, 24, Path.Direction.CW); canvas.clipPath(path);
                int bw = albumArt.getWidth(), bh = albumArt.getHeight(), side = Math.min(bw, bh);
                canvas.drawBitmap(albumArt, new Rect((bw - side) / 2, (bh - side) / 2, (bw - side) / 2 + side, (bh - side) / 2 + side), r, p);
                canvas.restore(); rrs(ax, ay, art, art, 24, cHair, 1.2f);
            } else ic(I_NOTE, ax + art / 2, ay + art / 2, art * .4f, accent);
            float tx = ax + art + (wide ? 40 : 24), tw = x + w - 32 - tx;
            float ts = wide ? 36 : 28, as = wide ? 24 : 20;
            float yl = wide ? 214 : 202, yt = wide ? 262 : 246, ya = wide ? 298 : 280, yh = wide ? 336 : 318;
            text(mediaPlaying ? "TOCANDO AGORA" : (mediaCtl != null ? "EM PAUSA" : mediaSource.toUpperCase(Locale.getDefault())), tx, yl, 14, accent, tfBold, Paint.Align.LEFT, .1f);
            t(fit(songTitle, ts, tfBold, tw), tx, yt, ts, cText, tfBold);
            t(fit(songArtist, as, tfReg, tw), tx, ya, as, cMuted, tfReg);
            if (mediaCtl == null) t(fit(mediaPermission ? "Toque para abrir o Spotify" : "Permitir acesso às notificações", 17, tfMed, tw), tx, yh, 17, accent, tfMed);
            long pos = livePosition();
            float by = wide ? 358 : 332, bh = wide ? 10 : 8, frac = songDuration > 0 ? Math.min(1f, (float) pos / songDuration) : 0f;
            rr(tx, by, tw, bh, bh / 2, cTrack);
            if (frac > 0) { rr(tx, by, Math.max(bh, tw * frac), bh, bh / 2, accent); circle(tx + tw * frac, by + bh / 2, wide ? 11 : 9, cText); }
            float ty = wide ? by + 46 : by + 40;
            t(songDuration > 0 ? timeString(pos) : "--:--", tx, ty, wide ? 17 : 16, cDim, tfReg);
            tr(songDuration > 0 ? timeString(songDuration) : "--:--", tx + tw, ty, wide ? 17 : 16, cDim, tfReg);
            seekX = tx; seekW = tw;
            hit(25, tx - 6, by - 18, tw + 12, 44);
            // controles grandes
            float cxc = wide ? tx + tw / 2 : x + w / 2, cyc = wide ? 474 : 472;
            roundBtn(20, cxc - 124, cyc, 46, I_PREV, 34, cSurf2, cText);
            roundBtn(21, cxc, cyc, 58, mediaPlaying ? I_PAUSE : I_PLAY, 46, accent, onAccent);
            roundBtn(22, cxc + 124, cyc, 46, I_NEXT, 34, cSurf2, cText);
            // Acesso discreto a letras inseridas pelo usuário, sem fonte não autorizada.
            if(mediaCtl != null){
                boolean pr=hit(26,x+w-140,y+70,112,34);
                rr(x+w-140,y+70,112,34,12,pr?mix(cSurf2,accent,.25f):cSurf2);
                tc("LETRAS",x+w-84,y+93,14,cText,tfMed);
            }
            hit(23, x, y, w, h);
        }

        void drawVehicle() {
            float x = 918, y = 96, w = 330, h = 456, cx = x + w / 2;
            card(x, y, w, h);
            text("VEÍCULO", x + 24, y + 38, 17, cMuted, tfMed, Paint.Align.LEFT, .1f);
            if(vehicleMenuEnabled) {hit(43,x+18,y+3,130,46);}
            circle(1112, y + 33, 4.5f, gpsFresh() ? cOk : cDim); t("GPS", 1124, y + 38, 14, cMuted, tfMed);
            circle(1176, y + 33, 4.5f, hasCanData() ? cOk : cDim); t("CAN", 1188, y + 38, 14, cMuted, tfMed);
            // 3 botões do carro em destaque (toque = comando, segurar = renomear)
            float bw = (w - 48 - 20) / 3f, by0 = y + 54, bh0 = 66;
            int[] bicons = {I_MENU, I_TRIP, I_BACK};
            for (int i = 0; i < 3; i++) {
                float bx = x + 24 + i * (bw + 10);
                boolean pr = hit(40 + i, bx, by0, bw, bh0); pushPress(pr, bx, by0, bw, bh0);
                rr(bx, by0, bw, bh0, 16, pr ? mix(cSurf2, accent, .5f) : mix(cSurf2, accent, .22f));
                rrs(bx, by0, bw, bh0, 16, accent, 2f);
                ic(bicons[i], bx + bw / 2, by0 + 24, 24, accent);
                tc(fit(vbtnLabel[i].toUpperCase(Locale.getDefault()), 13, tfBold, bw - 8), bx + bw / 2, by0 + 55, 13, cText, tfBold);
                popPress();
            }
            if (!hasCanData() || !gpsFresh()) {
                String note = (!hasCanData() ? "CANBUS não conectado" : "") + (!hasCanData() && !gpsFresh() ? "  ·  " : "") + (!gpsFresh() ? "GPS sem sinal" : "");
                tc(note, cx, y + h - 12, 12, faint(), tfReg);
            }
            String hero = vehicleVisible[0] ? (vehicleValue(0) == null ? "--" : vehicleValue(0)) : null;
            ArrayList<Integer> others = new ArrayList<>();
            for (int id = 1; id < vehicleNames.length; id++) if (vehicleVisible[id]) others.add(id);
            if (hero == null && others.isEmpty()) {
                ic(I_NAV, cx, 342, 50, cDim);
                tc("Aguardando GPS", cx, 410, 22, cText, tfMed);
                tc("Sem sinal de satélite ou CANBUS", cx, 440, 16, cMuted, tfReg);
                return;
            }
            int limit = hero != null ? 4 : 8;
            int pageCount=Math.max(1,(others.size()+limit-1)/limit);
            vehicleSelectedPage%=pageCount;
            int offset=vehicleSelectedPage*limit, n=Math.min(limit,others.size()-offset);
            boolean roomy = hero != null && n <= 2;
            float top = 228;
            if (hero != null) {
                float cy = roomy ? 306 : 294, R = roomy ? 72 : 60;
                boolean over = false;
                float spd = 0; try { spd = Float.parseFloat(hero); } catch (Exception ignored) {}
                if (speedAlert && spd > speedLimit) over = true;
                stroke(cTrack, 10); r.set(cx - R, cy - R, cx + R, cy + R); canvas.drawArc(r, 135, 270, false, p);
                float sweep = 270f * Math.max(0f, Math.min(1f, spd / 200f));
                if (sweep > 1) { stroke(over ? cBad : accent, 10); canvas.drawArc(r, 135, sweep, false, p); }
                tc(hero, cx, cy + (roomy ? 24 : 18), roomy ? 64 : 52, over ? cBad : cText, tfLight);
                tc(vehicleUnits[0], cx, cy + (roomy ? 54 : 46), 15, cMuted, tfReg);
                top = roomy ? 394 : 372;
            }
            if (n == 0) return;
            float bottom = 532, areaH = bottom - top, gap = 12, colW = (w - 48 - gap) / 2f;
            int rows = (n + 1) / 2;
            float cellH = Math.min(roomy ? 118 : (hero != null ? 76 : 100), (areaH - (rows - 1) * gap) / rows);
            float total = rows * cellH + (rows - 1) * gap, startY = hero != null ? top : top + (areaH - total) / 2f;
            for (int i = 0; i < n; i++) {
                int row = i / 2, col = i % 2, inRow = Math.min(2, n - row * 2), id = others.get(offset+i);
                float tw = inRow == 1 ? w - 48 : colW, bx = x + 24 + (inRow == 1 ? 0 : col * (colW + gap)), by = startY + row * (cellH + gap);
                tile(bx, by, tw, cellH, vehicleShort[id], vehicleValue(id)==null?"--":vehicleValue(id), vehicleUnits[id]);
            }
        }

        void drawDock() {
            float y = 576, h = 112;
            ArrayList<Integer> built = dockBuiltins();
            List<String> pins = dockPinned();
            int count = built.size() + pins.size();
            if (count == 0) { if (!drivingMode) tc("Abra Apps e fixe seus favoritos com ☆", 640, y + 62, 19, cMuted, tfReg); return; }
            float gap = 12, w = (1216 - (count - 1) * gap) / count;
            for (int slot = 0; slot < count; slot++) {
                float x = 32 + slot * (w + gap);
                boolean isBuiltin = slot < built.size();
                int id = isBuiltin ? built.get(slot) : -1;
                String pkg = isBuiltin ? (id == 9 ? lastAppCandidate() : null) : pins.get(slot - built.size());
                String label = isBuiltin ? (id == 9 ? (pkg == null ? names[9] : appLabel(pkg)) : names[id]) : appLabel(pkg);
                boolean pr = hit(100 + slot, x, y, w, h); pushPress(pr, x, y, w, h);
                fill(cShadow); r.set(x + 2, y + 6, x + w - 2, y + h + 4); canvas.drawRoundRect(r, 22, 22, p);
                rr(x, y, w, h, 22, pr ? mix(cSurfTop, accent, .22f) : cSurfTop);
                rrs(x, y, w, h, 22, cHair, 1.2f);
                float cx = x + w / 2, cy = y + 46, ico = Math.min(52, w * .44f);
                String realPkg = isBuiltin ? (id == 0 ? "com.waze" : id == 2 ? "com.spotify.music" : id == 9 ? pkg : null) : pkg;
                android.graphics.drawable.Drawable d = (minimalIcons && !isBuiltin && !originalAppIcons.contains(realPkg)) ? null : (realPkg == null ? null : iconOf(realPkg));
                if (d != null) { int is = (int) ico; d.setBounds((int) cx - is / 2, (int) cy - is / 2, (int) cx + is / 2, (int) cy + is / 2); d.draw(canvas); }
                else if (isBuiltin) {
                    int[] kinds = {I_NAV, I_RADIO, I_NOTE, I_PHONE, I_BT, I_GRID, I_GEAR, I_HOME, I_WORK, I_GRID};
                    ic(kinds[id], cx, cy, ico * .9f, (id == 0 || id == 7 || id == 8) ? accent : cText);
                } else ic(I_GRID, cx, cy, ico * .9f, accent);
                float ls = Math.max(13, Math.min(18, w / 8.2f));
                tc(fit(label, ls, tfMed, w - 14), cx, y + 94, ls, cText, tfMed);
                if (isBuiltin && id == 9 && pkg != null) circle(x+w-16,y+17,4.5f,cOk);
                popPress();
            }
        }

        void drawAlertPopup() {
            long now = SystemClock.uptimeMillis();
            if (now > alertUntil) return;
            long age = now - alertStart; float k = Math.min(1f, age / 220f), e = 1f - (1f - k) * (1f - k);
            if (k < 1f) postInvalidateOnAnimation();
            int col = alertKind == 1 ? cBad : 0xffFFB020;
            float w = 660, h = 164, x = 310, y = 322 - h / 2;
            rr(0, 73, 1280, 647, 0, ((int) (0x66 * e) << 24)); // véu atrás do aviso
            canvas.save(); canvas.scale(.9f + .1f * e, .9f + .1f * e, x + w / 2, y + h / 2);
            canvas.saveLayerAlpha(x - 30, y - 30, x + w + 30, y + h + 40, (int) (255 * e));
            card(x, y, w, h);
            rr(x + 18, y + 20, 8, h - 40, 4, col);
            circle(x + 92, y + h / 2, 44, col);
            fill(night ? 0xff0B1015 : 0xffFFFFFF); path.reset(); path.moveTo(x + 92, y + h / 2 - 24); path.lineTo(x + 92 + 27, y + h / 2 + 20); path.lineTo(x + 92 - 27, y + h / 2 + 20); path.close(); canvas.drawPath(path, p);
            fill(col); canvas.drawRect(x + 89.5f, y + h / 2 - 10, x + 94.5f, y + h / 2 + 5, p); canvas.drawCircle(x + 92, y + h / 2 + 13, 3f, p);
            t(fit(alertTitle, 36, tfBold, w - 190), x + 158, y + 76, 36, cText, tfBold);
            t(fit(alertSub, 25, tfMed, w - 190), x + 158, y + 116, 25, cMuted, tfMed);
            tr("toque para fechar", x + w - 22, y + h - 14, 13, faintC(), tfReg);
            canvas.restore(); canvas.restore();
        }
        int faintC() { return (cDim & 0x00FFFFFF) | 0xAA000000; }
        void drawVolume() {
            if (!volumeVisible) return;
            if (!isMuted() && SystemClock.uptimeMillis() > volumeUntil) { volumeVisible = false; return; }
            boolean mutedNow = isMuted();
            rr(0, 0, 1280, 720, 0, mutedNow ? 0xf0000309 : 0xb8000309);
            card(345, 191, 590, 335);
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            text(mutedNow ? "SOM DESATIVADO" : "VOLUME", 640, 246, 26, mutedNow ? cBad : cText, tfMed, Paint.Align.CENTER, .08f);
            if (mutedNow) {
                circle(640, 338, 64, mix(cSurf2, cBad, .25f)); ic(I_MUTE, 640, 338, 88, cBad);
                rr(410, 424, 460, 66, 33, accent);
                tc("TOQUE AQUI PARA TIRAR DO MUDO", 640, 466, 21, onAccent, tfBold);
            } else {
                tc(v + " / " + max, 640, 337, 53, accent, tfBold);
                rr(390, 381, 78, 70, 16, cSurf2); rr(812, 381, 78, 70, 16, cSurf2);
                ic(I_MINUS, 429, 416, 30, cText); ic(I_PLUS, 851, 416, 30, cText);
                rr(485, 407, 310, 16, 8, cTrack); rr(485, 407, Math.max(16, 310f * v / max), 16, 8, accent);
                rr(552, 462, 176, 48, 14, cSurf2);ic(I_MUTE,574,486,22,cText);
                tc("SILENCIAR",655,493,17,cText,tfMed);
            }
        }

        // ---------- toque
        void handle(int id, float x, float y) {
            feedback();
            if (id >= 100) {
                int slot = id - 100; ArrayList<Integer> b = dockBuiltins(); List<String> pins = dockPinned();
                if (slot < b.size()) launch(b.get(slot));
                else if (slot - b.size() < pins.size()) launchPackage(pins.get(slot - b.size()));
                return;
            }
            switch (id) {
                case 1: adjustVolume(-1); break;
                case 2: toggleMute(); break;
                case 3: adjustVolume(1); break;
                case 4: openSystemSetting(Settings.ACTION_BLUETOOTH_SETTINGS); break;
                case 5: openSystemSetting(Settings.ACTION_WIFI_SETTINGS); break;
                case 6: if (!drivingMode) settings(); break;
                case 7: openEq(); break;
                case 40: case 41: case 42: vehicleCommand(id - 40); break;
                case 43: vehicleOptionsMenu(); break;
                case 20: media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
                case 21: media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
                case 22: media(KeyEvent.KEYCODE_MEDIA_NEXT); break;
                case 25:
                    if (mediaCtl != null && songDuration > 0) {
                        float f = Math.max(0f, Math.min(1f, (x - seekX) / seekW));
                        try { mediaCtl.getTransportControls().seekTo((long) (f * songDuration)); } catch (Exception ignored) {}
                        handler.postDelayed(MainActivity.this::updateMedia, 300);
                    }
                    break;
                case 23: if (!mediaPermission) openNotificationAccess(); else launch(2); break;
                case 26: openLyrics();break;
                case 30: if (weatherCity.isEmpty() && !weatherAuto) editCity(); else fetchWeather(); break;
                default: break;
            }
        }
        void invalidateHeader() { invalidate((int) ox, (int) oy, (int) (ox + 720 * sx), (int) (oy + 80 * sy)); }
        Runnable lpTask = null; boolean lpFired = false;
        void cancelLp() { if (lpTask != null) { handler.removeCallbacks(lpTask); lpTask = null; } }
        Hit hitAt(float x, float y) {
            for (Hit h : hits) if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) return h;
            return null;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = (event.getX() - ox) / sx, y = (event.getY() - oy) / sy;
            int act = event.getActionMasked();
            if (SystemClock.uptimeMillis() <= alertUntil) { // qualquer toque fecha o aviso
                pressedId = -1; if (act == MotionEvent.ACTION_UP) { alertUntil = 0; invalidate(); } return true;
            }
            if (volumeVisible && (isMuted() || SystemClock.uptimeMillis() <= volumeUntil)) {
                pressedId = -1;
                if (act != MotionEvent.ACTION_UP) return true;
                if (isMuted()) {
                    if (x >= 410 && x <= 870 && y >= 424 && y <= 490) { feedback(); toggleMute(); }
                    return true; // bloqueia a tela enquanto mudo
                }
                if (y >= 375 && y <= 460 && x >= 385 && x <= 474) { feedback(); adjustVolume(-1); }
                else if (y >= 375 && y <= 460 && x >= 806 && x <= 900) { feedback(); adjustVolume(1); }
                else if (y >= 385 && y <= 448 && x >= 485 && x <= 795) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int value = Math.max(0, Math.min(max, Math.round((x - 485) / 310f * max)));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    if (value > 0) { previousVolume = value; volumeVisible = false; invalidate(); } else showVolume();
                    feedback();
                } else if (y >= 455 && y <= 510 && x >= 550 && x <= 730) { feedback(); toggleMute(); }
                else { volumeVisible = false; invalidate(); }
                return true;
            }
            switch (act) {
                case MotionEvent.ACTION_DOWN: {
                    Hit h = hitAt(x, y); pressedId = h == null ? -1 : h.id; lpFired = false; cancelLp();
                    if (h != null && h.id >= 40 && h.id <= 42) { // segurar o botão do carro: renomear
                        final int idx = h.id - 40;
                        lpTask = () -> { lpTask = null; lpFired = true; pressedId = -1; invalidate(); feedback(); editVehicleButton(idx, null); };
                        handler.postDelayed(lpTask, 650);
                    }
                    invalidate(); break;
                }
                case MotionEvent.ACTION_MOVE: { if (pressedId != -1) { Hit h = hitAt(x, y); if (h == null || h.id != pressedId) { pressedId = -1; cancelLp(); invalidate(); } } break; }
                case MotionEvent.ACTION_CANCEL: { pressedId = -1; cancelLp(); invalidate(); break; }
                case MotionEvent.ACTION_UP: {
                    cancelLp();
                    if (lpFired) { lpFired = false; pressedId = -1; invalidate(); break; }
                    int id = pressedId; pressedId = -1; invalidate(); if (id != -1) handle(id, x, y); break;
                }
                default: break;
            }
            return true;
        }
    }

    // ================================================================ v1.14 — Som: equalizador, loudness, volume por velocidade, campo sonoro
    private void openEq() {
        if (eqDialog != null || snd == null) return;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final EqView v = new EqView();
        d.setContentView(v);
        eqDialog = d;
        d.setOnDismissListener(x -> { if (eqDialog == d) eqDialog = null; v.stop(); snd.compare = false; snd.applyNow(); snd.saveNow(); });
        showBig(d, 1f, 1f);
        v.start();
    }
    private void pushSoundState() {
        if (snd == null) return;
        snd.speedKmh = speedNow();
        snd.volumeBusy = rampTask != null;
    }
    private static int al(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    private class EqView extends Dashboard {
        static final int R_BAND = 100, R_PRE = 110, R_GRAPH = 111, R_LAMT = 120, R_LBELOW = 121, R_AVMAX = 122, R_BAL = 130, R_FAD = 131, R_SUB = 132, R_PAD = 133;
        final String[] TABS = {"Equalizador", "Ajustes", "Campo sonoro"};
        int tab = 0;
        final ArrayList<float[]> regs = new ArrayList<>(); // regiões arrastáveis: {id, x, y, w, h, a, b}
        float[] dragReg = null; int dragId = -1;
        float presetScroll = 0, presetMax = 0, dnX = 0, scrollStart = 0; boolean scrolling = false, moved = false, reveal = true;
        final Runnable refresh = new Runnable() { public void run() { invalidate(); handler.postDelayed(this, 350); } };
        void start() { handler.postDelayed(refresh, 350); }
        void stop() { handler.removeCallbacks(refresh); cancelLp(); }

        float colX(int i) { return 257 + i * 101.8f; }
        float gy(float db) { return 252 - db * (76f / 12f); }
        ArrayList<String> presetList() {
            ArrayList<String> l = new ArrayList<>(); l.add("Usuário");
            for (String s : EqMath.PRESET_NAMES) l.add(s);
            l.addAll(snd.customNames);
            return l;
        }

        // ---------- widgets
        void toggle(int id, float x, float y, float w, float h, boolean on) {
            boolean pr = hit(id, x - 8, y - 8, w + 16, h + 16); pushPress(pr, x, y, w, h);
            rr(x, y, w, h, h / 2, on ? accent : cTrack);
            circle(on ? x + w - h / 2 : x + h / 2, y + h / 2, h / 2 - 5, on ? onAccent : cMuted); popPress();
        }
        void button(int id, float x, float y, float w, float h, String label, boolean on) {
            boolean pr = hit(id, x, y, w, h); pushPress(pr, x, y, w, h);
            rr(x, y, w, h, 16, on ? accent : (pr ? mix(cSurf2, accent, .3f) : cSurf2));
            tc(label, x + w / 2, y + h / 2 + 7, 20, on ? onAccent : cText, tfMed); popPress();
        }
        void hslider(int id, float x, float y, float w, float v01, boolean center) {
            float kx = x + w * v01; boolean dr = dragId == id;
            rr(x, y - 8, w, 16, 8, cTrack);
            if (center) { float c0 = x + w / 2, a = Math.min(c0, kx), b = Math.max(c0, kx); if (b - a > 1) rr(a, y - 8, b - a, 16, 8, accent); ln(c0, y - 17, c0, y + 17, cDim, 2); }
            else rr(x, y - 8, Math.max(16, kx - x), 16, 8, accent);
            if (dr) circle(kx, y, 36, al(accent, 55));
            circle(kx, y, dr ? 27 : 24, accent);
            ln(kx - 5, y - 8, kx - 5, y + 8, onAccent, 3); ln(kx + 5, y - 8, kx + 5, y + 8, onAccent, 3);
            regs.add(new float[]{id, x - 28, y - 38, w + 56, 76, x, w});
        }
        void vslider(int regId, int resetId, float cx, float val, String label) {
            float top = 440, bot = 586, y0 = (top + bot) / 2, yv = bot - (val + 12f) / 24f * (bot - top); boolean dr = dragId == regId;
            boolean pr = hit(resetId, cx - 46, 380, 92, 34); // toque no valor = zerar
            tc(EqMath.db(val), cx, 406, 26, pr ? accent : (val != 0 ? cText : cMuted), tfBold);
            rr(cx - 8, top - 12, 16, bot - top + 24, 8, cTrack);
            ln(cx - 24, y0, cx + 24, y0, cDim, 2);
            float a = Math.min(y0, yv), b = Math.max(y0, yv); if (b - a > 1) rr(cx - 8, a, 16, b - a, 8, accent);
            if (dr) circle(cx, yv, 38, al(accent, 55));
            circle(cx, yv, dr ? 28 : 25, accent);
            ln(cx - 9, yv - 4.5f, cx + 9, yv - 4.5f, onAccent, 3.2f); ln(cx - 9, yv + 4.5f, cx + 9, yv + 4.5f, onAccent, 3.2f);
            tc(label, cx, 628, 20, cDim, tfMed);
            regs.add(new float[]{regId, cx - 50, 414, 100, 204, top, bot - top});
        }
        float wrap(String s, float x, float y, float size, int col, Typeface tf, float maxW, float lh) {
            StringBuilder line = new StringBuilder();
            for (String w : s.split(" ")) {
                String test = line.length() == 0 ? w : line + " " + w;
                if (measure(test, size, tf, 0) > maxW && line.length() > 0) { t(line.toString(), x, y, size, col, tf); y += lh; line = new StringBuilder(w); }
                else line = new StringBuilder(test);
            }
            if (line.length() > 0) { t(line.toString(), x, y, size, col, tf); y += lh; }
            return y;
        }
        void title(String s, float x, float y) { text(s, x, y, 16, cDim, tfBold, Paint.Align.LEFT, .12f); }

        // ---------- tela
        @Override protected void onDraw(Canvas c) {
            canvas = c; hits.clear(); regs.clear();
            c.drawColor(cBg0);
            float s = Math.min(getWidth() / 1280f, getHeight() / 720f);
            sx = sy = s; ox = (getWidth() - 1280 * s) / 2f; oy = (getHeight() - 720 * s) / 2f;
            c.save(); c.translate(ox, oy); c.scale(s, s);
            p.reset(); p.setShader(new LinearGradient(0, 0, 0, 720, cBg0, cBg1, Shader.TileMode.CLAMP)); c.drawRect(0, 0, 1280, 720, p); p.setShader(null);
            drawHead();
            if (tab == 0) drawEqTab(); else if (tab == 1) drawAdjTab(); else drawFieldTab();
            c.restore();
        }

        void drawHead() {
            rr(0, 0, 1280, 72, 0, cHead); ln(0, 72, 1280, 72, cHair, 1.5f);
            boolean pr = hit(1, 32, 12, 64, 48); pushPress(pr, 32, 12, 64, 48);
            rr(32, 12, 64, 48, 14, pr ? mix(cSurf2, accent, .3f) : cSurf2); ic(I_BACK, 64, 36, 26, cText); popPress();
            text("SOM", 120, 47, 26, cText, tfBold, Paint.Align.LEFT, .16f);
            float tw = 176, tx = 640 - (tw * 3 + 8) / 2f;
            rr(tx, 12, tw * 3 + 8, 48, 24, cSurf2);
            for (int i = 0; i < 3; i++) {
                float x = tx + 4 + i * tw; boolean sel = tab == i;
                hit(2 + i, x, 12, tw, 48);
                if (sel) rr(x, 16, tw, 40, 20, accent);
                tc(TABS[i], x + tw / 2, 43, 19, sel ? onAccent : cMuted, tfMed);
            }
            text("ATIVO", 1152, 42, 15, cDim, tfBold, Paint.Align.RIGHT, .12f);
            toggle(5, 1166, 18, 82, 36, snd.on);
        }

        // ---------- aba 1: equalizador
        void drawEqTab() {
            // predefinições (rolagem horizontal)
            ArrayList<String> names = presetList(); float[] cw = new float[names.size()]; float total = 0;
            for (int i = 0; i < cw.length; i++) { cw[i] = measure(names.get(i), 20, tfMed, 0) + 42; total += cw[i] + 10; }
            presetMax = Math.max(0, total - 10 - (1090 - 32));
            if (reveal) {
                reveal = false; float x0 = 0;
                for (int i = 0; i < cw.length; i++) { if (names.get(i).equals(snd.presetName)) { if (x0 + cw[i] > 1058 - 32) presetScroll = x0 + cw[i] - (1058 - 32) + 12; } x0 += cw[i] + 10; }
            }
            presetScroll = Math.max(0, Math.min(presetMax, presetScroll));
            canvas.save(); canvas.clipRect(32, 84, 1090, 148);
            float x = 32 - presetScroll;
            for (int i = 0; i < cw.length; i++) {
                boolean sel = names.get(i).equals(snd.presetName);
                float v0 = Math.max(x, 32), v1 = Math.min(x + cw[i], 1090);
                boolean pr = v1 - v0 > 24 && hit(200 + i, v0, 92, v1 - v0, 48);
                pushPress(pr, x, 92, cw[i], 48);
                rr(x, 92, cw[i], 48, 24, sel ? accent : (pr ? mix(cSurf2, accent, .3f) : cSurf2));
                tc(names.get(i), x + cw[i] / 2, 124, 20, sel ? onAccent : cText, tfMed); popPress();
                x += cw[i] + 10;
            }
            canvas.restore();
            if (presetScroll > 2) { p.reset(); p.setShader(new LinearGradient(32, 0, 62, 0, cBg0, al(cBg0, 0), Shader.TileMode.CLAMP)); canvas.drawRect(32, 88, 62, 144, p); p.setShader(null); }
            if (presetScroll < presetMax - 2) { p.reset(); p.setShader(new LinearGradient(1060, 0, 1090, 0, al(cBg0, 0), cBg0, Shader.TileMode.CLAMP)); canvas.drawRect(1060, 88, 1090, 144, p); p.setShader(null); }
            boolean ps = hit(6, 1100, 92, 148, 48); pushPress(ps, 1100, 92, 148, 48);
            rrs(1100, 92, 148, 48, 24, accent, 3); tc("+ Salvar", 1174, 124, 20, accent, tfMed); popPress();

            // gráfico
            card(32, 152, 1216, 200);
            float pl = 205, pr2 = 1225, pt = 176, pb = 328;
            String[] yl = {"+12 dB", "+6 dB", "0 dB", "−6 dB", "−12 dB"};
            for (int k = 0; k < 5; k++) { float yy = pt + k * (pb - pt) / 4f; ln(pl, yy, pr2, yy, k == 2 ? mix(cHair, cMuted, .35f) : cHair, k == 2 ? 1.6f : 1.1f); tr(yl[k], pl - 14, yy + 5, 15, cDim, tfReg); }
            for (int i = 0; i < EqMath.N; i++) ln(colX(i), pt, colX(i), pb, cHair, 1f);
            boolean live = snd.on && !snd.compare; int cc = live ? accent : cDim;
            canvas.save(); canvas.clipRect(pl - 4, pt - 16, pr2 + 4, pb + 16);
            Path fillP = curvePath(snd.band, true); fill(al(cc, live ? 52 : 26)); canvas.drawPath(fillP, p);
            if (snd.loudOn && snd.loudCur > .03f && live) { // tracejado: o que o loudness acrescenta no volume atual
                float k = snd.loudCur * snd.loudAmt; float[] g2 = new float[EqMath.N];
                for (int i = 0; i < EqMath.N; i++) g2[i] = snd.band[i] + EqMath.loudShape(EqMath.FREQ[i], k * EqMath.LOUD_LOW_DB, k * EqMath.LOUD_HIGH_DB);
                stroke(cMuted, 2.6f); p.setPathEffect(new DashPathEffect(new float[]{9, 8}, 0)); canvas.drawPath(curvePath(g2, false), p); p.setPathEffect(null);
            }
            stroke(cc, 4.5f); canvas.drawPath(curvePath(snd.band, false), p);
            canvas.restore();
            for (int i = 0; i < EqMath.N; i++) { float cx = colX(i), cy = gy(snd.band[i]); circle(cx, cy, 12.5f, cc); circle(cx, cy, 6.5f, cSurfBot); }
            if (snd.loudOn && snd.loudCur > .03f && live) tr("- - -  com loudness no volume atual", 1236, 196, 14, cDim, tfReg);
            if (!snd.on) tc("EQUALIZADOR DESLIGADO", 640, 262, 22, cMuted, tfBold);
            else if (snd.compare) tc("OUVINDO O SOM ORIGINAL (SEM EQUALIZAÇÃO)", 640, 262, 22, accent, tfBold);
            regs.add(new float[]{R_GRAPH, pl - 24, pt - 16, pr2 - pl + 48, pb - pt + 32, pt, pb - pt});

            // controles
            card(32, 368, 1216, 272);
            vslider(R_PRE, 40, 110, snd.pre, "PRÉ");
            ln(190, 396, 190, 616, cHair, 1.5f);
            for (int i = 0; i < EqMath.N; i++) vslider(R_BAND + i, 30 + i, colX(i), snd.band[i], EqMath.FLABEL[i]);

            // rodapé
            button(7, 32, 656, 150, 48, "Redefinir", false);
            button(8, 194, 656, 190, 48, snd.compare ? "Ouvindo original" : "Comparar", snd.compare);
            tr((snd.effectOk ? "●  " : "○  ") + fit(snd.statusLine(), 15, tfReg, 820), 1248, 686, 15, snd.effectOk ? cMuted : cBad, tfReg);
        }
        Path curvePath(float[] g, boolean fillClose) {
            int n = EqMath.N; float[] xs = new float[n + 2], ys = new float[n + 2];
            xs[0] = 205; ys[0] = gy(g[0]);
            for (int i = 0; i < n; i++) { xs[i + 1] = colX(i); ys[i + 1] = gy(g[i]); }
            xs[n + 1] = 1225; ys[n + 1] = gy(g[n - 1]);
            Path pa = new Path(); boolean first = true;
            for (float x = 205; x <= 1225; x += 4) { float y = EqMath.mono(xs, ys, x); if (first) { pa.moveTo(x, y); first = false; } else pa.lineTo(x, y); }
            pa.lineTo(1225, ys[n + 1]);
            if (fillClose) { pa.lineTo(1225, gy(0)); pa.lineTo(205, gy(0)); pa.close(); }
            return pa;
        }

        // ---------- aba 2: ajustes (loudness, volume por velocidade, diagnóstico)
        void drawAdjTab() {
            card(32, 96, 600, 544);
            title("LOUDNESS DINÂMICO", 60, 140); toggle(50, 540, 114, 76, 36, snd.loudOn);
            wrap("Reforça graves e agudos quando o volume está baixo e vai sumindo aos poucos conforme o volume sobe. Resolve o som abafado em volume baixo.", 60, 178, 17, cMuted, tfReg, 548, 24);
            t("Intensidade", 60, 262, 20, cText, tfMed); tr(Math.round(snd.loudAmt * 100) + "%", 608, 262, 20, accent, tfBold);
            hslider(R_LAMT, 70, 296, 528, snd.loudAmt, false);
            t("Começa a agir abaixo de", 60, 360, 20, cText, tfMed); tr(Math.round(snd.loudBelow * 100) + "% do volume", 608, 360, 20, accent, tfBold);
            hslider(R_LBELOW, 70, 394, 528, (snd.loudBelow - .15f) / .85f, false);
            // gráfico ao vivo do que o loudness está somando agora
            rr(60, 440, 548, 134, 16, cSurf2);
            float k = snd.loudOn ? snd.loudCur * snd.loudAmt : 0f, lo = k * EqMath.LOUD_LOW_DB, hi = k * EqMath.LOUD_HIGH_DB;
            Path pa = new Path(); boolean first = true; double a = Math.log(25), b = Math.log(20000);
            for (int i = 0; i <= 90; i++) {
                float f = (float) Math.exp(a + (b - a) * i / 90.0), v = EqMath.loudShape(f, lo, hi);
                float px = 76 + i * (516 / 90f), py = 556 - Math.min(12f, v) * (100f / 12f);
                if (first) { pa.moveTo(px, py); first = false; } else pa.lineTo(px, py);
            }
            Path fp = new Path(pa); fp.lineTo(592, 556); fp.lineTo(76, 556); fp.close();
            fill(al(accent, 50)); canvas.drawPath(fp, p); stroke(accent, 3.5f); canvas.drawPath(pa, p);
            ln(76, 556, 592, 556, cHair, 1.5f);
            t("graves", 78, 454, 14, cDim, tfReg); tr("agudos", 590, 454, 14, cDim, tfReg);
            int vol = 0, vmax = 1; try { vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC); vmax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); } catch (Exception ignored) {}
            tc("Agora: volume " + vol + "/" + vmax + "  •  compensação " + Math.round(snd.loudCur * 100) + "%", 346, 612, 17, cMuted, tfReg);

            card(656, 96, 592, 258);
            title("VOLUME AUTOMÁTICO POR VELOCIDADE", 684, 140); toggle(51, 1148, 114, 76, 36, snd.autoVolOn);
            wrap("Sobe o volume aos poucos conforme o carro acelera (começa nos 40 km/h) e volta ao reduzir. Usa o GPS.", 684, 176, 17, cMuted, tfReg, 540, 24);
            t("Aumento máximo", 684, 252, 20, cText, tfMed); tr("+" + snd.autoVolMax + (snd.autoVolMax == 1 ? " nível" : " níveis"), 1220, 252, 20, accent, tfBold);
            hslider(R_AVMAX, 694, 286, 512, (snd.autoVolMax - 1) / 5f, false);
            String sp = snd.speedKmh < 0 ? "sem velocidade (GPS)" : Math.round(snd.speedKmh) + " km/h";
            tc(snd.autoBlocked ? "A central ignorou o ajuste de volume pelo Android" : "Agora: " + sp + "  •  somando +" + snd.autoOffset + (snd.autoVolOn ? "" : " (desligado)"), 952, 334, 16, snd.autoBlocked ? cBad : cMuted, tfReg);

            card(656, 374, 592, 266);
            title("DIAGNÓSTICO", 684, 418);
            wrap(snd.statusLine(), 684, 454, 18, snd.effectOk ? cText : cBad, tfMed, 540, 25);
            wrap("O teste aplica graves +9 dB e agudos −9 dB por 4 segundos (com volume moderado). Se o som não mudar, esta central não aceita efeitos do Android e o caminho é o app de som original.", 684, 522, 15, cDim, tfReg, 540, 21);
            button(52, 684, 582, 280, 46, snd.testing() ? "Testando… ouça" : "Testar efeito (4 s)", snd.testing());
        }

        // ---------- aba 3: campo sonoro
        void drawFieldTab() {
            card(32, 96, 560, 544);
            title("PONTO DE ESCUTA", 60, 140);
            rr(60, 160, 504, 400, 20, cSurf2);
            for (int k = 1; k < 3; k++) { ln(60 + k * 168, 160, 60 + k * 168, 560, cHair, 1.2f); ln(60, 160 + k * 133.3f, 564, 160 + k * 133.3f, cHair, 1.2f); }
            rrs(227, 176, 170, 368, 80, mix(cMuted, cSurf2, .4f), 3);
            float[][] seats = {{240, 250}, {334, 250}, {240, 380}, {334, 380}};
            for (float[] st : seats) rr(st[0], st[1], 50, 60, 12, mix(cSurf2, cMuted, .22f));
            String[] sp = {"FE", "FD", "TE", "TD"}; float[][] spp = {{100, 200}, {524, 200}, {100, 520}, {524, 520}};
            for (int i = 0; i < 4; i++) { circle(spp[i][0], spp[i][1], 27, al(accent, 46)); tc(sp[i], spp[i][0], spp[i][1] + 6, 17, cText, tfBold); }
            float px = 312 + snd.padX * 200, py = 352 + snd.padY * 180;
            circle(px, py, 40, al(accent, 55)); circle(px, py, 25, accent); circle(px, py, 8, onAccent);
            tc("Toque ou arraste para posicionar o som", 312, 596, 17, cMuted, tfReg);
            tc(snd.fieldName, 312, 624, 16, cDim, tfMed);
            regs.add(new float[]{R_PAD, 60, 160, 504, 400, 0, 1});

            card(616, 96, 632, 544);
            title("PREDEFINIÇÕES", 644, 140);
            String[] fn = {"Motorista", "Todos", "Passageiro", "Traseiros"}; float x = 644;
            for (int i = 0; i < 4; i++) { float w = measure(fn[i], 20, tfMed, 0) + 40; button(60 + i, x, 156, w, 46, fn[i], fn[i].equals(snd.fieldName)); x += w + 10; }
            t("Balanço  (esquerda / direita)*", 644, 262, 20, cText, tfMed); tr(snd.balance == 0 ? "0" : (snd.balance < 0 ? "E " : "D ") + Math.round(Math.abs(snd.balance)), 1220, 262, 20, accent, tfBold);
            hslider(R_BAL, 668, 296, 552, (snd.balance + 10f) / 20f, true);
            t("Fader  (frente / trás)*", 644, 372, 20, cText, tfMed); tr(snd.fader == 0 ? "0" : (snd.fader > 0 ? "F " : "T ") + Math.round(Math.abs(snd.fader)), 1220, 372, 20, accent, tfBold);
            hslider(R_FAD, 668, 406, 552, (snd.fader + 10f) / 20f, true);
            t("Reforço de graves", 644, 482, 20, cText, tfMed); tr("+" + Math.round(snd.bass * 10), 1220, 482, 20, accent, tfBold);
            hslider(R_SUB, 668, 516, 552, snd.bass, false);
            wrap("* Balanço e fader dependem do DSP da Jancar. Nesta versão os valores são apenas guardados e transmitidos por broadcast; NÃO controlam o hardware sem integração OEM. Graves usam equalização Android; avalie com volume moderado.", 644, 574, 14, cDim, tfReg, 584, 19);
        }

        // ---------- toque
        void setFromTouch(float[] r, float x, float y) {
            int id = (int) r[0];
            float h01 = Math.max(0f, Math.min(1f, (x - r[5]) / Math.max(1f, r[6])));
            if (id >= R_BAND && id < R_BAND + EqMath.N) snd.setBand(id - R_BAND, 12f - (y - r[5]) / r[6] * 24f);
            else if (id == R_PRE) snd.setPre(12f - (y - r[5]) / r[6] * 24f);
            else if (id == R_GRAPH) { int i = Math.max(0, Math.min(EqMath.N - 1, Math.round((x - colX(0)) / 101.8f))); snd.setBand(i, 12f - (y - r[5]) / r[6] * 24f); }
            else if (id == R_LAMT) snd.setLoudAmt(h01);
            else if (id == R_LBELOW) snd.setLoudBelow(.15f + h01 * .85f);
            else if (id == R_AVMAX) snd.setAutoVolMax(Math.round(1 + h01 * 5));
            else if (id == R_BAL) snd.setBalance(-10f + h01 * 20f);
            else if (id == R_FAD) snd.setFader(-10f + h01 * 20f);
            else if (id == R_SUB) snd.setBass(h01);
            else if (id == R_PAD) snd.setField((x - 312f) / 200f, (y - 352f) / 180f, "Personalizado");
        }
        float[] regAt(float x, float y) {
            for (int i = regs.size() - 1; i >= 0; i--) { float[] r = regs.get(i); if (x >= r[1] && x <= r[1] + r[3] && y >= r[2] && y <= r[2] + r[4]) return r; }
            return null;
        }
        void handleEq(int id) {
            feedback();
            if (id >= 200 && id < 400) { ArrayList<String> l = presetList(); if (id - 200 < l.size()) snd.applyPreset(l.get(id - 200)); return; }
            if (id >= 30 && id <= 39) { snd.setBand(id - 30, 0f); return; }
            if (id >= 60 && id <= 63) {
                float[][] pts = {{-0.24f, -0.39f}, {0f, 0f}, {0.24f, -0.39f}, {0f, 0.35f}}; String[] fn = {"Motorista", "Todos", "Passageiro", "Traseiros"};
                snd.setField(pts[id - 60][0], pts[id - 60][1], fn[id - 60]); return;
            }
            switch (id) {
                case 1: if (eqDialog != null) eqDialog.dismiss(); break;
                case 2: case 3: case 4: tab = id - 2; break;
                case 5: snd.setOn(!snd.on); break;
                case 6: popupInput("Salvar predefinição", "Nome (até 14 letras)", "", n -> { snd.saveCustom(n); reveal = true; invalidate(); }); break;
                case 7: snd.reset(); break;
                case 8: snd.compare = !snd.compare; snd.applyNow(); break;
                case 40: snd.setPre(0f); break;
                case 50: snd.setLoudOn(!snd.loudOn); break;
                case 51: snd.setAutoVol(!snd.autoVolOn); refreshGpsNeed(); break;
                case 52: snd.testEffect(); handler.postDelayed(this::invalidate, 4200); break;
                default: break;
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = (e.getX() - ox) / sx, y = (e.getY() - oy) / sy; int act = e.getActionMasked();
            switch (act) {
                case MotionEvent.ACTION_DOWN: {
                    dnX = x; moved = false; scrolling = false; lpFired = false; cancelLp(); dragId = -1; dragReg = null; pressedId = -1;
                    float[] r = regAt(x, y);
                    if (r != null) { dragReg = r; dragId = (int) r[0]; setFromTouch(r, x, y); invalidate(); return true; }
                    Hit h = hitAt(x, y); pressedId = h == null ? -1 : h.id;
                    if (tab == 0 && y >= 84 && y <= 148 && x >= 32 && x <= 1090) { scrolling = true; scrollStart = presetScroll; }
                    if (h != null && h.id >= 200 && h.id < 400) {
                        ArrayList<String> l = presetList(); final int ix = h.id - 200;
                        if (ix < l.size() && snd.isCustom(l.get(ix))) {
                            final String nm = l.get(ix);
                            lpTask = () -> { lpTask = null; lpFired = true; pressedId = -1; invalidate(); feedback(); popupMessage("Apagar predefinição", "Apagar \"" + nm + "\"?", "Apagar", () -> { snd.deleteCustom(nm); invalidate(); }, "Cancelar"); };
                            handler.postDelayed(lpTask, 650);
                        }
                    }
                    invalidate(); break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (dragId != -1 && dragReg != null) { setFromTouch(dragReg, x, y); invalidate(); break; }
                    if (scrolling && Math.abs(x - dnX) > 14) { moved = true; pressedId = -1; cancelLp(); }
                    if (scrolling && moved) { presetScroll = Math.max(0, Math.min(presetMax, scrollStart - (x - dnX))); invalidate(); break; }
                    if (pressedId != -1) { Hit h = hitAt(x, y); if (h == null || h.id != pressedId) { pressedId = -1; cancelLp(); invalidate(); } }
                    break;
                }
                case MotionEvent.ACTION_CANCEL: { pressedId = -1; dragId = -1; dragReg = null; scrolling = false; cancelLp(); invalidate(); break; }
                case MotionEvent.ACTION_UP: {
                    cancelLp();
                    if (dragId != -1) { dragId = -1; dragReg = null; snd.saveNow(); invalidate(); break; }
                    if (lpFired) { lpFired = false; pressedId = -1; invalidate(); break; }
                    int id = pressedId; pressedId = -1; boolean mv = moved; scrolling = false; moved = false; invalidate();
                    if (id != -1 && !mv) handleEq(id);
                    break;
                }
                default: break;
            }
            return true;
        }
    }
}
