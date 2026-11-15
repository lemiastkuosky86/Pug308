package com.peugeot308.oem;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

/**
 * Leitura passiva do serviço CAN original da Jancar (sem comandos à MCU).
 * Contrato identificado no APK original com.can.activity / ICanUI / ICanBus.
 * O funcionamento depende do firmware/serviço instalado na central.
 */
final class CanbusVehicleReader {
    static final String PERMISSION = "com.jancar.permission.BIND_CANBUS";
    private static final String UI_DESCRIPTOR = "com.autoai.canbus.ICanUI";
    private static final String CAN_DESCRIPTOR = "com.autoai.canbus.ICanBus";
    private static final String PACKAGE = "com.can.activity";
    private static final String SERVICE = "com.can.ui.CanPopWind";

    interface Listener {
        void onData(Snapshot snapshot, String state);
    }

    static final class Snapshot {
        // CabinInfo.writeToParcel: capô, 4 portas, porta-malas, demais campos.
        final int[] cabin; // 22 campos; porta-malas=5, travas=16
        final int[] lights; // esquerda, direita, pisca-alerta
        final int[] dashboardInts; // marcha, odômetro, autonomia, RPM, velocidade, tempo, tanque, 3 pedais
        final float[] dashboardFloats; // combustível, porcentagem, consumo médio/instantâneo, temperaturas
        final String canboxVersion;
        final long sampledAt;
        Snapshot(int[] cabin, int[] lights, int[] di, float[] df, String version) {
            this.cabin=cabin; this.lights=lights; this.dashboardInts=di; this.dashboardFloats=df;
            this.canboxVersion=version==null?"":version;
            this.sampledAt=SystemClock.elapsedRealtime();
        }
        boolean hasData() { return cabin!=null || lights!=null || dashboardInts!=null; }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private volatile boolean started=false;
    private boolean bound=false;
    private volatile IBinder uiBinder;
    private volatile IBinder canBinder;
    private volatile Snapshot lastSnapshot;
    private volatile String status="Não iniciado";

