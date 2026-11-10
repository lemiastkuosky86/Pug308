package com.peugeot308.oem;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Leitor ELM327 Bluetooth clássico (SPP), estritamente de leitura.
 * Somente consulta PIDs OBD-II padrão (modo 01): 0C RPM, 0D km/h, 05 temp. do motor, 2F combustível, 46 temp. externa.
 * Não envia comandos de controle para o veículo, não varre redes e nunca simula dados.
 *
 * v1.19: reconecta sozinho (adaptador desligado/ignição desligada), uma "geração" por sessão evita que uma conexão
 * antiga derrube a nova, aquecimento do protocolo antes de ler, RPM/velocidade lidos com mais frequência que os
 * sensores lentos.
 */
class ObdBluetoothReader {
    interface Listener { void onData(String[] values, boolean live, String status); }
    /** Canal de bytes com o adaptador (separado para poder ser testado sem Bluetooth). */
    interface Link { InputStream in() throws IOException; OutputStream out() throws IOException; void close(); }

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final int V_SPEED = 0, V_AMBIENT = 2, V_FUEL = 3, V_RPM = 4, V_COOLANT = 9; // índices do vetor de valores da tela
    long retryMs = 10000; // intervalo entre tentativas de reconexão (ajustável nos testes)
    private static final long LIVE_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final AtomicInteger gen = new AtomicInteger();
    private volatile boolean active;
    private volatile Link link;
    private volatile String status = "Desconectado";
    private volatile long lastData;

    ObdBluetoothReader(Listener listener) { this.listener = listener; }
    String getStatus() { return status; }
    boolean isLive() { return active && SystemClock.elapsedRealtime() - lastData < LIVE_MS; }

    void stop() {
        active = false;
        gen.incrementAndGet(); // qualquer thread antiga passa a ser "obsoleta" e sai sem mexer no estado compartilhado
        Link l = link; link = null;
        if (l != null) l.close();
        status = "Desconectado";
    }

    void start(String address) {
        stop();
        if (address == null || address.trim().isEmpty()) { status = "Escolha um adaptador OBD2 pareado"; post(null, false, status); return; }
        final int my = gen.incrementAndGet();
        active = true;
        Thread t = new Thread(() -> loop(address.trim(), my), "Peugeot-OBD-readonly");
        t.setDaemon(true);
        t.start();
    }

    private boolean current(int my) { return active && gen.get() == my; }
    private void post(String[] data, boolean live, String message) { main.post(() -> listener.onData(data, live, message)); }
    private void update(int my, String[] data, boolean live, String message) {
        if (!current(my)) return;
        status = message;
        post(data, live, message);
    }
    /** Dorme em fatias para reagir rápido a stop(); devolve false se a sessão foi encerrada. */
    private boolean pause(int my, long ms) {
        long until = SystemClock.elapsedRealtime() + ms;
        while (current(my) && SystemClock.elapsedRealtime() < until) {
            try { Thread.sleep(Math.min(100, Math.max(1, until - SystemClock.elapsedRealtime()))); } catch (InterruptedException e) { return false; }
        }
        return current(my);
    }

