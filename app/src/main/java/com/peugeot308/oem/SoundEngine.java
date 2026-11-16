package com.peugeot308.oem;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Motor de som do launcher (v1.14): equalizador de 10 bandas sobre o equalizador do Android,
 * pré-ganho, loudness dinâmico (acompanha o volume), reforço de graves, volume automático por velocidade
 * e "campo sonoro". Tudo é salvo em SharedPreferences e reaplicado quando o launcher abre (ao ligar o carro).
 *
 * Sobrevive à recriação da Activity (singleton) e re-cria o efeito se o sistema de áudio reiniciar.
 */
final class SoundEngine {
    private static SoundEngine instance;
    static synchronized SoundEngine get(Context c) {
        if (instance == null) instance = new SoundEngine(c.getApplicationContext());
        return instance;
    }

    private static final String PREFS = "sound";
    private static final String ACTION_AUDIO_COMMAND = "com.peugeot308.oem.AUDIO_COMMAND";

    private final Context ctx;
    private final AudioManager audio;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;

    // ---- estado salvo ----
    boolean on = false; // Som original da Jancar por padrao; EQ somente por escolha explicita
    final float[] band = new float[EqMath.N];       // curva atual (dB)
    float pre = 0f;                                  // pré-ganho (dB)
    String presetName = "Plano";                     // predefinição ativa ("Usuário" = curva manual)
    final float[] userBand = new float[EqMath.N];    // última curva manual
    float userPre = 0f;
    final ArrayList<String> customNames = new ArrayList<>();
    final ArrayList<float[]> customBands = new ArrayList<>();
    final ArrayList<Float> customPre = new ArrayList<>();
    boolean loudOn = false; // Opt-in: evita mudança espontânea do timbre/ganho
    float loudAmt = 0.6f;     // 0..1
    float loudBelow = 0.55f;  // volume (fração) abaixo do qual o loudness age
    boolean autoVolOn = false;
    int autoVolMax = 3;       // máximo de níveis somados ao volume do usuário
    float bass = 0f;          // 0..1 (BassBoost)
    float lowTone = 0f, highTone = 0f; // tonalidade independente -6..6 dB
    float balance = 0f, fader = 0f;   // -10..10
    float padX = -0.45f, padY = -0.35f; // ponto de escuta (-1..1)
    String fieldName = "Motorista";

    // ---- estado de execução ----
    boolean compare = false;       // ouvir sem equalização (A/B)
    private long testUntil = 0;
    float loudCur = 0f, loudTarget = 0f;
    float speedKmh = -1f;          // alimentado pela Activity (-1 = desconhecida)
    boolean volumeBusy = false;    // rampa de volume (fade/mudo) em andamento
    int autoOffset = 0, baseVol = -1;
    boolean autoBlocked = false;   // a central ignorou o ajuste de volume feito pelo Android
    private int autoGate = 0, prevVol = -1, expectVol = -1, prevOffset = 0, ignoredSets = 0;
    String status = "Iniciando…";
    boolean effectOk = false;
    int nBands = 0; float minDb = -15f, maxDb = 15f;
    private Equalizer eq; private BassBoost bb;
    private float[][] edges; private long retryAt = 0;
    private final HashMap<Integer, Equalizer> sessEq = new HashMap<>();
    // Metadados de CADA instancia: global e sessoes podem ter faixas distintas.
    // Ultimos comandos em mB evitam writes redundantes e o som "mudando sozinho".
    private static final class DeviceProfile {
        final int n;
        final float[][] ranges;
        final float[] centers;
        final short lowMb, highMb;
        final int[] lastMb;
        boolean enabled;
        DeviceProfile(int n, float[][] ranges, float[] centers, short lowMb, short highMb) {
            this.n=n; this.ranges=ranges; this.centers=centers;
            this.lowMb=lowMb;this.highMb=highMb;
            this.lastMb=new int[n];
            java.util.Arrays.fill(lastMb,Integer.MIN_VALUE);
        }
    }
    private final HashMap<Equalizer, DeviceProfile> profiles = new HashMap<>();
    private boolean started = false;
    private BroadcastReceiver sessionReceiver, volumeReceiver;

