package com.peugeot308.oem;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Alertas GPS a partir dos dados abertos Pardal (ODbL). Não substitui placas e sinalização oficial. */
final class PardalRadar {
    static final int SMART=0, VISUAL=1, BOTH=2, OFF=3;
    static final String URL="https://github.com/Murkiriel/pardal/releases/latest/download/pardal-SP.zip";
    private static final String CATALOG="https://github.com/Murkiriel/pardal/releases/latest/download/catalogo.json";
    // Alternativa semanal oficial quando a central não consegue baixar anexos das Releases.
    private static final String RAW_CATALOG="https://raw.githubusercontent.com/Murkiriel/pardal/main/catalogo.json";
    private static final String RAW_CSV="https://raw.githubusercontent.com/Murkiriel/pardal/main/estados/SP/radares.csv";
    private static final long DAY=86400000L;
    private static PardalRadar instance;
    static synchronized PardalRadar shared(Context context, SharedPreferences pref){
        if(instance==null)instance=new PardalRadar(context.getApplicationContext(),pref);
        return instance;
    }

    private final Context context;
    private final SharedPreferences pref;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"PardalData");t.setDaemon(true);return t;});
    private final File data;
    private final FieldLog logger;
    private volatile List<Camera> cameras=Collections.emptyList();
    private volatile Map<Long,List<Camera>> cells=Collections.emptyMap();
    private volatile String status="Base SP ainda não baixada";
    private volatile Camera current;
    private volatile int distance=-1;
    private volatile float speed=-1;
    private volatile long fixAt=0;
    private volatile float accuracy=-1;
    private volatile String gpsReason="Aguardando GPS";
    private volatile int mode;
    private volatile boolean busy=false;
    private long lastSound=0;
    private String lastCamera="";
    private ToneGenerator tone;
    private int toneVolume=-1;
    private long lastGpsLog=0;
    private Location lastBearingFix;
    private String recentlyPassedCamera="";
    private long recentlyPassedAt=0L;
    private volatile String lastAudioError="";
    private String lastLoggedCamera="";
    private volatile boolean simulating=false;
    private long simulationStart=0;
    private int modeBeforeSimulation=-1;
    private static final Camera SIM=new Camera(0,0,60,-1,"FIXED");

    private static final class Camera {
        final double lat,lon; final int limit,direction;final String kind;
        Camera(double lat,double lon,int limit,int direction,String kind){this.lat=lat;this.lon=lon;this.limit=limit;this.direction=direction;this.kind=kind;}
        String key(){return lat+":"+lon+":"+kind;}
    }
    private PardalRadar(Context context,SharedPreferences pref){
        this.context=context;this.pref=pref;
        data=new File(context.getFilesDir(),"pardal_sp_radares.csv");
        logger=new FieldLog(context,pref.getBoolean("field_log_enabled",false));
        // Se o usuário nunca configurou o radar, inicia no modo que realmente avisa.
        // Uma escolha anterior de "Desligado" continua sendo respeitada.
        mode=Math.max(0,Math.min(3,pref.getInt("pardal_radar_mode",BOTH)));
        io.execute(()->{try{
            List<Camera> list=parse(data);
            if(!list.isEmpty()){publish(list);status="Base SP: "+list.size()+" radares";}
        }catch(Exception e){status="Base indisponível: "+e.getClass().getSimpleName();}});
    }
    private void log(String event){logger.setEnabled(pref.getBoolean("field_log_enabled",false));logger.record("RADAR",event);}
    int mode(){return mode;}
    String modeLabel(){
        if(mode==OFF)return "Desligado";
        if(mode==VISUAL)return "Som desligado (somente visual)";
        if(mode==SMART)return "Inteligente (som só acima do limite)";
        return "Visual e áudio";
    }
    boolean testSound(){
        try{
            stopTone();
            tone=new ToneGenerator(AudioManager.STREAM_MUSIC,95);
            toneVolume=95;
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2,440);
            lastAudioError="";
            log("teste_som_midia=ok");
            return true;
        }catch(Exception e){
            lastAudioError=e.getClass().getSimpleName();
            log("teste_som_falha="+lastAudioError);
            return false;
        }
    }
    void setMode(int value){
        mode=Math.max(SMART,Math.min(OFF,value));
        pref.edit().putInt("pardal_radar_mode",mode).apply();
        if(mode==OFF){stopSimulation();current=null;distance=-1;stopTone();
            recentlyPassedCamera="";recentlyPassedAt=0L;}
        log("modo="+mode);
    }
    boolean isSimulating(){return simulating;}
    String status(){return status+(busy?" · atualizando…":"")
            +(lastAudioError.isEmpty()?"":" · falha som: "+lastAudioError);}
    int count(){return cameras.size();}
    private float remainingNow(){
        long ms=Math.max(0L,SystemClock.elapsedRealtime()-fixAt);
        // Só prediz enquanto o fix GPS está válido. Não extrapola posições antigas.
        return distance-Math.max(0f,speed)/3.6f*Math.min(6f,ms/1000f);
    }
    int distance(){return active()?Math.max(0,Math.round(remainingNow())):-1;}
    int limit(){Camera c=current;return active()&&c!=null?c.limit:0;}
    String kind(){Camera c=current;return c==null?"RADAR":c.kind.equals("RED_LIGHT")?"SEMÁFORO":c.kind.equals("SECTION")?"RADAR DE TRECHO":"RADAR FIXO";}
    boolean over(){return limit()>0&&speed>limit()+1;}
    int speed(){return Math.max(0,Math.round(speed));}
    boolean active(){
        long age=SystemClock.elapsedRealtime()-fixAt;
        return mode!=OFF&&current!=null&&distance>=0&&age>=0L&&age<6000L
                // Ao cruzar o ponto previsto, remove o aviso em vez de ficar em 0 m.
                &&remainingNow()>-6f;
    }
    String gpsStatus(){
        if(simulating)return "Simulação (não usa GPS)";
        if(fixAt>0&&SystemClock.elapsedRealtime()-fixAt<6000L)
            return accuracy<0?"GPS sem precisão informada":("GPS ±"+Math.round(accuracy)+" m");
        return gpsReason;
    }
    String lastUpdated(){
        long when=pref.getLong("pardal_last_success",0L);
        return when<=0?"Nunca (base inicial pendente)":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(when));
    }
    void checkDaily(boolean hasNetwork){if(mode!=OFF)requestUpdate(hasNetwork,false);}
    void forceUpdate(boolean hasNetwork){requestUpdate(hasNetwork,true);}
    private void requestUpdate(boolean network,boolean force){
        if(!network){if(force)status="Conecte o Wi-Fi para atualizar";return;}
        if(busy)return;
        long now=System.currentTimeMillis();
        if(!force&&now-pref.getLong("pardal_last_check",0L)<DAY)return;
        if(force&&now-pref.getLong("pardal_last_manual",0L)<60_000L){status="Aguarde 1 minuto entre atualizações manuais";return;}
        busy=true;
        if(force)pref.edit().putLong("pardal_last_manual",now).apply();
        io.execute(()->downloadAndReplace());
    }
    private void downloadAndReplace(){
        File temp=new File(context.getCacheDir(),"pardal_sp_new.csv");
        File bak=new File(context.getFilesDir(),"pardal_sp_old.csv");
        try{
            String source="release diária";
            try{
                downloadReleaseVerified(temp);
            }catch(Exception releaseError){
                log("release_indisponivel="+shortError(releaseError));
                temp.delete();
                // Caso a hospedagem de anexos da release falhe, a base do repositório
                // ainda permite alertas offline após conferir bytes e SHA-256.
                downloadRawVerified(temp);
                source="repositório semanal";
            }
            List<Camera> candidate=parse(temp);
            if(candidate.size()<100)throw new IOException("Base contém poucos radares ativos");
            if(bak.exists()&&!bak.delete())throw new IOException("Falha ao limpar backup antigo");
            if(data.exists()&&!data.renameTo(bak))throw new IOException("Falha no backup da base");
            if(!temp.renameTo(data)){
                if(bak.exists())bak.renameTo(data);
                throw new IOException("Falha na troca da base");
            }
            publish(candidate);
            bak.delete();
            status="Base SP: "+candidate.size()+" radares ("+source+")";
            long now=System.currentTimeMillis();
            pref.edit().putLong("pardal_last_check",now).putLong("pardal_last_success",now).apply();
            log("base_atualizada quantidade="+candidate.size()+" fonte="+source+" sha256=ok");
        }catch(Exception e){
            status="Falha ao carregar base: "+shortError(e);
            log("download_falha="+shortError(e));
            pref.edit().putLong("pardal_last_check",
                    System.currentTimeMillis()-23L*60*60*1000L).apply();
        }finally{temp.delete();busy=false;}
    }
    private static String shortError(Exception e){
        String message=e.getMessage();
        if(message==null||message.trim().isEmpty())message=e.getClass().getSimpleName();
        message=message.replace('\n',' ').replace('\r',' ').trim();
        return message.length()>100?message.substring(0,100):message;
    }
    private static JSONObject cameraCatalog(String url)throws Exception{
        byte[] json=fetch(url,2_000_000);
        return new JSONObject(new String(json,"UTF-8"))
                .getJSONObject("ufs").getJSONObject("SP").getJSONObject("cameras");
    }
    private static void verifyCsv(File csv,JSONObject record)throws Exception{
        String expected=record.getString("sha256").toLowerCase(Locale.ROOT);
        long expectedSize=record.getLong("bytes");
        if(!expected.matches("[a-f0-9]{64}")||expectedSize<1000||expectedSize>8_000_000)
            throw new IOException("Dados de integridade inválidos");
        if(csv.length()!=expectedSize||!expected.equals(sha256(csv)))
            throw new IOException("CSV não corresponde ao SHA-256 do catálogo");
    }
    private void downloadReleaseVerified(File temp)throws Exception{
        JSONObject record=cameraCatalog(CATALOG);
        HttpURLConnection con=connection(URL);
        try{
            if(con.getResponseCode()!=200)throw new IOException("HTTP ZIP "+con.getResponseCode());
            boolean extracted=false;
            long bytes=0;
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(con.getInputStream()))){
                ZipEntry ent;
                byte[] buffer=new byte[8192];
                while((ent=zip.getNextEntry())!=null){
                    String name=ent.getName().replace('\\','/');
                    if(name.equals("radares.csv")||name.endsWith("/radares.csv")){
                        try(OutputStream out=new FileOutputStream(temp)){
                            int n;
                            while((n=zip.read(buffer))!=-1){
                                bytes+=n;
                                if(bytes>8_000_000)throw new IOException("CSV grande demais");
                                out.write(buffer,0,n);
                            }
                        }
                        extracted=true;
                        break;
                    }
                    zip.closeEntry();
                }
            }
            if(!extracted)throw new IOException("radares.csv não encontrado no ZIP");
            verifyCsv(temp,record);
        }finally{con.disconnect();}
    }
    private void downloadRawVerified(File temp)throws Exception{
        JSONObject record=cameraCatalog(RAW_CATALOG);
        byte[] csv=fetch(RAW_CSV,8_000_000);
        try(OutputStream out=new FileOutputStream(temp)){out.write(csv);}
        verifyCsv(temp,record);
    }
    private static HttpURLConnection connection(String url)throws IOException{
        HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(12000);con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent","Peugeot308Launcher/1.53");
        return con;
    }
    private static byte[] fetch(String url,int max)throws IOException{
        HttpURLConnection con=connection(url);
        try{
            if(con.getResponseCode()!=200)throw new IOException("HTTP catalogo "+con.getResponseCode());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream input=con.getInputStream()){
                byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("Catálogo muito grande");out.write(buffer,0,n);}
            }
            return out.toByteArray();
        }finally{con.disconnect();}
    }
    private static String sha256(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1)digest.update(b,0,n);
        }
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.US,"%02x",b&255));
        return out.toString();
    }
    private static List<Camera> parse(File f)throws Exception{
        if(!f.exists())return Collections.emptyList();
        ArrayList<Camera> result=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){
            String header=r.readLine();
            if(header==null||!header.replace("\uFEFF","").equals("lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg"))
                throw new IOException("Cabeçalho inesperado");
            String s;while((s=r.readLine())!=null){
                String[] a=s.split(",",-1);if(a.length<9||!"1".equals(a[5]))continue;
                try{
                    double lat=Double.parseDouble(a[0]),lon=Double.parseDouble(a[1]);
                    if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -35||lat>6||lon < -75||lon > -30)continue;
                    int limit=a[3].isEmpty()?0:Integer.parseInt(a[3]);
                    if(limit<0||limit>200)limit=0;
                    double rawDirection=a[8].isEmpty()?-1:Double.parseDouble(a[8]);
                    int direction=Double.isFinite(rawDirection)?(int)Math.round(rawDirection):-1;
                    if(direction<0||direction>=360)direction=-1;
                    result.add(new Camera(lat,lon,limit,direction,a[2]));
                }catch(NumberFormatException ignored){}
            }
        }
        return Collections.unmodifiableList(result);
    }
    private static int cell(double coordinate){return (int)Math.floor(coordinate*100);}
    private static long key(int lat,int lon){return (((long)lat)<<32)^(lon&0xffffffffL);}
    private void publish(List<Camera> list){
        HashMap<Long,List<Camera>> index=new HashMap<>();
        for(Camera c:list)index.computeIfAbsent(key(cell(c.lat),cell(c.lon)),z->new ArrayList<>()).add(c);
        cameras=list;cells=Collections.unmodifiableMap(index);
    }

    /** Simulador apenas visual/sonoro, sem posições inventadas no GPS real. */
    void startSimulation(){
        if(mode==OFF){modeBeforeSimulation=OFF;mode=BOTH;}
        simulating=true;simulationStart=SystemClock.elapsedRealtime();
        current=SIM;distance=350;speed=80;accuracy=0;fixAt=simulationStart;
        lastCamera="";lastSound=0;log("simulacao_inicio");
    }
    void stopSimulation(){
        if(simulating)log("simulacao_fim");
        simulating=false;
        if(current==SIM){current=null;distance=-1;}
        if(modeBeforeSimulation>=0){mode=modeBeforeSimulation;modeBeforeSimulation=-1;}
    }
    void simulationTick(){
        if(!simulating)return;
        long elapsed=SystemClock.elapsedRealtime()-simulationStart;
        if(elapsed>20500L){stopSimulation();return;}
        current=SIM;distance=Math.max(0,350-(int)(elapsed/1000f*18));
        speed=80;fixAt=SystemClock.elapsedRealtime();
        beepIfNeeded(SIM);
    }
    void onLocation(Location loc){
        if(simulating)return;
        if(mode==OFF){current=null;distance=-1;return;}
        if(loc==null){current=null;distance=-1;gpsReason="Aguardando GPS";return;}

        long ageMs=loc.getElapsedRealtimeNanos()>0 ?
                (SystemClock.elapsedRealtimeNanos()-loc.getElapsedRealtimeNanos())/1_000_000L : 0L;
        boolean oldFix=ageMs>6000L;
        if(!loc.hasAccuracy()||loc.getAccuracy()>60f||oldFix){
            current=null;distance=-1;
            accuracy=loc.hasAccuracy()?loc.getAccuracy():-1f;
            gpsReason=oldFix?"GPS: posição antiga":
                    "GPS impreciso"+(loc.hasAccuracy()?" ±"+Math.round(loc.getAccuracy())+" m":"");
            return;
        }
        accuracy=loc.getAccuracy();
        Location previous=lastBearingFix;
        // Activity e serviço podem receber a mesma amostra GPS: não processar duas vezes.
        if(previous!=null&&previous.getElapsedRealtimeNanos()>0L
                &&loc.getElapsedRealtimeNanos()>0L
                &&loc.getElapsedRealtimeNanos()<=previous.getElapsedRealtimeNanos())return;
        lastBearingFix=new Location(loc);
        float bearing=loc.hasBearing()?loc.getBearing():Float.NaN;
        float movingSpeed=loc.hasSpeed()?loc.getSpeed():0f;
        if(previous!=null){
            long dt=loc.getElapsedRealtimeNanos()-previous.getElapsedRealtimeNanos();
            float elapsed=dt/1_000_000_000f;
            float traveled=previous.distanceTo(loc);
            // Algumas centrais não informam heading/speed embora enviem GPS válido.
            // Só estima rumo quando há deslocamento real, sem inventar posição.
            if(elapsed>.4f&&elapsed<12f&&traveled>6f&&traveled<160f){
                if(Float.isNaN(bearing))bearing=previous.bearingTo(loc);
                if(!loc.hasSpeed())movingSpeed=traveled/elapsed;
            }
        }
        if(Float.isNaN(bearing)||movingSpeed<2f){
            current=null;distance=-1;fixAt=SystemClock.elapsedRealtime();
            speed=movingSpeed*3.6f;
            gpsReason="GPS: aguardando movimento e rumo";
            return;
        }
        gpsReason=cameras.isEmpty()?"Base de radares vazia: atualize via Wi-Fi":"GPS estável";
        // Continuar a contagem do instante já exibido, sem saltar para cima quando
        // uma nova amostra GPS chega com pequena oscilação de coordenadas.
        long previousAge=SystemClock.elapsedRealtime()-fixAt;
        int previousShown=(current!=null&&distance>=0&&previousAge>=0&&previousAge<2500L)
                ?Math.max(0,Math.round(remainingNow())):-1;
        // O callback pode chegar atrasado: registrar a hora real da amostra GPS.
        fixAt=SystemClock.elapsedRealtime()-Math.max(0L,ageMs);
        speed=movingSpeed*3.6f;
        Camera best=null;
        float nearest=351f;
        float bestAlong=351f;
        float bestScore=Float.MAX_VALUE;
        float[] result=new float[2];
        Camera prior=current;
        int latCell=cell(loc.getLatitude()),lonCell=cell(loc.getLongitude());
        Map<Long,List<Camera>> index=cells;
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            List<Camera> nearby=index.get(key(latCell+dy,lonCell+dx));
            if(nearby==null)continue;
            for(Camera c:nearby){
                Location.distanceBetween(loc.getLatitude(),loc.getLongitude(),c.lat,c.lon,result);
                float d=result[0];
                if(!RadarGeo.candidate(d,bearing,result[1],loc.getAccuracy(),c.direction))
                    continue;
                if(c.key().equals(recentlyPassedCamera)
                        &&SystemClock.elapsedRealtime()-recentlyPassedAt<45000L)continue;
                float along=RadarGeo.forwardMeters(d,bearing,result[1]);
                float lateral=RadarGeo.lateralMeters(d,bearing,result[1]);
                float score=along+.15f*lateral;
                if(prior!=null&&prior.key().equals(c.key()))score-=12f;
                if(score>=bestScore)continue;
                best=c;nearest=d;bestAlong=along;bestScore=score;
            }
        }
        // Se o radar anteriormente acompanhado acabou de ficar para trás,
        // não deixá-lo reaparecer quando a posição GPS oscilar perto do poste.
        if(prior!=null && prior!=SIM && (best==null||!best.key().equals(prior.key()))){
            Location.distanceBetween(loc.getLatitude(),loc.getLongitude(),
                    prior.lat,prior.lon,result);
            if(RadarGeo.passed(result[0],bearing,result[1],loc.getAccuracy())){
                recentlyPassedCamera=prior.key();
                recentlyPassedAt=SystemClock.elapsedRealtime();
                log("radar_ultrapassado kind="+prior.kind+" offset_lateral="+
                        Math.round(RadarGeo.lateralMeters(result[0],bearing,result[1]))+"m");
            }
        }
        String bestKey=best==null?"":best.key();
        if(!bestKey.equals(lastLoggedCamera)){
            lastLoggedCamera=bestKey;
            if(best!=null)log("aproximacao kind="+best.kind+" limite="+best.limit+" distancia="+Math.round(nearest)+"m");
        }
        boolean same=best!=null&&prior!=null&&best.key().equals(prior.key());
        int nextDistance=best==null?-1:Math.max(0,Math.round(bestAlong));
        // O GPS pode oscilar alguns metros. Uma vez acompanhando o mesmo radar,
        // a contagem não sobe enquanto o carro continua em aproximação.
        if(same&&distance>=0) {
            nextDistance=Math.min(distance,nextDistance);
            if(previousShown>=0)nextDistance=Math.min(previousShown,nextDistance);
        }
        current=best;
        distance=nextDistance;
        if(best!=null)beepIfNeeded(best);
        else if(prior!=null)stopTone();
        if(SystemClock.elapsedRealtime()-lastGpsLog>60000L){
            lastGpsLog=SystemClock.elapsedRealtime();
            log("gps precisao="+Math.round(accuracy)+"m velocidade="+Math.round(speed)+"kmh radares_index="+cameras.size());
        }
    }
    private void beepIfNeeded(Camera c){
        if(mode==VISUAL||mode==OFF)return;
        boolean speeding=c.limit>0&&speed>c.limit+1;
        if(mode==SMART&&!speeding)return;
        long now=SystemClock.elapsedRealtime();
        int approaching=distance();
        if(approaching<0)return;
        long gap=approaching<=50?650:approaching<=100?1100:approaching<=150?1800:approaching<=200?2900:5500;
        if(!speeding)gap=Math.max(gap,8000);
        if(!c.key().equals(lastCamera)){
            lastCamera=c.key();
            // Evita uma rajada de bipes quando radares muito próximos alternam.
            lastSound=Math.max(0L,lastSound-1500L);
        }
        if(now-lastSound<gap)return;
        lastSound=now;
        try{
            // Na multimídia Jancar o canal de notificações pode estar em zero.
            // O canal de MÍDIA é o mesmo usado para reprodução normal do aparelho.
            int volume=speeding?(approaching<=50?95:approaching<=100?92:approaching<=200?88:82):76;
            if(tone==null||toneVolume!=volume){
                stopTone();tone=new ToneGenerator(AudioManager.STREAM_MUSIC,volume);toneVolume=volume;
            }
            boolean sent=tone.startTone(speeding?ToneGenerator.TONE_PROP_BEEP2:
                    ToneGenerator.TONE_PROP_BEEP,190);
            if(!sent)lastAudioError="Sistema não iniciou o som";
            else lastAudioError="";
            log("bipe enviado="+sent+" distancia="+approaching+"m acima_limite="+speeding);
        }catch(Exception e){
            lastAudioError=e.getClass().getSimpleName();
            log("audio_falha="+lastAudioError);
        }
    }
    private void stopTone(){if(tone!=null){tone.stopTone();tone.release();tone=null;}toneVolume=-1;}
    void close(){stopSimulation();stopTone();}
}
