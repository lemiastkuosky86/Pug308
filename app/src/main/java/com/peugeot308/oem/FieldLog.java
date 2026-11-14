package com.peugeot308.oem;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Histórico PRIVADO e limitado: sem logcat, localização, nome de músicas ou dados de rede identificáveis. */
public final class FieldLog {
    private static final long LIMIT=512L*1024L;
    private static final Object FILE_LOCK=new Object();
    private final File file;
    private volatile boolean enabled;
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US);
    public FieldLog(Context context,boolean enabled){this.file=new File(context.getFilesDir(),"field-events.txt");this.enabled=enabled;}
    public synchronized boolean isEnabled(){return enabled;}
    public synchronized void setEnabled(boolean enabled){this.enabled=enabled;}
    public synchronized int sizeKb(){return (int)((file.length()+1023)/1024);}
    public synchronized void clear(){synchronized(FILE_LOCK){if(file.exists())file.delete();}}
    public synchronized void record(String category,String data){
        if(!enabled)return;
        synchronized(FILE_LOCK){try {
            if(file.length()>LIMIT){
                byte[] all=new byte[(int)file.length()];
                try(FileInputStream input=new FileInputStream(file)){int done=0,n;while(done<all.length&&(n=input.read(all,done,all.length-done))>0)done+=n;}
                int start=Math.max(0,all.length-(int)LIMIT/2);
                while(start<all.length && all[start]!='\n')start++;
                if(start<all.length)start++;
                try(FileOutputStream output=new FileOutputStream(file,false)){
                    output.write("--- histórico anterior truncado por limite de espaço ---\n".getBytes(StandardCharsets.UTF_8));
                    output.write(all,start,all.length-start);
                }
            }
            String line=format.format(new Date())+" ["+category+"] "+data.replace('\n',' ')+'\n';
            try(FileOutputStream output=new FileOutputStream(file,true)){output.write(line.getBytes(StandardCharsets.UTF_8));}
        }catch(IOException ignored){} }
    }
    public synchronized String exportText(String snapshot) throws IOException {
        synchronized(FILE_LOCK){
        StringBuilder out=new StringBuilder("PEUGEOT 308 - LOG DE CAMPO\n");
        out.append("Eventos registrados apenas com o processo do launcher ativo.\n");
        out.append("Sem nomes de músicas, rede Wi-Fi, senha, coordenadas ou endereços.\n\n");
        out.append("--- ESTADO ATUAL ---\n").append(snapshot).append("\n--- EVENTOS ---\n");
        if(file.exists()){
            try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)out.append(line).append('\n');}
        }
        return out.toString();
        }
    }
}
