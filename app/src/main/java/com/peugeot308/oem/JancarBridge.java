package com.peugeot308.oem;

import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import java.util.List;

/**
 * Read-only discovery/binding for the original Jancar firmware service.
 * Action and descriptor were identified in the factory IVI Services APK.
 * Do not make raw Binder transactions until the AIDL contract and access rules
 * are verified on the actual unit. Binder connection != live CAN telemetry.
 */
final class JancarBridge {
    private static final String TAG = "PeugeotJancarBridge";
    private static final String CAR_ACTION = "com.jancar.services.action.car";
    private static final String EXPECTED_DESCRIPTOR = "com.jancar.services.car.ICar";
    private final Context context;
    private final Runnable changed;
    private boolean bound;
    private boolean keyReceiverRegistered;
    private int observedKeyEvents;
    private long lastKeyEventElapsed;
    private final BroadcastReceiver keyObserver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            // Diagnóstico passivo: não consumir teclas, enviar comandos ou confiar em extras não autenticados.
            observedKeyEvents++;
            lastKeyEventElapsed = android.os.SystemClock.elapsedRealtime();
            notifyChanged();
        }
    };
    private boolean connected;
    private String serviceName = "Não localizado";
    private String descriptor = "--";
    private String problem = "Não verificado";

    JancarBridge(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName component, IBinder binder) {
            connected = false;
            try {
                descriptor = binder.getInterfaceDescriptor();
                connected = EXPECTED_DESCRIPTOR.equals(descriptor);
                problem = connected ? "Serviço Jancar encontrado (dados CAN ainda não disponíveis)" : "Interface desconhecida: " + descriptor;
            } catch (RemoteException | RuntimeException ex) {
                problem = "Falha ao identificar serviço: " + ex.getClass().getSimpleName();
            }
            Log.i(TAG, "Conexão serviço: " + serviceName + "; " + problem);
            if (changed != null) changed.run();
        }
        @Override public void onServiceDisconnected(ComponentName component) {
            connected = false;
            descriptor = "--";
            problem = "Serviço desconectado";
            if (changed != null) changed.run();
        }
    };

    void start() {
        stopService();
        if (!keyReceiverRegistered) {
            try {
                IntentFilter f = new IntentFilter("com.jancar.services.action.key.event");
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(keyObserver, f, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(keyObserver, f);
                keyReceiverRegistered = true;
            } catch (RuntimeException ex) { Log.i(TAG, "Evento OEM não acessível: " + ex.getClass().getSimpleName()); }
        }
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> candidates;
        try { candidates = pm.queryIntentServices(new Intent(CAR_ACTION), 0); }
        catch (RuntimeException ex) { problem = "Consulta de serviços bloqueada: " + ex.getClass().getSimpleName(); notifyChanged(); return; }
        if (candidates == null || candidates.isEmpty()) {
            problem = "Serviço IVI Car não publicado para outros aplicativos";
            notifyChanged();
            return;
        }
        for (ResolveInfo match : candidates) {
            ServiceInfo si = match.serviceInfo;
            if (si == null) continue;
            serviceName = si.packageName + "/" + si.name;
            if (!si.exported) { problem = "Serviço encontrado, mas não é exportado"; continue; }
            if (si.permission != null && context.checkSelfPermission(si.permission) != PackageManager.PERMISSION_GRANTED) {
                problem = "Serviço exige permissão: " + si.permission;
                continue;
            }
            try {
                Intent explicit = new Intent(CAR_ACTION);
                explicit.setComponent(new ComponentName(si.packageName, si.name));
                if (context.bindService(explicit, connection, Context.BIND_AUTO_CREATE)) {
                    bound = true;
                    problem = "Conectando ao serviço IVI...";
                    notifyChanged();
                    return;
                }
                problem = "Android recusou a conexão ao serviço";
            } catch (SecurityException ex) {
                problem = "Serviço restrito a aplicativos do fabricante";
            } catch (RuntimeException ex) {
                problem = "Falha na conexão: " + ex.getClass().getSimpleName();
            }
        }
        notifyChanged();
    }

    void stop() {
        stopService();
        if (keyReceiverRegistered) {
            try { context.unregisterReceiver(keyObserver); } catch (RuntimeException ignored) {}
            keyReceiverRegistered = false;
        }
    }
    private void stopService() {
        if (bound) {
            try { context.unbindService(connection); } catch (RuntimeException ignored) {}
        }
        bound = false;
        connected = false;
    }

    boolean serviceAvailable() { return connected; }
    String diagnostic() {
        return "IVI Car: " + (connected ? "interface reconhecida" : "sem ligação confirmada") + "\n"
            + "Serviço: " + serviceName + "\n"
            + "Interface Binder: " + descriptor + "\n"
            + "Estado: " + problem + "\n"
            + "Eventos de teclas OEM observados: " + observedKeyEvents + " (informação não autenticada)\n"
            + "Último evento: " + (lastKeyEventElapsed > 0 ? (android.os.SystemClock.elapsedRealtime() - lastKeyEventElapsed)/1000 + " s atrás" : "nenhum") + "\n"
            + "CANBUS: telemetria não validada (sem valores simulados).";
    }
    private void notifyChanged() { if (changed != null) changed.run(); }
}