    CanbusVehicleReader(Context context, Listener listener) {
        this.context=context.getApplicationContext();
        this.listener=listener;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            status="Serviço CAN encontrado; verificando API";
            if(worker!=null)worker.post(() -> {
                uiBinder=binder;
                canBinder=null;
                poll();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            uiBinder=null; canBinder=null; lastSnapshot=null;
            updateStatus("Serviço CAN desconectado",null);
        }
    };

    void start() {
        if(started)return;
        started=true;
        workerThread=new HandlerThread("P308-CAN-ReadOnly");
        workerThread.start();
        worker=new Handler(workerThread.getLooper());
        if(context.checkSelfPermission(PERMISSION)!=PackageManager.PERMISSION_GRANTED) {
            updateStatus("Permissão CANBUS não concedida pelo Android",null);
            return;
        }
        try {
            Intent intent=new Intent("autoai.intent.action.CANBUS_SERVICE");
            intent.setComponent(new ComponentName(PACKAGE,SERVICE));
            bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
            if(!bound)updateStatus("Serviço CAN da Jancar não acessível",null);
        } catch(SecurityException ex) {
            updateStatus("Acesso CAN bloqueado: permissão do fabricante",null);
        } catch(RuntimeException ex) {
            updateStatus("Falha ao conectar CAN: "+ex.getClass().getSimpleName(),null);
        }
    }

    void stop() {
        started=false;
        if(bound) {
            try {context.unbindService(connection);} catch(RuntimeException ignored){}
            bound=false;
        }
        uiBinder=null;canBinder=null;lastSnapshot=null;
        if(worker!=null)worker.removeCallbacksAndMessages(null);
        if(workerThread!=null)workerThread.quitSafely();
        worker=null;workerThread=null;
    }

    String status() {return status;}
    /** Diagnóstico passivo da integração instalada no aparelho. Não envia comandos CAN. */
    String diagnostic() {
        StringBuilder b=new StringBuilder();
        b.append("CANBOX Jancar • investigação\n");
        b.append("Estado: ").append(status).append('\n');
        PackageManager pm=context.getPackageManager();
        boolean granted=context.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED;
        b.append("Permissão ").append(PERMISSION).append(": ")
                .append(granted?"CONCEDIDA":"NÃO CONCEDIDA").append('\n');
        try {
            PermissionInfo info=pm.getPermissionInfo(PERMISSION,0);
            int kind=info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            b.append("Proteção da permissão: ")
                    .append(kind==PermissionInfo.PROTECTION_SIGNATURE?"assinatura OEM":
                            kind==PermissionInfo.PROTECTION_DANGEROUS?"solicitável em runtime":
                            kind==PermissionInfo.PROTECTION_NORMAL?"normal":"especial ("+kind+")")
                    .append('\n');
        } catch(PackageManager.NameNotFoundException e) {
            b.append("Permissão OEM não declarada neste Android\n");
        } catch(RuntimeException e) {
            b.append("Consulta de permissão impedida: ").append(e.getClass().getSimpleName()).append('\n');
        }
        try {
            android.content.pm.PackageInfo pkg=pm.getPackageInfo(PACKAGE,0);
            b.append("Pacote OEM ").append(PACKAGE).append(": INSTALADO\n");
            b.append("Versão OEM: ").append(pkg.versionName).append('\n');
        } catch(PackageManager.NameNotFoundException e) {
            b.append("Pacote OEM ").append(PACKAGE).append(": não encontrado/visível\n");
        } catch(RuntimeException e) {
            b.append("Consulta do pacote bloqueada: ").append(e.getClass().getSimpleName()).append('\n');
        }
        try {
            ServiceInfo svc=pm.getServiceInfo(new ComponentName(PACKAGE,SERVICE),0);
            b.append("Serviço ").append(SERVICE).append(": declarado\n");
            b.append("Exportado: ").append(svc.exported?"SIM":"NÃO").append('\n');
            b.append("Permissão exigida pelo serviço: ")
                    .append(svc.permission==null?"nenhuma explícita":svc.permission).append('\n');
        } catch(PackageManager.NameNotFoundException e) {
            b.append("Serviço ").append(SERVICE).append(": ausente ou oculto\n");
        } catch(RuntimeException e) {
            b.append("Consulta do serviço bloqueada: ").append(e.getClass().getSimpleName()).append('\n');
        }
        b.append("Binder UI: ").append(uiBinder==null?"não vinculado":"vinculado").append('\n');
        b.append("Binder CAN: ").append(canBinder==null?"sem interface de dados":"interface localizada").append('\n');
        Snapshot snap=lastSnapshot;
        if(snap==null)b.append("Pacotes de telemetria: nenhum\n");
        else {
            b.append("Última resposta: ").append((SystemClock.elapsedRealtime()-snap.sampledAt)/1000L).append(" s\n");
            b.append("CANBOX versão: ").append(snap.canboxVersion.isEmpty()?"não informada":snap.canboxVersion).append('\n');
            b.append("Vetores presentes: portas=").append(snap.cabin!=null)
                    .append(" luzes=").append(snap.lights!=null)
                    .append(" painel=").append(snap.dashboardInts!=null).append('\n');
            if(snap.dashboardInts!=null)b.append("Painel (valores brutos): ")
                    .append(java.util.Arrays.toString(snap.dashboardInts)).append('\n');
            if(snap.dashboardFloats!=null)b.append("Sensores (valores brutos): ")
                    .append(java.util.Arrays.toString(snap.dashboardFloats)).append('\n');
            b.append("Valores brutos não são dados validados do Peugeot.\n");
        }
        return b.toString();
    }
    boolean fresh() {
        Snapshot s=lastSnapshot;
        return s!=null && s.hasData() && SystemClock.elapsedRealtime()-s.sampledAt<10000L;
    }

    private void updateStatus(String value,Snapshot snapshot) {
        status=value;
        if(snapshot!=null)lastSnapshot=snapshot;
        if(listener!=null)main.post(()->{if(started)listener.onData(snapshot,value);});
    }

    private IBinder obtainCanService(IBinder b) throws RemoteException {
        if(b==null)return null;
        if(!UI_DESCRIPTOR.equals(b.getInterfaceDescriptor()))
            throw new IllegalStateException("Interface CAN desconhecida: "+b.getInterfaceDescriptor());
        Parcel request=Parcel.obtain(),response=Parcel.obtain();
        try{
            request.writeInterfaceToken(UI_DESCRIPTOR);
            request.writeString("CanBusManager");
            if(!b.transact(1,request,response,0))return null;
            response.readException();
            IBinder found=response.readStrongBinder();
            if(found!=null&&!CAN_DESCRIPTOR.equals(found.getInterfaceDescriptor()))
                throw new IllegalStateException("Dados CAN com interface desconhecida");
            return found;
        }finally{request.recycle();response.recycle();}
    }

    private int[] readInts(int code,int length) throws RemoteException {
        IBinder b=canBinder; if(b==null)return null;
        Parcel request=Parcel.obtain(),response=Parcel.obtain();
        try{
            request.writeInterfaceToken(CAN_DESCRIPTOR);
            if(!b.transact(code,request,response,0))return null;
            response.readException();
            if(response.readInt()==0)return null;
            int[] values=new int[length];
            for(int i=0;i<length;i++)values[i]=response.readInt();
            return values;
        }finally{request.recycle();response.recycle();}
    }

    private String readVersion() throws RemoteException {
        IBinder b=canBinder; if(b==null)return "";
        Parcel request=Parcel.obtain(),response=Parcel.obtain();
        try{
            request.writeInterfaceToken(CAN_DESCRIPTOR);
            if(!b.transact(5,request,response,0))return "";
            response.readException();
            String version=response.readString();
            return version==null?"":version;
        }finally{request.recycle();response.recycle();}
    }

    private Dashboard readDashboard() throws RemoteException {
        IBinder b=canBinder; if(b==null)return null;
        Parcel request=Parcel.obtain(),response=Parcel.obtain();
        try{
            request.writeInterfaceToken(CAN_DESCRIPTOR);
            if(!b.transact(18,request,response,0))return null;
            response.readException();
            if(response.readInt()==0)return null;
            int[] ints=new int[10];
            float[] floats=new float[8];
            for(int i=0;i<7;i++)ints[i]=response.readInt();
            for(int i=0;i<8;i++)floats[i]=response.readFloat();
            for(int i=7;i<10;i++)ints[i]=response.readInt();
            return new Dashboard(ints,floats);
        }finally{request.recycle();response.recycle();}
    }

    private static final class Dashboard {
        final int[] ints;final float[] floats;
        Dashboard(int[] ints,float[] floats){this.ints=ints;this.floats=floats;}
    }

    private void poll() {
        if(!started)return;
        try{
            if(canBinder==null)canBinder=obtainCanService(uiBinder);
            if(canBinder==null){
                updateStatus("Serviço CAN localizado; interface de dados indisponível",null);
            }else{
                int[] cabin=readInts(14,22);
                int[] lights=readInts(17,3);
                Dashboard dash=readDashboard();
                String ver=readVersion();
                Snapshot snapshot=new Snapshot(cabin,lights,dash==null?null:dash.ints,
                        dash==null?null:dash.floats,ver);
                updateStatus(snapshot.hasData()
                        ?"API CAN respondendo • confirme abrindo uma porta"
                        :"API CAN conectada, mas sem telemetria",snapshot);
            }
        }catch(Exception ex){
            canBinder=null;
            String detail=ex.getMessage()==null?"":ex.getMessage().replace('\n',' ');
            if(detail.length()>120)detail=detail.substring(0,120);
            updateStatus("Leitura CAN indisponível: "+ex.getClass().getSimpleName()
                    +(detail.isEmpty()?"":" • "+detail),null);
        }
        if(started&&worker!=null)worker.postDelayed(this::poll,2500L);
    }
}
