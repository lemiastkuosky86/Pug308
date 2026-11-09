package com.peugeot308.oem;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Leitor ELM327 Bluetooth clássico (SPP), estritamente de leitura.
 * Somente consulta PIDs OBD-II padrão 01 0C (RPM), 01 0D (km/h), 01 05 (temp.), 01 2F (combustível).
 * Não envia comandos de controle para o veículo, não varre redes e nunca simula dados.
 */
final class ObdBluetoothReader {
    interface Listener { void onData(String[] values, boolean live, String status); }
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean running;
    private volatile BluetoothSocket socket;
    private Thread worker;
    private volatile String status = "Desconectado";
    private volatile long lastData;
    ObdBluetoothReader(Listener listener) { this.listener = listener; }
    String getStatus() { return status; }
    boolean isLive() { return running && SystemClock.elapsedRealtime() - lastData < 5000; }
    void stop() {
        running = false;
        BluetoothSocket s = socket;
        socket = null;
        if(s != null) try { s.close(); } catch(Exception ignored) {}
        if(worker != null) worker.interrupt();
        status = "Desconectado";
    }
    void start(String address) {
        stop();
        if(address == null || address.trim().isEmpty()) { update(null, false, "Escolha um adaptador OBD2 pareado"); return; }
        running = true;
        worker = new Thread(() -> poll(address), "Peugeot-OBD-readonly");
        worker.setDaemon(true);
        worker.start();
    }
    private void update(String[] data, boolean live, String message) {
        status = message;
        main.post(() -> listener.onData(data, live, message));
    }
    private void poll(String address) {
        BluetoothSocket s = null;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if(a == null || !a.isEnabled()) throw new IOException("Bluetooth desligado ou indisponível");
            BluetoothDevice d = a.getRemoteDevice(address);
            update(null,false,"Conectando ao adaptador OBD2…");
            s = d.createRfcommSocketToServiceRecord(SPP); socket = s;
            s.connect();
            InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
            send(out,in,"ATZ");send(out,in,"ATE0");send(out,in,"ATL0");send(out,in,"ATS0");send(out,in,"ATH0");send(out,in,"ATSP0");
            String[] values = new String[11];
            while(running) {
                boolean got = false;
                Integer rpm = pid(out,in,"010C",0x0C,2);
                if(rpm != null) { values[4] = Integer.toString(rpm / 4); got = true; }
                Integer speed = pid(out,in,"010D",0x0D,1);
                if(speed != null) { values[0] = Integer.toString(speed); got = true; }
                Integer coolant = pid(out,in,"0105",0x05,1);
                if(coolant != null) { values[9] = Integer.toString(coolant - 40); got = true; }
                Integer fuel = pid(out,in,"012F",0x2F,1);
                if(fuel != null) { values[3] = Integer.toString(Math.round(100f * fuel / 255f)); got = true; }
                if(got) {
                    lastData = SystemClock.elapsedRealtime();
                    update(values.clone(),true,"OBD2 conectado • leituras confirmadas");
                } else update(null,false,"OBD2 conectado, mas sem PIDs disponíveis");
                Thread.sleep(1250);
            }
        } catch(SecurityException e) { update(null,false,"Permissão Bluetooth negada"); }
        catch(Exception e) { if(running) update(null,false,"OBD2: " + (e.getMessage()==null?"falha de conexão":e.getMessage())); }
        finally { if(s != null) try { s.close(); } catch(Exception ignored) {} socket = null; running=false; }
    }
    private String send(OutputStream out, InputStream in, String cmd) throws IOException {
        out.write((cmd+"\r").getBytes("US-ASCII"));out.flush();
        StringBuilder text = new StringBuilder();long until=SystemClock.elapsedRealtime()+2400;
        while(running && SystemClock.elapsedRealtime()<until) {
            if(in.available()>0) {
                int b=in.read();if(b<0)break;
                if(b=='>')break;
                if(text.length()<256)text.append((char)b);
            } else try {Thread.sleep(20);}catch(InterruptedException e){throw new IOException("Interrompido");}
        }
        return text.toString().toUpperCase(Locale.ROOT).replace(" ","").replace("\r","").replace("\n","");
    }
    private Integer pid(OutputStream out, InputStream in,String cmd,int pid,int bytes) throws IOException {
        String r=send(out,in,cmd);
        String key=String.format(Locale.ROOT,"41%02X",pid);
        int start=r.indexOf(key);
        if(start<0 || start+key.length()+bytes*2>r.length())return null;
        try {return Integer.parseInt(r.substring(start+key.length(),start+key.length()+bytes*2),16);}catch(Exception ignored){return null;}
    }
}
