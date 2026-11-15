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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import java.text.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Peugeot 308 OEM Launcher v1.43
 * - Visual "concessionária": superfícies planas, ícones vetoriais, tipografia em hierarquia
 * - Tema automático dia/noite (nascer/pôr do sol), escuro ou claro
 * - Estados de toque, escala uniforme (sem distorcer), cache de ícones, aceleração por hardware
 * - Velocidade/rumo/altitude via GPS quando não há CANBUS; sem valores inventados
 */
public class MainActivity extends Activity {
    private static final String DEFAULT_SERVER = "http://192.168.15.30:8080/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FieldLog fieldLog;
    private static final int REQ_EXPORT_LOG = 1481, REQ_BACKUP = 1482, REQ_RESTORE = 1483;
    private boolean lastLogWifi=false, lastLogNet=false, lastLogBluetooth=false;
    private int lastLogMediaState=-1, lastLogVolume=-1, lastLogAudioRoute=-1;
    private long lastLogHeartbeat=0;
    private void logField(String group, String detail) { if (fieldLog != null) fieldLog.record(group, detail); }
    // API publica do Android: inventaria saidas disponiveis, nao a rota ativa do Spotify.
    private int audioOutputDevicesFingerprint() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23 || audio == null) return -1;
            AudioDeviceInfo[] outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            int fingerprint = 1;
            for (AudioDeviceInfo device : outputs) {
                fingerprint = 31 * fingerprint + device.getType();
                fingerprint = 31 * fingerprint + device.getId();
            }
            return fingerprint;
        } catch (Exception ignored) { return -1; }
    }
    private String audioSnapshot() {
        int vol=-1,max=-1, route=-1; boolean muted=false;
        try { vol=audio.getStreamVolume(AudioManager.STREAM_MUSIC);max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            route=audioOutputDevicesFingerprint(); muted=audio.isStreamMute(AudioManager.STREAM_MUSIC); } catch(Exception ignored) {}
        return "volume="+vol+"/"+max+" mute="+muted+" outputsFingerprint="+route+" userMuted="+userMuted;
    }
    private void logPeriodicHealth() {
        if(fieldLog==null || !fieldLog.isEnabled()) return;
        long now=SystemClock.elapsedRealtime();
        if(lastLogWifi!=wifiConnected || lastLogNet!=netOnline || lastLogBluetooth!=btConnected){
            lastLogWifi=wifiConnected;lastLogNet=netOnline;lastLogBluetooth=btConnected;
            logField("REDE", "wifiInternet="+wifiConnected+" online="+netOnline+" btAudio="+btConnected);
        }
        int vol=-1,route=-1;
        try {vol=audio.getStreamVolume(AudioManager.STREAM_MUSIC);route=audioOutputDevicesFingerprint();} catch(Exception ignored) {}
        if(vol!=lastLogVolume || route!=lastLogAudioRoute){lastLogVolume=vol;lastLogAudioRoute=route;logField("AUDIO",audioSnapshot());}
        // Uma sessão PLAYING pode continuar atualizando a barra mesmo sem saída de áudio.
        // Somente registra indícios; não manda play/pause nem altera o nível do som.
        if (mediaPlaying && mediaIsSpotify && now - lastSpotifyWarningAt > 60000L) {
            boolean muted=false; int volume=-1;
            try { muted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
                volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC); } catch(Exception ignored) {}
            int cause = muted ? 1 : (volume == 0 ? 2 : 0);
            if (cause != 0) {
                lastSpotifyWarningAt=now;
                lastSpotifyWarning=cause;
                logField("SPOTIFY_DIAG", "playing_sem_som_possivel causa="+
                    (muted?"stream_muted":"volume_zero")+" "+audioSnapshot());
            }
        }
        if(now-lastLogHeartbeat>=30000L){
            lastLogHeartbeat=now;
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            try {((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);}catch(Exception ignored){}
            long appHeap=(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576L;
            logField("SAUDE", "heapMB="+appHeap+" freeRamMB="+(info.availMem/1048576L)+" wifi="+wifiConnected+" playing="+mediaPlaying+" "+audioSnapshot());
        }
    }
    private AudioManager audio;
    private ToneGenerator tone;
    private MediaSessionManager mediaManager;
    private MediaController mediaCtl;
    private String songTitle = "Nenhuma música em reprodução", songArtist = "Abra o Spotify para começar", mediaSource = "Spotify";
    private String userLyrics="", userLyricsSong="";
    private boolean lyricsOn=false;
    private boolean speedPreferGps = true;
    private boolean immersiveIdle = true;
    // Tela de descanso v1.51: apenas interface; nao altera som, Wi-Fi ou estado do carro.
    private boolean screenSaverEnabled = true;
    private int screenSaverSeconds = 600;
    private long radarModeNoticeUntil = 0L;
    private String radarModeNoticeText = "";
    private Dialog screenSaverDialog;
    private long screenSaverDismissedAt = 0L;
    private static final long HOME_IDLE_MS = 180000L; // três minutos
    private static final long HOME_TRANSITION_MS = 780L; // transição de layout e dock mais suave
    private float homeIdleFraction = 0f;
    private float homeIdleTransitionFrom = 0f;
    private long homeIdleTransitionAt = SystemClock.uptimeMillis();
    private boolean lastHomeIdleTarget = false;
    private long lastHomeInteraction = SystemClock.uptimeMillis();
    // Agendamento independente do redraw: recolhe o dock exatamente após inatividade.
    private final Runnable homeIdleTimeout = () -> {
        // Referência qualificada: o campo Dashboard é declarado adiante na Activity.
        if (immersiveIdle && MainActivity.this.dashboard != null && !isFinishing() &&
                SystemClock.uptimeMillis() - lastHomeInteraction >= HOME_IDLE_MS) {
            MainActivity.this.dashboard.invalidate();
        }
    };
    private void resetHomeIdleTimer() {
        lastHomeInteraction = SystemClock.uptimeMillis();
        handler.removeCallbacks(homeIdleTimeout);
        if (immersiveIdle) handler.postDelayed(homeIdleTimeout, HOME_IDLE_MS + 30L);
        if (dashboard != null) dashboard.invalidate();
    }
    private String lyricsCacheKey = "", lyricsCacheText = "", lyricsCacheSync = "";
    private boolean lyricsLoading = false;
    private Bitmap albumArt;
    // Cache privado e pequeno: apenas a última capa do Spotify, sem download de imagens.
    private Bitmap lastSpotifyArt;
    private boolean lastSpotifyQueuedHasArt = false;
    private String lastSpotifyTitle = "", lastSpotifyArtist = "", lastSpotifyQueued = "";
    private boolean cachedSpotifyPreview = false;
    private final ExecutorService spotifyCacheWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PugSpotifyCache");
        t.setDaemon(true);
        return t;
    });
    private boolean firstResume = true;
    private int lastMinuteRendered = -1;

    private boolean mediaPlaying = false, mediaIsSpotify = true, mediaPermission = false;
    private long songDuration = 0, songPosBase = 0, songPosStamp = 0;
    private float songSpeed = 1f;
    // Atualizações leves, direcionadas à sessão já selecionada: não pesquisa todas
    // as sessões a cada troca de música. O Spotify fornece os eventos diretamente.
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) {
            handler.post(() -> {
                if (mediaCtl != null) {
                    try { applyCurrentMedia(mediaCtl, m, mediaCtl.getPlaybackState()); }
                    catch (Exception ignored) { updateMedia(); }
                }
            });
        }
        @Override public void onPlaybackStateChanged(PlaybackState st) {
            handler.post(() -> {
                if (mediaCtl != null) {
                    try { applyCurrentMedia(mediaCtl, mediaCtl.getMetadata(), st); }
                    catch (Exception ignored) { updateMedia(); }
                }
            });
        }
        @Override public void onSessionDestroyed() { handler.post(() -> updateMedia()); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener mediaSessionsChanged =
            controllers -> handler.post(() -> updateMedia());
    private boolean mediaSessionObserverRegistered = false;

    private Dashboard dashboard;
    private PardalRadar pardalRadar;
    private JancarBridge jancarBridge;
    private int accentBase = Color.rgb(0, 174, 255), accent = accentBase, onAccent = Color.WHITE;
    private boolean beep;
    private boolean volumeVisible;
    private boolean userMuted = false; // Mudo acionado pelo botão, distinto de volume zero durante fade
    private boolean wifiConnected, btConnected;
    // v1.31: preferencia opcional, sem serviço de fundo ou chamadas repetidas.
    private boolean wifiAutoOn = false;
    private boolean wifiAutoAttemptedThisWake = false;
    private long wifiAutoLastTryElapsed = 0L;
    private long wifiAutoPausedAtElapsed = 0L;
    private String wifiAutoStatus = "Ainda não testado";
    // v1.49: reconexão com limite de tentativas e sem UI de configurações.
    private int wifiWakeAttempts = 0;
    private long wifiMonitorEpoch = 0L;
    private boolean appForeground = false;
    private int lastSpotifyWarning = 0;
    private long lastSpotifyWarningAt = 0L;

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
    private int brightDay = 80, brightNight = 15;
    // v1.35: 0=automático dia/noite; 1=mínimo; 2=máximo; 3=manual.
    // Os limites de brilho nunca chegam a zero para que o display continue utilizável.
    private static final int BRIGHT_AUTO = 0, BRIGHT_MIN = 1, BRIGHT_MAX = 2, BRIGHT_MANUAL = 3;
    private int brightnessMode = BRIGHT_AUTO, manualBrightness = 60;
    private boolean topBrightnessVisible = false; // opt-in para respeitar 4 atalhos + Config
    private int appliedBrightnessPct = -1;

    private boolean fadeBoot = true, fadePending = false, wasPlaying = false;
    private long fadeWindowUntil = 0, screenOffAt = 0;
    private static boolean bootArmed = false;
    private BroadcastReceiver screenReceiver;
    // Sem bloqueio de menus por velocidade GPS/CAN. Preserva toda a navegação.
    private final boolean drivingMode = false;
    private Dialog activeDialog;
    private Dialog quickDialog; // Diálogo rápido; correção do erro de compilação v1.44
    private SoundEngine snd;      // v1.14: motor de som (equalizador, loudness, volume por velocidade)
    private Dialog eqDialog;
    private final int[] topSlot = {0, 4, 9}; // indicadores do topo; -1 desligado, -2 relógio, -3 mídia, -4 clima
    // Visibilidade dos atalhos da barra superior (não altera o estado real de Wi-Fi/BT).
    private boolean topBtVisible = true, topWifiVisible = true;
    private boolean topPcVisible = true, topEqVisible = true, topVolumeVisible = true;
    private boolean topCustomVisible = false;
    private String topCustomPackage = "";
    private String audioStatus = "Ainda não testado";
    private boolean alertSound = true, overNow = false;
    private long lastOverAlert = 0;
    private String alertTitle = "", alertSub = "";
    private int alertKind = 0;
    private long alertUntil = 0, alertStart = 0;
    private ToneGenerator alertTone;
    private double gpsLat = Double.NaN, gpsLon = Double.NaN;

    private static final long SHINE_PERIOD = 3200L, SHINE_MS = 850L;
    private static final long SPEED_ALERT_DURATION_MS = 7000L; // v1.33: 4 s antigos + 3 s solicitados
    private static final float SET_SCALE = 1.5f; // tamanho dos menus de configuração
    private boolean speedAlert = false;
    private int speedLimit = 110;
    // 0 = apenas velocidade; 1 = visual; 2 = sonoro; 3 = visual e sonoro.
    private int speedWarningMode = 3;
    private boolean speedVisualOn() { return speedWarningMode == 1 || speedWarningMode == 3; }
    private boolean speedSoundOn() { return speedWarningMode == 2 || speedWarningMode == 3; }
    private void saveSpeedWarningMode(int mode) {
        speedWarningMode = Math.max(0, Math.min(3, mode));
        speedAlert = speedWarningMode != 0;
        overNow = false;
        getPreferences(0).edit().putInt("speed_warning_mode", speedWarningMode).putBoolean("speed_alert", speedAlert).apply();
        refreshGpsNeed();
        if (dashboard != null) dashboard.invalidate();
    }
    private static final String DEFAULT_VEHICLE_ACTION = "com.peugeot308.oem.VEHICLE_COMMAND";
    private String vehicleAction = DEFAULT_VEHICLE_ACTION;
    private final String[] vbtnLabel = {"MENU", "PC BORDO", "VOLTAR"};
    private final String[] vbtnCmd = {"MENU", "TRIP", "BACK"};
    private String homeAddr = "", workAddr = "";
    private final ArrayList<String> recentPackages = new ArrayList<>();
    private String lastCandPkg = null;
    private long lastCandStamp = 0;

    // A Jancar ainda não fornece protocolo CANBUS conhecido. Nunca indicar conectado sem telemetria válida.
    // Fontes de telemetria: 0 = Jancar CANBOX, 1 = adaptador ELM327 Bluetooth pareado.
    private int vehicleDataSource = 0;
    private String obdAddress = "";
    private ObdBluetoothReader obdReader;
    private boolean obdLive = false;
    private String obdState = "Não configurado";
    private int lastSettingsTab = 0;
    private final int[] lastSettingsScroll = new int[7];
    private boolean canConnected = false;
    private CanbusVehicleReader canbusReader;
    private CanbusVehicleReader.Snapshot canbusSnapshot;
    private String canbusApiStatus = "Ainda não consultado";
    private int observedOpenDoors = 0; // evita declarar fechado sem ter observado sinal válido
    private long lastCanPacket = 0;
    // Card principal do veículo: exatamente 2 ou 3 indicadores escolhidos pelo usuário.
    // As opções permanecem salvas; os valores GPS da sessão nunca são gravados em disco.
    private int vehicleCardCount = 3;
    // Terceiro card: 0 = indicador anterior; 1 = carro completo; 2 = só desenho; 3 = personalizado.
    // Não há telemetria CANBOX confirmada para portas, travas e luzes: todos os estados iniciam desconhecidos.
    private int vehicleThirdVisualMode = 1;
    private int vehicleThirdVisualFlags = 255;
    private final String[] thirdCarIndicators = {"Portas", "Porta-malas", "Travamento", "Faróis", "Setas", "Freio estacion.", "Temp. motor", "Combustível"};
    private final int[] vehicleCardSlots = {0, 12, 7}; // velocidade, km rodados, altitude
    private int estimatedFuelLPer100 = 9; // parâmetro MANUAL, não consumo instantâneo
    private boolean vehicleMenuEnabled = false; // menu do veículo (3 botões + dados) desligado por padrão
    private boolean topWidgetEnabled = true;    // mini widget na barra superior
    private final String[] vehicleNames = {"Velocidade", "Autonomia", "Temperatura externa", "Combustível", "RPM do motor", "Portas", "Bússola (GPS)", "Altitude (GPS)", "Consumo instantâneo (OBD/CAN)", "Temperatura do motor", "Limite de velocidade", "Precisão do GPS", "Km rodados (GPS, sessão)", "Velocidade média (GPS)", "Velocidade máxima (GPS)", "Combustível gasto (estimado)"};
    private final String[] vehicleShort = {"VELOCIDADE", "AUTONOMIA", "EXTERNA", "COMBUSTÍVEL", "RPM", "PORTAS", "BÚSSOLA", "ALTITUDE", "CONSUMO", "TEMP. MOTOR", "LIMITE", "GPS PRECISÃO", "KM RODADOS", "MÉDIA GPS", "MÁXIMA GPS", "GASTO EST."};
    private final String[] vehicleUnits = {"km/h", "km", "°C", "%", "rpm", "", "", "m", "km/l", "°C", "km/h", "m", "km", "km/h", "km/h", "L est."};
    private final String[] vehicleValues = {"--", "--", "--", "--", "--", "--", "--", "--", "--", "--", "--", "--", "--", "--", "--", "--"};
    private boolean hasCanData() {
        return vehicleDataSource == 1
            ? obdReader != null && obdReader.isLive()
            : canConnected && SystemClock.elapsedRealtime() - lastCanPacket < 10000;
    }
    /** Leitura somente do decodificador CAN original. Valores ausentes não são substituídos por estados inventados. */
    private void acceptCanbusSnapshot(CanbusVehicleReader.Snapshot data,String state){
        canbusApiStatus=state;
        if(data==null){if(dashboard!=null)dashboard.invalidate();return;}
        canbusSnapshot=data;
        if(vehicleDataSource!=0){if(dashboard!=null)dashboard.invalidate();return;}
        int[] d=data.dashboardInts;
        float[] f=data.dashboardFloats;
        // Só consideramos a API como fonte útil quando há telemetria plausível,
        // não simplesmente quando Binder responde com uma estrutura zerada.
        boolean real=false;
        if(d!=null && d.length>=7){
            if(d[3]>=300&&d[3]<=9000){vehicleValues[4]=String.valueOf(d[3]);real=true;}
            if(d[4]>0&&d[4]<=250){vehicleValues[0]=String.valueOf(d[4]);real=true;}
            if(d[2]>0&&d[2]<=3000){vehicleValues[1]=String.valueOf(d[2]);real=true;}
        }
        if(f!=null&&f.length>=8){
            if(Float.isFinite(f[1])&&f[1]>0f&&f[1]<=100f){
                vehicleValues[3]=String.format(Locale.getDefault(),"%.0f",f[1]);real=true;
            }
            if(Float.isFinite(f[4])&&f[4]>20f&&f[4]<150f){
                vehicleValues[9]=String.format(Locale.getDefault(),"%.0f",f[4]);real=true;
            }
            if(Float.isFinite(f[7])&&f[7]>-40f&&f[7]<70f&&Math.abs(f[7])>0.01f){
                vehicleValues[2]=String.format(Locale.getDefault(),"%.1f",f[7]);real=true;
            }
        }
        if(data.cabin!=null&&data.cabin.length>=17){
            int opened=0;
            for(int i=1;i<=5;i++)if(data.cabin[i]==1){opened|=1<<(i-1);observedOpenDoors|=1<<(i-1);}
            if(opened!=0){vehicleValues[5]="ABERTA";real=true;}
            else if(observedOpenDoors!=0){vehicleValues[5]="FECHADAS";real=true;}
            if(opened!=0)logField("CAN","abertura_observada_mascara="+opened);
        }
        if(real){canConnected=true;lastCanPacket=SystemClock.elapsedRealtime();}
        if(dashboard!=null)dashboard.invalidate();
    }

    private void useDataSource(int source) {
        vehicleDataSource = source;
        getPreferences(0).edit().putInt("vehicle_source", source).apply();
        if(obdReader != null) obdReader.stop();
        obdLive = false;
        Arrays.fill(vehicleValues, "--");
        canConnected=false;
        if(source == 1 && obdReader != null && !obdAddress.isEmpty()) obdReader.start(obdAddress);
        if(source==0&&canbusSnapshot!=null)acceptCanbusSnapshot(canbusSnapshot,canbusApiStatus);
        if(dashboard != null) dashboard.invalidate();
    }
    private void chooseObdAdapter() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if(a == null || !a.isEnabled()) { snack("Ligue o Bluetooth e pareie um ELM327 antes"); return; }
            ArrayList<BluetoothDevice> devices = new ArrayList<>(a.getBondedDevices());
            if(devices.isEmpty()) { snack("Nenhum dispositivo Bluetooth pareado"); return; }
            String[] names = new String[devices.size()];
            for(int i=0;i<devices.size();i++) names[i]=devices.get(i).getName()+"  •  "+devices.get(i).getAddress();
            new AlertDialog.Builder(this).setTitle("Adaptador OBD2 Bluetooth")
                .setItems(names,(d,index)->{
                    obdAddress=devices.get(index).getAddress();
                    getPreferences(0).edit().putString("obd_address",obdAddress).apply();
                    if(vehicleDataSource == 1) useDataSource(1);
                    snack("Adaptador selecionado. Use Conectar OBD2.");
                }).setNegativeButton("Cancelar",null).show();
        }catch(SecurityException e){snack("Permita acesso ao Bluetooth nas configurações do Android");}
        catch(Exception e){snack("Falha ao listar dispositivos: "+e.getMessage());}
    }
    private void showVehicleSourceStatus() {
        String status = vehicleDataSource == 0
           ? ("CANBUS original: "+canbusApiStatus+"\nLeitura válida recente: "+hasCanData()+"\n\n"+
               (jancarBridge == null ? "Serviço Jancar indisponível" : jancarBridge.diagnostic()))
           : ("Fonte: OBD2 Bluetooth\nAdaptador: "+(obdAddress.isEmpty()?"não selecionado":obdAddress)
               +"\nEstado: "+obdState+"\nDados recentes: "+hasCanData());
        ScrollView scroll = new ScrollView(this);
        TextView details = label(status, 14, cText, false);
        details.setPadding(dp(20), dp(12), dp(20), dp(16));
        details.setHorizontallyScrolling(false);
        details.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        scroll.addView(details);
        new AlertDialog.Builder(this).setTitle("Diagnóstico da fonte do veículo").setView(scroll)
           .setPositiveButton("Fechar",null).show();
    }

    // ---- GPS ----
    private LocationManager locationManager;
    private boolean gpsRunning = false;
    private float gpsSpeedKmh = -1, gpsBearing = -1, gpsAccuracy = -1;
    private double gpsAlt = Double.NaN;
    private long gpsLastFix = 0;
    // Apenas RAM: um percurso começa ao abrir o launcher ou tocar em Zerar percurso.
    private Location gpsTripPrevious;
    private boolean gpsTripValidFix = false;
    private double gpsTripMeters = 0;
    private long gpsTripMovingMillis = 0;
    private float gpsTripMaxKmh = 0;
    private void resetGpsTrip() {
        gpsTripPrevious = null;
        gpsTripValidFix = false;
        gpsTripMeters = 0;
        gpsTripMovingMillis = 0;
        gpsTripMaxKmh = 0;
        if (dashboard != null) dashboard.invalidate();
    }
    /** Não acumula saltos do GPS, coordenadas ruins ou longos intervalos entre fixes. */
    private void accountGpsTrip(Location l) {
        if (!l.hasAccuracy() || l.getAccuracy() > 35f || (l.hasSpeed() && (l.getSpeed() < 0f || l.getSpeed() > 65f))) {
            gpsTripPrevious = null;
            return;
        }
        gpsTripValidFix = true;
        if (l.hasSpeed()) gpsTripMaxKmh = Math.max(gpsTripMaxKmh, l.getSpeed() * 3.6f);
        if (gpsTripPrevious != null) {
            double elapsed = (l.getElapsedRealtimeNanos() - gpsTripPrevious.getElapsedRealtimeNanos()) / 1.0e9;
            float meters = gpsTripPrevious.distanceTo(l);
            boolean moving = l.hasSpeed() ? l.getSpeed() >= 1.1f : (elapsed > 0 && meters / elapsed >= 1.1);
            if (elapsed >= .4 && elapsed <= 12 && meters >= 2 && meters <= elapsed * 65 && moving) {
                gpsTripMeters += meters;
                gpsTripMovingMillis += Math.round(elapsed * 1000);
            }
        }
        gpsTripPrevious = new Location(l);
    }
    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            accountGpsTrip(l);
            gpsLastFix = SystemClock.elapsedRealtime();
            float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : -1f;
            gpsSpeedKmh = kmh < 0 ? -1f : (kmh < 2f ? 0f : kmh);
            if (l.hasBearing() && kmh >= 4f) gpsBearing = l.getBearing();
            gpsAccuracy = l.hasAccuracy() ? l.getAccuracy() : -1f;
            gpsAlt = l.hasAltitude() ? l.getAltitude() : Double.NaN;
            gpsLat = l.getLatitude(); gpsLon = l.getLongitude();
            if (weatherAuto) tryAutoWeather();
            if (pardalRadar != null) {
                // Feed GPS também pelo launcher: o serviço pode ter iniciado sem localização.
                // O cooldown compartilhado evita bipes duplicados.
                pardalRadar.onLocation(l);
                if (dashboard != null) dashboard.invalidate();
            }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };
    private boolean needGps() { return weatherAuto || vehicleMenuEnabled || topWidgetEnabled || speedAlert || speedPreferGps || (snd != null && snd.autoVolOn) || (pardalRadar != null && pardalRadar.mode()!=PardalRadar.OFF); }
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
            if (speedPreferGps && gpsFresh() && gpsSpeedKmh >= 0) return String.valueOf(Math.round(gpsSpeedKmh));
            if (hasCanData() && !"--".equals(vehicleValues[0])) return vehicleValues[0];
            return gpsFresh() && gpsSpeedKmh >= 0 ? String.valueOf(Math.round(gpsSpeedKmh)) : null;
        }
        if (id == 6) {
            if (!gpsFresh() || gpsBearing < 0) return null;
            String[] dirs = {"Norte", "Nordeste", "Leste", "Sudeste", "Sul", "Sudoeste", "Oeste", "Noroeste"};
            return dirs[(int)(((gpsBearing + 22.5f) % 360f) / 45f) % 8];
        }
        if (id == 11) return gpsFresh() && gpsAccuracy >= 0 ? String.valueOf(Math.round(gpsAccuracy)) : null;
        if (id == 7) return gpsFresh() && !Double.isNaN(gpsAlt) ? String.valueOf(Math.round(gpsAlt)) : null;
        if (id == 10) return String.valueOf(speedLimit);
        // 12 a 15: GPS da sessão. Não são leituras de combustível/ECU.
        if (id == 12) return gpsTripValidFix ? String.format(new Locale("pt","BR"), "%.1f", gpsTripMeters / 1000.0) : null;
        if (id == 13) return gpsTripMovingMillis > 0 ? String.valueOf(Math.round((gpsTripMeters / 1000.0) / (gpsTripMovingMillis / 3600000.0))) : null;
        if (id == 14) return gpsTripValidFix ? String.valueOf(Math.round(gpsTripMaxKmh)) : null;
        if (id == 15) return gpsTripValidFix ? "≈" + String.format(new Locale("pt","BR"), "%.1f", gpsTripMeters / 1000.0 * estimatedFuelLPer100 / 100.0) : null;
        return hasCanData() && !"--".equals(vehicleValues[id]) ? vehicleValues[id] : null;
    }
    private float speedNow() { String sv = vehicleValue(0); if (sv == null) return -1; try { return Float.parseFloat(sv); } catch (Exception e) { return -1; } }

    // ---- Clima ----
    private long clockTextMinute = Long.MIN_VALUE;
    private String clockText = "--:--", clockDateText = "";
    private void refreshClockText() {
        long minute = System.currentTimeMillis() / 60000L;
        if (minute == clockTextMinute) return;
        clockTextMinute = minute;
        Date d = new Date();
        clockText = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d);
        Locale br = new Locale("pt", "BR");
        String day = new SimpleDateFormat("EEEE, d 'de' MMMM", br).format(d);
        clockDateText = day.isEmpty() ? "" : day.substring(0, 1).toUpperCase(br) + day.substring(1);
    }
    private String weatherCity = "", weatherLine = "Configure a cidade", weatherDetail = "";
    private boolean weatherAuto = false;
    // So desenha movimento no icone do clima, sem nova consulta meteorologica.
    private boolean weatherMotionEnabled = true;
    private double weatherAutoLat = Double.NaN, weatherAutoLon = Double.NaN;
    private long weatherAutoChecked = 0;
    private String brandTitle = "PEUGEOT 308";
    private boolean minimalIcons = true;
    private final HashSet<String> originalAppIcons = new HashSet<>();
    private boolean standaloneVehicleButton = true;
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
    // A ordem e os nomes curtos pertencem somente ao launcher; não alteram os aplicativos.
    private final ArrayList<String> appOrder = new ArrayList<>();
    private final HashMap<String,String> appAliases = new HashMap<>();
    private final String[] names = {"Waze", "Rádio", "Spotify", "Telefone", "Bluetooth", "Apps", "Config.", "Casa", "Trabalho", "Último app"};
    private final String[] packages = {"com.waze", "com.jancar.radio", "com.spotify.music", "com.jancar.bluetooth", "", "", "", "", "", ""};

    private long volumeUntil;
    private int previousVolume = 5;
    private int tickCount = 0;
    private Runnable rampTask;
    private int rampTarget = -1;
    private ConnectivityManager.NetworkCallback netCallback;
    private String updateBase = DEFAULT_SERVER;
    private String ghRepo = "lemiastkuosky86/Pug308", notifiedVersion = ""; private int updateSource = 0; private boolean autoUpdate = true, autoDownloadUpdates = true, updateChecking = false;
    private String updateDownloadVersion = "";
    private boolean updateInstallPromptShown = false;
    private long updateDownloadId = -1; private Uri pendingInstall = null; private BroadcastReceiver downloadReceiver;

    private final Runnable tick = new Runnable() {
        public void run() {
            if(pardalRadar!=null&&pardalRadar.isSimulating()){
                pardalRadar.simulationTick();
                if(dashboard!=null)dashboard.invalidate();
            }
            updateDrivingMode();
            if (appForeground || (snd != null && snd.autoVolOn)) pushSoundState();
            checkAlerts();
            if (appForeground && screenSaverEnabled && screenSaverDialog == null
                    && !drivingMode && dashboard != null && dashboard.hasWindowFocus()
                    && (activeDialog == null || !activeDialog.isShowing())
                    && (eqDialog == null || !eqDialog.isShowing())
                    && SystemClock.uptimeMillis() - lastHomeInteraction >= screenSaverSeconds * 1000L
                    && SystemClock.uptimeMillis() - screenSaverDismissedAt >= 5000L) {
                openScreenSaver();
            }
            if (effectiveNight() != night) applyTheme();
            // Brilho, rede e sessão são checados em cadências independentes.
            if (brightnessMode == BRIGHT_AUTO && tickCount % 60 == 0) applyBrightness();
            // Em segundo plano, reduz consultas; no painel mantém atualização de 3 s.
            if (tickCount % (appForeground ? 3 : 12) == 0) { refreshConnections(); logPeriodicHealth(); }
            if (tickCount % (appForeground ? 15 : 45) == 0) updateMedia();
            if (tickCount % 600 == 599) maybeAutoUpdate();
            if (tickCount % 60 == 0 && pardalRadar != null) pardalRadar.checkDaily(wifiConnected);
            tickCount++;
            if (dashboard != null && dashboard.getWindowToken() != null && !isFinishing()) {
                Calendar now = Calendar.getInstance();
                int currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
                boolean minuteChanged = lastMinuteRendered != currentMinute;
                lastMinuteRendered = currentMinute;
                boolean shouldCollapse = immersiveIdle && !drivingMode && !lastHomeIdleTarget &&
                        SystemClock.uptimeMillis() - lastHomeInteraction >= HOME_IDLE_MS;
                if (minuteChanged || shouldCollapse) dashboard.invalidate();
                else if (mediaPlaying && songDuration > 0) dashboard.invalidateMediaProgress();
                // GPS, rede, mídia, animações e alertas invalidam apenas quando mudam.
            }
            handler.postDelayed(this, 1000L);
        }
    };

    // ---------------------------------------------------------------- tema
    private boolean computeNight() {
        if (themeMode == 1) return true;
        if (themeMode == 2) return false;
        return isNightByClock();
    }
    private boolean isNightByClock() {
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (sunriseMin >= 0 && sunsetMin >= 0) return now < sunriseMin || now >= sunsetMin;
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h < 6 || h >= 18;
    }
    private boolean effectiveNight() {
        return ThemePalette.create(presetIndex, themeMode, isNightByClock(), accentBase).dark;
    }
    private void applyTheme() {
        ThemePalette palette=ThemePalette.create(presetIndex, themeMode, isNightByClock(), accentBase);
        night=palette.dark;
        cBg0=palette.bg0; cBg1=palette.bg1; cHead=palette.head;
        cSurfTop=palette.surfTop; cSurfBot=palette.surfBot; cSurf2=palette.surf2;
        cHair=palette.hair; cText=palette.text; cMuted=palette.muted; cDim=palette.dim;
        cShadow=palette.shadow; cTrack=palette.track; cOk=palette.ok; cBad=palette.bad;
        accent=palette.accent; onAccent=palette.onAccent;
        // Tema define somente a aparência: intensidade da tela é uma escolha independente.
        applyBrightness();
        if (dashboard != null) dashboard.invalidate();
    }
    private static int tintC(int base, int tint, float k) { return mix(base, 0xff000000 | (tint & 0xffffff), k); }
    private static float lum(int c) { return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f; }
    private void setPreset(int i) {
        presetIndex = Math.max(0, Math.min(PRESET_NAMES.length - 1, i));
        accentBase = PRESET_ACCENTS[presetIndex];
        getPreferences(0).edit().putInt("theme2", presetIndex).apply();
        applyTheme();
    }
    private static int safeBrightness(int percent) { return Math.max(5, Math.min(100, percent)); }
    private int brightnessPercent() {
        switch (brightnessMode) {
            case BRIGHT_MIN: return safeBrightness(brightNight);
            case BRIGHT_MAX: return safeBrightness(brightDay);
            case BRIGHT_MANUAL: return safeBrightness(manualBrightness);
            default: return safeBrightness(isNightByClock() ? brightNight : brightDay);
        }
    }
    private String brightnessModeName() {
        switch (brightnessMode) {
            case BRIGHT_MIN: return "Mínimo";
            case BRIGHT_MAX: return "Máximo";
            case BRIGHT_MANUAL: return "Manual";
            default: return "Automático";
        }
    }
    private void saveBrightnessMode(int mode) {
        brightnessMode = Math.max(BRIGHT_AUTO, Math.min(BRIGHT_MANUAL, mode));
        autoBrightness = brightnessMode == BRIGHT_AUTO;
        getPreferences(0).edit().putInt("brightness_mode", brightnessMode)
                .putBoolean("auto_bright", autoBrightness).apply();
        appliedBrightnessPct = -1;
        applyBrightness();
        if (dashboard != null) dashboard.invalidateHeader();
    }
    private void cycleBrightness() {
        // Toque simples alterna os três presets. Manual só é selecionado pelo slider.
        if (brightnessMode == BRIGHT_AUTO) saveBrightnessMode(BRIGHT_MIN);
        else if (brightnessMode == BRIGHT_MIN) saveBrightnessMode(BRIGHT_MAX);
        else saveBrightnessMode(BRIGHT_AUTO);
        snack("Brilho solicitado: " + brightnessModeName() + " (" + brightnessPercent() + "%)");
    }
    /** A janela funciona sem permissão. O brilho global só é tentado se WRITE_SETTINGS existir. */
    private void applyBrightness() {
        try {
            int pct = brightnessPercent();
            if (pct == appliedBrightnessPct) return; // evita regravar Settings a cada redesenho
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = pct / 100f;
            getWindow().setAttributes(lp);
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(13, Math.round(pct * 255f / 100f)));
            }
            appliedBrightnessPct = pct;
        } catch (Exception ignored) { appliedBrightnessPct = -1; }
    }
    private void openBrightnessQuickMenu() {
        quickPanel("BRILHO • CONTROLE RÁPIDO", root -> {
            section(root, "PERFIS", "Toque rápido no botão superior para alternar entre Auto, Mínimo e Máximo");
            choiceRow(root, "Modo de brilho", new String[]{"Automático (dia/noite)", "Mínimo", "Máximo", "Manual"},
                    brightnessMode, mode -> { saveBrightnessMode(mode); dismissQuick(); openBrightnessQuickMenu(); });
            divider(root);
            TextView level = label("Brilho solicitado: " + brightnessPercent() + "% • " + brightnessModeName(), 18, cText, true);
            root.addView(level);
            SeekBar seek = new SeekBar(this);
            seek.setMax(65); // 15% a 80%, ajuste manual seguro dentro da faixa definida
            seek.setProgress(Math.max(15, Math.min(80, manualBrightness)) - 15);
            root.addView(seek, new LinearLayout.LayoutParams(-1, dp(66)));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    manualBrightness = Math.max(15, Math.min(80, progress + 15));
                    getPreferences(0).edit().putInt("brightness_manual", manualBrightness).apply();
                    if (brightnessMode != BRIGHT_MANUAL) saveBrightnessMode(BRIGHT_MANUAL);
                    else { appliedBrightnessPct = -1; applyBrightness(); }
                    level.setText("Brilho solicitado: " + brightnessPercent() + "% • Manual");
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            root.addView(label("Arrastar seleciona Manual. No automático, alterna entre os valores de dia e noite.", 13, cMuted, false));
            divider(root);
            stepperRow(root, "Brilho mínimo", brightNight, 5, 95, 5, "%", val -> {
                brightNight = val; if (brightDay < val) brightDay = val;
                getPreferences(0).edit().putInt("bright_night", brightNight).putInt("bright_day", brightDay).apply();
                appliedBrightnessPct = -1; applyBrightness();
                level.setText("Brilho solicitado: " + brightnessPercent() + "% • " + brightnessModeName());
            });
            stepperRow(root, "Brilho máximo", brightDay, 10, 100, 5, "%", val -> {
                brightDay = Math.max(val, brightNight);
                getPreferences(0).edit().putInt("bright_day", brightDay).apply();
                appliedBrightnessPct = -1; applyBrightness();
                level.setText("Brilho solicitado: " + brightnessPercent() + "% • " + brightnessModeName());
            });
            root.addView(label("O percentual indica o valor pedido ao Android. A iluminação física da Jancar pode ser diferente.",12,cMuted,false));
            if (!Settings.System.canWrite(this)) {
                LinearLayout perms = new LinearLayout(this); root.addView(perms);
                actionButton(perms,"Permissão de brilho do sistema",() -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { snack("Esta central não permite configurar a permissão"); }
                });
            }
        });
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
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        snd = SoundEngine.get(this); // inicializa o efeito depois que a primeira tela for exibida
        SharedPreferences prefs = getPreferences(0);
        fieldLog = new FieldLog(getApplicationContext(), prefs.getBoolean("field_log_enabled", false));
        pardalRadar = PardalRadar.shared(this,prefs);
        logField("APP", "create version="+BuildConfig.VERSION_NAME+" uptimeMs="+SystemClock.elapsedRealtime()
                +" start="+(SystemClock.elapsedRealtime()<6*60*1000L?"boot_recente":"processo_recriado_ou_retorno"));
        presetIndex = Math.max(0, Math.min(PRESET_NAMES.length - 1, prefs.getInt("theme2", 0))); // padrão: Peugeot Âmbar (preto + âmbar)
        accentBase = PRESET_ACCENTS[presetIndex];
        albumBg = prefs.getBoolean("album_bg", true);
        autoBrightness = prefs.getBoolean("auto_bright", true);
        brightnessMode = Math.max(BRIGHT_AUTO, Math.min(BRIGHT_MANUAL,
                prefs.getInt("brightness_mode", autoBrightness ? BRIGHT_AUTO : BRIGHT_MANUAL)));
        autoBrightness = brightnessMode == BRIGHT_AUTO;
        // v1.45: migração única para os níveis pedidos (15% mínimo / 80% máximo).
        if (!prefs.getBoolean("brightness_145_migrated", false)) {
            prefs.edit().putInt("bright_day", 80).putInt("bright_night", 15)
                    .putBoolean("brightness_145_migrated", true).apply();
        }
        brightDay = Math.max(15, Math.min(100, prefs.getInt("bright_day", 80)));
        brightNight = Math.max(5, Math.min(brightDay, prefs.getInt("bright_night", 15)));
        manualBrightness = Math.max(15, Math.min(80, prefs.getInt("brightness_manual", 60)));
        // v1.47: o fade automático manipulava o STREAM_MUSIC durante a retomada
        // e podia provocar silêncio temporário no Spotify. Mantido apenas como opção manual.
        if (!prefs.getBoolean("audio_147_migrated", false)) {
            prefs.edit().putBoolean("fade_boot", false).putBoolean("audio_147_migrated", true).apply();
        }
        fadeBoot = prefs.getBoolean("fade_boot", false);
        speedAlert = prefs.getBoolean("speed_alert", false);
        speedLimit = Math.max(30, Math.min(220, prefs.getInt("speed_limit", 110)));
        speedWarningMode = Math.max(0, Math.min(3, prefs.getInt("speed_warning_mode", speedAlert ? 3 : 1)));
        speedAlert = speedWarningMode != 0;
        int[] defSlots = {0, 4, 9};
        for (int i = 0; i < topSlot.length; i++) topSlot[i] = Math.max(-4, Math.min(vehicleNames.length - 1, prefs.getInt("top_slot_" + i, defSlots[i])));
        alertSound = prefs.getBoolean("alert_sound", true);
        vehicleAction = prefs.getString("vehicle_action", DEFAULT_VEHICLE_ACTION);
        for (int i = 0; i < 3; i++) { vbtnLabel[i] = prefs.getString("vbtn_label_" + i, vbtnLabel[i]); vbtnCmd[i] = prefs.getString("vbtn_cmd_" + i, vbtnCmd[i]); }
        homeAddr = prefs.getString("home_addr", ""); workAddr = prefs.getString("work_addr", "");
        for (String pkg : prefs.getString("recent_apps", "").split(",")) if (!pkg.isEmpty() && !recentPackages.contains(pkg)) recentPackages.add(pkg);
        beep = prefs.getBoolean("beep", false);
        themeMode = Math.max(0, Math.min(2, prefs.getInt("theme", 0)));
        updateBase = prefs.getString("server", DEFAULT_SERVER);
        ghRepo = prefs.getString("gh_repo", "lemiastkuosky86/Pug308"); if (ghRepo.trim().isEmpty()) ghRepo = "lemiastkuosky86/Pug308"; updateSource = prefs.getInt("upd_source", 0); autoUpdate = prefs.getBoolean("upd_auto", true); autoDownloadUpdates = prefs.getBoolean("upd_auto_download", true); notifiedVersion = prefs.getString("upd_notified", "");
        updateDownloadId = prefs.getLong("upd_download_id", -1L);
        updateDownloadVersion = prefs.getString("upd_download_version", "");
        vehicleMenuEnabled = prefs.getBoolean("vehicle_menu", prefs.getBoolean("vehicle_card", false));
        vehicleCardCount = Math.max(1, Math.min(3, prefs.getInt("vehicle_cards_count", 3)));
        vehicleThirdVisualMode = Math.max(0, Math.min(3, prefs.getInt("vehicle_third_visual_mode", 1)));
        vehicleThirdVisualFlags = prefs.getInt("vehicle_third_visual_flags", 255) & 255;
        estimatedFuelLPer100 = Math.max(4, Math.min(25, prefs.getInt("fuel_avg_manual", 9)));
        int[] defaultVehicleCardSlots = {0, 12, 7};
        for (int i = 0; i < vehicleCardSlots.length; i++)
            vehicleCardSlots[i] = Math.max(0, Math.min(vehicleNames.length - 1, prefs.getInt("vehicle_card_slot_" + i, defaultVehicleCardSlots[i])));
        vehicleDataSource = Math.max(0,Math.min(1,prefs.getInt("vehicle_source",0)));
        obdAddress = prefs.getString("obd_address", "");
        lastSettingsTab = Math.max(0,Math.min(6,prefs.getInt("last_settings_tab",0)));
        for(int tab=0;tab<7;tab++)lastSettingsScroll[tab]=Math.max(0,prefs.getInt("settings_scroll_"+tab,0));
        speedPreferGps = prefs.getBoolean("speed_prefer_gps",true);
        immersiveIdle = prefs.getBoolean("immersive_idle",true);
        // Restaura uma vez o comportamento aprovado após regressão em versões anteriores.
        // O usuário continua podendo alterar a opção mais tarde.
        if (!prefs.getBoolean("home_idle_restored_162", false)) {
            immersiveIdle = true;
            prefs.edit().putBoolean("immersive_idle", true)
                    .putBoolean("home_idle_restored_162", true).apply();
        }
        screenSaverEnabled = prefs.getBoolean("screensaver_enabled", true);
        screenSaverSeconds = Math.max(600, Math.min(3600, prefs.getInt("screensaver_seconds", 600)));
        topWidgetEnabled = prefs.getBoolean("top_widget", true);
        topBtVisible = prefs.getBoolean("top_show_bt", true);
        topWifiVisible = prefs.getBoolean("top_show_wifi", true);
        // Opcional: os quatro atalhos existentes não devem desaparecer numa atualização.
        topBrightnessVisible = prefs.getBoolean("top_show_brightness", false);
        // v1.47: reconexão de Wi-Fi habilitada na primeira execução após atualizar.
        // Não força rede, não abre configurações e respeita bloqueios do firmware.
        if (!prefs.getBoolean("wifi_147_migrated", false)) {
            prefs.edit().putBoolean("wifi_auto_on", true).putBoolean("wifi_147_migrated", true).apply();
        }
        wifiAutoOn = prefs.getBoolean("wifi_auto_on", true);
        wifiAutoStatus = prefs.getString("wifi_auto_status", "Ainda não testado");
        topPcVisible = prefs.getBoolean("top_show_pc", true);
        topEqVisible = prefs.getBoolean("top_show_eq", true);
        topVolumeVisible = prefs.getBoolean("top_show_volume", true);
        topCustomVisible = prefs.getBoolean("top_show_custom", false);
        topCustomPackage = prefs.getString("top_custom_package", "");
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
        loadAppPersonalization(prefs);
        weatherCity = prefs.getString("city", "");
        weatherAuto = prefs.getBoolean("weather_auto", false);
        weatherMotionEnabled = prefs.getBoolean("weather_motion", true);
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
        // Mostra a Home logo; serviços OEM e disco não devem atrasar a primeira renderização.
        setContentView(dashboard);
        restoreLastSpotifyPreview();
        jancarBridge = new JancarBridge(this, () -> handler.post(() -> { if (dashboard != null) dashboard.invalidate(); }));
        handler.postDelayed(() -> { if (!isFinishing() && jancarBridge != null) jancarBridge.start(); }, 300L);
        canbusReader = new CanbusVehicleReader(this, this::acceptCanbusSnapshot);
        handler.postDelayed(() -> { if (!isFinishing() && canbusReader != null) canbusReader.start(); }, 600L);
        handler.postDelayed(() -> { if (!isFinishing() && snd != null) snd.start(); }, 380L);
        obdReader = new ObdBluetoothReader((values, live, stateText) -> {
            obdState = stateText; obdLive = live;
            if(vehicleDataSource != 1) return;
            if(values != null && live) {
                for(int i=0;i<Math.min(values.length,vehicleValues.length);i++)
                    if(values[i] != null) vehicleValues[i] = values[i];
                lastCanPacket = SystemClock.elapsedRealtime();
            }
            if(dashboard != null) dashboard.invalidate();
        });
        if(vehicleDataSource == 1 && !obdAddress.isEmpty()) obdReader.start(obdAddress);
        mediaManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            ensureMediaSessionObserver();
            updateMedia();
            refreshConnections();
        }, 180L);
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) need.add("android.permission.BLUETOOTH_CONNECT");
        if (needGps() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 14); else {startGps();syncRadarService();}
        registerNetworkWatcher();
        if (!weatherCity.isEmpty() && !weatherAuto) fetchWeather(); else if (weatherAuto) tryAutoWeather();

        registerScreenReceiver();
        // Em algumas centrais o estado Wi-Fi só estabiliza depois de acordar a MCU.
        // Uma tentativa por inicialização; sem polling nem serviço residente.
        if (wifiAutoOn) startWifiWakeMonitor("inicio");
        handler.postDelayed(()->{if(pardalRadar!=null)pardalRadar.checkDaily(wifiConnected);},15000);
        handler.post(shineTick);
        if (!bootArmed) { bootArmed = true; if (SystemClock.elapsedRealtime() < 6 * 60 * 1000L) armStartupFade(); } // ligou o carro (boot recente)
        handler.post(tick);
        registerUpdateDownloadReceiver();
        handler.postDelayed(this::checkStoredDownload, 1400L);
        handler.postDelayed(this::maybeAutoUpdate, 25000);
    }
    @Override protected void onResume() {
        super.onResume();
        appForeground = true;
        resetHomeIdleTimer();
        logField("APP", "resume");
        // Permissão de notificações pode ter sido concedida enquanto a Activity
        // estava pausada; tentar registrar novamente, sem duplicar ouvintes.
        if (!firstResume) {
            ensureMediaSessionObserver();
            updateMedia();
        }
        if (pendingInstall != null && getPackageManager().canRequestPackageInstalls()) { Uri pu = pendingInstall; pendingInstall = null; installApk(pu); }
        handler.postDelayed(this::checkStoredDownload, 900L);
        startGps();
        if(pardalRadar!=null)pardalRadar.checkDaily(wifiConnected);
        RadarForegroundService.setHomeVisible(true);
        syncRadarService();
        // onResume também ocorre no primeiro lançamento: não duplicar a inicialização do áudio.
        if (!firstResume && snd != null) snd.start();
        firstResume = false;
        appliedBrightnessPct = -1; applyBrightness(); lastCandStamp = 0;
        if (dashboard != null) {
            dashboard.invalidate(); // ícones já estão em cache por pacote
            dashboard.setWeatherAnimationActive(true); // somente com a Activity em primeiro plano
        }
        // Cobre firmwares que retomam a Activity mas não transmitem ACTION_SCREEN_ON.
        if (wifiAutoOn && wifiAutoPausedAtElapsed > 0L &&
                SystemClock.elapsedRealtime() - wifiAutoPausedAtElapsed > 60000L) {
            startWifiWakeMonitor("retorno_activity");
        }
        wifiAutoPausedAtElapsed = 0L;
    }
    @Override protected void onPause() {
        handler.removeCallbacks(homeIdleTimeout);
        if (screenSaverDialog != null) screenSaverDialog.dismiss();
        if (dashboard != null) dashboard.setWeatherAnimationActive(false);
        appForeground = false;
        RadarForegroundService.setHomeVisible(false);
        if(pardalRadar!=null)pardalRadar.stopSimulation();
        logField("APP", "pause");
        wifiAutoPausedAtElapsed = SystemClock.elapsedRealtime();
        if (snd == null || !snd.autoVolOn) stopGps();
        super.onPause();
    }
    @Override protected void onDestroy() {
        logField("APP", "destroy");
        if (rampTask != null) { // não deixa o volume preso no meio do fade/mudo
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, rampTarget, 0); } catch (Exception ignored) {}
            rampTask = null;
        }
        dismissQuick();
        try { if (eqDialog != null) eqDialog.dismiss(); } catch (Exception ignored) {}
        if (snd != null) snd.saveNow();
        if (jancarBridge != null) jancarBridge.stop();
        if (canbusReader != null) canbusReader.stop();
        if (obdReader != null) obdReader.stop();
        try { if (downloadReceiver != null) unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        if (mediaCtl != null) mediaCtl.unregisterCallback(mediaCallback);
        if (mediaSessionObserverRegistered && mediaManager != null) {
            try { mediaManager.removeOnActiveSessionsChangedListener(mediaSessionsChanged); }
            catch (Exception ignored) {}
            mediaSessionObserverRegistered = false;
        }
        wifiMonitorEpoch++;
        handler.removeCallbacksAndMessages(null);
        spotifyCacheWorker.shutdownNow();
        try { if (netCallback != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        stopGps();
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (pardalRadar != null && !RadarForegroundService.isRunning()) pardalRadar.close();
        if (alertTone != null) alertTone.release();
        if (tone != null) tone.release();
        super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 14) { refreshConnections(); startGps(); syncRadarService(); }
    }

    // ================================================================ v1.12
    private final Runnable shineTick = new Runnable() {
        public void run() {
            long m = SystemClock.uptimeMillis() % SHINE_PERIOD;
            // Atualiza a piscada vermelha da velocidade crítica, inclusive fora do brilho do logotipo.
            boolean criticalSpeedCard = topWidgetEnabled && speedVisualOn() && speedNow() > speedLimit * 1.15f;
            boolean speedCardShown = false;
            for (int id : topSlot) if (id == 0) { speedCardShown = true; break; }
            if (dashboard != null && (m < SHINE_MS || (criticalSpeedCard && speedCardShown))) dashboard.invalidateHeader();
            handler.postDelayed(this, m < SHINE_MS ? 65 : Math.min(500, SHINE_PERIOD - m));
        }
    };

    // ---- Alertas de velocidade (popup + som opcional) ----
    private void showAlert(int kind, String title, String sub, long ms, boolean sound) {
        alertKind = kind; alertTitle = title; alertSub = sub; alertStart = SystemClock.uptimeMillis(); alertUntil = alertStart + ms;
        if (sound && alertSound) alertBeep();
        if (dashboard != null) dashboard.invalidate();
        // Garante que o aviso desapareça mesmo que o GPS pare de enviar posições.
        handler.postDelayed(() -> {
            if (dashboard != null && SystemClock.uptimeMillis() >= alertUntil) dashboard.invalidate();
        }, ms + 80);
    }
    private void alertBeep() {
        try { if (alertTone == null) alertTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); alertTone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350); } catch (Exception ignored) {}
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
                    if (speedVisualOn()) showAlert(1,
                            "VOCÊ ESTÁ ACIMA DE " + speedLimit + " km/h",
                            "Velocidade atual: " + Math.round(v) + " km/h",
                            SPEED_ALERT_DURATION_MS, speedSoundOn());
                    else if (speedSoundOn() && alertSound) { alertKind = 1; alertBeep(); }
                }
            } else if (v <= speedLimit - 2) overNow = false;
        } else overNow = false;
    }


    /** Painel leve: aberto por toque do usuário, sem serviço adicional. */
    private void dismissQuick(){
        if(quickDialog!=null){Dialog d=quickDialog;quickDialog=null;try{d.dismiss();}catch(Exception ignored){}}
    }
    private Dialog quickPanel(String title,java.util.function.Consumer<LinearLayout> build){
        dismissQuick();
        Dialog d=new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=vertical();root.setPadding(dp(20),dp(14),dp(20),dp(16));root.setBackground(popBg(22));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=label(title,22,accent,true);heading.addView(name,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView close=label("✕",24,cText,true);close.setGravity(Gravity.CENTER);close.setBackground(panel(cSurf2,12));
        heading.addView(close,new LinearLayout.LayoutParams(dp(50),dp(46)));close.setOnClickListener(v->d.dismiss());
        root.addView(heading);divider(root);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        LinearLayout content=vertical();content.setPadding(dp(2),dp(5),dp(2),dp(10));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        build.accept(content);
        d.setContentView(root);
        quickDialog=d;d.setOnDismissListener(ignored->{if(quickDialog==d)quickDialog=null;});
        d.show();
        Window w=d.getWindow();if(w!=null){
            w.setBackgroundDrawableResource(android.R.color.transparent);
            android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
            w.setLayout(Math.min(dp(710),dm.widthPixels-dp(28)),Math.min(dp(560),dm.heightPixels-dp(24)));
            immersiveFix(w);
        }
        return d;
    }

    // Tipos especiais: -1 oculto, -2 relógio, -3 mídia e -4 clima.
    // As opções de toque mantêm poucos estados úteis para não exigir vários toques dirigindo.
    private String[] topMiniChoices() {
        String[] choices = new String[vehicleNames.length + 4];
        choices[0] = "Desligado";
        choices[1] = "Clima";
        choices[2] = "Música";
        choices[3] = "Relógio";
        System.arraycopy(vehicleNames, 0, choices, 4, vehicleNames.length);
        return choices;
    }
    private int topMiniIndex(int slot) {
        return slot == -1 ? 0 : slot == -4 ? 1 : slot == -3 ? 2 : slot == -2 ? 3 : slot + 4;
    }
    private int topMiniFromIndex(int index) {
        return index == 0 ? -1 : index == 1 ? -4 : index == 2 ? -3 : index == 3 ? -2 : index - 4;
    }
    private void setTopMiniCard(int slot, int value) {
        if(slot < 0 || slot >= topSlot.length) return;
        topSlot[slot] = value;
        getPreferences(0).edit().putInt("top_slot_" + slot, value).apply();
        if(dashboard != null) dashboard.invalidate();
    }
    private void cycleTopMiniCard(int slot) {
        if(slot < 0 || slot >= topSlot.length) return;
        final int[][] routes = {
            {-4, 6, 11, 0, 7},       // clima, bússola, sinal GPS, velocidade, altitude
            {-3, -2, 4, 3, 9, 1},   // música, hora, RPM, combustível, motor, autonomia
            {-2, 0, 7, 12, 13, 14} // relógio, velocidade, altitude, percurso GPS, médias
        };
        int[] route = routes[slot];
        int index = -1;
        for(int i=0;i<route.length;i++) if(route[i] == topSlot[slot]) { index=i;break; }
        int next = route[(index+1)%route.length];
        setTopMiniCard(slot,next);
        String label = next == -4 ? "Clima" : next == -3
                ? "Música" : next == -2 ? "Relógio" : vehicleNames[next];
        snack("Mini-card " + (slot+1) + ": " + label);
    }
    private void openConnectionQuickMenu(){
        if(drivingMode){snack("Configure as conexões com o carro parado");return;}
        quickPanel("CONEXÕES • WI-FI + BT",root->{
            section(root,"WI-FI","Status: "+(wifiConnected?"Internet via Wi-Fi":"Sem conexão Wi-Fi"));
            switchRow(root,"Ligar Wi-Fi ao iniciar",wifiAutoOn,(v,on)->{
                wifiAutoOn=on;getPreferences(0).edit().putBoolean("wifi_auto_on",on).apply();
                if(on) startWifiWakeMonitor("configuracao"); else wifiMonitorEpoch++;
            });
            LinearLayout wifiButtons=new LinearLayout(this);root.addView(wifiButtons);
            actionButton(wifiButtons,"Abrir Wi-Fi",()->{dismissQuick();openSystemSetting(Settings.ACTION_WIFI_SETTINGS);});
            actionButton(wifiButtons,"Testar ativação",()->tryAutoEnableWifi(true));
            divider(root);
            section(root,"BLUETOOTH","Estado: "+(btConnected?"Áudio conectado":"Sem áudio Bluetooth conectado"));
            LinearLayout bt=new LinearLayout(this);root.addView(bt);
            actionButton(bt,"Dispositivos",()->{dismissQuick();openSystemSetting(Settings.ACTION_BLUETOOTH_SETTINGS);});
            actionButton(bt,"Escolher OBD2",()->{dismissQuick();chooseObdAdapter();});
            root.addView(label("As ações do sistema podem abrir a tela nativa da Jancar. O launcher não troca sozinho o Bluetooth.",12,cMuted,false));
        });
    }
    private void openVehicleQuickMenu(){
        if(drivingMode){snack("Configure o veículo com o carro parado");return;}
        quickPanel("VEÍCULO • PC DE BORDO",root->{
            switchRow(root,"Mostrar card grande na Home",vehicleMenuEnabled,(v,on)->{
                vehicleMenuEnabled=on;getPreferences(0).edit().putBoolean("vehicle_menu",on).apply();
                refreshGpsNeed();if(dashboard!=null)dashboard.invalidate();
            });
            switchRow(root,"Mostrar mini-cards no topo",topWidgetEnabled,(v,on)->{
                topWidgetEnabled=on;getPreferences(0).edit().putBoolean("top_widget",on).apply();
                refreshGpsNeed();if(dashboard!=null)dashboard.invalidate();
            });
            choiceRow(root,"Cards do painel",new String[]{"1 card","2 cards","3 cards"},vehicleCardCount-1,idx->{
                vehicleCardCount=idx+1;getPreferences(0).edit().putInt("vehicle_cards_count",vehicleCardCount).apply();dashboard.invalidate();
            });
            divider(root);
            section(root,"INDICADORES SUPERIORES","Escolha o que mostrar em cada posição");
            String[] list=topMiniChoices();
            for(int i=0;i<3;i++){
                final int pos=i;
                choiceRow(root,"Mini-card "+(i+1),list,topMiniIndex(topSlot[i]),idx->{
                    setTopMiniCard(pos,topMiniFromIndex(idx));
                });
            }
            section(root,"CARDS DO PAINEL","Personalize as posições do PC de bordo");
            for(int i=0;i<vehicleCardSlots.length;i++){
                final int pos=i;
                choiceRow(root,"Card painel "+(i+1),vehicleNames,vehicleCardSlots[i],idx->{
                    vehicleCardSlots[pos]=idx;getPreferences(0).edit().putInt("vehicle_card_slot_"+pos,idx).apply();dashboard.invalidate();
                });
            }
            choiceRow(root,"Terceiro card • visual",new String[]{"Indicador normal","Carro completo","Apenas carro","Personalizado"},vehicleThirdVisualMode,idx->{
                vehicleThirdVisualMode=idx;getPreferences(0).edit().putInt("vehicle_third_visual_mode",idx).apply();dashboard.invalidate();
            });
            divider(root);
            switchRow(root,"Velocidade via GPS",speedPreferGps,(v,on)->{
                speedPreferGps=on;getPreferences(0).edit().putBoolean("speed_prefer_gps",on).apply();refreshGpsNeed();dashboard.invalidate();
            });
            choiceRow(root,"Fonte do motor",new String[]{"CANBUS Jancar", "OBD2 Bluetooth"},vehicleDataSource,this::useDataSource);
            choiceRow(root,"Alerta de velocidade",new String[]{"Sem avisos", "Visual", "Sonoro", "Visual + sonoro"},speedWarningMode,this::saveSpeedWarningMode);
            stepperRow(root,"Limite de velocidade",speedLimit,30,220,5," km/h",val->{
                speedLimit=val;getPreferences(0).edit().putInt("speed_limit",val).apply();dashboard.invalidate();
            });
            LinearLayout sourceButtons=new LinearLayout(this);root.addView(sourceButtons);
            actionButton(sourceButtons,"Diagnóstico CAN/OBD2",()->{dismissQuick();showVehicleSourceStatus();});
            actionButton(sourceButtons,"Parear OBD2",()->{dismissQuick();chooseObdAdapter();});
            LinearLayout buttons=new LinearLayout(this);root.addView(buttons);
            actionButton(buttons,"Ajustar velocidade",()->{dismissQuick();openSpeedCardMenu();});
            actionButton(buttons,"Mais configurações",()->{dismissQuick();settings(3);});
            LinearLayout extra=new LinearLayout(this);root.addView(extra);
            actionButton(extra,"App veículo Jancar",()->{dismissQuick();openJancarApp(true);});
            actionButton(extra,"Zerar km da sessão",()->{resetGpsTrip();dashboard.invalidate();});
        });
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
        if (!fadeBoot || userMuted || audio == null || audio.isVolumeFixed()) return;
        fadePending = true; fadeWindowUntil = SystemClock.elapsedRealtime() + 120000L;
        fadeFromZero();
    }
    /** Se o Spotify só começar a tocar depois da rampa (ex.: retoma após o Bluetooth), repete o fade uma vez. */
    private void trackPlaybackFade() {
        boolean now = mediaPlaying;
        if (now && !wasPlaying && fadePending) {
            // Não executar um segundo fade durante a transição PAUSADO → TOCANDO:
            // a retomada da sessão pode coincidir com o áudio ser roteado pela MCU.
            fadePending = false;
        }
        wasPlaying = now;
    }
    private void registerScreenReceiver() {
        try {
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(a) && snd != null) snd.start();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                        screenOffAt = SystemClock.elapsedRealtime();
                        wifiAutoAttemptedThisWake = false;
                        wifiMonitorEpoch++; // interrompe verificações do ciclo anterior
                        logField("ENERGIA", "screen_off");
                    } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                        logField("ENERGIA", "screen_on");
                        appliedBrightnessPct = -1;
                        applyBrightness();
                        long sleepMs = screenOffAt > 0 ? SystemClock.elapsedRealtime() - screenOffAt : 0;
                        if (sleepMs > 20000L) { screenOffAt = 0; armStartupFade(); }
                        if (wifiAutoOn && sleepMs > 20000L)
                            startWifiWakeMonitor("screen_on");
                    }
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

    // Antigo modo direção desativado permanentemente: não bloquear toques ou menus.
    // Mantemos o método porque o monitor periódico já o chama.
    private void updateDrivingMode() { }

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

    private void openJancarSound() {
        Intent query=new Intent(Intent.ACTION_MAIN);query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps=getPackageManager().queryIntentActivities(query,0);
        if(apps!=null)for(android.content.pm.ResolveInfo info:apps){
            String name=info.loadLabel(getPackageManager()).toString().toLowerCase(Locale.ROOT);
            String pkg=info.activityInfo.packageName.toLowerCase(Locale.ROOT);
            if(name.equals("eq")||name.contains("equalizador")||name.contains("audio settings")||pkg.contains("audio.settings")){
                try{
                    Intent it=new Intent(query);it.setClassName(info.activityInfo.packageName,info.activityInfo.name);
                    startActivity(it);return;
                }catch(Exception ignored){}
            }
        }
        snack("Ajustes de som da Jancar não encontrados. Abra o EQ original em Apps.");
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
        String[] options={"Personalizar 2 ou 3 cards", "Exibir/ocultar painel do veículo", "Abrir informações da Jancar", "Configurar fonte CANBUS / OBD2"};
        LinearLayout buttons=vertical();buttons.setPadding(dp(20),dp(14),dp(20),dp(14));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("PC DE BORDO • PEUGEOT")
            .setView(buttons).setNegativeButton("Fechar",null).create();
        for(int i=0;i<options.length;i++) {
            final int item=i; Button btn=new Button(this);btn.setText(options[i]);btn.setAllCaps(false);
            btn.setTextColor(cText);btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(cSurf2));
            buttons.addView(btn,new LinearLayout.LayoutParams(-1,dp(52)));
            btn.setOnClickListener(v->{dialog.dismiss();
               if(item==0){settings(3);}
               else if(item==1){vehicleMenuEnabled=!vehicleMenuEnabled;getPreferences(0).edit().putBoolean("vehicle_menu",vehicleMenuEnabled).apply();refreshGpsNeed();dashboard.invalidate();}
               else if(item==2){openJancarApp(true);}else{settings(3);}
            });
        }
        dialog.show();
    }
    /** Letras sincronizadas LRC: segue a posição da sessão Spotify sem serviço adicional. */
    private void showTimedLyrics(final Dialog dialog, final TextView content, final ScrollView scrolling,
            final List<SyncedLyrics.Line> timed, final String title, final String artist) {
        if(timed==null || timed.isEmpty())return;
        final StringBuilder full=new StringBuilder();
        final int[] starts=new int[timed.size()],ends=new int[timed.size()];
        for(int i=0;i<timed.size();i++) {
            starts[i]=full.length();
            String line=timed.get(i).text;
            full.append(line.isEmpty()?"♪":line).append('\n');
            ends[i]=full.length()-1;
        }
        final int[] last={Integer.MIN_VALUE};
        final Runnable[] update={null};
        update[0] = () -> {
            if(!dialog.isShowing() || !title.equals(songTitle) || !artist.equals(songArtist))return;
            int current=SyncedLyrics.activeLine(timed,livePosition());
            if(current!=last[0]) {
                last[0]=current;
                SpannableStringBuilder styled=new SpannableStringBuilder(full.toString());
                for(int i=0;i<timed.size();i++) {
                    int color=i==current?accent:i<current?cDim:cText;
                    styled.setSpan(new ForegroundColorSpan(color),starts[i],ends[i],Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if(i==current)styled.setSpan(new StyleSpan(Typeface.BOLD),starts[i],ends[i],Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                content.setText(styled);
                if(current>=0) {
                    final int offset=starts[current];
                    scrolling.post(() -> {
                        if(!dialog.isShowing() || content.getLayout()==null)return;
                        int line=content.getLayout().getLineForOffset(offset);
                        int target=Math.max(0,content.getLayout().getLineTop(line)-scrolling.getHeight()/3);
                        scrolling.smoothScrollTo(0,target);
                    });
                }
            }
            handler.postDelayed(update[0],350L);
        };
        dialog.setOnDismissListener(d -> handler.removeCallbacks(update[0]));
        update[0].run();
    }
    /** Janela estilizada: LRC sincronizado quando disponível; caso contrário texto comum. */
    private void openLyrics() {
        final String title=songTitle==null?"":songTitle.trim();
        final String artist=songArtist==null?"":songArtist.trim();
        final String key=title+" • "+artist;
        final boolean ownMatch=title.equalsIgnoreCase(userLyricsSong) && !userLyrics.trim().isEmpty();
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=vertical();root.setBackground(popBg(22));
        root.setPadding(dp(26),dp(20),dp(26),dp(18));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView=label("♫  LETRAS",24,accent,true);
        heading.addView(titleView,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView close=label("✕",23,cText,true);close.setGravity(Gravity.CENTER);close.setBackground(panel(cSurf2,12));
        heading.addView(close,new LinearLayout.LayoutParams(dp(49),dp(45)));close.setOnClickListener(v->dialog.dismiss());
        root.addView(heading);
        TextView song=label(title,20,cText,true);song.setSingleLine(true);
        song.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(song);
        TextView performer=label(artist,16,cMuted,false);performer.setSingleLine(true);
        performer.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(performer);
        View line=new View(this);line.setBackgroundColor(cHair);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));
        lp.setMargins(0,dp(15),0,dp(10));root.addView(line,lp);
        ScrollView scrolling=new ScrollView(this);scrolling.setFillViewport(false);
        TextView content=label("",18,cText,false);content.setGravity(Gravity.TOP);
        content.setLineSpacing(dp(7),1.20f);content.setPadding(dp(10),dp(12),dp(10),dp(18));
        scrolling.addView(content);root.addView(scrolling,new LinearLayout.LayoutParams(-1,0,1));
        TextView source=label("",12,cMuted,false);root.addView(source);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(58)));
        actionButton(actions,"Abrir Spotify",()->{dialog.dismiss();launch(2);});
        actionButton(actions,"Inserir letra",()->{
            dialog.dismiss();
            popupInput("Letras autorizadas","Cole texto de sua autoria ou autorizado",ownMatch?userLyrics:"",v->{
                userLyrics=v;userLyricsSong=title;
                getPreferences(0).edit().putString("lyrics_text",v).putString("lyrics_song",title).apply();
            });
        });
        dialog.setContentView(root);showBig(dialog,.72f,.82f);
        if(ownMatch) {content.setText(userLyrics);source.setText("Texto pessoal • sem sincronização");return;}
        if(title.isEmpty() || title.startsWith("Nenhuma música") || artist.isEmpty()) {
            content.setText("Abra o Spotify e toque uma música. Permita o acesso às notificações para reconhecer a faixa.");return;
        }
        if(key.equals(lyricsCacheKey) && !lyricsCacheText.isEmpty()) {
            List<SyncedLyrics.Line> timed=SyncedLyrics.parse(lyricsCacheSync);
            if(!timed.isEmpty()) {source.setText("LRCLIB • acompanhando a música");showTimedLyrics(dialog,content,scrolling,timed,title,artist);}
            else {content.setText(lyricsCacheText);source.setText("LRCLIB • letra sem marcações de tempo");}
            return;
        }
        content.setText("Buscando letra na LRCLIB…\nRequer internet.");
        new Thread(()->{
            String result=null,synced="";
            try {
                String url="https://lrclib.net/api/get?track_name="+URLEncoder.encode(title,"UTF-8")+
                    "&artist_name="+URLEncoder.encode(artist,"UTF-8");
                JSONObject data=readJson(url);
                if(!data.optBoolean("instrumental",false)) {
                    synced=data.optString("syncedLyrics","");
                    if("null".equals(synced))synced="";
                    result=data.optString("plainLyrics","");
                    if(result.isEmpty()||"null".equals(result))result=plainFromLrc(synced);
                } else result="Faixa instrumental (sem letra).";
            } catch(Exception ignored) {}
            final String fetched=result, syncedLrc=synced;
            handler.post(()->{
                if(!dialog.isShowing())return;
                if(fetched==null||fetched.trim().isEmpty()) {
                    content.setText("Letra não encontrada ou serviço indisponível.\n\nTente abrir no Spotify ou inserir um texto autorizado.");
                } else {
                    lyricsCacheKey=key;lyricsCacheText=fetched;lyricsCacheSync=syncedLrc;
                    List<SyncedLyrics.Line> timed=SyncedLyrics.parse(syncedLrc);
                    if(!timed.isEmpty()) {source.setText("LRCLIB • acompanhando a música");showTimedLyrics(dialog,content,scrolling,timed,title,artist);}
                    else {content.setText(fetched);source.setText("LRCLIB • letra sem marcações de tempo");}
                }
            });
        },"PugLyrics").start();
    }
    private String plainFromLrc(String lrc) {
        if(lrc==null || lrc.trim().isEmpty()) return "";
        return lrc.replaceAll("(?m)\\[\\d{1,2}:\\d{2}(?:\\.\\d+)?\\]", "").trim();
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
                        float w=getWidth(),h=getHeight();
                        ThemePalette shown=ThemePalette.create(idx,themeMode,isNightByClock(),PRESET_ACCENTS[idx]);
                        int bg=shown.bg0, light=shown.accent;
                        preview.setColor(bg);cv.drawRoundRect(0,0,w,h,dp(5),dp(5),preview);
                        preview.setColor(shown.head);cv.drawRoundRect(0,0,w,h*.14f,dp(2),dp(2),preview);
                        preview.setColor(light);
                        cv.drawRoundRect(w*.06f,h*.10f,w*.94f,h*.22f,dp(2),dp(2),preview);
                        preview.setColor(shown.surfTop);
                        for(int z=0;z<3;z++)cv.drawRoundRect(w*(.06f+.30f*z),h*.30f,w*(.29f+.30f*z),h*.84f,dp(2),dp(2),preview);
                        preview.setColor(light);cv.drawRoundRect(w*.12f,h*.53f,w*.23f,h*.57f,dp(1),dp(1),preview);
                        cv.drawRoundRect(w*.42f,h*.67f,w*.52f,h*.71f,dp(1),dp(1),preview);
                        cv.drawCircle(w*.76f,h*.53f,dp(2),preview);
                        preview.setColor(sel?accent:shown.hair);preview.setStyle(Paint.Style.STROKE);preview.setStrokeWidth(dp(sel?2:1));
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
            if (audio == null) return;
            if (audio.isVolumeFixed()) { audioStatus = "Android informa volume fixo: controle da MCU não disponível"; snack("O volume real está sob controle da Jancar/MCU"); return; }
            // No MUDO só preparamos o volume futuro. Não desligamos o mudo sem querer.
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (userMuted) {
                previousVolume = Math.max(1, Math.min(max, previousVolume + delta));
                if (dashboard != null) dashboard.invalidate();
                return;
            }
            cancelRamp();
            int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            int after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (after == before && ((delta > 0 && before < max) || (delta < 0 && before > 0))) {
                // Algumas centrais ignoram adjustStreamVolume mas aceitam setStreamVolume.
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, before + delta)), 0);
                after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
            audioStatus = after != before ? "Volume de mídia Android alterado: " + before + " → " + after
                    : "Android não alterou o volume; possível controle exclusivo da MCU";
            if (after > 0) previousVolume = after;
            if (snd != null) snd.volumeChanged();
            if (after == before && before > 0 && before < max) snack("Volume Android sem mudança. Consulte Config. > Som > Diagnóstico");
            showVolume();
        } catch (Exception e) { audioStatus = "Erro de áudio: " + e.getClass().getSimpleName(); snack("Volume indisponível"); }
    }
    /** Mudo com aviso persistente: descida curta e retorno gradual de 1,5 segundo. */
    private void toggleMute() {
        try {
            cancelRamp();
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (audio.isVolumeFixed()) { audioStatus = "Volume fixo na Jancar/MCU"; snack("Mudo Android indisponível nesta central"); return; }
            if (!userMuted && v > 0) {
                previousVolume = v;
                userMuted = true;
                showVolume();
                audioStatus = "Mudo Android acionado; MCU pode ter controle independente";
                rampVolume(0, 450, null);
            } else {
                userMuted = false;
                // O MUDO só desaparece depois que começa a restauração gradual.
                audioStatus = "Retornando volume de mídia Android";
                rampVolume(Math.max(1, previousVolume), 1500, () -> showVolume());
                showVolume();
            }
        } catch (Exception ignored) {}
    }
    private void rampVolume(final int target, final int totalMs, final Runnable done) {
        cancelRamp();
        rampTarget = target;
        final int from = audio.getStreamVolume(AudioManager.STREAM_MUSIC), steps = Math.abs(target - from);
        if (steps == 0) { if (done != null) done.run(); return; }
        final int dir = target > from ? 1 : -1;
        final double e = dir > 0 ? 1.6 : 1.0; // subida suave, começa devagar
        rampTask = new Runnable() {
            int k = 0;
            public void run() {
                k++;
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, from + dir * k, 0);
                    if (snd != null) snd.volumeChanged();
                } catch (Exception ignored) {}
                if (dashboard != null) dashboard.invalidate();
                if (k >= steps) { rampTask = null; if (done != null) done.run(); return; }
                double tNow = totalMs * Math.pow((double) k / steps, 1.0 / e);
                double tNext = totalMs * Math.pow((double) (k + 1) / steps, 1.0 / e);
                handler.postDelayed(this, Math.max(30, (long) (tNext - tNow)));
            }
        };
        handler.postDelayed(rampTask, 30);
    }
    private void cancelRamp() { if (rampTask != null) { handler.removeCallbacks(rampTask); rampTask = null; } }
    /** Indicador normal temporário; painel MUDO permanece fixo até desativação explícita. */
    private void showVolume() {
        volumeVisible = true;
        volumeUntil = SystemClock.uptimeMillis() + 2000;
        if (dashboard != null) dashboard.invalidate();
    }
    private boolean isMuted() { return userMuted || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0; }

    // ---------------------------------------------------------------- mídia (Spotify primeiro; senão qualquer sessão ativa)
    private void ensureMediaSessionObserver() {
        if (mediaManager == null || mediaSessionObserverRegistered) return;
        try {
            mediaManager.addOnActiveSessionsChangedListener(mediaSessionsChanged,
                    new ComponentName(this, SpotifyNotificationListener.class), handler);
            mediaSessionObserverRegistered = true;
        } catch (SecurityException ignored) { // usuário ainda não liberou acesso às notificações
        } catch (Exception ignored) { // firmware pode restringir os ouvintes; fallback periódico continua
        }
    }
    /** Restaura um rascunho local da última música enquanto o serviço Spotify reconecta. */
    private void restoreLastSpotifyPreview() {
        SharedPreferences prefs = getPreferences(0);
        lastSpotifyTitle = prefs.getString("spotify_cache_title", "");
        lastSpotifyArtist = prefs.getString("spotify_cache_artist", "");
        if (lastSpotifyTitle.isEmpty()) return;
        showLastSpotifyPreview();
        spotifyCacheWorker.execute(() -> {
            Bitmap bmp = null;
            try {
                File f = new File(getFilesDir(), "spotify_last_cover.jpg");
                if (f.isFile() && f.length() > 0 && f.length() < 300_000)
                    bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            } catch (Exception ignored) { }
            final Bitmap loaded = bmp;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (loaded != null) {
                    lastSpotifyArt = loaded;
                    if (cachedSpotifyPreview && mediaCtl == null) {
                        albumArt = loaded;
                        artKey = "";
                        maybeUpdateArtColor();
                        if (dashboard != null) dashboard.invalidate();
                    }
                }
            });
        });
    }
    private void showLastSpotifyPreview() {
        cachedSpotifyPreview = true;
        mediaPlaying = false;
        mediaIsSpotify = true;
        mediaSource = lastSpotifyTitle.isEmpty() ? "Spotify" : "Spotify • última música";
        songTitle = lastSpotifyTitle.isEmpty() ? "Nenhuma música em reprodução" : lastSpotifyTitle;
        songArtist = lastSpotifyTitle.isEmpty() ? "Abra o Spotify para começar" : lastSpotifyArtist;
        albumArt = lastSpotifyArt;
        songDuration = songPosBase = songPosStamp = 0;
        songSpeed = 0;
    }
    /** Compacta a capa para 320px em uma fila de I/O, apenas quando o título muda. */
    private void cacheCurrentSpotify(String title, String artist, Bitmap artwork) {
        if (title == null || title.isEmpty()) return;
        String key = title + "\u0001" + artist;
        boolean newTrack = !key.equals(lastSpotifyQueued);
        lastSpotifyTitle = title;
        lastSpotifyArtist = artist;
        if (artwork != null || newTrack) lastSpotifyArt = artwork;
        if (!newTrack && (artwork == null || lastSpotifyQueuedHasArt)) return;
        lastSpotifyQueued = key;
        lastSpotifyQueuedHasArt = artwork != null;
        spotifyCacheWorker.execute(() -> {
            File dst = new File(getFilesDir(), "spotify_last_cover.jpg");
            File tmp = new File(getFilesDir(), "spotify_last_cover.jpg.tmp");
            try {
                if (artwork != null && !artwork.isRecycled()) {
                    int edge = Math.min(artwork.getWidth(), artwork.getHeight());
                    if (edge > 0) {
                        Bitmap scaled = Bitmap.createScaledBitmap(artwork, 320, 320, true);
                        try (FileOutputStream out = new FileOutputStream(tmp)) {
                            scaled.compress(Bitmap.CompressFormat.JPEG, 78, out);
                        }
                        if (scaled != artwork) scaled.recycle();
                        if (!tmp.renameTo(dst)) {
                            if (dst.exists()) dst.delete();
                            if (!tmp.renameTo(dst)) throw new IOException("Falha ao salvar capa");
                        }
                    }
                } else if (dst.exists()) {
                    dst.delete(); // não manter capa da faixa anterior com um título novo.
                }
                getPreferences(0).edit()
                        .putString("spotify_cache_title", title)
                        .putString("spotify_cache_artist", artist).apply();
            } catch (Exception ignored) { tmp.delete(); }
        });
    }

    /** Atualiza apenas o estado da sessão atual, sem obter lista de sessões novamente. */
    private void applyCurrentMedia(MediaController controller, MediaMetadata m, PlaybackState state) {
        if (controller == null) return;
        String oldTitle = songTitle, oldArtist = songArtist, oldSource = mediaSource;
        boolean oldPlaying = mediaPlaying, oldCached = cachedSpotifyPreview;
        mediaIsSpotify = "com.spotify.music".equals(controller.getPackageName());
        mediaSource = mediaIsSpotify ? "Spotify" : appLabel(controller.getPackageName());
        cachedSpotifyPreview = false;
        boolean artworkChanged = false;
        if (m != null) {
            String nextTitle = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String nextArtist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            nextTitle = nextTitle == null || nextTitle.isEmpty() ? mediaSource : nextTitle;
            nextArtist = nextArtist == null ? "" : nextArtist;
            boolean trackChanged = !nextTitle.equals(songTitle) || !nextArtist.equals(songArtist) || oldCached;
            songTitle = nextTitle;
            songArtist = nextArtist;
            Bitmap image = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (image == null) image = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (image == null && mediaIsSpotify && nextTitle.equals(lastSpotifyTitle)
                    && nextArtist.equals(lastSpotifyArtist)) image = lastSpotifyArt;
            // Alguns firmwares emitem a NOVA música com a capa da faixa anterior.
            // Não reutilizar o mesmo bitmap na transição até chegar a arte correta.
            if(trackChanged && !oldCached && image != null && image == albumArt)
                image = null;
            // Não reatribuir a mesma capa a cada evento de posição: menos carga na GPU.
            if (trackChanged || (image != null && albumArt != image)) {
                artworkChanged = albumArt != image;
                albumArt = image; // faixa nova sem capa: nunca mostrar capa antiga
            }
            if (mediaIsSpotify && !nextTitle.equals("Spotify") &&
                    (trackChanged || (image != null && !lastSpotifyQueuedHasArt)))
                cacheCurrentSpotify(nextTitle, nextArtist, image);
            songDuration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } else if (oldCached) {
            // A sessão foi encontrada, mas ainda não enviou metadados: não promover
            // uma prévia antiga a música atual.
            songTitle = mediaSource;
            songArtist = "Aguardando informações da música";
            albumArt = null;
            songDuration = 0;
        }
        mediaPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        int playStateCode = state == null ? -1 : state.getState();
        if (playStateCode != lastLogMediaState) {
            lastLogMediaState = playStateCode;
            logField("SPOTIFY", "sessionState="+playStateCode+" sourceSpotify="+mediaIsSpotify+" "+audioSnapshot());
        }
        if (state != null) {
            songPosBase = Math.max(0, state.getPosition());
            songPosStamp = state.getLastPositionUpdateTime();
            songSpeed = state.getPlaybackSpeed();
        } else { songPosBase = 0; songPosStamp = 0; songSpeed = 0; }
        trackPlaybackFade();
        if (artworkChanged) { artKey = ""; maybeUpdateArtColor(); }
        boolean significant = oldCached || oldPlaying != mediaPlaying ||
                !oldTitle.equals(songTitle) || !oldArtist.equals(songArtist) ||
                !oldSource.equals(mediaSource) || artworkChanged;
        if (dashboard != null) {
            if (significant) dashboard.invalidate();
            else dashboard.invalidateMediaProgress();
        }
    }
    private void refreshCurrentMedia() {
        MediaController current = mediaCtl;
        if (current == null) return;
        try { applyCurrentMedia(current, current.getMetadata(), current.getPlaybackState()); }
        catch (Exception ignored) { /* sessão encerrou; o observador descobrirá outra */ }
    }
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
            // getActiveSessions pode criar novos objetos MediaController para a mesma sessão.
            // Não remover/registrar o callback a cada varredura: comparar o SessionToken.
            boolean sameSession = false;
            if (mediaCtl != null && found != null) {
                try { sameSession = mediaCtl.getSessionToken().equals(found.getSessionToken()); }
                catch (Exception ignored) {}
            }
            if (!sameSession) {
                if (mediaCtl != null) mediaCtl.unregisterCallback(mediaCallback);
                mediaCtl = found;
                if (found != null) found.registerCallback(mediaCallback, handler);
            }
            if (mediaCtl != null) applyCurrentMedia(mediaCtl, mediaCtl.getMetadata(), mediaCtl.getPlaybackState());
            else if (!cachedSpotifyPreview) {
                showLastSpotifyPreview(); // não exibir como reprodução atual
                trackPlaybackFade();
                artKey = "";
                maybeUpdateArtColor();
                if (dashboard != null) dashboard.invalidate();
            }
        } catch (SecurityException ex) {
            mediaPermission = false; mediaCtl = null;
            songTitle = "Ative o acesso às notificações";
            songArtist = "Para mostrar a música em reprodução";
        } catch (Exception ignored) {}
    }
    /** Diagnóstico manual não intrusivo: nunca envia play/pause nem modifica volume. */
    private void diagnoseSpotifyNow() {
        updateMedia();
        String source = mediaCtl == null ? "sem_sessao" :
                ("com.spotify.music".equals(mediaCtl.getPackageName()) ? "spotify" : "outro_player");
        int state = -1;
        try { if (mediaCtl != null && mediaCtl.getPlaybackState() != null)
            state = mediaCtl.getPlaybackState().getState(); } catch (Exception ignored) {}
        String report = "fonte="+source+" estado="+state+" barra_avanca="+mediaPlaying+
                " duracaoMs="+songDuration+" " +audioSnapshot();
        logField("SPOTIFY_DIAG", report);
        new AlertDialog.Builder(this).setTitle("Diagnóstico Spotify")
                .setMessage(report+"\n\nO Android pode indicar reprodução mesmo quando o amplificador da central está sem som.")
                .setPositiveButton("OK", null).show();
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
        // Retorno visual rápido; não aguarda a próxima varredura geral (até 15s).
        // O callback do Spotify é a fonte preferencial; estes são só fallbacks leves.
        handler.postDelayed(this::refreshCurrentMedia, 320);
        if (dashboard != null) dashboard.invalidate();
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
    private final HashMap<String, String> labelCache = new HashMap<>();
    private void loadAppPersonalization(SharedPreferences prefs) {
        appOrder.clear(); appAliases.clear();
        try {
            JSONArray order = new JSONArray(prefs.getString("app_custom_order", "[]"));
            for (int i=0;i<order.length();i++) { String pkg=order.optString(i, "");
                if (!pkg.isEmpty() && !appOrder.contains(pkg)) appOrder.add(pkg); }
            JSONObject aliases = new JSONObject(prefs.getString("app_custom_names", "{}"));
            Iterator<String> keys=aliases.keys();
            while (keys.hasNext()) {String pkg=keys.next(), name=aliases.optString(pkg, "").trim();
                if (!name.isEmpty()) appAliases.put(pkg,name);}
        } catch (Exception ignored) { appOrder.clear(); appAliases.clear(); }
    }
    private void saveAppPersonalization() {
        JSONArray order = new JSONArray(); for(String pkg:appOrder) order.put(pkg);
        JSONObject names = new JSONObject();
        try { for(Map.Entry<String,String> e:appAliases.entrySet()) names.put(e.getKey(),e.getValue()); }
        catch (JSONException ignored) {}
        getPreferences(0).edit().putString("app_custom_order",order.toString())
                .putString("app_custom_names",names.toString()).apply();
        if (dashboard != null) dashboard.invalidate();
    }
    private String displayAppLabel(String pkg,String original) {
        String alias=appAliases.get(pkg);
        return alias!=null && !alias.trim().isEmpty() ? alias : original;
    }
    private void ensureAppOrder(List<android.content.pm.ResolveInfo> apps) {
        boolean changed=false;
        for (android.content.pm.ResolveInfo info:apps) {
            String pkg=info.activityInfo.packageName;
            if (!appOrder.contains(pkg)) { appOrder.add(pkg); changed=true; }
        }
        if(changed) saveAppPersonalization();
    }
    private void sortAppList(ArrayList<android.content.pm.ResolveInfo> apps) {
        Collections.sort(apps, (a,b) -> {
            int ai=appOrder.indexOf(a.activityInfo.packageName), bi=appOrder.indexOf(b.activityInfo.packageName);
            if(ai>=0 && bi>=0 && ai!=bi) return Integer.compare(ai,bi);
            if(ai>=0 && bi<0) return -1;
            if(bi>=0 && ai<0) return 1;
            return a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString());
        });
    }
    /** Lista curta com a mesma paleta da Home, inclusive nos temas claros e coloridos. */
    private void themedChoices(String title, String[] options, java.util.function.IntConsumer selected) {
        if (drivingMode) { snack("Personalize com o carro parado"); return; }
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=vertical();root.setPadding(dp(23),dp(18),dp(23),dp(18));
        root.setBackground(panel(cBg1,20));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView textTitle=label(title,20,cText,true);textTitle.setSingleLine(true);
        textTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(textTitle,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView close=label("✕",23,cText,true);close.setGravity(Gravity.CENTER);
        close.setBackground(panel(cSurf2,12));
        heading.addView(close,new LinearLayout.LayoutParams(dp(45),dp(44)));
        close.setOnClickListener(v->dialog.dismiss());root.addView(heading);
        divider(root);
        ScrollView scroll=new ScrollView(this);
        LinearLayout rows=vertical();rows.setPadding(0,dp(2),0,dp(4));scroll.addView(rows);
        for(int i=0;i<options.length;i++){
            final int which=i;
            TextView choice=label(options[i],16,cText,false);
            choice.setBackground(panel(cSurf2,12));choice.setPadding(dp(16),dp(9),dp(14),dp(9));
            choice.setMaxLines(2);choice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(52));rp.bottomMargin=dp(7);
            rows.addView(choice,rp);
            choice.setOnClickListener(v->{feedback();dialog.dismiss();selected.accept(which);});
        }
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(root);
        activeDialog=dialog;dialog.setOnDismissListener(d->{if(activeDialog==dialog)activeDialog=null;});
        showBig(dialog,.62f,.78f);
    }

    private void appOptions(String pkg,String original,ArrayList<android.content.pm.ResolveInfo> apps,Runnable render) {
        String shown=displayAppLabel(pkg,original);
        boolean favorite=pinnedPackages.contains(pkg);
        String[] actions=favorite ? new String[]{"Renomear atalho", "Restaurar nome original",
                "Subir no menu Apps", "Descer no menu Apps", "Mover favorito para a esquerda", "Mover favorito para a direita"}
                : new String[]{"Renomear atalho", "Restaurar nome original",
                "Subir no menu Apps", "Descer no menu Apps"};
        themedChoices(shown,actions,which->{
            if (which==0) {
                promptText("Nome curto do aplicativo", original, shown, value->{
                    String name=value.trim();
                    if(name.length()>25) name=name.substring(0,25);
                    if(name.isEmpty() || name.equals(original)) appAliases.remove(pkg);
                    else appAliases.put(pkg,name);
                    saveAppPersonalization();render.run();
                }); return;
            }
            if(which==1) appAliases.remove(pkg);
            if(which==2 || which==3) {
                int at=appOrder.indexOf(pkg),to=at+(which==2?-1:1);
                if(at>=0 && to>=0 && to<appOrder.size()) Collections.swap(appOrder,at,to);
            }
            if(which==4 || which==5) {
                int at=pinnedPackages.indexOf(pkg),to=at+(which==4?-1:1);
                if(at>=0 && to>=0 && to<pinnedPackages.size()) {
                    Collections.swap(pinnedPackages,at,to);savePinned();
                }
            }
            saveAppPersonalization();sortAppList(apps);render.run();
        });
    }
    private String appLabel(String pkg) {
        if(pkg==null || pkg.isEmpty())return "Aplicativo";
        String alias=appAliases.get(pkg); if(alias!=null && !alias.isEmpty()) return alias;
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label;
        try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception ex) { return pkg; } // falha não entra no cache: o app pode ser instalado depois
        labelCache.put(pkg, label);
        return label;
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
        ensureAppOrder(apps); sortAppList(apps);
        LinearLayout root = vertical(); root.setPadding(dp(20), dp(14), dp(20), dp(16)); root.setBackground(panel(cBg1, 20));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("APLICATIVOS", 22, cText, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView close = label("✕", 22, cText, true); close.setGravity(Gravity.CENTER); close.setBackground(panel(cSurf2, 12));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44))); root.addView(header);
        divider(root);
        root.addView(label("Toque para abrir • ☆ favorito • ⋮ renomear / ordenar • MIN/ORIG ícone", 13, cMuted, false), new LinearLayout.LayoutParams(-1, dp(33)));
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
                String original = info.loadLabel(getPackageManager()).toString();
                String name = displayAppLabel(pkg,original);
                if (!name.toLowerCase(Locale.getDefault()).contains(filter) &&
                        !original.toLowerCase(Locale.getDefault()).contains(filter)) continue;
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
                item.addView(star, new LinearLayout.LayoutParams(dp(49), dp(48)));
                TextView options=label("⋮",28,accent,true);
                options.setGravity(Gravity.CENTER); options.setBackground(panel(cSurf2,12));
                LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(dp(45),dp(48));op.setMargins(dp(5),0,0,0);
                item.addView(options,op);
                options.setOnClickListener(v -> appOptions(pkg,original,apps,render[0]));
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

    private void exportFieldLog() {
        if(fieldLog==null){snack("Log indisponível");return;}
        try {Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,"peugeot308-log-"+System.currentTimeMillis()+".txt");startActivityForResult(i,REQ_EXPORT_LOG);
        }catch(Exception e){snack("Não foi possível abrir o salvamento");}
    }
    private void confirmDeleteFieldLog(){
        new AlertDialog.Builder(this).setTitle("Apagar log?")
            .setMessage("Remove o histórico deste launcher. A gravação, se ativa, continuará com um arquivo novo.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Apagar",(d,w)->{if(fieldLog!=null){fieldLog.clear();logField("APP","log_limpo");snack("Histórico apagado");}}).show();
    }
    private static boolean allowedBackupKey(String key) {
        return key.startsWith("theme") || key.startsWith("bright") || key.startsWith("top_")
            || key.startsWith("dock_") || key.startsWith("vehicle_card_") || key.startsWith("vehicle_third_visual")
            || key.equals("vehicle_cards_count") || key.equals("vehicle_menu") || key.equals("album_bg")
            || key.equals("minimal_icons") || key.equals("immersive_idle") || key.equals("weather_motion")
            || key.equals("speed_prefer_gps") || key.equals("standalone_vehicle_button")
            || key.equals("pinned_apps") || key.equals("original_app_icons") || key.equals("fade_boot");
    }
    private String visualBackupJson() throws JSONException {
        JSONObject o=new JSONObject();o.put("format","p308-visual-v1");JSONObject settings=new JSONObject();
        for(Map.Entry<String,?> e:getPreferences(0).getAll().entrySet()) {
            if(!allowedBackupKey(e.getKey()))continue;
            Object v=e.getValue();if(v instanceof Boolean || v instanceof Number || v instanceof String)settings.put(e.getKey(),v);
        }o.put("prefs",settings);return o.toString(2);
    }
    private void exportVisualBackup(){try{
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"peugeot308-config.json");startActivityForResult(i,REQ_BACKUP);
    }catch(Exception e){snack("Não foi possível abrir o salvamento");}}
    private void importVisualBackup(){try{
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");startActivityForResult(i,REQ_RESTORE);
    }catch(Exception e){snack("Não foi possível abrir o arquivo");}}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(requestCode==REQ_EXPORT_LOG || requestCode==REQ_BACKUP){
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IOException("Destino inválido");
                String content=requestCode==REQ_EXPORT_LOG ? fieldLog.exportText(safeFieldReport()) : visualBackupJson();
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();
                snack(requestCode==REQ_EXPORT_LOG?"Log exportado":"Configuração salva");
            }catch(Exception e){snack("Erro ao exportar arquivo");logField("ARQUIVO","falha_exportacao");}
        } else if(requestCode==REQ_RESTORE){
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException("Arquivo inválido");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
                while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>100000)throw new IOException("Arquivo muito grande");}
                JSONObject source=new JSONObject(bytes.toString("UTF-8"));
                if(!"p308-visual-v1".equals(source.optString("format")))throw new JSONException("Formato incorreto");
                JSONObject cfg=source.getJSONObject("prefs");
                new AlertDialog.Builder(this).setTitle("Restaurar aparência?")
                    .setMessage("As preferências visuais atuais serão substituídas. O aplicativo deve ser reiniciado depois.")
                    .setNegativeButton("Cancelar",null)
                    .setPositiveButton("Restaurar",(d,w)->{
                        SharedPreferences.Editor editor=getPreferences(0).edit();
                        Iterator<String> keys=cfg.keys();int total=0;
                        while(keys.hasNext()){
                            String key=keys.next();if(!allowedBackupKey(key))continue;
                            Object value=cfg.opt(key);
                            if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
                            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
                            else if(value instanceof Long)editor.putLong(key,(Long)value);
                            else if(value instanceof String)editor.putString(key,(String)value);
                            else continue;total++;
                        }
                        editor.apply();logField("CONFIG","restauradas="+total);snack("Configuração restaurada. Reinicie o launcher.");
                    }).show();
            }catch(Exception e){snack("Arquivo incompatível ou inválido");}
        }
    }

    private String safeFieldReport() {
        // O relatório interno pode mostrar detalhes úteis na tela. Ao exportar, omitimos
        // localização aproximada e conteúdo livre de serviços OEM.
        String original=diagnosticReport();
        StringBuilder clean=new StringBuilder();
        for(String line:original.split("\\n")) {
            if(line.startsWith("--- SERVIÇO JANCAR ---")) break;
            if(line.startsWith("Cidade detectada:")) continue;
            clean.append(line).append('\n');
        }
        return clean.toString();
    }
    // Relatório de diagnóstico seguro: somente estados da aplicação; não registra coordenadas,
    // credenciais, músicas, endereços, conteúdo de notificações nem parâmetros privados da MCU.
    private String diagnosticReport() {
        StringBuilder b = new StringBuilder("PEUGEOT LAUNCHER - DIAGNÓSTICO DE CAMPO\n");
        b.append("Versão: ").append(BuildConfig.VERSION_NAME).append(" / ").append(BuildConfig.VERSION_CODE).append('\n');
        b.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        b.append("Hardware: ").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL).append('\n');
        b.append("Pacote: ").append(getPackageName()).append('\n');
        b.append("Wi-Fi conectado à internet: ").append(wifiConnected ? "SIM" : "NÃO").append('\n');
        b.append("Wi-Fi automático ao iniciar: ").append(wifiAutoOn ? "LIGADO" : "DESLIGADO").append('\n');
        b.append("Última tentativa Wi-Fi: ").append(wifiAutoStatus).append('\n');
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            b.append("Rádio Wi-Fi habilitado: ").append(wm != null && wm.isWifiEnabled() ? "SIM" : "NÃO").append('\n');
        } catch(Exception e) { b.append("Rádio Wi-Fi habilitado: sem leitura\n"); }
        b.append("Bluetooth: ").append(btConnected ? "ativo" : "inativo").append('\n');
        b.append("Rede: ").append(netOnline ? "online" : "offline").append('\n');
        b.append("Localização: ").append(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ? "permitida" : "sem permissão").append('\n');
        b.append("GPS: ").append(gpsFresh() ? "posição recente" : "sem posição recente").append('\n');
        b.append("Clima: ").append(lastWeatherSuccess > 0 ? "consulta concluída" : "sem consulta concluída").append('\n');
        b.append("Clima GPS: ").append(weatherAuto ? "ligado" : "desligado").append('\n');
        b.append("Cidade detectada: ").append(weatherDetail.isEmpty() ? "indisponível" : weatherDetail).append('\n');
        b.append("Tema: ").append(PRESET_NAMES[presetIndex]).append(" • ")
                .append(themeMode==0?"Automático":themeMode==1?"Escuro":"Claro")
                .append(" • paleta ").append(night?"escura":"clara").append('\n');
        b.append("Brilho: ").append(brightnessModeName()).append(" • ").append(brightnessPercent()).append("% • Botão visível: ").append(topBrightnessVisible ? "SIM" : "NÃO").append('\n');
        b.append("Fonte do veículo: ").append(vehicleDataSource==1?"OBD2 Bluetooth":"Jancar CANBOX").append('\n');
        if(vehicleDataSource==1)b.append("OBD2: ").append(obdState).append('\n');
        b.append("CAN/OBD telemetria: ").append(hasCanData() ? "com valores plausíveis" : "não confirmada").append('\n');
        b.append("CANBUS OEM: ").append(canbusApiStatus).append('\n');
        b.append("Widget veículo: ").append(vehicleMenuEnabled ? "ligado" : "desligado").append('\n');
        b.append("Cards do veículo: ").append(vehicleCardCount).append('\n');
        for (int i=0;i<vehicleCardCount;i++) b.append("Card ").append(i+1).append(": ").append(vehicleNames[vehicleCardSlots[i]]).append('\n');
        b.append("GPS da sessão: ").append(gpsTripValidFix ? String.format(new Locale("pt","BR"), "%.1f km",gpsTripMeters/1000.0) : "sem fix válido").append('\n');
        b.append("Atalhos favoritos: ").append(pinnedPackages.size()).append('\n');
        b.append("Áudio equalizador: ").append(snd != null ? (snd.effectOk ? "efeito criado" : "efeito não confirmado") : "motor ausente").append('\n');
        b.append("Áudio loudness: ").append(snd != null && snd.loudOn ? "configurado" : "desligado").append('\n');
        b.append("Áudio balanço/fader: sem API OEM confirmada\n");
        b.append("Spotify: ").append(mediaPermission ? "acesso à sessão permitido" : "acesso à sessão não confirmado").append('\n');
        b.append("Atualização: ").append(updateSource == 0 ? "GitHub" : "servidor local").append('\n');
        b.append("Registro contínuo: ").append(fieldLog != null && fieldLog.isEnabled()?"ATIVO":"DESLIGADO").append('\n');
        b.append("Registro disponível: ").append(fieldLog == null?"não":fieldLog.sizeKb()+" KB").append('\n');
        b.append("Áudio atual: ").append(audioSnapshot()).append('\n');
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
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=vertical();root.setPadding(dp(22),dp(14),dp(22),dp(14));
        root.setBackground(panel(cBg1,20));
        root.addView(label("DIAGNÓSTICO • PEUGEOT LAUNCHER",20,cText,true));divider(root);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(54)));
        actionButton(actions,"Atualizar",()->report.setText(diagnosticReport()));
        actionButton(actions,"Copiar",this::copyTestReport);
        actionButton(actions,"Fechar",dialog::dismiss);
        dialog.setContentView(root);showBig(dialog,.75f,.84f);
    }

    // ---------------------------------------------------------------- configurações (abas, sem rolagem)
    /** Configuração rápida do card, acessível com um toque no topo. */
    private void openSpeedCardMenu() {
        feedback();
        LinearLayout root = vertical();
        root.setPadding(dp(24), dp(20), dp(24), dp(18));
        root.setBackground(panel(cBg1, 20));
        root.addView(label("VELOCIDADE  /  AVISOS", 22, cText, true));
        root.addView(label("Configuração rápida do mini-card • velocidade GPS ou fonte selecionada", 12, cMuted, false));
        divider(root);
        stepperRow(root, "Avisar acima de", speedLimit, 30, 220, 5, " km/h", value -> {
            speedLimit = value;
            overNow = false;
            getPreferences(0).edit().putInt("speed_limit", value).apply();
            if (dashboard != null) dashboard.invalidate();
        });
        choiceRow(root, "Tipo de aviso", new String[]{"Só velocidade", "Visual", "Sonoro", "Visual + som"}, speedWarningMode, this::saveSpeedWarningMode);
        root.addView(label("Visual: vermelho acima do limite e piscando acima de 15%. Sonoro: toque de alerta, sem precisar deixar o card vermelho.", 12, cMuted, false));
        root.addView(label("O alerta de velocidade utiliza o GPS e respeita a opção geral de áudio em Alertas.", 12, cDim, false));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(55)));
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        actionButton(actions, "CONCLUÍDO", dialog::dismiss);
        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(dp(790), getResources().getDisplayMetrics().widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Math.min(dp(790), getResources().getDisplayMetrics().widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    // O serviço de localização deve começar com Activity visível para respeitar as
    // restrições de localização/serviços em primeiro plano do Android moderno.
    private void syncRadarService(){
        if(pardalRadar==null)return;
        Intent service=new Intent(this,RadarForegroundService.class);
        if(pardalRadar.mode()==PardalRadar.OFF){
            if(RadarForegroundService.isRunning())stopService(service);
            return;
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
        if(RadarForegroundService.isRunning())return;
        try{
            if(Build.VERSION.SDK_INT>=26)startForegroundService(service);
            else startService(service);
            logField("RADAR","servico_iniciado");
        }catch(Exception e){logField("RADAR","servico_indisponivel="+e.getClass().getSimpleName());}
    }
    private void radarOverlayPermission(){
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){
            new AlertDialog.Builder(this).setTitle("Avisos de radar sobre outros apps")
                .setMessage("Para mostrar o radar enquanto Waze, Maps ou Android Auto estiverem abertos, autorize a sobreposição do Peugeot 308 Launcher. Sem essa permissão, o alerta sonoro e a notificação continuam disponíveis quando o serviço estiver ativo.")
                .setPositiveButton("Abrir permissão",(d,w)->{
                    try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                         Uri.parse("package:"+getPackageName())));}catch(Exception e){snack("Abra Permissões especiais > Sobrepor outros apps.");}
                }).setNegativeButton("Depois",null).show();
        }else snack("Sobreposição já autorizada");
    }
    private void radarTools(){
        if(pardalRadar==null)return;
        String[] tools={"Estado da base e GPS", "Atualizar radares agora (Wi-Fi)",
                "Testar áudio do radar",
                pardalRadar.isSimulating()?"Parar simulação":"Simular radar de 350 a 0 m",
                "Permitir avisos sobre outros aplicativos"};
        new AlertDialog.Builder(this).setTitle("Radar Pardal • Ferramentas")
            .setItems(tools,(d,which)->{
                switch(which){
                    case 0:
                        new AlertDialog.Builder(this).setTitle("Base Pardal • São Paulo")
                          .setMessage("Modo: "+pardalRadar.modeLabel()
                            +"\n"+pardalRadar.status()
                            +"\nRadares carregados: "+pardalRadar.count()
                            +"\nAtualizada: "+pardalRadar.lastUpdated()
                            +"\n"+pardalRadar.gpsStatus()
                            +"\nServiço ativo: "+RadarForegroundService.isRunning()
                            +"\nSobreposição: "+Settings.canDrawOverlays(this)
                            +"\nFonte: Pardal (ODbL). A sinalização local prevalece.")
                          .setPositiveButton("OK",null).show();break;
                    case 1:
                        pardalRadar.forceUpdate(wifiConnected||netOnline);
                        snack("Atualização solicitada: "+pardalRadar.status());break;
                    case 2:
                        snack(pardalRadar.testSound() ? "Teste do radar: áudio enviado" :
                                "Som indisponível: veja estado e áudio do radar");break;
                    case 3:
                        if(pardalRadar.isSimulating()){
                            pardalRadar.stopSimulation();snack("Simulação encerrada");
                        }else{
                            pardalRadar.startSimulation();
                            snack("Simulação de radar: 350 m até 0 m, velocidade de teste 80 km/h");
                        }
                        if(dashboard!=null)dashboard.invalidate();break;
                    case 4: radarOverlayPermission();break;
                }
            }).setNegativeButton("Fechar",null).show();
    }
    /** Toque direto no botão do radar: Inteligente -> Visual -> Visual+Áudio -> Desligado. */
    private void cyclePardalMode(){
        if(pardalRadar==null)return;
        int next=(pardalRadar.mode()+1)%4;
        pardalRadar.setMode(next);
        String[] names={"Inteligente • áudio acima do limite","Apenas visual",
                "Visual + áudio","Desligado"};
        if(next!=PardalRadar.OFF){
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},14);
            else {startGps();syncRadarService();}
            pardalRadar.checkDaily(wifiConnected||netOnline);
        }else{syncRadarService();refreshGpsNeed();}
        // Aviso contextual integrado à Home; sem Toast do Android.
        radarModeNoticeText=names[next];
        radarModeNoticeUntil=SystemClock.uptimeMillis()+2200L;
        if(dashboard!=null)dashboard.invalidate();
    }

    private void openPardalMenu(){
        if(pardalRadar==null)return;
        String[] modes={"Ligado (inteligente: som acima do limite)","Ligado apenas visual",
                "Ligado visual e áudio","Desligado"};
        new AlertDialog.Builder(this).setTitle("RADAR • PARDAL SP")
            .setSingleChoiceItems(modes,pardalRadar.mode(),(d,which)->{
                pardalRadar.setMode(which);d.dismiss();
                if(which!=PardalRadar.OFF){
                    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},14);
                    else {startGps();syncRadarService();}
                    pardalRadar.checkDaily(wifiConnected||netOnline);
                    if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},153);
                }else syncRadarService();
                if(dashboard!=null)dashboard.invalidate();
            }).setNeutralButton("Ferramentas",(d,w)->radarTools())
            .setNegativeButton("Fechar",null).show();
    }
    private int topOptionalCount(){
        return ((topBtVisible||topWifiVisible)?1:0)+(topBrightnessVisible?1:0)
                +(topPcVisible?1:0)+(topEqVisible?1:0)+(topCustomVisible?1:0);
    }
    private boolean canShowExtraTop(boolean enabled){
        if(enabled&&topOptionalCount()>=4){
            snack("Máximo de 4 atalhos + Config. Desative outro grupo primeiro.");return false;
        }
        return true;
    }
    private void chooseTopCustomApp() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<android.content.pm.ResolveInfo> apps = new ArrayList<>(getPackageManager().queryIntentActivities(query, 0));
        Collections.sort(apps, (a, b) -> a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        String[] labels = new String[apps.size()];
        for (int i=0;i<apps.size();i++) labels[i] = apps.get(i).loadLabel(getPackageManager()).toString();
        themedChoices("Aplicativo do botão personalizado",labels,index -> {
            topCustomPackage=apps.get(index).activityInfo.packageName;
            getPreferences(0).edit().putString("top_custom_package",topCustomPackage).apply();
            if(dashboard!=null)dashboard.invalidate();
            snack("Botão personalizado: " + labels[index]);
        });
    }
    private void showAudioDiagnostic() {
        String detail;
        try {
            detail = "Volume Android fixo: " + (audio.isVolumeFixed() ? "SIM" : "NÃO") +
                "\nVolume mídia: " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) +
                " / " + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) +
                "\nÚltimo ajuste: " + audioStatus +
                "\nA central Jancar pode usar volume separado na MCU. Este app não tem API OEM validada para alterá-lo.";
        } catch(Exception e) { detail = "Falha ao consultar o sistema: " + e.getMessage(); }
        new AlertDialog.Builder(this).setTitle("Diagnóstico de áudio")
            .setMessage(detail).setPositiveButton("Fechar", null).show();
    }
    /** Tela de descanso minimalista. Sem WakeLock: respeita a suspensao da central. */
    private void openScreenSaver() {
        if (isFinishing() || screenSaverDialog != null) return;
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        screenSaverDialog = dlg;
        final View clock = new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Runnable refresh = new Runnable() {
                @Override public void run() {
                    if (screenSaverDialog == dlg && dlg.isShowing()) {
                        invalidate(); postDelayed(this, 1000L);
                    }
                }
            };
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow(); post(refresh);
            }
            @Override protected void onDetachedFromWindow() {
                removeCallbacks(refresh); super.onDetachedFromWindow();
            }
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                float w=getWidth(),h=getHeight();
                if(w<=0||h<=0)return;
                // Descanso OEM minimalista: preto verdadeiro, sem gradiente, widgets ou cards.
                c.drawColor(Color.BLACK);
                float scale=Math.min(w/1280f,h/720f);
                c.save();
                c.translate((w-1280f*scale)/2f,(h-720f*scale)/2f);
                c.scale(scale,scale);
                paint.reset();
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                paint.setTextSize(25f);
                paint.setLetterSpacing(.13f);
                paint.setColor(0xffACB3B9);
                c.drawText("PEUGEOT",640f,155f,paint);
                paint.setLetterSpacing(0f);
                paint.setTextSize(24f);
                paint.setColor(0xff7B828A);
                c.drawText("308",640f,194f,paint);
                Calendar now=Calendar.getInstance();
                Locale loc=new Locale("pt","BR");
                String hour=new SimpleDateFormat("HH:mm",loc).format(now.getTime());
                String date=new SimpleDateFormat("EEEE, dd 'de' MMMM",loc)
                        .format(now.getTime()).toUpperCase(loc);
                paint.setTypeface(Typeface.create("sans-serif-thin",Typeface.NORMAL));
                paint.setColor(0xffF4F5F6);
                paint.setTextSize(208f);
                while(paint.getTextSize()>150f&&paint.measureText(hour)>1020f)
                    paint.setTextSize(paint.getTextSize()-2f);
                c.drawText(hour,640f,428f,paint);
                paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
                paint.setTextSize(27f);
                paint.setColor(0xff90979E);
                while(paint.getTextSize()>18f&&paint.measureText(date)>1000f)
                    paint.setTextSize(paint.getTextSize()-1f);
                c.drawText(date,640f,504f,paint);
                paint.setTextSize(16f);
                paint.setColor(0xff4E555C);
                c.drawText("TOQUE PARA VOLTAR",640f,663f,paint);
                c.restore();
            }
        };
        clock.setOnClickListener(v -> dlg.dismiss());
        dlg.setContentView(clock);
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        dlg.setOnDismissListener(d -> {
            if (screenSaverDialog == dlg) screenSaverDialog = null;
            screenSaverDismissedAt = SystemClock.uptimeMillis();
            resetHomeIdleTimer();
            logField("TELA_DESCANSO", "fechada");
            if (dashboard != null) dashboard.invalidate();
        });
        try { dlg.show(); logField("TELA_DESCANSO", "aberta"); }
        catch (Exception ex) { screenSaverDialog = null; }
    }

    private void settings() { settings(lastSettingsTab); }
    private void settings(int initialTab) {
        feedback();
        LinearLayout root = vertical(); root.setPadding(dp(20), dp(16), dp(20), dp(16)); root.setBackground(panel(cBg1, 20));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView settingsTitle=label("CONFIGURAÇÕES", 22, cText, true);
        header.addView(settingsTitle, new LinearLayout.LayoutParams(0, dp(53), 1));
        TextView close = label("✕", 24, cText, true); close.setGravity(Gravity.CENTER); close.setBackground(panel(cSurf2, 12));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44))); root.addView(header);
        View headerLine = new View(this); headerLine.setBackgroundColor(cHair); root.addView(headerLine, new LinearLayout.LayoutParams(-1, dp(1)));
        final TextView settingsCaption=label("PEUGEOT 308  /  PERSONALIZAÇÃO DO SISTEMA", 12, cDim, false);
        root.addView(settingsCaption, new LinearLayout.LayoutParams(-1, dp(32)));
        LinearLayout tabs = new LinearLayout(this); root.addView(tabs);
        LinearLayout page = vertical(); page.setPadding(0, dp(14), 0, dp(10));
        ScrollView pageScroll = new ScrollView(this); pageScroll.addView(page);
        root.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        String[] titles = {"Aparência", "Clima", "Atalhos", "Veículo", "Alertas", "Som", "Sistema"};
        final int[] current = {Math.max(0, Math.min(6, initialTab))}, shownTab = {-1};
        lastSettingsTab = current[0];
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            // Reaplica toda a paleta: não deixa a moldura anterior ao trocar Claro/Escuro/Cor.
            root.setBackground(panel(cBg1,20));
            settingsTitle.setTextColor(cText);
            settingsCaption.setTextColor(cDim);
            close.setTextColor(cText);close.setBackground(panel(cSurf2,12));
            headerLine.setBackgroundColor(cHair);
            if(shownTab[0]>=0) lastSettingsScroll[shownTab[0]]=pageScroll.getScrollY();
            final int keepY = shownTab[0] == current[0] ? pageScroll.getScrollY() : lastSettingsScroll[current[0]];
            shownTab[0] = current[0];
            tabs.removeAllViews(); page.removeAllViews();
            for (int i = 0; i < titles.length; i++) {
                final int index = i; Button tab = new Button(this);
                tab.setText(titles[i]); tab.setAllCaps(false); tab.setTextSize(11); tab.setMinWidth(0); tab.setMinimumWidth(0); tab.setPadding(0, 0, 0, 0);
                tab.setTextColor(i == current[0] ? onAccent : cText);
                tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(i == current[0] ? accent : cSurf2));
                LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, dp(46), 1); tabParams.setMargins(dp(2), 0, dp(2), 0); tabs.addView(tab, tabParams);
                tab.setOnClickListener(v -> {
                    feedback(); current[0] = index; lastSettingsTab = index;
                    getPreferences(0).edit().putInt("last_settings_tab",index).apply(); redraw[0].run();
                });
            }
            switch (current[0]) {
                case 0: {
                    section(page, "APARÊNCIA", "32 temas prontos (preto, cinza chumbo e cores), claro/escuro, capa do álbum e brilho");
                    section(page,"BARRA SUPERIOR", "Escolha quais botões ficam visíveis. Configurações permanece sempre acessível.");
                    switchRow(page,"Mostrar botão Volume",topVolumeVisible,(v,checked)->{
                        topVolumeVisible=checked;getPreferences(0).edit().putBoolean("top_show_volume",checked).apply();dashboard.invalidate();
                    });
                    switchRow(page,"Conexões • mostrar Bluetooth",topBtVisible,(v,checked)->{
                        if (checked && !topWifiVisible && !canShowExtraTop(true)) { ((Switch)v).setChecked(false); return; }
                        topBtVisible=checked;getPreferences(0).edit().putBoolean("top_show_bt",checked).apply();dashboard.invalidate();
                    });
                    switchRow(page,"Conexões • mostrar Wi-Fi",topWifiVisible,(v,checked)->{
                        if (checked && !topBtVisible && !canShowExtraTop(true)) { ((Switch)v).setChecked(false); return; }
                        topWifiVisible=checked;getPreferences(0).edit().putBoolean("top_show_wifi",checked).apply();dashboard.invalidate();
                    });
                    switchRow(page,"Mostrar Brilho (toque / segurar 0,7 s)",topBrightnessVisible,(v,checked)->{
                        if (checked && !canShowExtraTop(true)) { ((Switch)v).setChecked(false); return; }
                        topBrightnessVisible=checked;getPreferences(0).edit().putBoolean("top_show_brightness",checked).apply();dashboard.invalidate();
                    });
                    switchRow(page,"Mostrar Veículo (submenu rápido)",topPcVisible,(v,checked)->{
                        if (checked && !canShowExtraTop(true)) { ((Switch)v).setChecked(false); return; }
                        topPcVisible=checked;getPreferences(0).edit().putBoolean("top_show_pc",checked).apply();dashboard.invalidate();
                    });
                    switchRow(page,"Mostrar Equalizador completo",topEqVisible,(v,checked)->{
                        if (checked && !canShowExtraTop(true)) { ((Switch)v).setChecked(false); return; }
                        topEqVisible=checked;getPreferences(0).edit().putBoolean("top_show_eq",checked).apply();dashboard.invalidate();
                    });
                    switchRow(page, "Mostrar aplicativo personalizado", topCustomVisible, (v, checked) -> {
                        if (checked && !canShowExtraTop(true)) { ((Switch)v).setChecked(false); return; }
                        topCustomVisible = checked; getPreferences(0).edit().putBoolean("top_show_custom", checked).apply(); dashboard.invalidate();
                    });
                    LinearLayout customRow = new LinearLayout(this); page.addView(customRow);
                    actionButton(customRow, "Escolher aplicativo do botão", this::chooseTopCustomApp);
                    page.addView(label("Conexões reúne Wi-Fi e Bluetooth. Brilho entra ao lado de Rede quando habilitado; limite de 4 atalhos + Config.",12,cMuted,false));
                    page.addView(label("Brilho: toque = Auto → Mínimo → Máximo; segurar por 0,7 segundo = ajuste manual. EQ permanece completo.",12,cMuted,false));
                    page.addView(label("Estas opções escondem apenas os botões da barra, não desligam Bluetooth, Wi-Fi ou os serviços do veículo.",12,cMuted,false));
                    page.addView(label("Se o Wi-Fi desliga ao ligar o carro, confira as configurações de energia/rede da Jancar: o launcher não executa comandos para desligá-lo.",12,cMuted,false));
                    divider(page);
                    section(page, "TELA DE DESCANSO", "Relogio em tela cheia; o som continua tocando e um toque retorna a Home.");
                    switchRow(page, "Ativar tela de descanso automatica", screenSaverEnabled, (v,checked)->{
                        screenSaverEnabled=checked; getPreferences(0).edit().putBoolean("screensaver_enabled", checked).apply();
                        resetHomeIdleTimer();
                    });
                    stepperRow(page, "Iniciar após", screenSaverSeconds / 60, 10, 60, 5, " min", val->{
                        screenSaverSeconds=val * 60;
                        getPreferences(0).edit().putInt("screensaver_seconds",screenSaverSeconds).apply();
                        resetHomeIdleTimer();
                    });
                    LinearLayout saverButtons=new LinearLayout(this);page.addView(saverButtons);
                    actionButton(saverButtons,"Testar tela de descanso",()->{
                        if (activeDialog!=null && activeDialog.isShowing()) activeDialog.dismiss();
                        handler.postDelayed(this::openScreenSaver, 250L);
                    });
                    page.addView(label("Nao abre quando o modo direcao esta ativo. Respeita o standby da central e nao interrompe o Spotify.",12,cMuted,false));
                    divider(page);
                    switchRow(page,"Recolher atalhos após 3 minutos sem tocar",immersiveIdle,(v,checked)->{
                        immersiveIdle=checked;getPreferences(0).edit().putBoolean("immersive_idle",checked).apply();
                        resetHomeIdleTimer();
                    });
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
                    section(page, "BRILHO", "Escolha o modo no menu rápido ou faça um ajuste manual detalhado");
                    choiceRow(page,"Modo de brilho",new String[]{"Automático (dia/noite)","Mínimo","Máximo","Manual"},brightnessMode,
                            value -> { saveBrightnessMode(value); redraw[0].run(); });
                    stepperRow(page, "Brilho máximo (dia)", brightDay, 10, 100, 5, "%", val -> {
                        brightDay = Math.max(val, brightNight);
                        getPreferences(0).edit().putInt("bright_day", brightDay).apply(); appliedBrightnessPct=-1; applyBrightness();
                    });
                    stepperRow(page, "Brilho mínimo (noite)", brightNight, 5, 95, 5, "%", val -> {
                        brightNight = Math.min(val, brightDay);
                        getPreferences(0).edit().putInt("bright_night", brightNight).apply(); appliedBrightnessPct=-1; applyBrightness();
                    });
                    LinearLayout brightActions=new LinearLayout(this);page.addView(brightActions);
                    actionButton(brightActions,"Abrir ajuste manual",this::openBrightnessQuickMenu);
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
                    switchRow(page, "Animar ícone do clima (leve)", weatherMotionEnabled, (v, enabled) -> {
                        weatherMotionEnabled = enabled;
                        getPreferences(0).edit().putBoolean("weather_motion", enabled).apply();
                        if (dashboard != null) { dashboard.setWeatherAnimationActive(enabled); dashboard.invalidate(); }
                    });
                    page.addView(label("Sol, nuvens, chuva, neve e neblina com animação discreta; sem consultas extras à internet. Pode desligar para economizar processamento.", 12, cMuted, false));
                    page.addView(label("Condição: " + weatherCondition + "   •   " + (weatherCity.isEmpty() && !weatherAuto ? "--" : weatherLine), 15, cMuted, false));
                    page.addView(label("Cidade GPS: " + (weatherAuto ? (weatherDetail.isEmpty()?"Aguardando consulta":weatherDetail) : "Modo manual") + "  •  Fonte: Geocoder / OpenStreetMap", 12, cMuted, false));
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
                    LinearLayout allApps=new LinearLayout(this);page.addView(allApps);
                    actionButton(allApps,"Organizar / renomear aplicativos",this::picker);
                    divider(page);
                    page.addView(label("ÍCONES DOS APLICATIVOS FIXADOS",14,accent,true));
                    page.addView(label("Escolha o ícone original do aplicativo ou o desenho minimalista para cada favorito.",12,cMuted,false));
                    for(String pkg : new ArrayList<>(pinnedPackages)) {
                        String display;
                        try{display=appLabel(pkg);}
                        catch(Exception ignored){display=pkg;}
                        final String fixedPkg=pkg;
                        choiceRow(page,display,new String[]{"Minimalista","Original"},originalAppIcons.contains(pkg)?1:0,style->{
                            if(style==1)originalAppIcons.add(fixedPkg);else originalAppIcons.remove(fixedPkg);
                            getPreferences(0).edit().putString("original_app_icons",android.text.TextUtils.join(",",originalAppIcons)).apply();
                            dashboard.clearIcons();dashboard.invalidate();
                        });
                    }
                    divider(page);
                    page.addView(label("Destinos no Waze (aparecem no dock como Casa e Trabalho)", 14, accent, true));
                    addressRow(page, true, () -> redraw[0].run());
                    addressRow(page, false, () -> redraw[0].run());
                    page.addView(label("Endereço completo ou coordenadas (-23.03,-45.55). O Waze já abre navegando.", 12, cMuted, false));
                    page.addView(label("O LED verde identifica o último aplicativo aberto pelo launcher. Não confirma que ainda está executando em segundo plano.", 12, cMuted, false));
                    break;
                }
                case 3: {
                    section(page, "VEÍCULO E PAINEL", "Escolha o que aparece na tela inicial");
                    LinearLayout box = page;
                    section(box, "PAINEL NA TELA INICIAL", "Ative ou oculte o card grande do computador de bordo");
                    switchRow(box, "Mostrar card grande do veículo", vehicleMenuEnabled, (v, checked) -> {
                        vehicleMenuEnabled = checked; getPreferences(0).edit().putBoolean("vehicle_menu", checked).apply();
                        refreshGpsNeed(); if (dashboard != null) dashboard.invalidate();
                    });
                    box.addView(label("DESLIGADO: Clima e Spotify ocupam o espaço. Mini-cards do topo são independentes; acesso PC pode continuar no topo.", 12, cMuted, false));
                    switchRow(box, "Manter acesso PC no topo quando oculto", standaloneVehicleButton, (v, checked) -> {
                        standaloneVehicleButton = checked; getPreferences(0).edit().putBoolean("standalone_vehicle_button", checked).apply();
                        if (dashboard != null) dashboard.invalidate();
                    });
                    divider(box);
                    section(box, "CARDS DO COMPUTADOR DE BORDO", "Escolha 1, 2 ou 3 indicadores; sem histórico de viagens");
                    choiceRow(box, "Quantidade de cards", new String[]{"1 card", "2 cards", "3 cards"}, vehicleCardCount - 1, index -> {
                        vehicleCardCount = index + 1;
                        getPreferences(0).edit().putInt("vehicle_cards_count", vehicleCardCount).apply();
                        if (dashboard != null) dashboard.invalidate();
                        redraw[0].run();
                    });
                    for (int ci = 0; ci < vehicleCardCount; ci++) {
                        final int slot = ci;
                        choiceRow(box, "Conteúdo do card " + (ci + 1), vehicleNames, vehicleCardSlots[ci], selected -> {
                            for (int other = 0; other < vehicleCardCount; other++) {
                                if (other != slot && vehicleCardSlots[other] == selected) {
                                    snack("Esse indicador já está em outro card");
                                    redraw[0].run(); return;
                                }
                            }
                            vehicleCardSlots[slot] = selected;
                            getPreferences(0).edit().putInt("vehicle_card_slot_" + slot, selected).apply();
                            if (dashboard != null) dashboard.invalidate();
                        });
                    }
                    divider(box);
                    section(box, "TERCEIRO CARD • ESTADO DO CARRO", "Pode ocultar o terceiro card; a opção de apenas 1 card continua acima.");
                    switchRow(box, "Exibir terceiro card", vehicleCardCount == 3, (v, enabled) -> {
                        vehicleCardCount = enabled ? 3 : Math.min(vehicleCardCount, 2);
                        getPreferences(0).edit().putInt("vehicle_cards_count", vehicleCardCount).apply();
                        if (dashboard != null) dashboard.invalidate();
                        redraw[0].run();
                    });
                    choiceRow(box, "Visual do terceiro card", new String[]{"Indicador normal", "Carro + todas informações", "Apenas desenho do carro", "Carro + informações escolhidas"}, vehicleThirdVisualMode, mode -> {
                        vehicleThirdVisualMode = mode;
                        getPreferences(0).edit().putInt("vehicle_third_visual_mode", mode).apply();
                        if (dashboard != null) dashboard.invalidate();
                        redraw[0].run();
                    });
                    if (vehicleThirdVisualMode == 3) {
                        for (int vi=0; vi<thirdCarIndicators.length; vi++) {
                            final int bit=vi;
                            switchRow(box, thirdCarIndicators[vi], (vehicleThirdVisualFlags & (1<<vi)) != 0, (v, enabled) -> {
                                if (enabled) vehicleThirdVisualFlags |= (1<<bit);
                                else vehicleThirdVisualFlags &= ~(1<<bit);
                                getPreferences(0).edit().putInt("vehicle_third_visual_flags", vehicleThirdVisualFlags).apply();
                                if (dashboard != null) dashboard.invalidate();
                            });
                        }
                    }
                    box.addView(label("Indicadores sem sinal CANBUS confirmado ficam cinza (--). O painel nunca presume que uma porta está fechada ou travada.", 12, cMuted, false));
                    box.addView(label("Km rodados, média, máxima e altitude usam GPS. O percurso não é salvo e pode ficar incompleto se o Android interromper a localização em segundo plano.", 12, cMuted, false));
                    stepperRow(box, "Consumo médio manual (L/100km)", estimatedFuelLPer100, 4, 25, 1, "", value -> {
                        estimatedFuelLPer100 = value;
                        getPreferences(0).edit().putInt("fuel_avg_manual", value).apply();
                        if (dashboard != null) dashboard.invalidate();
                    });
                    box.addView(label("Gasto ESTIMADO: distância GPS × média informada. Não é consumo instantâneo nem leitura do tanque.", 12, cMuted, false));
                    LinearLayout tripButtons = new LinearLayout(this); box.addView(tripButtons, new LinearLayout.LayoutParams(-1, dp(54)));
                    actionButton(tripButtons, "Zerar quilômetros da sessão", () -> new AlertDialog.Builder(this)
                        .setTitle("Zerar percurso GPS?")
                        .setMessage("Os quilômetros, média, máxima e gasto estimado desta sessão serão zerados. Isso não altera o odômetro do carro.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Zerar", (dialog, which) -> { resetGpsTrip(); snack("Percurso GPS zerado"); })
                        .show());
                    divider(box);
                    box.addView(label((vehicleDataSource==1 ? "OBD2: " : "CANBUS: ") + (hasCanData() ? "CONECTADO" : "SEM DADOS") + "     GPS: " + (gpsFresh() ? "SINAL OK" : "SEM SINAL"), 15, hasCanData() || gpsFresh() ? cOk : cMuted, true));
                    divider(box);
                    section(box,"FONTE DE DADOS DO CARRO","Escolha entre a CANBOX original ou um leitor OBD2 Bluetooth pareado");
                    choiceRow(box,"Fonte dos indicadores",new String[]{"CANBUS Jancar (Raise)","OBD2 Bluetooth (ELM327)"},vehicleDataSource,index->{
                        useDataSource(index);redraw[0].run();
                    });
                    if(vehicleDataSource == 1) {
                        box.addView(label("OBD2 Bluetooth clássico: RPM, velocidade, temperatura do motor e combustível (quando suportados). Não altera a configuração da CANBOX.",12,cMuted,false));
                        LinearLayout obdActions=new LinearLayout(this);box.addView(obdActions,new LinearLayout.LayoutParams(-1,dp(54)));
                        actionButton(obdActions,"Escolher adaptador",()->chooseObdAdapter());
                        actionButton(obdActions,"Conectar OBD2",()->{
                            if(obdAddress.isEmpty()) chooseObdAdapter();
                            else if(obdReader!=null) obdReader.start(obdAddress);
                        });
                        actionButton(obdActions,"Desconectar",()->{if(obdReader!=null)obdReader.stop();obdState="Desconectado";dashboard.invalidate();});
                        box.addView(label("Estado: "+obdState,13,cMuted,false));
                    }
                    LinearLayout testSource=new LinearLayout(this);box.addView(testSource,new LinearLayout.LayoutParams(-1,dp(50)));
                    actionButton(testSource,"Diagnóstico da fonte",()->showVehicleSourceStatus());
                    // Diagnóstico adicional sem modificar nenhum dos cartões ou botões existentes.
                    divider(box);
                    box.addView(label("INTEGRAÇÃO JANCAR (SOMENTE LEITURA)", 14, accent, true));
                    box.addView(label("Conecta ao serviço IVI Car do firmware quando o Android permite. A conexão não significa que valores CAN estejam disponíveis.", 12, cMuted, false));
                    LinearLayout jancarActions = new LinearLayout(this);
                    box.addView(jancarActions, new LinearLayout.LayoutParams(-1, dp(54)));
                    actionButton(jancarActions, "Diagnóstico Jancar", () -> {
                        if(jancarBridge == null)return;
                        jancarBridge.start();
                        ScrollView scroll=new ScrollView(this);
                        TextView report=label(jancarBridge.diagnostic(),13,cText,false);
                        report.setTextIsSelectable(true);
                        report.setPadding(dp(14),dp(14),dp(14),dp(14));
                        scroll.addView(report);
                        AlertDialog d=new AlertDialog.Builder(this)
                            .setTitle("Jancar • Diagnóstico CAN")
                            .setView(scroll).setPositiveButton("Fechar",null)
                            .setNegativeButton("Atualizar",null).create();
                        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_NEGATIVE)
                            .setOnClickListener(btn->report.setText(jancarBridge.diagnostic())));
                        d.show();
                        handler.postDelayed(()->{if(d.isShowing())report.setText(jancarBridge.diagnostic());},800);
                    });
                    actionButton(jancarActions, "Abrir app do veículo", () -> openJancarApp(true));
                    actionButton(jancarActions, "Rádio original", () -> openJancarApp(false));
                    box.addView(label("Rádio e volante continuam pelo sistema original até que os contratos da MCU/CAN sejam confirmados.", 12, cMuted, false));
                    divider(box);
                    section(box, "CONTROLES ORIGINAIS DO VEÍCULO", "Botões ainda sem comando MCU confirmado");
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
                    box.addView(label("Cada mini card pode ser desligado separadamente. Com os três em Desligado, nenhum card aparece no topo.", 12, cMuted, false));
                    String[] topOpts = topMiniChoices();
                    for (int si = 0; si < topSlot.length; si++) {
                        final int slot = si;
                        choiceRow(box, "Card " + (si + 1), topOpts, topMiniIndex(topSlot[si]), idx -> setTopMiniCard(slot,topMiniFromIndex(idx)));
                    }
                    LinearLayout clearCardsRow = new LinearLayout(this);
                    box.addView(clearCardsRow, new LinearLayout.LayoutParams(-1, dp(49)));
                    actionButton(clearCardsRow, "Desligar os 3 mini cards", () -> {
                        android.content.SharedPreferences.Editor editor = getPreferences(0).edit();
                        for (int i = 0; i < topSlot.length; i++) { topSlot[i] = -1; editor.putInt("top_slot_" + i, -1); }
                        editor.apply(); dashboard.invalidate(); redraw[0].run();
                    });
                    box.addView(label("Temperatura/RPM/consumo dependem do CANBUS ou OBD2; sem dados válidos mostra --.", 12, cMuted, false));
                    box.addView(label("Toque no card VELOCIDADE na tela inicial para definir limite, alerta visual, sonoro ou somente leitura. O vermelho e a piscada de 15% dependem do modo visual.", 12, cMuted, false));
                    stepperRow(box, "Limite de velocidade do mini card", speedLimit, 30, 220, 5, " km/h", val -> {
                        speedLimit = val; getPreferences(0).edit().putInt("speed_limit", val).apply(); dashboard.invalidate();
                    });
                    switchRow(box,"Priorizar GPS para mostrar velocidade",speedPreferGps,(v,checked)->{
                        speedPreferGps=checked;getPreferences(0).edit().putBoolean("speed_prefer_gps",checked).apply();refreshGpsNeed();dashboard.invalidate();
                    });
                    divider(box);
                    box.addView(label("O painel pode mostrar 1, 2 ou 3 cards. O terceiro fica desligado com 1 ou 2 cards; os mini-cards superiores são independentes.", 12, cMuted, false));
                    break;
                }
                case 4: {
                    section(page, "VELOCIDADE", "Velocímetro GPS e aviso opcional de excesso de velocidade");
                    actionButton(page, "Radar Pardal • modos e ferramentas", this::openPardalMenu);
                    switchRow(page, "Alerta ao passar do limite de velocidade", speedAlert, (v, checked) -> { saveSpeedWarningMode(checked ? (speedWarningMode == 0 ? 3 : speedWarningMode) : 0); });
                    stepperRow(page, "Limite de velocidade", speedLimit, 30, 220, 10, " km/h", val -> { speedLimit = val; getPreferences(0).edit().putInt("speed_limit", val).apply(); dashboard.invalidate(); });
                    switchRow(page, "Som nos alertas de velocidade", alertSound, (v, checked) -> { alertSound = checked; getPreferences(0).edit().putBoolean("alert_sound", checked).apply(); });
                    LinearLayout alertTests = new LinearLayout(this); alertTests.setPadding(0, dp(8), 0, dp(4)); page.addView(alertTests);
                    actionButton(alertTests, "Testar velocidade", () -> {
                        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
                        showAlert(1, "VOCÊ ESTÁ ACIMA DE " + speedLimit + " km/h", "Teste: " + (speedLimit + 12) + " km/h", SPEED_ALERT_DURATION_MS, true);
                    });
                    page.addView(label("Aviso baseado na velocidade medida pelo GPS. Configure o modo visual/sonoro pelo card de velocidade.", 12, cMuted, false));
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
                    page.addView(label("Opcional e desligado por padrão para não interferir no Spotify. Se ativado, aplica uma única rampa ao acordar; nunca repete o fade quando a música começa.", 12, cMuted, false));
                    LinearLayout fadeTest = new LinearLayout(this); fadeTest.setPadding(0, dp(8), 0, 0); page.addView(fadeTest);
                    actionButton(fadeTest, "Testar fade agora", () -> fadeFromZero());
                    actionButton(fadeTest, "Abrir equalizador", () -> openEq());
                    divider(page);
                    section(page, "TONALIDADE", "Tres controles independentes: grave, agudo e reforco. Processados pelo EQ Android quando o firmware permite.");
                    stepperRow(page,"Graves", Math.round(snd.lowTone), -6, 6, 1, " dB", v -> snd.setLowTone(v));
                    stepperRow(page,"Agudos", Math.round(snd.highTone), -6, 6, 1, " dB", v -> snd.setHighTone(v));
                    stepperRow(page,"Reforco de graves", Math.round(snd.bass * 10f), 0, 10, 1, "", v -> snd.setBass(v / 10f));
                    page.addView(label("A resposta sonora depende do processamento Android/MCU da Jancar; se nao houver efeito audivel, utilize Som original Jancar.",12,cMuted,false));

                    LinearLayout oemAudio=new LinearLayout(this);oemAudio.setPadding(0,dp(8),0,0);page.addView(oemAudio);
                    actionButton(oemAudio,"Som original Jancar (balanço / fader)",()->openJancarSound());
                    LinearLayout audioDiagnostics=new LinearLayout(this);page.addView(audioDiagnostics);
                    actionButton(audioDiagnostics,"Diagnóstico do volume Android / MCU",this::showAudioDiagnostic);
                    page.addView(label("O balanço/fader do launcher não controlam a MCU sem API autorizada. Para ajustes reais, use o painel de áudio original da Jancar.",12,cMuted,false));
                    break;
                }
                default: {
                    section(page, "SISTEMA E ATUALIZAÇÕES", "Versão instalada e atualização pelo GitHub");
                    section(page, "CONEXÕES • WI-FI", "Teste opcional de restauração do Wi-Fi ao ligar ou acordar a multimídia");
                    switchRow(page, "Tentar ligar Wi-Fi automaticamente ao iniciar", wifiAutoOn, (v, checked) -> {
                        wifiAutoOn = checked;
                        getPreferences(0).edit().putBoolean("wifi_auto_on", checked).apply();
                        if (checked) startWifiWakeMonitor("configuracao");
                        else { wifiMonitorEpoch++; recordWifiAutoStatus("Função desativada pelo usuário"); }
                    });
                    page.addView(label("LIGADO por padrão. Até 3 verificações por despertar, sem abrir telas nem forçar redes. A associação à rede salva depende do sistema Jancar.", 12, cMuted, false));
                    page.addView(label("Último resultado: " + wifiAutoStatus, 13, cMuted, false));
                    LinearLayout wifiAutoButtons = new LinearLayout(this);
                    wifiAutoButtons.setPadding(0, dp(8), 0, 0); page.addView(wifiAutoButtons);
                    actionButton(wifiAutoButtons, "Testar ativação", () -> tryAutoEnableWifi(true));
                    actionButton(wifiAutoButtons, "Abrir Wi-Fi", () -> openSystemSetting(Settings.ACTION_WIFI_SETTINGS));
                    page.addView(label("Caso a central recuse a solicitação, use Abrir Wi-Fi. O diagnóstico detalhado aparece no relatório de sistema; não há tentativas repetidas em segundo plano.", 12, cMuted, false));
                    divider(page);
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
                    switchRow(page, "Baixar nova versão automaticamente", autoDownloadUpdates, (v, checked) -> {
                        autoDownloadUpdates=checked;
                        getPreferences(0).edit().putBoolean("upd_auto_download", checked).apply();
                    });
                    page.addView(label("Ao encontrar um APK mais novo em uma fonte acessível, o download começa sozinho. A instalação sempre pede sua confirmação no Android.", 12, cMuted, false));
                    page.addView(label("GitHub: exige Release pública com APK. Um repositório privado ou apenas o Artifact do Actions não pode ser baixado diretamente pela central sem autenticação. Alternativa: servidor local com update.json.", 12, cMuted, false));
                    divider(page);
                    section(page,"DIAGNÓSTICO PARA TESTE NA JANCAR","Confere serviços, permissões e recursos sem alterar dados do veículo");
                    LinearLayout diagnosticActions = new LinearLayout(this); diagnosticActions.setPadding(0,dp(8),0,0);page.addView(diagnosticActions);
                    actionButton(diagnosticActions, "Ver relatório", () -> showTestReport());
                    actionButton(diagnosticActions, "Copiar relatório", () -> copyTestReport());
                    page.addView(label("Após testar no carro, copie o relatório e envie na conversa. Ele não inclui coordenadas, músicas nem dados pessoais.", 12, cMuted, false));
                    divider(page);
                    section(page, "LOG DE CAMPO • REGISTRO CONTÍNUO", "Eventos com horário, sem músicas, senhas, SSID ou localização");
                    switchRow(page, "Registrar eventos de diagnóstico", fieldLog != null && fieldLog.isEnabled(), (v, checked) -> {
                        if(fieldLog!=null){fieldLog.setEnabled(checked);getPreferences(0).edit().putBoolean("field_log_enabled",checked).apply();
                            if(checked){ logField("APP","registro_ativado"); lastLogHeartbeat=0; logPeriodicHealth(); }
                        }
                    });
                    LinearLayout logBtns = new LinearLayout(this);page.addView(logBtns);
                    actionButton(logBtns,"Exportar log .txt",this::exportFieldLog);
                    LinearLayout spotifyDiagButtons = new LinearLayout(this);page.addView(spotifyDiagButtons);
                    actionButton(spotifyDiagButtons,"Diagnosticar Spotify agora",this::diagnoseSpotifyNow);
                    actionButton(logBtns,"Apagar log",this::confirmDeleteFieldLog);
                    page.addView(label("Registro limitado a aproximadamente 512 KB e permanece na área privada até você exportar. Funciona enquanto o processo do launcher estiver ativo; o Android pode interrompê-lo ao suspender o sistema.",12,cMuted,false));
                    divider(page);
                    section(page,"CÓPIA DE SEGURANÇA","Somente preferências de aparência e cards; não inclui endereços ou credenciais");
                    LinearLayout backupBtns=new LinearLayout(this);page.addView(backupBtns);
                    actionButton(backupBtns,"Salvar configuração",this::exportVisualBackup);
                    actionButton(backupBtns,"Restaurar configuração",this::importVisualBackup);
                    page.addView(label("Ao restaurar, reinicie o launcher para aplicar as preferências. Os demais dados do aplicativo permanecem intactos.",12,cMuted,false));
                    break;
                }
            }
            scaleTree(tabs, SET_SCALE); scaleTree(page, SET_SCALE);
            pageScroll.post(() -> pageScroll.scrollTo(0, keepY));
        };
        redraw[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            lastSettingsScroll[current[0]]=pageScroll.getScrollY();
            android.content.SharedPreferences.Editor edit=getPreferences(0).edit().putInt("last_settings_tab",current[0]);
            for(int tab=0;tab<7;tab++)edit.putInt("settings_scroll_"+tab,lastSettingsScroll[tab]);
            edit.apply();
            if (activeDialog == dialog) activeDialog = null;
        });
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
    private void newUpdateFound(String version, String notes, long size, String url, boolean silent) {
        if(!autoDownloadUpdates || !silent) {
            offerUpdate(version,notes,size,url);return;
        }
        if(drivingMode || !netOnline) return;
        if(updateDownloadId>=0 && version.equals(updateDownloadVersion)) {
            checkStoredDownload();return;
        }
        if(updateDownloadId>=0) return;
        downloadUpdate(url,version,true);
    }
    private void offerUpdate(String version, String notes, long size, final String url) {
        String n = notes == null ? "" : notes.trim(); if (n.length() > 380) n = n.substring(0, 380) + "…";
        String msg = "Instalada: " + BuildConfig.VERSION_NAME + "\nNova versão: " + version + (size > 0 ? String.format(Locale.US, "  (%.1f MB)", size / 1048576.0) : "") + (n.isEmpty() ? "" : "\n\nNovidades:\n" + n);
        popupMessage("Atualização disponível", msg, "Baixar", () -> downloadUpdate(url,version,false), "Depois");
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
                    if (silent && (drivingMode || activeDialog != null)) return;
                    if (silent && !autoDownloadUpdates && tag.equals(notifiedVersion)) return;
                    notifiedVersion = tag; getPreferences(0).edit().putString("upd_notified", tag).apply();
                    newUpdateFound(tag, body, sz, url, silent);
                });
            } catch (final Exception ex) {
                final String m = ex instanceof FileNotFoundException ? "Repositório ou release não encontrado. O repositório precisa ser público e ter ao menos uma Release com um arquivo .apk."
                    : String.valueOf(ex.getMessage()).contains("403") ? "O GitHub limitou as consultas por agora. Tente de novo em alguns minutos."
                    : "Confira a internet da central.\n\n" + ex.getMessage();
                getPreferences(0).edit().putLong("upd_last", System.currentTimeMillis() - 11L * 3600 * 1000).apply(); // falhou: tenta de novo só em ~1 h
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
                if (!apk.matches("[A-Za-z0-9._ -]+\\.apk")) throw new IOException("Nome de APK inválido");
                getPreferences(0).edit().putLong("upd_last", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    if (remoteCode <= BuildConfig.VERSION_CODE) { if (!silent) popupInfo("Sistema atualizado", "Versão instalada: " + BuildConfig.VERSION_NAME + "\nVersão no servidor: " + remoteName); return; }
                    if (silent && (drivingMode || activeDialog != null)) return;
                    if (silent && !autoDownloadUpdates && remoteName.equals(notifiedVersion)) return;
                    notifiedVersion = remoteName; getPreferences(0).edit().putString("upd_notified", remoteName).apply();
                    newUpdateFound(remoteName, "", 0, updateApkBase + Uri.encode(apk), silent);
                });
            } catch (final Exception ex) {
                if (!silent) runOnUiThread(() -> popupInfo("Não foi possível verificar", "Confira se o PC e a central estão na mesma rede e se o servidor está aberto.\n\n" + ex.getMessage()));
            } finally { updateChecking = false; }
        }).start();
    }

    private void registerUpdateDownloadReceiver() {
        if(downloadReceiver!=null)return;
        downloadReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){
                if(DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(i.getAction()))
                    onDownloadDone(i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1));
            }
        };
        try {registerReceiver(downloadReceiver,new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));}
        catch(RuntimeException ex){downloadReceiver=null;logField("UPDATE","receiver_error="+ex.getClass().getSimpleName());}
    }
    private void clearUpdateDownload(){
        updateDownloadId=-1L;updateDownloadVersion="";
        getPreferences(0).edit().remove("upd_download_id").remove("upd_download_version").apply();
    }
    private void downloadUpdate(String url,String version,boolean automatic) {
        if(updateDownloadId>=0){checkStoredDownload();return;}
        if(url==null||(!(url.startsWith("https://")||url.startsWith("http://")))) {
            if(!automatic)popupInfo("Atualização","Endereço de download inválido");
            return;
        }
        // Downloads são aceitos somente da origem escolhida, nunca de um endereço fornecido por outro app.
        if(updateSource==0 && !url.startsWith("https://github.com/")){
            if(!automatic)popupInfo("Atualização","O endereço da Release não é confiável");
            return;
        }
        if(updateSource==1 && !url.startsWith(updateApkBase)){
            if(!automatic)popupInfo("Atualização","O APK não pertence ao servidor configurado");
            return;
        }
        try {
            File dir=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(dir==null)throw new IOException("Pasta de downloads inacessível");
            File old=new File(dir,"peugeot308-update.apk");
            if(old.exists()&&!old.delete())throw new IOException("Não consegui substituir APK antigo");
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Peugeot 308 • v"+version.replaceFirst("^v",""));
            req.setDescription("Nova versão sendo baixada automaticamente");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            req.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,"peugeot308-update.apk");
            DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            if(dm==null)throw new IOException("Gerenciador de downloads indisponível");
            updateDownloadVersion=version;
            updateInstallPromptShown=false;
            updateDownloadId=dm.enqueue(req);
            getPreferences(0).edit().putLong("upd_download_id",updateDownloadId)
                .putString("upd_download_version",version).apply();
            registerUpdateDownloadReceiver();
            logField("UPDATE","download_iniciado versao="+version+" automatico="+automatic);
            if(!automatic)snack("Baixando v"+version+"…");
        } catch(Exception ex){
            clearUpdateDownload();
            logField("UPDATE","erro_download="+ex.getClass().getSimpleName());
            if(!automatic)popupInfo("Falha no download",String.valueOf(ex.getMessage()));
        }
    }
    private boolean validDownloadedUpdate(){
        File dir=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(dir==null)return false;
        File apk=new File(dir,"peugeot308-update.apk");
        if(!apk.isFile()||apk.length()<20000)return false;
        try{
            PackageManager pm=getPackageManager();
            android.content.pm.PackageInfo downloaded=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNATURES);
            android.content.pm.PackageInfo installed=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNATURES);
            if(downloaded==null||installed==null||!getPackageName().equals(downloaded.packageName))return false;
            if(downloaded.versionCode<=BuildConfig.VERSION_CODE)return false;
            if(downloaded.signatures==null||installed.signatures==null||downloaded.signatures.length==0)return false;
            return java.util.Arrays.equals(downloaded.signatures,installed.signatures);
        } catch(Exception e){logField("UPDATE","apk_invalido="+e.getClass().getSimpleName());return false;}
    }
    private void checkStoredDownload(){
        if(updateDownloadId<0)return;
        onDownloadDone(updateDownloadId);
    }
    private void onDownloadDone(long id) {
        if(id<0||id!=updateDownloadId)return;
        DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        if(dm==null)return;
        android.database.Cursor c=null;
        int status=-1;
        try{
            c=dm.query(new DownloadManager.Query().setFilterById(id));
            if(c!=null&&c.moveToFirst())status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        }catch(Exception e){logField("UPDATE","consulta_download="+e.getClass().getSimpleName());return;}
        finally{if(c!=null)c.close();}
        if(status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PAUSED||status==DownloadManager.STATUS_PENDING)return;
        if(status!=DownloadManager.STATUS_SUCCESSFUL){
            clearUpdateDownload();
            logField("UPDATE","download_falhou");
            if(appForeground&&!drivingMode)snack("Atualização: download falhou. Tente mais tarde.");
            return;
        }
        if(!validDownloadedUpdate()){
            clearUpdateDownload();
            File dir=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(dir!=null)new File(dir,"peugeot308-update.apk").delete();
            logField("UPDATE","apk_rejeitado pacote_versao_ou_assinatura");
            if(appForeground&&!drivingMode)popupInfo("Atualização rejeitada","O APK baixado não corresponde ao launcher, não é mais novo ou não tem a assinatura original.");
            return;
        }
        if(updateInstallPromptShown||!appForeground||drivingMode||(activeDialog!=null&&activeDialog.isShowing()))return;
        Uri uri=dm.getUriForDownloadedFile(id);
        if(uri==null)return;
        updateInstallPromptShown=true;
        final String version=updateDownloadVersion;
        popupMessage("Atualização pronta",
            "A versão "+version+" foi baixada e verificada. Instalar agora? O Android pedirá sua confirmação.",
            "Instalar",()->{
                clearUpdateDownload();
                installApk(uri);
            },"Depois");
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
    /**
     * Solicita Wi-Fi somente se o usuário autorizou e o rádio está desligado.
     * O Android pode recusar setWifiEnabled em ROMs atuais ou sem privilégio OEM.
     * Não usa root, comandos shell, acessibilidade ou retries em loop.
     */
    /** Ciclo limitado: no máximo três verificações por despertar. Não força associação a SSID. */
    private void startWifiWakeMonitor(String reason) {
        if (!wifiAutoOn) return;
        wifiMonitorEpoch++;
        wifiWakeAttempts = 0;
        wifiAutoAttemptedThisWake = false;
        final long epoch = wifiMonitorEpoch;
        logField("WIFI", "monitor_iniciado motivo="+reason);
        handler.postDelayed(() -> wifiWakeCheck(epoch), 1800L);
    }
    private void wifiWakeCheck(long epoch) {
        if (epoch != wifiMonitorEpoch || !wifiAutoOn || isFinishing()) return;
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi == null) { recordWifiAutoStatus("Wi-Fi indisponível"); return; }
        boolean enabled;
        try { enabled = wifi.isWifiEnabled(); }
        catch (Exception ex) { logField("WIFI", "estado_indisponivel="+ex.getClass().getSimpleName()); return; }
        if (!enabled && wifiWakeAttempts == 0) tryAutoEnableWifi(false);
        refreshConnections();
        logField("WIFI", "checagem="+(wifiWakeAttempts+1)+" radio="+enabled+" internet="+wifiConnected);
        if (enabled && wifiConnected) {
            logField("WIFI", "reconectado_sem_abrir_configuracoes"); return;
        }
        if (enabled && !wifiConnected && wifiWakeAttempts == 2)
            recordWifiAutoStatus("Rádio Wi-Fi ligado; conexão à rede/internet ainda não confirmada");
        wifiWakeAttempts++;
        if (wifiWakeAttempts < 3) {
            long delay = wifiWakeAttempts == 1 ? 10000L : 30000L;
            handler.postDelayed(() -> wifiWakeCheck(epoch), delay);
        } else logField("WIFI", "monitor_encerrado; requer_firmware_ou_rede_salva");
    }
    private void recordWifiAutoStatus(String status) {
        wifiAutoStatus = status;
        logField("WIFI", status);
        getPreferences(0).edit()
                .putString("wifi_auto_status", status)
                .putLong("wifi_auto_status_at", System.currentTimeMillis()).apply();
    }
    private void tryAutoEnableWifi(boolean userRequested) {
        if (!wifiAutoOn) {
            if (userRequested) snack("Ative primeiro a opção de Wi-Fi automático");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!userRequested && (wifiAutoAttemptedThisWake ||
                (wifiAutoLastTryElapsed > 0 && now - wifiAutoLastTryElapsed < 20000L))) return;
        wifiAutoAttemptedThisWake = true;
        wifiAutoLastTryElapsed = now;
        final android.net.wifi.WifiManager wm =
                (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null) { recordWifiAutoStatus("Serviço Wi-Fi indisponível na Jancar"); if (userRequested) snack(wifiAutoStatus); return; }
        try {
            if (wm.isWifiEnabled()) {
                recordWifiAutoStatus("Wi-Fi já estava ligado; nenhuma ação necessária");
                if (userRequested) snack("Wi-Fi já está ativado");
                return;
            }
            boolean accepted = wm.setWifiEnabled(true);
            if (!accepted) {
                recordWifiAutoStatus("Android/Jancar recusou ativação automática; use Abrir Wi-Fi");
                if (userRequested) snack("Sistema bloqueou a ativação. Use Abrir Wi-Fi.");
                return;
            }
            recordWifiAutoStatus("Solicitação enviada; aguardando confirmação do Wi-Fi");
            if (userRequested) snack("Tentando ativar o Wi-Fi...");
            handler.postDelayed(() -> {
                if (!wifiAutoOn) return;
                try {
                    if (wm.isWifiEnabled()) recordWifiAutoStatus("Wi-Fi ativado com sucesso");
                    else recordWifiAutoStatus("Comando aceito, mas Wi-Fi ainda desligado; confira a Jancar");
                } catch (SecurityException e) {
                    recordWifiAutoStatus("Sem permissão para confirmar o estado do Wi-Fi");
                } catch (Exception e) {
                    recordWifiAutoStatus("Falha ao consultar o estado após solicitação");
                }
                refreshConnections();
                if (dashboard != null) dashboard.invalidate();
            }, 4000L);
        } catch (SecurityException e) {
            recordWifiAutoStatus("Permissão negada para alterar o Wi-Fi");
            if (userRequested) snack("Sem permissão para ativar Wi-Fi automaticamente");
        } catch (Exception e) {
            recordWifiAutoStatus("Falha na Jancar ao solicitar Wi-Fi: " + e.getClass().getSimpleName());
            if (userRequested) snack("Wi-Fi não pôde ser ativado automaticamente");
        }
    }
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
        boolean was = netOnline, wifiWas = wifiConnected;
        refreshConnections();
        if (netOnline && (!weatherCity.isEmpty() || weatherAuto) && !weatherLoading && (!was || weatherStale() || weatherFailed)) fetchWeather();
        if (dashboard != null && (netOnline != was || wifiConnected != wifiWas)) dashboard.invalidate();
        if (netOnline && !was) handler.postDelayed(this::maybeAutoUpdate, 8000);
        if (wifiConnected && pardalRadar != null) pardalRadar.checkDaily(true);
        if (netOnline != was || wifiConnected != wifiWas)
            logField("WIFI", "internet="+netOnline+" wifi="+wifiConnected);
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
    /** Cidade aproximada da posição GPS; não mostra coordenadas ao usuário. Executar somente em thread de rede. */
    private String gpsCityName(double lat, double lon) {
        try {
            android.location.Geocoder coder = new android.location.Geocoder(this, new Locale("pt", "BR"));
            List<android.location.Address> results = coder.getFromLocation(lat, lon, 1);
            if (results != null && !results.isEmpty()) {
                android.location.Address a = results.get(0);
                String city = a.getLocality();
                if (city == null || city.trim().isEmpty()) city = a.getSubAdminArea();
                if (city != null && !city.trim().isEmpty()) return city.trim();
            }
        } catch (Exception ignored) {}
        // Consulta ocasional durante a atualização do clima, não em cada posição GPS.
        try {
            JSONObject response = readJson("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&addressdetails=1&lat="
                + lat + "&lon=" + lon);
            JSONObject address = response.optJSONObject("address");
            if (address != null) {
                for (String k : new String[]{"city", "town", "municipality", "village", "county"}) {
                    String place = address.optString(k, "").trim();
                    if (!place.isEmpty()) return place;
                }
            }
        } catch (Exception ignored) {}
        return "Cidade não identificada";
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
                    lat = weatherAutoLat; lon = weatherAutoLon;
                    place = gpsCityName(lat, lon);
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
                    if (effectiveNight() != night) applyTheme();
                }
                dashboard.invalidate();
            });
        }).start();
    }
    private JSONObject readJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(7000); conn.setReadTimeout(7000); conn.setRequestProperty("User-Agent", "Pug308Launcher/1.32 (contato: github.com/lemiastkuosky86/Pug308)");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { conn.disconnect(); }
    }
    private String weatherDescription(int code) {
        if (code < 0) return "--";
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
                I_MINUS = 8, I_PLUS = 9, I_PREV = 10, I_NEXT = 11, I_PLAY = 12, I_PAUSE = 13, I_SPEAKER = 14, I_MUTE = 15, I_HOME = 16, I_WORK = 17, I_MENU = 18, I_TRIP = 19, I_BACK = 20, I_EQ = 21, I_YOUTUBE = 22, I_MAPS = 23, I_CHROME = 24, I_SUN = 26, I_MOON = 27, I_AUTO_BRIGHT = 28;
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

        // Atualização localizada: ~5 quadros/segundo so no desenho meteorologico.
        // Nunca acorda o GPS nem consulta a rede para desenhar as particulas.
        private static final long WEATHER_FRAME_MS = 200L;
        private boolean weatherAnimationRunning = false;
        private final Runnable weatherFrame = new Runnable() {
            @Override public void run() {
                if (!weatherAnimationRunning) return;
                if (weatherMotionEnabled && getWidth() > 0 && getHeight() > 0
                        && getWindowVisibility() == View.VISIBLE && isShown()) {
                    float scale = Math.min(getWidth()/1280f, getHeight()/720f);
                    float left = (getWidth() - 1280f*scale)/2f;
                    float top = (getHeight() - 720f*scale)/2f;
                    // Clima sempre na coluna x=32..362; animamos so a faixa do icone.
                    invalidate((int)(left+32f*scale), (int)(top+341f*scale),
                            (int)(left+331f*scale), (int)(top+488f*scale));
                }
                postDelayed(this, WEATHER_FRAME_MS);
            }
        };
        void setWeatherAnimationActive(boolean enabled) {
            removeCallbacks(weatherFrame);
            weatherAnimationRunning = enabled && weatherMotionEnabled;
            if (weatherAnimationRunning) postDelayed(weatherFrame, WEATHER_FRAME_MS);
        }
        @Override protected void onDetachedFromWindow() {
            setWeatherAnimationActive(false);
            super.onDetachedFromWindow();
        }
        Dashboard() { super(MainActivity.this); setHapticFeedbackEnabled(true); }
        void clearIcons() { iconCache.clear(); labelCache.clear(); }
        /** Clipa o redesenho ao progresso do Spotify, sem invalidar toda a tela por segundo. */
        void invalidateMediaProgress() {
            if (getWidth() == 0 || getHeight() == 0) return;
            float scale = Math.min(getWidth() / 1280f, getHeight() / 720f);
            float left = (getWidth() - 1280 * scale) / 2f;
            float top = (getHeight() - 720 * scale) / 2f;
            invalidate((int)(left + 380*scale), (int)(top + 310*scale),
                    (int)(left + 1250*scale), (int)(top + 555*scale));
        }
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
        boolean morphContentOnly = false;
        void card(float x, float y, float w, float h) {
            // Durante a transição os fundos são desenhados uma única vez, com geometria interpolada.
            if (morphContentOnly) return;
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
                case I_SUN: {
                    stroke(col,sw);canvas.drawCircle(0,0,.42f*u,p);
                    for (int n=0;n<8;n++) { double a=n*Math.PI/4;
                        canvas.drawLine((float)Math.cos(a)*.62f*u,(float)Math.sin(a)*.62f*u,
                                (float)Math.cos(a)*.92f*u,(float)Math.sin(a)*.92f*u,p); }
                    break;
                }
                case I_MOON: {
                    fill(col);canvas.drawCircle(0,0,.76f*u,p);
                    fill(cSurf2);canvas.drawCircle(.37f*u,-.26f*u,.73f*u,p);
                    break;
                }
                case I_AUTO_BRIGHT: {
                    stroke(col,sw);canvas.drawCircle(-.22f*u,0,.50f*u,p);
                    canvas.drawArc(-.54f*u,-.54f*u,.92f*u,.92f*u,-55,105,false,p);
                    canvas.drawLine(-.16f*u,-.91f*u,-.16f*u,-.72f*u,p);
                    break;
                }
                case I_YOUTUBE: {
                    fill(col); r.set(-.95f*u,-.65f*u,.95f*u,.65f*u);canvas.drawRoundRect(r,.27f*u,.27f*u,p);
                    fill(cSurf2);path.reset();path.moveTo(-.2f*u,-.4f*u);path.lineTo(.55f*u,0);path.lineTo(-.2f*u,.4f*u);path.close();canvas.drawPath(path,p);break;
                }
                case I_MAPS: {
                    stroke(col,sw);r.set(-.65f*u,-.9f*u,.65f*u,.4f*u);canvas.drawOval(r,p);
                    fill(col);path.reset();path.moveTo(-.45f*u,.15f*u);path.lineTo(0,.92f*u);path.lineTo(.45f*u,.15f*u);path.close();canvas.drawPath(path,p);
                    fill(cSurf2);canvas.drawCircle(0,-.3f*u,.2f*u,p);break;
                }
                case I_CHROME: {
                    stroke(col,sw*1.3f);canvas.drawCircle(0,0,.8f*u,p);canvas.drawCircle(0,0,.3f*u,p);
                    for(int i=0;i<3;i++){double a=i*2*Math.PI/3;canvas.drawLine((float)Math.cos(a)*.34f*u,(float)Math.sin(a)*.34f*u,(float)Math.cos(a+1.1f)*.75f*u,(float)Math.sin(a+1.1f)*.75f*u,p);}break;
                }
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
        /** Ícone meteorológico animado em Canvas, sem GIF, imagens ou bibliotecas extras.
         *  Fases dependem do relógio: não ficam acumulando timers nem objetos.
         *  O contorno do ícone é mantido para não sobrepor temperatura ou textos.
         */
        void weatherIcon(int code, float cx, float cy, float s) {
            long now = weatherMotionEnabled ? SystemClock.uptimeMillis() : 0L;
            float seconds = now / 1000f;
            boolean animate = weatherMotionEnabled;
            int sun = 0xffFFB84D, cl = night ? 0xffC9D3DF : 0xff9AA8BA;
            float cloudDx = animate ? (float)Math.sin(seconds*.75f)*s*.045f : 0f;
            float cloudDy = animate ? (float)Math.sin(seconds*.93f)*s*.017f : 0f;
            float pulse = animate ? 1f + .025f*(float)Math.sin(seconds*1.4f) : 1f;
            if (code == 0) {
                canvas.save();
                if (animate) canvas.rotate((seconds*4f)%360f,cx,cy);
                sunIcon(cx,cy,s*.8f*pulse,sun);
                canvas.restore();
                return;
            }
            if (code == 1 || code == 2) {
                float sx = cx-s*.16f, sy = cy-s*.16f;
                canvas.save();
                if (animate) canvas.rotate((seconds*3f)%360f,sx,sy);
                sunIcon(sx,sy,s*.56f*pulse,sun);
                canvas.restore();
                cloudIcon(cx+s*.08f+cloudDx, cy+s*.1f+cloudDy, s*.82f,cl);
                return;
            }
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
            boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
            boolean storm = code >= 95, fog = code >= 45 && code <= 48;
            float shift = (rain || snow || storm || fog) ? -s*.14f : 0f;
            cloudIcon(cx+cloudDx,cy+shift+cloudDy,s,cl);
            if (rain || storm) {
                stroke(accent,Math.max(2.5f,s*.064f));
                for (int i=0;i<3;i++) {
                    float phase = animate ? WeatherMotion.progress(now, 1050L, i, 3) : .45f;
                    float x=cx-s*.26f+i*s*.26f;
                    float y=cy+s*(.26f+.21f*phase);
                    canvas.drawLine(x,y,x-s*.065f,y+s*.10f,p);
                }
            }
            if (snow) {
                fill(cText);
                for (int i=0;i<4;i++) {
                    float phase = animate ? WeatherMotion.progress(now, 2200L, i, 4) : .55f;
                    float sway=animate ? (float)Math.sin(seconds*1.6f+i*1.7f)*s*.045f : 0f;
                    float x=cx-s*.32f+(i%4)*s*.20f+sway;
                    float y=cy+s*(.25f+.28f*phase);
                    canvas.drawCircle(x,y,s*.035f,p);
                }
            }
            if (storm) {
                int flash=animate && ((now/800L)%5L==0L) ? 0xffFFF1B5 : sun;
                fill(flash);
                float u=s/2f;
                path.reset();path.moveTo(cx+.12f*u,cy+.30f*u);
                path.lineTo(cx-.20f*u,cy+.78f*u);path.lineTo(cx+.02f*u,cy+.78f*u);
                path.lineTo(cx-.10f*u,cy+1.14f*u);path.lineTo(cx+.30f*u,cy+.62f*u);
                path.lineTo(cx+.08f*u,cy+.62f*u);path.close();canvas.drawPath(path,p);
            } else if (fog) {
                stroke(cl,Math.max(2.5f,s*.06f));
                float dx=animate ? (float)Math.sin(seconds*.9f)*s*.06f : 0f;
                canvas.drawLine(cx-s*.30f+dx,cy+s*.30f,cx+s*.30f+dx,cy+s*.30f,p);
                canvas.drawLine(cx-s*.20f-dx,cy+s*.44f,cx+s*.20f-dx,cy+s*.44f,p);
            }
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
        // Informações complementares menores, sem alterar temperatura e máxima/mínima.
        void statCellCompact(float x, float y, String label, String value) {
            text(label, x, y, 12, cDim, tfBold, Paint.Align.LEFT, .05f);
            t(fit(value, 18, tfMed, 134), x, y + 27, 18, cMuted, tfMed);
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

        // ---------- transição do rodapé: cards crescem sem imagens-fantasma,
        // enquanto os atalhos deslizam para baixo com leve fade.
        float homeBlend(float v) { return Math.max(0f, Math.min(1f, v)); }
        float homeSmooth(float v) { v = homeBlend(v); return v * v * (3f - 2f * v); }
        float homeLerp(float a, float b, float fraction) { return a + (b - a) * fraction; }
        void drawStandardHome() {
            drawClockWeather();
            if (vehicleMenuEnabled) { drawMedia(386, 508, false); drawVehicle(); }
            else drawMedia(386, 862, true, false);
        }
        void drawIdleHome() {
            final float left = 32f, right = 1248f, gap = 20f;
            final float weatherW = 330f, panelH = 592f;
            drawWeatherIdle(left, weatherW, panelH);
            float mediaLeft = left + weatherW + gap;
            if (vehicleMenuEnabled) {
                final float vehicleW = 330f;
                float vehicleLeft = right - vehicleW;
                drawMediaIdle(mediaLeft, vehicleLeft - gap - mediaLeft, panelH);
                drawVehicleIdle(vehicleLeft, vehicleW, panelH);
            } else drawMediaIdle(mediaLeft, right - mediaLeft, panelH);
        }
        void drawHomeMorphCards(float f) {
            float height = homeLerp(456f, 592f, f);
            card(32f, 96f, 330f, height);
            card(homeLerp(386f, 382f, f), 96f,
                    homeLerp(vehicleMenuEnabled ? 508f : 862f,
                             vehicleMenuEnabled ? 516f : 866f, f), height);
            if (vehicleMenuEnabled) card(918f, 96f, 330f, height);
        }
        void drawHomeLayer(boolean idle, float opacity, boolean interactive) {
            if (opacity <= 0.001f) return;
            int count = canvas.saveLayerAlpha(0, 74, 1280, 720,
                    Math.max(0, Math.min(255, Math.round(255 * opacity))));
            boolean previousHit = noHit;
            noHit = !interactive;
            morphContentOnly = true;
            try {
                if (idle) drawIdleHome(); else drawStandardHome();
            } finally {
                morphContentOnly = false;
                noHit = previousHit;
                canvas.restoreToCount(count);
            }
        }
        void drawAnimatedDock(float f) {
            // Atalhos visíveis com 2 ou 3 cards; só recolhem após 3 minutos.
            // Não deixa atalhos invisíveis capturarem toques após recolher.
            float visible = 1f - homeSmooth(f / .72f);
            if (visible < .003f) return;
            int count = canvas.saveLayerAlpha(0f, 550f, 1280f, 720f,
                    Math.round(255f * visible));
            canvas.translate(0f, 122f * homeSmooth(f));
            boolean prior = noHit;
            noHit = f > .08f;
            try { drawDock(); } finally {
                noHit = prior;
                canvas.restoreToCount(count);
            }
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); canvas = c; hits.clear();
            c.drawColor(cBg0);
            float s = Math.min(getWidth() / 1280f, getHeight() / 720f);
            sx = sy = s; ox = (getWidth() - 1280 * s) / 2f; oy = (getHeight() - 720 * s) / 2f;
            c.save(); c.translate(ox, oy); c.scale(s, s);
            p.reset(); p.setShader(new LinearGradient(0, 0, 0, 720, cBg0, cBg1, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1280, 720, p); p.setShader(null);
            if (albumBg && Color.alpha(artCur) > 0) drawArtGlow();
            drawHeader();
            long now = SystemClock.uptimeMillis();
            // Com dois OU três cards, os atalhos aparecem normalmente.
            // Apenas 3 minutos sem tocar recolhem a Home (quando habilitado).
            boolean idleTarget = immersiveIdle && !drivingMode &&
                    (now - lastHomeInteraction >= HOME_IDLE_MS);
            if (idleTarget != lastHomeIdleTarget) {
                lastHomeIdleTarget = idleTarget;
                homeIdleTransitionFrom = homeIdleFraction;
                homeIdleTransitionAt = now;
            }
            float progress = homeBlend((now - homeIdleTransitionAt) / (float) HOME_TRANSITION_MS);
            // Ease-in-out cúbico: início e fim suaves, sem salto na reversão.
            float ease = progress < .5f ? 4f * progress * progress * progress
                    : 1f - (float) Math.pow(-2f * progress + 2f, 3f) / 2f;
            homeIdleFraction = homeIdleTransitionFrom + ((idleTarget ? 1f : 0f) - homeIdleTransitionFrom) * ease;
            float f = homeBlend(homeIdleFraction);
            if (progress >= 1f && f < .001f) {
                // Estado normal: desenha diretamente, sem buffers/efeitos desnecessários.
                drawStandardHome();
                drawDock();
            } else if (progress >= 1f && f > .999f) {
                // Estado recolhido: também sem camadas intermediárias (melhor no RK3326).
                drawIdleHome();
            } else {
                // Apenas durante os 780 ms: interpola os cartões sem duplicar seus fundos.
                drawHomeMorphCards(f);
                float idleOpacity = homeSmooth((f - .20f) / .60f);
                float normalOpacity = 1f - idleOpacity;
                if (normalOpacity > .001f) drawHomeLayer(false, normalOpacity, f < .5f);
                if (idleOpacity > .001f) drawHomeLayer(true, idleOpacity, f >= .5f);
                drawAnimatedDock(f);
            }
            if (progress < 1f) postInvalidateOnAnimation();
            drawVolume(); drawAlertPopup(); drawPardalPopup(); drawRadarModeNotice();
            // O modo do radar aparece no popup do launcher sem abrir um menu.
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
            ArrayList<Integer> positions = new ArrayList<>();
            for (int j = 0; j < topSlot.length; j++) {
                int item = topSlot[j];
                if (item >= -4 && item != -1 && item < vehicleNames.length) positions.add(j);
            }
            if (positions.isEmpty()) return;
            float gap = 8, available = Math.max(0, 596 - startX);
            float w = Math.min(positions.size() == 1 ? 180 : positions.size() == 2 ? 158 : 122,
                    (available - gap * (positions.size() - 1)) / positions.size());
            if (w < 70) return;
            float firstX = startX + Math.max(0, (available - (w * positions.size() + gap * (positions.size() - 1))) / 2f);
            float spd = speedNow();
            boolean critical = speedVisualOn() && spd >= 0 && spd > speedLimit * 1.15f;
            boolean flashOn = (SystemClock.uptimeMillis() / 450L) % 2L == 0L;
            for (int i = 0; i < positions.size(); i++) {
                int slot = positions.get(i), id = topSlot[slot];
                float x = firstX + i * (w + gap), y = 10, h = 52;
                // O identificador corresponde ao slot salvo, não à posição na tela.
                hit(60 + slot, x, y, w, h);
                String title, val, unit;
                if (id == -4) {
                    title = "CLIMA"; val = weatherLine != null && weatherLine.contains("°") ? weatherLine : null; unit = "";
                } else if (id == -3) {
                    title = "MÚSICA";
                    val = (mediaCtl != null || cachedSpotifyPreview) && songTitle != null && !songTitle.isEmpty()
                            ? songTitle : null;
                    unit = "";
                } else if (id == -2) {
                    title = "RELÓGIO";
                    val = new SimpleDateFormat("HH:mm", new Locale("pt", "BR")).format(new java.util.Date());
                    unit = "";
                } else {
                    title = vehicleShort[id]; val = vehicleValue(id); unit = vehicleUnits[id];
                }
                boolean live = val != null && !val.trim().isEmpty();
                boolean over = speedVisualOn() && id == 0 && live && spd > speedLimit;
                boolean flashing = over && critical && flashOn;
                int cardBg = flashing ? cBad : (over ? mix(cSurf2, cBad, .34f) : cSurf2);
                int titleColor = flashing ? Color.WHITE : (over ? cBad : cDim);
                int valueColor = flashing ? Color.WHITE : (over ? cBad : (live ? cText : cDim));
                rr(x, y, w, h, 13, cardBg);
                rrs(x, y, w, h, 13, cHair, 1f);
                text(fit(title, 11, tfBold, w - 14), x + w / 2f, y + 18, 11, titleColor, tfBold, Paint.Align.CENTER, .04f);
                String shown = live ? val : "--";
                float uw = unit.isEmpty() ? 0 : measure(unit, 12, tfReg, 0) + 4, vs = 22;
                // Texto das músicas ocupa mais espaço: reduzimos a fonte, nunca extrapola a borda.
                if (id == -3) vs = 15;
                while (vs > 12 && measure(shown, vs, tfBold, 0) + uw > w - 18) vs -= 1;
                shown = fit(shown, vs, tfBold, Math.max(8, w - 18 - uw));
                float tw = measure(shown, vs, tfBold, 0) + uw;
                float valueX = x + (w - tw) / 2f;
                t(shown, valueX, y + 43, vs, valueColor, tfBold);
                if (!unit.isEmpty()) t(unit, valueX + measure(shown, vs, tfBold, 0) + 4,
                        y + 43, 12, flashing ? Color.WHITE : cDim, tfReg);
            }
        }

        // Mesmo padrão dos mini-cards: y=10, altura=52, raio=13 e contorno leve.
        void topPill(int id,float x,float w,int kind,String label,boolean active) {
            boolean pr=hit(id,x,10,w,52);
            rr(x,10,w,52,13,pr?mix(cSurf2,accent,.24f):cSurf2);
            rrs(x,10,w,52,13,cHair,1f);
            // Conjunto ícone + texto centralizado, com a mesma hierarquia dos mini-cards.
            float fs = w < 77 ? 10 : 11;
            float iconSize = w < 77 ? 16 : 19;
            float gapLabel = 5;
            String visibleLabel = fit(label, fs, tfBold, Math.max(8, w - iconSize - gapLabel - 12));
            float labelWidth = measure(visibleLabel, fs, tfBold, 0);
            float groupWidth = iconSize + gapLabel + labelWidth;
            float groupX = x + (w - groupWidth)/2f;
            ic(kind, groupX + iconSize/2f, 36, iconSize, active ? accent : cDim);
            t(visibleLabel, groupX + iconSize + gapLabel, 40, fs, cText, tfBold);
        }

        void topVolumeKey(int id, float x, float w, int kind, String description, boolean active) {
            boolean pressed = hit(id, x, 10, w, 52);
            if (pressed) rr(x + 4, 15, w - 8, 42, 9, mix(cSurf2, accent, .28f));
            ic(kind, x + w/2f, 36, 22, active ? cBad : accent);
        }

        void drawHeader() {
            rr(0, 0, 1280, 72, 0, cHead); ln(0, 72, 1280, 72, cHair, 1.5f);
            float brandFont=25;
            while(brandFont>16 && measure(brandTitle,brandFont,tfBold,.025f)>270)brandFont--;
            float wm = measure(brandTitle, brandFont, tfBold, .025f), w308 = 0, gx = 32 + wm;
            long sm = SystemClock.uptimeMillis() % SHINE_PERIOD; // brilho que corre pelo logo a cada poucos segundos
            float sweep = sm < SHINE_MS ? 10 + (sm / (float) SHINE_MS) * (wm + w308 + 70) : Float.NaN;
            // Brilho perceptível mesmo quando os botões e as letras são brancos no tema preto.
            int logoBase=cText, logoShine=accent;
            if(Math.abs(lum(cText)-lum(accent))<.22f){
                if(lum(cText)>.64f){logoBase=0xffA5ACB7;logoShine=Color.WHITE;}
                else logoShine=0xffE3E8EE;
            }
            shineText(brandTitle,32,47,brandFont,logoBase,logoShine,tfBold,.025f,sweep);
            hit(51, 23, 7, wm + 16, 58); // toque no logotipo abre a tela de descanso
            float dvx = gx + w308 + 14;
            if (topWidgetEnabled) ln(dvx, 22, dvx, 50, cHair, 1.5f);
            drawTopWidget(dvx + 14);
            // Grupos proporcionais e espaços reais entre botões; não sobrepor rótulos.
            if (topVolumeVisible) {
                // Um único conjunto: menos, mudo e mais. Áreas de toque independentes.
                ln(598, 20, 598, 52, cHair, 1.5f);
                rr(606, 10, 204, 52, 14, cSurf2);
                rrs(606, 10, 204, 52, 14, cHair, 1.1f);
                ln(674, 18, 674, 54, cHair, 1f);
                ln(742, 18, 742, 54, cHair, 1f);
                topVolumeKey(1, 606, 68, I_MINUS, "Diminuir volume", false);
                topVolumeKey(3, 674, 68, isMuted() ? I_MUTE : I_SPEAKER, "Alternar mudo", isMuted());
                topVolumeKey(2, 742, 68, I_PLUS, "Aumentar volume", false);
                ln(818, 20, 818, 52, cHair, 1.5f);
            }
            // Até quatro atalhos opcionais + Config, com largura proporcional.
            float left = topVolumeVisible ? 826 : 615;
            float right = 1256, spacing = 8;
            // 4 atalhos rápidos + Config fixo. Wi-Fi e Bluetooth são um único submenu.
            ArrayList<Integer> choices = new ArrayList<>();
            if(topBtVisible||topWifiVisible)choices.add(48);
            choices.add(50); // botão Radar fixo perto de Rede
            if(topBrightnessVisible)choices.add(49); // ao lado de Rede, respeitando limite de atalhos
            if(topPcVisible)choices.add(43);
            if(topEqVisible)choices.add(7); // EQ sempre abre o painel COMPLETO
            if(topCustomVisible&&!topCustomPackage.isEmpty())choices.add(47);
            while(choices.size()>5)choices.remove(choices.size()-1);
            int count=choices.size()+1;
            float itemW=Math.min(136,(right-left-spacing*(count-1))/count);
            float at=right-itemW*count-spacing*(count-1);
            for(int id:choices){
                boolean enabled=id==48?(wifiConnected||btConnected):id==43?vehicleMenuEnabled:id==7?(snd!=null&&snd.on):true;
                if(id==48)topPill(id,at,itemW,I_WIFI,"Rede",enabled);
                else if(id==50)topPill(id,at,itemW,I_TRIP,"Radar",pardalRadar!=null&&pardalRadar.mode()!=PardalRadar.OFF);
                else if(id==49)topPill(id,at,itemW,
                        brightnessMode==BRIGHT_MIN?I_MOON:brightnessMode==BRIGHT_MAX?I_SUN:I_AUTO_BRIGHT,
                        "Brilho",true);
                else if(id==43)topPill(id,at,itemW,I_TRIP,"Veículo",enabled);
                else if(id==7)topPill(id,at,itemW,I_EQ,"EQ",enabled);
                else if(id==47)topPill(id,at,itemW,I_GRID,fit(appLabel(topCustomPackage),12,tfMed,itemW-38),true);
                at+=itemW+spacing;
            }
            noHit = false;
            // Configurações sempre visíveis, inclusive em movimento.
            topPill(6,at,itemW,I_GEAR,"Config",true);
        }

        // v1.23: cards do modo recolhido usam a MESMA grade, sem deformar conteúdo.
        void drawWeatherIdle(float x, float w, float h) {
            float y = 96, cx = x + w/2;
            card(x,y,w,h);
            refreshClockText();
            tc(clockText,cx,y+112,Math.min(90,w*.19f),cText,tfLight);
            tc(fit(clockDateText,19,tfReg,w-35),cx,y+156,19,cMuted,tfReg);
            ln(x+24,y+177,x+w-24,y+177,cHair,1.5f);
            boolean hasWx = (weatherAuto || !weatherCity.isEmpty()) && weatherLine.endsWith("°C");
            if (!hasWx) {
                weatherIcon(3,cx,y+292,82);
                tc(fit(weatherLoading?"Buscando previsão…":weatherCity.isEmpty() && !weatherAuto?"Escolha sua cidade":"Previsão indisponível",21,tfMed,w-30),cx,y+383,21,cText,tfMed);
                tc("Toque para configurar",cx,y+419,16,cMuted,tfReg);
            } else {
                tc(fit(weatherDetail.isEmpty()?weatherCity:weatherDetail,27,tfBold,w-38),cx,y+219,27,cText,tfBold);
                tc(fit(weatherCondition,18,tfReg,w-40),cx,y+255,18,cMuted,tfReg);
                weatherIcon(weatherCode,cx-63,y+330,72);
                t(weatherLine,cx-20,y+347,Math.min(50,w*.115f),cText,tfBold);
                float dx = w>500?124:100;
                text("MÁX / MÍN",cx-dx,y+423,13,cDim,tfBold,Paint.Align.LEFT,0);
                text("CHUVA",cx+12,y+423,11,cDim,tfBold,Paint.Align.LEFT,0);
                t(fit(weatherHigh+" / "+weatherLow,19,tfMed,dx+4),cx-dx,y+449,19,cText,tfMed);
                t(fit(weatherRain,16,tfMed,dx-18),cx+12,y+449,16,cMuted,tfMed);
                text("VENTO",cx-dx,y+499,11,cDim,tfBold,Paint.Align.LEFT,0);
                text("UMIDADE",cx+12,y+499,11,cDim,tfBold,Paint.Align.LEFT,0);
                t(fit(weatherWind,16,tfMed,dx+4),cx-dx,y+525,16,cMuted,tfMed);
                t(fit(weatherHumidity,16,tfMed,dx-18),cx+12,y+525,16,cMuted,tfMed);
            }
            hit(30,x,y+180,w,h-180);
        }

        void drawMediaIdle(float x,float w,float h) {
            float y=96;
            card(x,y,w,h);
            float pad=12,ax=x+pad,ay=y+pad,aw=w-2*pad,ah=h-2*pad;
            canvas.save();path.reset();r.set(ax,ay,ax+aw,ay+ah);
            path.addRoundRect(r,22,22,Path.Direction.CW);canvas.clipPath(path);
            if(albumArt!=null && !albumArt.isRecycled()){
                // A capa é ampliada por crop central, preservando proporções originais.
                p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);
                int bw=albumArt.getWidth(),bh=albumArt.getHeight();
                float srcRatio=bw/(float)bh,dstRatio=aw/ah;
                int sw=bw,sh=bh;
                if(srcRatio>dstRatio)sw=Math.max(1,Math.round(bh*dstRatio));
                else sh=Math.max(1,Math.round(bw/dstRatio));
                canvas.drawBitmap(albumArt,new Rect((bw-sw)/2,(bh-sh)/2,(bw+sw)/2,(bh+sh)/2),r,p);
            } else {
                rr(ax,ay,aw,ah,22,cSurf2);
                ic(I_NOTE,ax+aw/2,ay+ah*.44f,Math.min(120,aw*.25f),accent);
            }
            // Degradê interno no rodapé, com tipografia legível sobre qualquer capa.
            p.reset();p.setAntiAlias(true);
            p.setShader(new LinearGradient(ax,ay+ah*.49f,ax,ay+ah,
                new int[]{0x00000000,0xBB000000,0xF5000000},null,Shader.TileMode.CLAMP));
            canvas.drawRect(ax,ay+ah*.49f,ax+aw,ay+ah,p);p.setShader(null);
            float tx=ax+22,ty=ay+ah-91;
            t(fit(songTitle,Math.min(30,w*.055f),tfBold,aw-44),tx,ty,Math.min(30,w*.055f),Color.WHITE,tfBold);
            t(fit(songArtist,Math.min(20,w*.04f),tfReg,aw-44),tx,ty+33,Math.min(20,w*.04f),0xffDDDDDD,tfReg);
            t(mediaIsSpotify?"● Spotify":"♫ "+mediaSource,tx,ty+64,13,0xffA4E5B6,tfMed);
            canvas.restore();
            // No modo recolhido o toque serve para revelar os botões, não abrir o Spotify.
        }

        void drawVehicleIdle(float x,float w,float h) {
            float y=96;
            card(x,y,w,h);
            text("VEÍCULO",x+24,y+38,17,cMuted,tfMed,Paint.Align.LEFT,.1f);
            circle(x+w-135,y+33,4.5f,gpsFresh()?cOk:cDim);
            t("GPS",x+w-124,y+38,13,cMuted,tfMed);
            circle(x+w-70,y+33,4.5f,hasCanData()?cOk:cDim);
            t(vehicleDataSource==1?"OBD":"CAN",x+w-60,y+38,13,cMuted,tfMed);
            float gap=8,bw=(w-48-2*gap)/3f,by=y+60;
            int[] kinds={I_MENU,I_TRIP,I_BACK};
            for(int i=0;i<3;i++) {
                float bx=x+24+i*(bw+gap);
                boolean pr=hit(40+i,bx,by,bw,64);
                rr(bx,by,bw,64,14,pr?mix(cSurf2,accent,.45f):cSurf2);
                ic(kinds[i],bx+bw/2,by+23,22,accent);
                tc(fit(vbtnLabel[i].toUpperCase(Locale.getDefault()),12,tfBold,bw-8),bx+bw/2,by+51,12,cText,tfBold);
            }
            // Mesmo conteúdo configurado na tela normal, sem histórico nem páginas ocultas.
            drawSelectableVehicleCards(x, y+150, w, h-174, true);
            hit(43,x+18,y+4,120,46);
        }

        void drawSelectableVehicleCards(float x, float y, float w, float availableH, boolean roomy) {
            float gap=12f;
            int n=vehicleCardCount;
            float cardH=(availableH-(n-1)*gap)/n;
            for (int i=0;i<n;i++) {
                int id=vehicleCardSlots[i];
                String value=vehicleValue(id);
                float cy=y+i*(cardH+gap);
                if (i==2 && vehicleThirdVisualMode!=0) {
                    drawThirdVehicleVisual(x+24, cy, w-48, cardH);
                    continue;
                }
                boolean overSpeed=id==0 && speedVisualOn() && value!=null && speedNow()>speedLimit;
                if (overSpeed) rr(x+24,cy,w-48,cardH,16,mix(cSurf2,cBad,.40f));
                else rr(x+24,cy,w-48,cardH,16,cSurf2);
                String shown=value==null?"--":value;
                String label=vehicleShort[id];
                float labelSize=roomy?15:13;
                tc(fit(label,labelSize,tfBold,w-66),x+w/2,cy+(roomy?34:26),labelSize,overSpeed?cBad:cDim,tfBold);
                float size=roomy?Math.min(50,cardH*.37f):Math.min(43,cardH*.42f);
                if (size<24)size=24;
                float unitSize=roomy?17:15;
                float avail=w-68;
                float textWidth=measure(shown,size,tfBold,0);
                float unitsWidth=vehicleUnits[id].isEmpty()?0:measure(vehicleUnits[id],unitSize,tfReg,0)+7;
                while(size>22 && textWidth+unitsWidth>avail){size-=2;textWidth=measure(shown,size,tfBold,0);}
                float baseline=cy+cardH*.70f;
                t(shown,x+w/2-(textWidth+unitsWidth)/2,baseline,size,overSpeed?cBad:cText,tfBold);
                if(unitsWidth>0)t(vehicleUnits[id],x+w/2-(textWidth+unitsWidth)/2+textWidth+7,baseline,unitSize,cDim,tfReg);
            }
        }

        /** Silhueta vetorial premium do 308, com informações CAN reais e sem estados artificiais. */
        void drawThirdVehicleVisual(float x, float y, float w, float h) {
            rr(x,y,w,h,16,cSurf2);
            canvas.save();
            canvas.clipRect(x+3,y+3,x+w-3,y+h-3);
            Paint finish=new Paint(Paint.ANTI_ALIAS_FLAG);
            finish.setShader(new LinearGradient(x,y,x+w,y+h,
                new int[]{mix(cSurf2,cBg0,.25f),cSurf2,mix(cSurf2,accent,.085f)},
                null,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(x+2,y+2,x+w-2,y+h-2,15,15,finish);
            finish.setShader(null);

            boolean carOnly=vehicleThirdVisualMode==2;
            boolean live=vehicleDataSource==0 && hasCanData()
                    && canbusSnapshot!=null && canbusSnapshot.cabin!=null;
            int[] cabin=live?canbusSnapshot.cabin:null;
            if(cabin!=null&&cabin.length<17)cabin=null;
            float carH=Math.min(h-18f,132f);
            float carW=carH*.47f;
            float carX=carOnly?x+(w-carW)*.5f:x+17f;
            float carY=y+(h-carH)*.5f;
            float cx=carX+carW*.5f;

            // Área iluminada sob o carro, discreta até mesmo no tema claro.
            finish.setShader(new RadialGradient(cx,carY+carH*.55f,
                Math.max(carW*.95f,26f),
                new int[]{mix(cSurf2,accent,.19f),mix(cSurf2,accent,.04f),cSurf2},
                new float[]{0f,.72f,1f},Shader.TileMode.CLAMP));
            canvas.drawOval(carX-carW*.52f,carY-5f,carX+carW*1.52f,carY+carH+5f,finish);
            finish.setShader(null);

            // Rodas, contorno e carroceria em vista superior do 308 hatch.
            int rubber=0xff171b22;
            rr(carX-carW*.13f,carY+carH*.22f,carW*.19f,carH*.16f,3,rubber);
            rr(carX+carW*.94f,carY+carH*.22f,carW*.19f,carH*.16f,3,rubber);
            rr(carX-carW*.13f,carY+carH*.65f,carW*.19f,carH*.16f,3,rubber);
            rr(carX+carW*.94f,carY+carH*.65f,carW*.19f,carH*.16f,3,rubber);
            Path shell=new Path();
            shell.moveTo(cx,carY);
            shell.cubicTo(carX+carW*.88f,carY+carH*.02f,carX+carW,carY+carH*.12f,carX+carW,carY+carH*.25f);
            shell.lineTo(carX+carW,carY+carH*.76f);
            shell.cubicTo(carX+carW,carY+carH*.93f,carX+carW*.84f,carY+carH, cx,carY+carH);
            shell.cubicTo(carX+carW*.16f,carY+carH,carX,carY+carH*.93f,carX,carY+carH*.76f);
            shell.lineTo(carX,carY+carH*.25f);
            shell.cubicTo(carX,carY+carH*.12f,carX+carW*.12f,carY+carH*.02f,cx,carY);
            shell.close();
            finish.setColor(0x55000000);
            canvas.save();canvas.translate(1.5f,3f);canvas.drawPath(shell,finish);canvas.restore();
            finish.setShader(new LinearGradient(carX,carY,carX+carW,carY+carH,
                new int[]{0xffe8edf1,0xff8796a7,0xff495969,0xffb6c3ce},
                new float[]{0f,.36f,.76f,1f},Shader.TileMode.CLAMP));
            canvas.drawPath(shell,finish);
            finish.setShader(null);
            finish.setStyle(Paint.Style.STROKE);finish.setStrokeWidth(1.3f);finish.setColor(mix(cText,cSurf2,.43f));
            canvas.drawPath(shell,finish);finish.setStyle(Paint.Style.FILL);

            // Parabrisa, teto panorâmico e vidro traseiro com reflexo.
            rr(carX+carW*.13f,carY+carH*.18f,carW*.74f,carH*.17f,5,0xff233546);
            rr(carX+carW*.13f,carY+carH*.39f,carW*.74f,carH*.29f,4,0xff263342);
            rr(carX+carW*.17f,carY+carH*.73f,carW*.66f,carH*.14f,4,0xff344657);
            ln(carX+carW*.16f,carY+carH*.19f,carX+carW*.84f,carY+carH*.19f,0x99e3e9f1,1.2f);
            ln(carX+carW*.17f,carY+carH*.72f,carX+carW*.83f,carY+carH*.72f,0x8899aab8,.9f);
            ln(carX+carW*.17f,carY+carH*.45f,carX+carW*.83f,carY+carH*.45f,0x558c9aaa,1f);
            rr(carX+carW*.18f,carY+carH*.025f,carW*.23f,Math.max(2f,carH*.025f),2,0xffd5e3f4);
            rr(carX+carW*.59f,carY+carH*.025f,carW*.23f,Math.max(2f,carH*.025f),2,0xffd5e3f4);
            rr(carX+carW*.15f,carY+carH*.95f,carW*.24f,Math.max(2f,carH*.022f),2,0xffad3340);
            rr(carX+carW*.61f,carY+carH*.95f,carW*.24f,Math.max(2f,carH*.022f),2,0xffad3340);

            int muted=mix(cDim,cSurf2,.38f);
            float dot=Math.max(2.7f,Math.min(5.2f,carW*.087f));
            boolean showDoors=vehicleThirdVisualMode!=3 || (vehicleThirdVisualFlags&1)!=0;
            if(showDoors){
                for(int i=0;i<4;i++){
                    int index=i+1;
                    boolean opened=cabin!=null&&cabin[index]==1;
                    float yy=carY+carH*(i<2?.32f:.66f);
                    float xx=i%2==0?carX-5:carX+carW+5;
                    circle(xx,yy,dot,opened?cBad:muted);
                    if(opened) {
                        finish.setStyle(Paint.Style.STROKE);finish.setStrokeWidth(1.5f);
                        finish.setColor(cBad);canvas.drawCircle(xx,yy,dot+3.3f,finish);
                        finish.setStyle(Paint.Style.FILL);
                    }
                }
            }
            if(vehicleThirdVisualMode!=3||(vehicleThirdVisualFlags&2)!=0){
                boolean opened=cabin!=null&&cabin[5]==1;
                circle(cx,carY+carH+3,dot,opened?cBad:muted);
                if(opened){
                    finish.setColor(cBad);finish.setStyle(Paint.Style.STROKE);
                    finish.setStrokeWidth(1.5f);
                    canvas.drawCircle(cx,carY+carH+3,dot+3,finish);
                    finish.setStyle(Paint.Style.FILL);
                }
            }

            if(!carOnly) {
                float tx=carX+carW+18f, areaW=x+w-tx-10f;
                if(areaW>75f){
                    int titleColor=live?cOk:cDim;
                    t(live?"DADOS DO VEÍCULO":"AGUARDANDO CANBUS",tx,y+17f,10,titleColor,tfBold);
                    String[] labels={"PORTAS","PORTA-MALAS","TRAVAS","FARÓIS","SETAS","FREIO","MOTOR","TANQUE"};
                    String[] vals={"--","--","--","--","--","--","--","--"};
                    int[] colors={cDim,cDim,cDim,cDim,cDim,cDim,cDim,cDim};
                    if(cabin!=null){
                        boolean open=false;for(int i=1;i<=4;i++)if(cabin[i]==1)open=true;
                        if(open){vals[0]="ABERTA";colors[0]=cBad;}
                        else if((observedOpenDoors&15)!=0){vals[0]="FECHADAS";colors[0]=cOk;}
                        if(cabin[5]==1){vals[1]="ABERTO";colors[1]=cBad;}
                        else if((observedOpenDoors&16)!=0){vals[1]="FECHADO";colors[1]=cOk;}
                    }
                    if(live && canbusSnapshot.lights!=null && canbusSnapshot.lights.length>=3){
                        int[] light=canbusSnapshot.lights;
                        if(light[0]==1 || light[1]==1 || light[2]==1){
                            vals[4]="ATIVAS";colors[4]=accent;
                        }
                    }
                    if(hasCanData()&&!"--".equals(vehicleValues[9])){vals[6]=vehicleValues[9]+"°";colors[6]=cText;}
                    if(hasCanData()&&!"--".equals(vehicleValues[3])){vals[7]=vehicleValues[3]+"%";colors[7]=cText;}
                    int count=0;
                    for(int i=0;i<8;i++)if(vehicleThirdVisualMode!=3||(vehicleThirdVisualFlags&(1<<i))!=0)count++;
                    if(count==0){
                        t("Selecione os indicadores",tx,y+h*.60f,10,cMuted,tfReg);
                    }else{
                        int rows=(count+1)/2;
                        float top=y+24f, usableH=Math.max(22f,h-28f);
                        float rowH=usableH/Math.max(1,rows);
                        float gap=4f,cellW=(areaW-gap)/2f;
                        int k=0;
                        for(int i=0;i<8;i++){
                            if(vehicleThirdVisualMode==3&&(vehicleThirdVisualFlags&(1<<i))==0)continue;
                            int col=k%2,row=k/2;
                            float bx=tx+col*(cellW+gap),by=top+row*rowH;
                            rr(bx,by,cellW,Math.max(12,rowH-2f),5,mix(cSurf2,cBg0,.18f));
                            float font=Math.max(7f,Math.min(9f,rowH*.42f));
                            t(fit(labels[i],font,tfMed,cellW-6f),bx+4f,by+rowH*.42f,font,cMuted,tfMed);
                            t(fit(vals[i],font+1,tfBold,cellW-6f),bx+4f,by+rowH*.80f,font+1,colors[i],tfBold);
                            k++;
                        }
                    }
                }
            }
            canvas.restore();
        }

        void drawClockWeather() {
            float x = 32, y = 96, w = 330, h = 456, cx = x + w / 2;
            card(x, y, w, h);
            refreshClockText();
            tc(clockText, cx, 202, 92, cText, tfLight);
            tc(clockDateText, cx, 240, 20, cMuted, tfReg);
            ln(x + 24, 264, x + w - 24, 264, cHair, 1.5f);
            boolean hasWx = (weatherAuto || !weatherCity.isEmpty()) && weatherLine.endsWith("°C");
            if (!hasWx) {
                weatherIcon(3, cx, 372, 84);
                if (weatherCity.isEmpty() && !weatherAuto) { tc("Escolha sua cidade", cx, 450, 24, cText, tfMed); tc("Toque para configurar", cx, 482, 17, cMuted, tfReg); }
                else { tc(weatherLoading ? "Buscando previsão…" : "Previsão indisponível", cx, 450, 24, cText, tfMed); tc(fit(weatherCity, 17, tfReg, 280) + (weatherLoading ? "" : "  •  toque para tentar"), cx, 482, 17, cMuted, tfReg); }
            } else {
                boolean offline = !netOnline || weatherFailed;
                String cityName = weatherDetail.isEmpty() ? weatherCity : weatherDetail;
                t(fit(cityName, 26, tfBold, w - 48), x + 24, 309, 26, cText, tfBold);
                t(fit(weatherCondition, 18, tfReg, w - 48), x + 24, 339, 18, cMuted, tfReg);
                String stamp = !netOnline || weatherFailed ? "offline" :
                        weatherLoading ? "Atualizando…" : (lastWeatherSuccess > 0
                        ? "atualizado há " + ((SystemClock.elapsedRealtime() - lastWeatherSuccess) / 60000) + " min" : "");
                t(fit(stamp, 13, tfReg, w - 48), x + 24, 359, 13, cDim, tfReg);
                weatherIcon(weatherCode, x + 68, 402, 74);
                t(fit(weatherLine, 55, tfBold, 195), x + 126, 416, 55, cText, tfBold);
                statCell(x + 24, 438, "MÁX / MÍN", weatherHigh + " / " + weatherLow);
                statCellCompact(x + 174, 438, "CHUVA", weatherRain);
                statCellCompact(x + 24, 494, "VENTO", weatherWind);
                statCellCompact(x + 174, 494, "UMIDADE", weatherHumidity);
            }
            hit(30, x, 268, w, y + h - 268);
        }

        void drawMedia(float x, float w, boolean wide) { drawMedia(x,w,wide,false); }
        void drawMedia(float x, float w, boolean wide, boolean expanded) {
            float y = 96, h = expanded ? 592 : 456;
            card(x, y, w, h);
            int srcCol = mediaIsSpotify ? 0xff1ED760 : accent;
            circle(x + 40, y + 42, 22, srcCol); ic(I_NOTE, x + 40, y + 42, 24, mediaIsSpotify ? 0xff06210F : onAccent);
            t(fit(mediaSource, 21, tfMed, 230), x + 76, y + 50, 21, cMuted, tfMed);
            if (mediaCtl != null) {
                float pw = 156, px = x + w - 24 - pw, py = y + 25;
                rr(px, py, pw, 34, 17, mediaPlaying ? mix(cSurf2, cOk, .22f) : cSurf2);
                text(mediaPlaying ? "REPRODUZINDO" : "PAUSADO", px + pw / 2, py + 23, 14, mediaPlaying ? cOk : cMuted, tfBold, Paint.Align.CENTER, .06f);
            }
            // capa grande e quadrada (recorte central, sem distorcer)
            float art = wide ? Math.min(300, w*.36f) : Math.min(216, Math.max(120,w*.34f));
            float ax = x + 32, ay = wide ? 168 : 172;
            rr(ax, ay, art, art, 24, cSurf2);
            if (albumArt != null) {
                p.reset(); p.setAntiAlias(true); p.setFilterBitmap(true);
                canvas.save(); path.reset(); r.set(ax, ay, ax + art, ay + art); path.addRoundRect(r, 24, 24, Path.Direction.CW); canvas.clipPath(path);
                int bw = albumArt.getWidth(), bh = albumArt.getHeight(), side = Math.min(bw, bh);
                canvas.drawBitmap(albumArt, new Rect((bw - side) / 2, (bh - side) / 2, (bw - side) / 2 + side, (bh - side) / 2 + side), r, p);
                canvas.restore(); rrs(ax, ay, art, art, 24, cHair, 1.2f);
            } else ic(I_NOTE, ax + art / 2, ay + art / 2, art * .4f, accent);
            hit(23, ax, ay, art, art); // fora da capa permanece na Home
            float tx = ax + art + (wide ? 36 : 22), tw = Math.max(24, x + w - 28 - tx);
            float ts = wide ? 38 : 30, as = wide ? 23 : 20;
            float yl = wide ? 214 : 202, yt = wide ? 262 : 246, ya = wide ? 298 : 280, yh = wide ? 336 : 318;
            text(mediaPlaying ? "TOCANDO AGORA" : (mediaCtl != null ? "EM PAUSA" : mediaSource.toUpperCase(Locale.getDefault())), tx, yl, 14, accent, tfBold, Paint.Align.LEFT, .1f);
            canvas.save(); canvas.clipRect(tx, y+112, x+w-22, y+263);
            t(fit(songTitle, ts, tfBold, tw-3), tx, yt, ts, cText, tfBold);
            t(fit(songArtist, as, tfReg, tw-3), tx, ya, as, cMuted, tfReg);
            canvas.restore();
            if (mediaCtl == null) t(fit(cachedSpotifyPreview ? "Última música • aguardando conexão" :
                    (mediaPermission ? "Toque na capa para abrir o Spotify" : "Permitir acesso às notificações"),
                    17, tfMed, tw), tx, yh, 17, accent, tfMed);
            long pos = livePosition();
            float by = wide ? 358 : 332, bh = wide ? 10 : 8, frac = songDuration > 0 ? Math.min(1f, (float) pos / songDuration) : 0f;
            rr(tx, by, tw, bh, bh / 2, cTrack);
            if (frac > 0) { rr(tx, by, Math.max(bh, tw * frac), bh, bh / 2, accent); circle(tx + tw * frac, by + bh / 2, wide ? 11 : 9, cText); }
            float ty = wide ? by + 46 : by + 40;
            t(songDuration > 0 ? timeString(pos) : "--:--", tx, ty, wide ? 17 : 16, cDim, tfReg);
            tr(songDuration > 0 ? timeString(songDuration) : "--:--", tx + tw, ty, wide ? 17 : 16, cDim, tfReg);
            seekX = tx; seekW = tw;
            hit(25, tx - 6, by - 18, tw + 12, 44);
            // Com 3 cards, a coluna do titulo e estreita. Uma faixa de controles
            // ocupa TODA a largura LIVRE abaixo da capa e do botao Letras;
            // os botoes ficam grandes, proximos e simetricos, sem flutuar a direita.
            // Com 2 cards, preserva a disposicao aprovada ao lado da capa.
            if (!wide) {
                float rackX=x+24f, rackY=y+309f, rackW=w-48f, rackH=114f;
                rr(rackX,rackY,rackW,rackH,21,mix(cSurfTop,cSurf2,.64f));
                rrs(rackX,rackY,rackW,rackH,21,cHair,1.0f);
                MediaControlsLayout controls=MediaControlsLayout.forFooter(rackX+22f,rackX+rackW-22f,false);
                float cx=controls.centerX, cy=y+366f;
                roundBtn(20,cx-controls.separation,cy,controls.sideRadius,I_PREV,controls.sideRadius*.78f,cSurf2,cText);
                roundBtn(21,cx,cy,controls.playRadius,mediaPlaying?I_PAUSE:I_PLAY,controls.playRadius*.78f,accent,onAccent);
                roundBtn(22,cx+controls.separation,cy,controls.sideRadius,I_NEXT,controls.sideRadius*.78f,cSurf2,cText);
            } else {
                float cy=y+378f;
                MediaControlsLayout controls=MediaControlsLayout.forRegion(tx,x+w-26f,false,false);
                float cx=controls.centerX;
                roundBtn(20,cx-controls.separation,cy,controls.sideRadius,I_PREV,controls.sideRadius*.72f,cSurf2,cText);
                roundBtn(21,cx,cy,controls.playRadius,mediaPlaying?I_PAUSE:I_PLAY,controls.playRadius*.76f,accent,onAccent);
                roundBtn(22,cx+controls.separation,cy,controls.sideRadius,I_NEXT,controls.sideRadius*.72f,cSurf2,cText);
            }
            // Letras discretas centralizadas logo abaixo da capa, longe do título e dos controles.
            float lw=wide ? art : art - 32, lh=wide ? 31 : 27;
            float lx=ax+(art-lw)/2f, ly=ay+art+(wide?12:5);
            boolean lyricsPress=hit(26,lx,ly,lw,lh);
            rr(lx,ly,lw,lh,11,lyricsPress?mix(cSurf2,accent,.25f):cSurf2);
            text("♫  Letras",lx+lw/2,ly+(wide?19:17),13,accent,tfMed,Paint.Align.CENTER,.02f);
        }

        void drawVehicle() {
            float x=918,y=96,w=330,h=456;
            card(x,y,w,h);
            text("VEÍCULO", x+24,y+38,17,cMuted,tfMed,Paint.Align.LEFT,.1f);
            hit(43,x+18,y+3,130,46);
            circle(1112,y+33,4.5f,gpsFresh()?cOk:cDim);t("GPS",1124,y+38,14,cMuted,tfMed);
            circle(1176,y+33,4.5f,hasCanData()?cOk:cDim);t(vehicleDataSource==1?"OBD":"CAN",1188,y+38,14,cMuted,tfMed);
            float bw=(w-48-20)/3f,by=y+54,bh=66;
            int[] icons={I_MENU,I_TRIP,I_BACK};
            for(int i=0;i<3;i++){
                float bx=x+24+i*(bw+10);
                boolean pressed=hit(40+i,bx,by,bw,bh);pushPress(pressed,bx,by,bw,bh);
                rr(bx,by,bw,bh,16,pressed?mix(cSurf2,accent,.5f):mix(cSurf2,accent,.22f));
                rrs(bx,by,bw,bh,16,accent,2f);
                ic(icons[i],bx+bw/2,by+24,24,accent);
                tc(fit(vbtnLabel[i].toUpperCase(Locale.getDefault()),13,tfBold,bw-8),bx+bw/2,by+55,13,cText,tfBold);
                popPress();
            }
            drawSelectableVehicleCards(x,y+142,w,h-162,false);
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
                } else {
                    int picture=I_GRID;
                    if(realPkg!=null) {
                        if(realPkg.contains("youtube")) picture=I_YOUTUBE;
                        else if(realPkg.contains("maps")||realPkg.contains("waze")) picture=I_MAPS;
                        else if(realPkg.contains("chrome")||realPkg.contains("browser")) picture=I_CHROME;
                        else if(realPkg.contains("spotify")) picture=I_NOTE;
                        else if(realPkg.contains("phone")||realPkg.contains("dialer")) picture=I_PHONE;
                    }
                    ic(picture,cx,cy,ico*.9f,accent);
                }
                float ls = Math.max(13, Math.min(18, w / 8.2f));
                tc(fit(label, ls, tfMed, w - 14), cx, y + 94, ls, cText, tfMed);
                if (isBuiltin && id == 9 && pkg != null) circle(x+w-16,y+17,4.5f,cOk);
                popPress();
            }
        }

        /** Confirmação visual de radar usando as mesmas cores, bordas e tipografia do launcher. */
        void drawRadarModeNotice() {
            long now=SystemClock.uptimeMillis();
            if(now>=radarModeNoticeUntil || radarModeNoticeText==null
                    || radarModeNoticeText.isEmpty()) return;
            float x=430,y=262,w=420,h=132;
            boolean off=pardalRadar==null || pardalRadar.mode()==PardalRadar.OFF;
            int color=off?cDim:accent;
            rr(x,y,w,h,21,cSurfTop);
            rrs(x,y,w,h,21,cHair,1.5f);
            rr(x+20,y+25,6,h-50,3,color);
            circle(x+64,y+65,26,mix(cSurf2,color,.18f));
            ic(I_TRIP,x+64,y+65,25,color);
            t("RADAR PARDAL",x+104,y+51,17,cMuted,tfBold);
            t(fit(radarModeNoticeText,22,tfBold,w-132),x+104,y+89,22,cText,tfBold);
            postInvalidateDelayed(Math.min(1000L,radarModeNoticeUntil-now));
        }

        void drawPardalPopup(){
            if(pardalRadar==null||!pardalRadar.active())return;
            int d=pardalRadar.distance(), lim=pardalRadar.limit();
            boolean over=pardalRadar.over();
            // Popup centralizado na tela 1280 x 720, sem deslocar os demais cards.
            final float w=560f,h=224f,x=(1280f-w)/2f,y=(720f-h)/2f;
            int lineColor=over?0xffFA7069:0xff4CB8C9;
            int secondary=0xffAEBFCB;

            // Sombra, painel e acabamento discretos no padrão noturno do launcher.
            rr(x+8,y+12,w,h,26,0x77000000);
            rr(x,y,w,h,26,0xff15202B);
            rrs(x,y,w,h,26,over?0xff9B494B:0xff486C7D,1.6f);
            rr(x+20,y+19,5,147,2.5f,lineColor);

            // Placa real de limite de velocidade, em tamanho legível.
            float signX=x+38,signY=y+27,signW=124,signH=137;
            rr(signX,signY,signW,signH,19,0xffF0F3F5);
            circle(signX+signW/2f,signY+signH/2f,48,0xffE23838);
            circle(signX+signW/2f,signY+signH/2f,40,Color.WHITE);
            String displayedLimit=lim>0?String.valueOf(lim):"?";
            float limFont=displayedLimit.length()>3?31f:displayedLimit.length()>2?38f:44f;
            text(displayedLimit,signX+signW/2f,signY+signH/2f+limFont*.34f,
                    limFont,Color.BLACK,tfBold,Paint.Align.CENTER,0);

            // Hierarquia OEM: tipo, distância dominante, velocidade atual.
            float infoX=x+186;
            text(fit(pardalRadar.kind(),17,tfBold,over?199f:346f),
                    infoX,y+52,17,secondary,tfBold,Paint.Align.LEFT,.04f);
            if(over)text("REDUZA",x+w-24,y+52,15,0xffFF7770,
                    tfBold,Paint.Align.RIGHT,.06f);
            text(d+" m",infoX,y+119,58,Color.WHITE,tfBold,Paint.Align.LEFT,0);
            text("ATUAL  "+pardalRadar.speed()+" km/h",infoX,y+157,19,
                    over?0xffFF8B83:0xff79D7C1,tfMed,Paint.Align.LEFT,0);

            // Barra de aproximação de 350 a 0 m: sem excesso de elementos.
            float barX=x+25,barY=y+185,barW=w-50f;
            rr(barX,barY,barW,6f,3f,0xff33424D);
            rr(barX,barY,barW*Math.max(0f,Math.min(1f,(350f-d)/350f)),
                    6f,3f,lineColor);
            text("APROXIMAÇÃO",barX,y+210,12,0xff8497A7,tfMed,Paint.Align.LEFT,.04f);
            text("350 m  →  0 m",x+w-25,y+210,12,0xff8497A7,
                    tfMed,Paint.Align.RIGHT,0);
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
            // Título do excesso mostra o limite configurado sem cortar na borda.
            float titleSize = alertKind == 1 ? 29 : 36;
            while (titleSize > 23 && measure(alertTitle, titleSize, tfBold, 0) > w - 180) titleSize -= 1;
            t(fit(alertTitle, titleSize, tfBold, w - 180), x + 158, y + 76, titleSize, cText, tfBold);
            t(fit(alertSub, 25, tfMed, w - 190), x + 158, y + 116, 25, cMuted, tfMed);
            tr("toque para fechar", x + w - 22, y + h - 14, 13, faintC(), tfReg);
            canvas.restore(); canvas.restore();
        }
        int faintC() { return (cDim & 0x00FFFFFF) | 0xAA000000; }
        void drawVolume() {
            if (!volumeVisible && !userMuted) return;
            long now = SystemClock.uptimeMillis();
            if (!userMuted && now >= volumeUntil) {
                volumeVisible = false;
                return;
            }
            // MUDO persistente e fixo no centro, sem barra nem número.
            // Tocar no próprio painel desativa o mudo, assim como o botão do topo.
            if (userMuted) {
                float w = 254, h = 112;
                float x = (1280 - w) / 2f;
                float y = (720 - h) / 2f;
                boolean pressed = hit(46, x, y, w, h);
                pushPress(pressed, x, y, w, h);
                rr(x, y, w, h, 22, pressed ? cSurf2 : cSurfTop);
                rrs(x, y, w, h, 22, cHair, 1.3f);
                ic(I_MUTE, x + w / 2, y + 40, 40, 0xffEF4444);
                tc("MUDO", x + w / 2, y + 91, 29, cText, tfBold);
                popPress();
                return;
            }
            // Volume normal: HUD central por 2 segundos sem capturar toques.
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            float x = 475, y = 265, w = 330, h = 155;
            rr(x, y, w, h, 23, cSurfTop);
            rrs(x, y, w, h, 23, cHair, 1.3f);
            ic(I_NOTE, x + 43, y + 44, 26, accent);
            text("VOLUME", x + 71, y + 51, 20, cText, tfBold, Paint.Align.LEFT, .06f);
            tr(String.valueOf(v), x + w - 25, y + 55, 30, accent, tfBold);
            rr(x + 26, y + 100, w - 52, 13, 7, cTrack);
            if (v > 0) rr(x + 26, y + 100, (w - 52) * v / max, 13, 7, accent);
            tc("Volume de mídia Android", x + w / 2, y + 136, 13, cMuted, tfReg);
            postInvalidateDelayed(Math.max(32, Math.min(250, volumeUntil - now)));
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
                case 2: adjustVolume(1); break;
                case 3: toggleMute(); break;
                case 4: openSystemSetting(Settings.ACTION_BLUETOOTH_SETTINGS); break;
                case 5: openSystemSetting(Settings.ACTION_WIFI_SETTINGS); break;
                case 47: if (!topCustomPackage.isEmpty()) launchPackage(topCustomPackage); break;
                case 45: openSpeedCardMenu(); break;
                case 46: if (userMuted) toggleMute(); break;
                case 48: openConnectionQuickMenu(); break;
                case 49: cycleBrightness(); break;
                case 50: cyclePardalMode(); break;
                case 51: openScreenSaver(); break;
                case 60: case 61: case 62: cycleTopMiniCard(id - 60); break;
                case 6: if (!drivingMode) settings(); break;
                case 7: openEq(); break;
                case 40: openVehicleQuickMenu(); break;
                case 41: case 42: vehicleCommand(id - 40); break;
                case 43: openVehicleQuickMenu(); break;
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
        float downX,downY; long downAt;
        void cancelLp() { if (lpTask != null) { handler.removeCallbacks(lpTask); lpTask = null; } }
        Hit hitAt(float x, float y) {
            // Painel MUDO fica por cima dos cards: seu toque precisa ter prioridade.
            for (Hit h : hits) if(h.id == 46 && x >= h.x && x <= h.x+h.w && y >= h.y && y <= h.y+h.h) return h;
            // Alvo específico de letras tem prioridade sobre o toque genérico do painel Spotify.
            for (Hit h : hits) if(h.id == 45 && x >= h.x && x <= h.x+h.w && y >= h.y && y <= h.y+h.h) return h;
            for (Hit h : hits) if(h.id == 26 && x >= h.x && x <= h.x+h.w && y >= h.y && y <= h.y+h.h) return h;
            for (Hit h : hits) if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) return h;
            return null;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = (event.getX() - ox) / sx, y = (event.getY() - oy) / sy;
            int act = event.getActionMasked();
            if(act==MotionEvent.ACTION_DOWN) {
                boolean wasIdle=immersiveIdle && SystemClock.uptimeMillis()-lastHomeInteraction>HOME_IDLE_MS;
                resetHomeIdleTimer();
                if(wasIdle && y>555) { pressedId=-1;invalidate();return true; }
            }
            if (SystemClock.uptimeMillis() <= alertUntil) {
                pressedId = -1; if (act == MotionEvent.ACTION_UP) { alertUntil = 0; invalidate(); } return true;
            }
            switch (act) {
                case MotionEvent.ACTION_DOWN: {
                    Hit h = hitAt(x, y); pressedId = h == null ? -1 : h.id; lpFired = false; cancelLp();
                    downX=x;downY=y;downAt=SystemClock.uptimeMillis();
                    if (h != null && h.id == 50) {
                        // Pressão longa no Radar: abre modos, status, atualização e teste de som.
                        lpTask = () -> {
                            lpTask = null; lpFired = true; pressedId = -1;
                            invalidate(); feedback(); openPardalMenu();
                        };
                        handler.postDelayed(lpTask, 750L);
                    } else if (h != null && h.id == 49) {
                        // Pressão de 0,75 segundo; ao soltar não executa o toque rápido também.
                        lpTask = () -> {
                            lpTask = null; lpFired = true; pressedId = -1;
                            invalidate(); feedback(); openBrightnessQuickMenu();
                        };
                        handler.postDelayed(lpTask, 750L);
                    } else if (h != null && h.id >= 60 && h.id <= 62
                            && topSlot[h.id-60] == 0) {
                        // Segurar o mini-card Velocidade mantém acesso aos alertas/limite.
                        lpTask = () -> { lpTask=null; lpFired=true; pressedId=-1;
                            invalidate(); feedback(); openSpeedCardMenu(); };
                        handler.postDelayed(lpTask, 700L);
                    } else if (h != null && h.id >= 40 && h.id <= 42) { // segurar o botão do carro: renomear
                        final int idx = h.id - 40;
                        lpTask = () -> { lpTask = null; lpFired = true; pressedId = -1; invalidate(); feedback(); editVehicleButton(idx, null); };
                        handler.postDelayed(lpTask, 650);
                    }
                    invalidate(); break;
                }
                case MotionEvent.ACTION_MOVE: { if (pressedId != -1) {
                    // Tolerância a movimentos leves do dedo durante a pressão longa do botão Brilho.
                    if((pressedId==49||pressedId==50) && Math.hypot(x-downX,y-downY)<32) break;
                    Hit h = hitAt(x, y); if (h == null || h.id != pressedId) { pressedId = -1; cancelLp(); invalidate(); }
                } break; }
                case MotionEvent.ACTION_CANCEL: { pressedId = -1; cancelLp(); invalidate(); break; }
                case MotionEvent.ACTION_UP: {
                    cancelLp();
                    if (lpFired) { lpFired = false; pressedId = -1; invalidate(); break; }
                    int id = pressedId; pressedId = -1; invalidate();
                    if(id==49 && SystemClock.uptimeMillis()-downAt>=700L) {
                        feedback();openBrightnessQuickMenu();break;
                    }
                    if (id != -1) handle(id, x, y); break;
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
            wrap(snd.statusLine(), 684, 454, 15, snd.effectOk ? cText : cBad, tfMed, 525, 21);
            wrap("Teste por 4 s. Se o som não mudar, use os ajustes originais da Jancar.",684,522,14,cDim,tfReg,525,19);
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
            t("Balanço (prévia sem controle OEM)*", 644, 262, 20, cText, tfMed); tr(snd.balance == 0 ? "0" : (snd.balance < 0 ? "E " : "D ") + Math.round(Math.abs(snd.balance)), 1220, 262, 20, accent, tfBold);
            hslider(R_BAL, 668, 296, 552, (snd.balance + 10f) / 20f, true);
            t("Fader (prévia sem controle OEM)*", 644, 372, 20, cText, tfMed); tr(snd.fader == 0 ? "0" : (snd.fader > 0 ? "F " : "T ") + Math.round(Math.abs(snd.fader)), 1220, 372, 20, accent, tfBold);
            hslider(R_FAD, 668, 406, 552, (snd.fader + 10f) / 20f, true);
            t("Reforço de graves", 644, 482, 20, cText, tfMed); tr("+" + Math.round(snd.bass * 10), 1220, 482, 20, accent, tfBold);
            hslider(R_SUB, 668, 516, 552, snd.bass, false);
            wrap("* Balanço e fader: não controlam a MCU da Jancar sem API OEM. Use Som original Jancar nas configurações. Reforço de graves suavizado e ainda sujeito ao DSP físico; teste em volume baixo.", 644, 574, 14, cDim, tfReg, 584, 19);
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