    /** Abre o canal com o adaptador. Sobrescrito nos testes. */
    Link openLink(String address) throws IOException {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) throw new IOException("Este aparelho não tem Bluetooth");
        if (!a.isEnabled()) throw new IOException("Bluetooth desligado");
        BluetoothDevice d = a.getRemoteDevice(address);
        try { a.cancelDiscovery(); } catch (Exception ignored) {} // busca ativa atrapalha a conexão; se não puder cancelar, segue
        final BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP);
        s.connect();
        return new Link() {
            public InputStream in() throws IOException { return s.getInputStream(); }
            public OutputStream out() throws IOException { return s.getOutputStream(); }
            public void close() { try { s.close(); } catch (Exception ignored) {} }
        };
    }

    private void loop(String address, int my) {
        while (current(my)) {
            Link l = null;
            try {
                update(my, null, false, "Conectando ao adaptador OBD2…");
                l = openLink(address);
                link = l;
                if (!current(my)) return;
                session(l, my);
            } catch (SecurityException e) {
                update(my, null, false, "Permissão Bluetooth negada");
                return; // sem permissão não adianta insistir
            } catch (Exception e) {
                String m = e.getMessage();
                update(my, null, false, "OBD2: " + (m == null || m.isEmpty() ? "falha de conexão" : m) + " • nova tentativa em " + (retryMs / 1000) + " s");
            } finally {
                if (l != null) l.close();
                if (link == l) link = null; // só limpa se ainda for o nosso (outra sessão pode ter assumido)
            }
            if (!pause(my, retryMs)) return;
        }
    }

    private void session(Link l, int my) throws Exception {
        InputStream in = l.in(); OutputStream out = l.out();
        flush(in);
        send(out, in, "ATZ", 3000, my);
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"}) send(out, in, c, 2000, my);
        update(my, null, false, "Adaptador conectado • procurando a ECU…");
        // O primeiro comando OBD dispara a busca do protocolo (pode levar vários segundos); sem isso as respostas desalinham.
        send(out, in, "0100", 12000, my);
        final String[] values = new String[11];
        int cycle = 0, silent = 0;
        while (current(my)) {
            boolean got = false;
            Integer rpm = pid(out, in, "010C", 0x0C, 2, my);
            if (rpm != null) { values[V_RPM] = Integer.toString(rpm / 4); got = true; }
            Integer speed = pid(out, in, "010D", 0x0D, 1, my);
            if (speed != null) { values[V_SPEED] = Integer.toString(speed); got = true; }
            if (cycle % 8 == 0) { // sensores lentos: a cada ~8 ciclos
                Integer coolant = pid(out, in, "0105", 0x05, 1, my);
                if (coolant != null) { values[V_COOLANT] = Integer.toString(coolant - 40); got = true; }
                Integer fuel = pid(out, in, "012F", 0x2F, 1, my);
                if (fuel != null) { values[V_FUEL] = Integer.toString(Math.round(100f * fuel / 255f)); got = true; }
                Integer amb = pid(out, in, "0146", 0x46, 1, my);
                if (amb != null) { values[V_AMBIENT] = Integer.toString(amb - 40); got = true; }
            }
            cycle++;
            if (got) {
                silent = 0;
                lastData = SystemClock.elapsedRealtime();
                update(my, values.clone(), true, "OBD2 conectado • leituras confirmadas");
                if (!pause(my, 120)) return;
            } else {
                // ECU muda: ignição desligada ou protocolo ainda não encontrado. Mantém a ligação e tenta de novo.
                update(my, null, false, "OBD2 conectado, aguardando a ECU (ligue a ignição)");
                if (++silent >= 25) throw new IOException("ECU sem resposta");
                if (!pause(my, 2000)) return;
            }
        }
    }

    private static void flush(InputStream in) {
        try { while (in.available() > 0) if (in.read() < 0) break; } catch (IOException ignored) {}
    }

    /** Envia um comando e lê até o prompt '>' (ou até o tempo limite). Descarta lixo anterior para não desalinhar respostas. */
    private String send(OutputStream out, InputStream in, String cmd, long timeoutMs, int my) throws IOException {
        flush(in);
        out.write((cmd + "\r").getBytes("US-ASCII")); out.flush();
        StringBuilder text = new StringBuilder();
        long until = SystemClock.elapsedRealtime() + timeoutMs;
        while (current(my) && SystemClock.elapsedRealtime() < until) {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) throw new IOException("Adaptador desconectado");
                if (b == '>') break;
                if (text.length() < 256) text.append((char) b);
            } else {
                try { Thread.sleep(15); } catch (InterruptedException e) { throw new IOException("Interrompido"); }
            }
        }
        return text.toString().toUpperCase(Locale.ROOT).replace(" ", "").replace("\r", "").replace("\n", "");
    }

    private Integer pid(OutputStream out, InputStream in, String cmd, int pid, int bytes, int my) throws IOException {
        String r = send(out, in, cmd, 2500, my);
        String key = String.format(Locale.ROOT, "41%02X", pid);
        int start = r.indexOf(key);
        if (start < 0 || start + key.length() + bytes * 2 > r.length()) return null;
        try { return Integer.parseInt(r.substring(start + key.length(), start + key.length() + bytes * 2), 16); }
        catch (Exception ignored) { return null; }
    }
}