    private SoundEngine(Context c) {
        ctx = c;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        sp = c.getSharedPreferences(PREFS, 0);
        // Migracao unica: atualizacoes nao devem religar EQ e volume automatico
        // sobre o DSP original da Jancar. As curvas e presets ficam preservados.
        if (!sp.getBoolean("jancar_audio_passthrough_175", false)) {
            sp.edit().putBoolean("on", false).putBoolean("autovol_on", false)
                    .putBoolean("jancar_audio_passthrough_175", true).apply();
        }
        load();
        // Migracao v1.76: loudness deixa de ser automatico ate o usuario reativar.
        // Valores das bandas, presets, pre e ajustes de tonalidade sao mantidos.
        if (!sp.getBoolean("loudness_optin_176", false)) {
            loudOn = false;
            autoVolOn = false;
            sp.edit().putBoolean("loud_on", false).putBoolean("autovol_on", false)
                    .putBoolean("loudness_optin_176", true).apply();
        }
    }

    // ------------------------------------------------------------ persistência
    private static String join(float[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.toString();
    }
    private static boolean parse(String s, float[] out) {
        try {
            String[] p = s.split(",");
            if (p.length != out.length) return false;
            float[] t = new float[out.length];
            for (int i = 0; i < t.length; i++) t[i] = EqMath.clamp(Float.parseFloat(p[i].trim()), -EqMath.RANGE, EqMath.RANGE);
            System.arraycopy(t, 0, out, 0, t.length);
            return true;
        } catch (Exception e) { return false; }
    }
    private static String cleanName(String n) { return n == null ? "" : n.replaceAll("[|;,\\n\\r]", " ").trim(); }

    private void load() {
        on = sp.getBoolean("on", false);
        parse(sp.getString("bands", ""), band);
        pre = EqMath.clamp(sp.getFloat("pre", 0f), -EqMath.RANGE, EqMath.RANGE);
        presetName = sp.getString("preset", "Plano");
        if (!parse(sp.getString("user_bands", ""), userBand)) System.arraycopy(band, 0, userBand, 0, EqMath.N);
        userPre = EqMath.clamp(sp.getFloat("user_pre", pre), -EqMath.RANGE, EqMath.RANGE);
        customNames.clear(); customBands.clear(); customPre.clear();
        for (String e : sp.getString("custom", "").split(";")) {
            String[] f = e.split("\\|");
            if (f.length != 3) continue;
            float[] b = new float[EqMath.N];
            try { if (parse(f[1], b) && !cleanName(f[0]).isEmpty()) { customNames.add(cleanName(f[0])); customBands.add(b); customPre.add(Float.parseFloat(f[2])); } } catch (Exception ignored) {}
        }
        loudOn = sp.getBoolean("loud_on", false);
        loudAmt = EqMath.clamp(sp.getFloat("loud_amt", 0.6f), 0f, 1f);
        loudBelow = EqMath.clamp(sp.getFloat("loud_below", 0.55f), 0.15f, 1f);
        autoVolOn = sp.getBoolean("autovol_on", false);
        autoVolMax = Math.max(1, Math.min(6, sp.getInt("autovol_max", 3)));
        bass = EqMath.clamp(sp.getFloat("bass", 0f), 0f, 1f);
        lowTone = EqMath.clamp(sp.getFloat("low_tone", 0f), -6f, 6f);
        highTone = EqMath.clamp(sp.getFloat("high_tone", 0f), -6f, 6f);
        balance = EqMath.clamp(sp.getFloat("balance", 0f), -10f, 10f);
        fader = EqMath.clamp(sp.getFloat("fader", 0f), -10f, 10f);
        padX = EqMath.clamp(sp.getFloat("pad_x", -0.45f), -1f, 1f);
        padY = EqMath.clamp(sp.getFloat("pad_y", -0.35f), -1f, 1f);
        fieldName = sp.getString("field", "Motorista");
    }

    private final Runnable saveTask = this::saveNow;
    void saveSoon() { h.removeCallbacks(saveTask); h.postDelayed(saveTask, 350); }
    void saveNow() {
        h.removeCallbacks(saveTask);
        StringBuilder cu = new StringBuilder();
        for (int i = 0; i < customNames.size(); i++) {
            if (i > 0) cu.append(';');
            cu.append(customNames.get(i)).append('|').append(join(customBands.get(i))).append('|').append(customPre.get(i));
        }
        sp.edit().putBoolean("on", on).putString("bands", join(band)).putFloat("pre", pre).putString("preset", presetName)
            .putString("user_bands", join(userBand)).putFloat("user_pre", userPre).putString("custom", cu.toString())
            .putBoolean("loud_on", loudOn).putFloat("loud_amt", loudAmt).putFloat("loud_below", loudBelow)
            .putBoolean("autovol_on", autoVolOn).putInt("autovol_max", autoVolMax).putFloat("bass", bass)
            .putFloat("low_tone", lowTone).putFloat("high_tone", highTone)
            .putFloat("balance", balance).putFloat("fader", fader).putFloat("pad_x", padX).putFloat("pad_y", padY)
            .putString("field", fieldName).apply();
    }

    // ------------------------------------------------------------ ciclo de vida
    /** Inicia (uma vez por processo): cria o efeito, aplica o que estava salvo e começa a acompanhar o volume. */
    void start() {
        if (started) { ensureEffect(); applyNow(); return; }
        started = true;
        try {
            sessionReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    int s = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
                    if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) openSession(s);
                    else closeSession(s);
                }
            };
            IntentFilter f = new IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            ctx.registerReceiver(sessionReceiver, f);
        } catch (Exception ignored) {}
        try {
            volumeReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { volumeChanged(); } };
            ctx.registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        } catch (Exception ignored) {}
        ensureEffect();
        applyNow();
        h.postDelayed(tickTask, 1000);
    }

    private final Runnable tickTask = new Runnable() {
        public void run() {
            try { tick(); } catch (Exception ignored) {}
            h.postDelayed(this, 1000);
        }
    };

    private void tick() {
        if (on && eq == null && sessEq.isEmpty()) {
            ensureEffect();
            if (eq != null) applyNow();
        }
        if (on && loudOn) volumeChanged();
        if (autoVolOn || autoOffset != 0) autoVolumeStep();
        // A ROM pode reiniciar o servico de audio: recria so quando perder o efeito.
        if (eq != null) { try { eq.getEnabled(); } catch (Exception e) { releaseEffects(); ensureEffect(); applyNow(); } }
    }

    // ------------------------------------------------------------ efeitos
    private void releaseEffects() {
        if (eq != null) profiles.remove(eq);
        try { if (eq != null) eq.release(); } catch (Exception ignored) {}
        try { if (bb != null) bb.release(); } catch (Exception ignored) {}
        eq = null; bb = null; effectOk = false;
    }

    /** Lê os limites REAIS do equalizador; só aproxima quando a ROM não os fornece. */
    private DeviceProfile inspectDevice(Equalizer e) {
        int n=e.getNumberOfBands();
        if(n<1||n>64)throw new IllegalStateException("Quantidade de bandas invalida: "+n);
        short[] limits=e.getBandLevelRange();
        if(limits==null||limits.length<2)throw new IllegalStateException("Limites de ganho ausentes");
        float[][] bands=new float[n][2];
        float[] centers=new float[n];
        boolean valid=true;
        for(int i=0;i<n;i++){
            try {
                centers[i]=e.getCenterFreq((short)i)/1000f; // milliHz -> Hz
                int[] hz=e.getBandFreqRange((short)i);
                if(hz!=null&&hz.length==2){
                    bands[i][0]=hz[0]/1000f;
                    bands[i][1]=hz[1]/1000f;
                }
                if(bands[i][0]<10f||bands[i][1]<=bands[i][0]||centers[i]<=0f)
                    valid=false;
            }catch(Exception ex){valid=false;}
            if(centers[i]<=0f)
                centers[i]=(float)Math.exp(Math.log(60.0)+i*(Math.log(16000.0)-Math.log(60.0))/Math.max(1,n-1));
        }
        if(!valid)bands=EqMath.edgesFromCenters(centers);
        return new DeviceProfile(n,bands,centers,limits[0],limits[1]);
    }

    private void ensureEffect() {
        if (!on && !testing()) return;
        if (eq != null || !sessEq.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (now < retryAt) return;
        Equalizer e=null;
        try {
            e=new Equalizer(0,0); // Sessao global, apenas se habilitado pelo usuario
            DeviceProfile profile=inspectDevice(e);
            profiles.put(e,profile);
            eq=e;
            nBands=profile.n;edges=profile.ranges;
            minDb=profile.lowMb/100f;maxDb=profile.highMb/100f;
            effectOk=true;
            status="Equalizador Android global • "+nBands+" bandas reais";
        } catch(Throwable t) {
            if(e!=null)try{e.release();}catch(Exception ignored){}
            eq=null;effectOk=false;retryAt=now+20000;
            status=sessEq.isEmpty()
                    ?"Efeito global indisponível ("+t.getClass().getSimpleName()+")"
                    :"Ativo por aplicativo ("+sessEq.size()+")";
        }
        // BassBoost nativo nao e usado; evita DSP duplicado e ganho inesperado.
    }

    private void openSession(int s) {
        if((!on&&!testing())||s<=0||effectOk||sessEq.containsKey(s))return;
        Equalizer e=null;
        try{
            e=new Equalizer(0,s);
            DeviceProfile profile=inspectDevice(e);
            sessEq.put(s,e);profiles.put(e,profile);
            nBands=profile.n;edges=profile.ranges;
            minDb=profile.lowMb/100f;maxDb=profile.highMb/100f;
            applyNow();
            status="EQ por aplicativo • "+profile.n+" bandas reais";
        }catch(Throwable t){if(e!=null)try{e.release();}catch(Exception ignored){}}
    }
    private void closeSession(int s) {
        Equalizer e=sessEq.remove(s);
        if(e!=null){profiles.remove(e);try{e.release();}catch(Exception ignored){}}
    }

    /** Só envia os níveis quantizados que mudaram. Cada sessão usa seus PRÓPRIOS Hz. */
    private void setLevels(Equalizer e, boolean active) throws Exception {
        DeviceProfile profile=profiles.get(e);
        if(profile==null){
            profile=inspectDevice(e);
            profiles.put(e,profile);
        }
        if(!active){
            if(profile.enabled){e.setEnabled(false);profile.enabled=false;}
            return;
        }
        float[] dbs=targetLevels(profile);
        for(short b=0;b<profile.n;b++){
            int mb=Math.max(profile.lowMb,Math.min(profile.highMb,Math.round(dbs[b]*100f)));
            if(profile.lastMb[b]!=mb){
                e.setBandLevel(b,(short)mb);
                profile.lastMb[b]=mb;
            }
        }
        if(!profile.enabled){
            e.setEnabled(true);
            profile.enabled=true;
        }
    }

    /** Valor pedido, em dB, a CADA banda fisica, com frequencias fornecidas pela ROM. */
    private float[] targetLevels(DeviceProfile profile) {
        int n=profile.n;
        float[] out=new float[n];
        if(testing()){
            for(int b=0;b<n;b++)
                out[b]=b<(n+1)/2?Math.min(profile.highMb/100f,9f)
                        :Math.max(profile.lowMb/100f,-9f);
            return out;
        }
        float k=loudOn?loudCur*loudAmt:0f;
        float lowDb=k*EqMath.LOUD_LOW_DB,highDb=k*EqMath.LOUD_HIGH_DB;
        float headroom=-0.25f*Math.max(lowDb,highDb);
        for(int b=0;b<n;b++){
            float lo=profile.ranges[b][0],hi=profile.ranges[b][1];
            out[b]=EqMath.bandDb(band,lo,hi,pre+headroom,lowDb,highDb);
            // Tonalidade e reforco manuais (nao confundidos com as barras do EQ).
            float mid=(lo+hi)*.5f;
            if(mid<170f)out[b]+=lowTone;
            else if(mid<380f)out[b]+=lowTone*.45f;
            if(mid>4500f)out[b]+=highTone;
            else if(mid>2300f)out[b]+=highTone*.45f;
            if(mid<110f)out[b]+=bass*1.5f;
            else if(mid<170f)out[b]+=bass*.5f;
        }
        return out;
    }

    void applyNow() {
        boolean testingNow=testing();
        if(!on&&!testingNow){
            h.removeCallbacks(smoothTask);
            loudTarget=loudCur=0f;
            releaseEffects();
            for(Equalizer e:new ArrayList<>(sessEq.values())){
                profiles.remove(e);
                try{e.release();}catch(Exception ignored){}
            }
            sessEq.clear();
            status="Som original Jancar: sem processamento do launcher";
            return;
        }
        ensureEffect();
        boolean active=(on&&!compare)||testingNow;
        if(eq!=null){
            try{setLevels(eq,active);}
            catch(Exception e){releaseEffects();status="EQ global indisponível: "+e.getClass().getSimpleName();}
        }
        // Nunca habilitar BassBoost nativo em paralelo ao EQ.
        if(bb!=null)try{bb.setEnabled(false);}catch(Exception ignored){}
        for(Equalizer e:new ArrayList<>(sessEq.values())){
            try{setLevels(e,active);}
            catch(Exception ex){profiles.remove(e);try{e.release();}catch(Exception ignored){}}
        }
    }

    /** Teste audível de 4 s: se o som não mudar, a central não aceita efeitos do Android. */
    void testEffect() {
        testUntil = SystemClock.elapsedRealtime() + 4000;
        applyNow();
        h.postDelayed(this::applyNow, 4100);
    }

    // ------------------------------------------------------------ loudness dinâmico
    float volumeFraction() {
        try {
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        } catch (Exception e) { return 1f; }
    }

    void volumeChanged() {
        if ((!on && !testing()) || !loudOn) {
            h.removeCallbacks(smoothTask);
            boolean wasActive = loudCur != 0f;
            loudTarget = loudCur = 0f;
            if (wasActive && on) applyNow();
            return;
        }
        float vf = volumeFraction();
        loudTarget = (loudOn && vf > 0f) ? EqMath.loudFactor(vf, loudBelow) : 0f;
        if (Math.abs(loudTarget - loudCur) > 0.002f) { h.removeCallbacks(smoothTask); h.post(smoothTask); }
    }
    private final Runnable smoothTask = new Runnable() {
        public void run() {
            float d = loudTarget - loudCur;
            loudCur += Math.max(-0.04f, Math.min(0.04f, d)); // passos curtos: sem estalo nem salto audível
            applyNow();
            if (Math.abs(loudTarget - loudCur) > 0.002f) h.postDelayed(this, 70);
        }
    };

    // ------------------------------------------------------------ volume automático por velocidade
    /** Nível extra desejado (contínuo) para a velocidade: 0 até 40 km/h, máximo a partir de 110 km/h. */
    float autoWanted() {
        if (speedKmh < 0f) return 0f;
        float t = EqMath.clamp((speedKmh - 40f) / 70f, 0f, 1f);
        return autoVolMax * (t * t * (3f - 2f * t));
    }

    private void autoVolumeStep() {
        try {
            int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (cur == 0 || volumeBusy) { autoOffset = 0; baseVol = cur; expectVol = -1; return; } // mudo ou fade: não mexe
            if (expectVol >= 0 && cur != expectVol && cur == prevVol) { // o Android ignorou o nosso ajuste: desfaz e desiste depois de 3 vezes
                autoOffset = prevOffset; expectVol = -1;
                if (++ignoredSets >= 3) autoBlocked = true;
                return;
            }
            expectVol = -1;
            if (baseVol < 0 || cur != baseVol + autoOffset) { // o usuário mexeu no volume: passa a ser a nova referência
                int nb = cur - autoOffset;
                if (nb < 1) { autoOffset = 0; nb = cur; }
                baseVol = nb;
            }
            if (autoBlocked) return;
            int want = autoVolOn ? Math.round(autoWanted()) : 0;
            want = Math.max(0, Math.min(want, max - baseVol));
            if (autoOffset == want) { autoGate = 0; return; }
            if (++autoGate < 2) return; // um nível a cada ~2 s: subida/descida bem gradual
            autoGate = 0;
            prevVol = cur; prevOffset = autoOffset;
            autoOffset += want > autoOffset ? 1 : -1;
            expectVol = baseVol + autoOffset;
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, expectVol, 0);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ ações da tela
    void changed() { applyNow(); saveSoon(); }

    private void markUser() {
        presetName = "Usuário";
        System.arraycopy(band, 0, userBand, 0, EqMath.N); userPre = pre;
    }
    void setBand(int i, float v) {
        if(i<0||i>=band.length)return;
        float next=EqMath.snap(v);
        if(band[i]==next)return; // sem escrita ao arrastar dentro do mesmo 0,5 dB
        band[i]=next;markUser();changed();
    }
    void setPre(float v) {
        float next=EqMath.snap(v);
        if(pre==next)return;
        pre=next;markUser();changed();
    }
    void setOn(boolean v) { on = v; changed(); }
    void reset() { java.util.Arrays.fill(band, 0f); pre = 0f; presetName = "Plano"; changed(); }
    void applyPreset(String name) {
        if ("Usuário".equals(name)) { System.arraycopy(userBand, 0, band, 0, EqMath.N); pre = userPre; presetName = name; changed(); return; }
        for (int i = 0; i < EqMath.PRESET_NAMES.length; i++) if (EqMath.PRESET_NAMES[i].equals(name)) {
            System.arraycopy(EqMath.PRESETS[i], 0, band, 0, EqMath.N); pre = EqMath.autoPre(band); presetName = name; changed(); return;
        }
        int c = customNames.indexOf(name);
        if (c >= 0) { System.arraycopy(customBands.get(c), 0, band, 0, EqMath.N); pre = customPre.get(c); presetName = name; changed(); }
    }
    boolean isCustom(String name) { return customNames.contains(name); }
    /** Salva a curva atual como predefinição própria (se o nome já existe, substitui). */
    void saveCustom(String rawName) {
        String n = cleanName(rawName);
        if (n.isEmpty()) return;
        if (n.length() > 14) n = n.substring(0, 14).trim();
        for (String b : EqMath.PRESET_NAMES) if (b.equalsIgnoreCase(n)) n = n + " 2";
        if ("Usuário".equalsIgnoreCase(n)) n = "Meu ajuste";
        int idx = customNames.indexOf(n);
        if (idx >= 0) { customBands.set(idx, band.clone()); customPre.set(idx, pre); }
        else { if (customNames.size() >= 12) { customNames.remove(0); customBands.remove(0); customPre.remove(0); } customNames.add(n); customBands.add(band.clone()); customPre.add(pre); }
        presetName = n; saveNow();
    }
    void deleteCustom(String name) {
        int i = customNames.indexOf(name);
        if (i < 0) return;
        customNames.remove(i); customBands.remove(i); customPre.remove(i);
        if (name.equals(presetName)) presetName = "Usuário";
        saveNow();
    }
    void setLoudOn(boolean v) { loudOn = v; volumeChanged(); changed(); }
    void setLoudAmt(float v) { loudAmt = EqMath.clamp(v, 0f, 1f); changed(); }
    void setLoudBelow(float v) { loudBelow = EqMath.clamp(v, 0.15f, 1f); volumeChanged(); changed(); }
    void setAutoVol(boolean v) {
        if(!v && autoVolOn && autoOffset!=0 && baseVol>0 && !volumeBusy) {
            try{audio.setStreamVolume(AudioManager.STREAM_MUSIC,baseVol,0);}
            catch(Exception ignored){}
        }
        autoVolOn=v;
        if(v){autoBlocked=false;ignoredSets=0;}
        else{autoOffset=0;baseVol=-1;expectVol=-1;autoGate=0;}
        saveSoon();
    }
    void setAutoVolMax(int v) { autoVolMax = Math.max(1, Math.min(6, v)); saveSoon(); }
    void setBass(float v) { bass = EqMath.clamp(v, 0f, 1f); changed(); }
    void setLowTone(float v) { lowTone = EqMath.clamp(v, -6f, 6f); changed(); }
    void setHighTone(float v) { highTone = EqMath.clamp(v, -6f, 6f); changed(); }

    // campo sonoro: o Android não tem API pública de balanço/fader; os valores ficam salvos e são enviados por broadcast
    void setField(float x, float y, String name) {
        padX = EqMath.clamp(x, -1f, 1f); padY = EqMath.clamp(y, -1f, 1f); fieldName = name == null ? "Personalizado" : name;
        balance = Math.round(padX * 10f); fader = Math.round(-padY * 10f); sendField(); saveSoon();
    }
    void setBalance(float v) { balance = Math.round(EqMath.clamp(v, -10f, 10f)); padX = balance / 10f; fieldName = "Personalizado"; sendField(); saveSoon(); }
    void setFader(float v) { fader = Math.round(EqMath.clamp(v, -10f, 10f)); padY = -fader / 10f; fieldName = "Personalizado"; sendField(); saveSoon(); }
    private long lastField = 0;
    private void sendField() {
        long now = SystemClock.uptimeMillis();
        if (now - lastField < 150) return; lastField = now;
        try {
            Intent i = new Intent(ACTION_AUDIO_COMMAND).putExtra("balance", (int) balance).putExtra("fader", (int) fader)
                .putExtra("bass", Math.round(bass * 10f)).putExtra("x", padX).putExtra("y", padY);
            ctx.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    /** Diagnostico honesto: bandas virtuais versus nativas, frequencias e leitura do Android.
     *  Nao afirma efeito audivel na Jancar (MCU/DSP pode ignorar).
     */
    String bandsReport() {
        StringBuilder result=new StringBuilder();
        result.append("Controles na tela: ").append(EqMath.N).append(" (passos de 0,5 dB)\n");
        result.append("Estado: ").append(on?"EQ ligado":"som original / EQ desligado").append("\n");
        result.append("Loudness: ").append(loudOn?"ligado":"desligado").append("\n");
        result.append("Pré-ganho: ").append(EqMath.db(pre)).append(" dB\n");
        Equalizer target=eq;
        if(target==null&&!sessEq.isEmpty())target=sessEq.values().iterator().next();
        DeviceProfile info=target==null?null:profiles.get(target);
        if(info==null){
            result.append("Bandas nativas: não identificadas. Ligue o EQ para consultar.");
            return result.toString();
        }
        result.append("Bandas nativas: ").append(info.n).append("\n");
        if(info.n<EqMath.N)result.append("Alguns controles compartilham filtros físicos; ganho independente não é possível.\n");
        float[] levels=targetLevels(info);
        for(int i=0;i<info.n;i++){
            result.append(i+1).append(". ")
                .append(Math.round(info.centers[i])).append(" Hz: pedido ")
                .append(String.format(Locale.US,"%+.2f",levels[i])).append(" dB");
            try{
                result.append(" / Android ")
                    .append(String.format(Locale.US,"%+.2f",target.getBandLevel((short)i)/100f)).append(" dB");
            }catch(Exception ignored){result.append(" / sem leitura");}
            result.append("\n");
        }
        result.append("A leitura do Android não confirma a saída física da Jancar.");
        return result.toString();
    }

    /** Linha de diagnóstico para a tela. */
    String statusLine() { return status; }
    boolean testing() { return testUntil > SystemClock.elapsedRealtime(); }
}
