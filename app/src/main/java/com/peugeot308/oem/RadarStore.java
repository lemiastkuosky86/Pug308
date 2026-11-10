package com.peugeot308.oem;

import java.io.*;
import java.text.Normalizer;
import java.util.*;

/** Catálogo de pontos de alerta: arquivo pessoal importado e consulta OSM, sem rastreamento em segundo plano. */
public final class RadarStore {
    public static final int FIXO=0, MOVEL=1, SEMAFORO_CAMERA=2, SEMAFORO_RADAR=3,
            POLICIA=4, PEDAGIO=5, LOMBADA=6, PERIGO=7, RISCO=8, COUNT=9;
    public static final String[] LABELS = {"Radar fixo", "Radar móvel (local conhecido)",
            "Semáforo com câmera", "Semáforo com radar", "Polícia rodoviária", "Pedágio", "Lombada", "Perigo na via", "Área de risco"};
    public static final int ALL = (1<<COUNT)-1;
    private static final int MAGIC=0x50554752, VERSION=2, MAX=200000;
    private static final double CELL=0.02;
    public static final class Point {
        public final double lat, lon;
        public final int limit, type;
        public final long id;
        public final boolean imported;
        /** Tracksource 7Ways: DirType 0=ambos/sem direção, 1/2=indicação de sentido. */
        public final int dirType, heading;
        public Point(double lat,double lon,int limit,int type,long id,boolean imported){
            this(lat,lon,limit,type,id,imported,0,0);
        }
        public Point(double lat,double lon,int limit,int type,long id,boolean imported,int dirType,int heading){
            this.lat=lat;this.lon=lon;this.limit=limit;this.type=type;this.id=id;this.imported=imported;
            this.dirType=dirType;this.heading=((heading%360)+360)%360;
        }
    }
    private final ArrayList<Point> imported=new ArrayList<>(), online=new ArrayList<>(), bundled=new ArrayList<>();
    public synchronized int bundledCount(){return bundled.size();}
    public synchronized ArrayList<Point> importedSnapshot(){return new ArrayList<>(imported);}
    public synchronized void setBundled(List<Point> points){bundled.clear();bundled.addAll(points);index();}
    private final HashMap<Long,ArrayList<Point>> bins=new HashMap<>();
    public synchronized int personalCount(){return imported.size();}
    /** Contagem por categoria apenas de pontos offline importados (não soma o OSM). */
    public synchronized int[] personalCounts(){
        int[] counts=new int[COUNT];
        for(Point p:imported)if(p.type>=0&&p.type<COUNT)counts[p.type]++;
        return counts;
    }
    public synchronized int onlineCount(){return online.size();}
    public synchronized int count(int type){int n=0;for(Point p:bundled)if(p.type==type)n++;for(Point p:imported)if(p.type==type)n++;for(Point p:online)if(p.type==type)n++;return n;}
    private static long cellKey(int la,int lo){return (((long)la)<<32) ^ (lo&0xffffffffL);}
    private static int grid(double value){return (int)Math.floor(value/CELL);}
    private void index(){
        bins.clear();
        for(Point p:bundled)addIndex(p);
        for(Point p:imported)addIndex(p);
        for(Point p:online)addIndex(p);
    }
    private void addIndex(Point p){
        long key=cellKey(grid(p.lat),grid(p.lon));
        ArrayList<Point> b=bins.get(key);
        if(b==null){b=new ArrayList<>();bins.put(key,b);} b.add(p);
    }
    public synchronized void setOnline(List<Point> points){online.clear();online.addAll(points);index();}
    public synchronized void replaceImported(List<Point> points){
        imported.clear();imported.addAll(points.subList(0,Math.min(points.size(),MAX)));index();
    }
    /** Distância geodésica em metros, usada após o filtro por células para não aceitar
     *  radares além do raio solicitado (o filtro antigo era apenas retangular). */
    private static double distanceMeters(double lat1,double lon1,double lat2,double lon2){
        double dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1);
        double la1=Math.toRadians(lat1),la2=Math.toRadians(lat2);
        double h=Math.pow(Math.sin(dLat/2),2)+Math.cos(la1)*Math.cos(la2)*Math.pow(Math.sin(dLon/2),2);
        return 12742000.0*Math.asin(Math.min(1.0,Math.sqrt(Math.max(0,h))));
    }
    public synchronized List<Point> nearby(double lat,double lon,double meters,int mask){
        ArrayList<Point> found=new ArrayList<>();
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(meters)||meters<=0)return found;
        int a=grid(lat),b=grid(lon);
        int radius=Math.max(1,(int)Math.ceil(meters/1400)+1);
        double maxLatDiff=meters/110574.0;
        double maxLonDiff=meters/(111320.0*Math.max(.12,Math.cos(Math.toRadians(lat))));
        for(int x=a-radius;x<=a+radius;x++)for(int y=b-radius;y<=b+radius;y++){
            ArrayList<Point> pts=bins.get(cellKey(x,y));if(pts==null)continue;
            for(Point p:pts){
                if((mask & (1<<p.type))==0)continue;
                if(Math.abs(p.lat-lat)>maxLatDiff || Math.abs(p.lon-lon)>maxLonDiff)continue;
                if(distanceMeters(lat,lon,p.lat,p.lon)<=meters)found.add(p);
            }
        }
        return found;
    }
    public synchronized void save(File f)throws IOException{
        File tmp=new File(f.getParentFile(),f.getName()+".tmp");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(imported.size());
            for(Point p:imported){out.writeDouble(p.lat);out.writeDouble(p.lon);out.writeInt(p.limit);out.writeInt(p.type);out.writeLong(p.id);out.writeInt(p.dirType);out.writeInt(p.heading);}
        }
        File backup=new File(f.getParentFile(),f.getName()+".bak");
        if(backup.exists())backup.delete();
        boolean hadOld=f.exists();
        if(hadOld&&!f.renameTo(backup))throw new IOException("Não foi possível proteger a base anterior");
        if(!tmp.renameTo(f)){
            if(hadOld)backup.renameTo(f);
            throw new IOException("Falha ao gravar nova base de radares");
        }
        if(hadOld)backup.delete();
    }
    public synchronized void load(File f)throws IOException{
        if(!f.exists())return;
        ArrayList<Point> list=new ArrayList<>();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f)))){
            if(in.readInt()!=MAGIC)throw new IOException("Formato de base inválido");
            int format=in.readInt();if(format!=1&&format!=VERSION)throw new IOException("Versão de base inválida");
            int size=in.readInt();if(size<0||size>MAX)throw new IOException("Base com tamanho inválido");
            for(int i=0;i<size;i++){
                double a=in.readDouble(),b=in.readDouble();int speed=in.readInt(),type=in.readInt();long id=in.readLong();int dir=format>=2?in.readInt():0,heading=format>=2?in.readInt():0;
                if(validCoord(a,b)&&type>=0&&type<COUNT)list.add(new Point(a,b,speed,type,id,true,dir,heading));
            }
        }
        replaceImported(list);
    }
    public static boolean validCoord(double a,double b){return a>=-90&&a<=90&&b>=-180&&b<=180&&Double.isFinite(a)&&Double.isFinite(b);}
    private static String norm(String text){
        return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
    }
    public static int category(String text){
        String s=norm(text);
        if(s.contains("perigo")||s.contains("hazard"))return PERIGO;
        if(s.contains("risco")||s.contains("danger area"))return RISCO;
        if(s.contains("movel")||s.contains("mobile")||s.contains("speed trap"))return MOVEL;
        if(s.contains("semaforo")||s.contains("traffic light"))return s.contains("radar")||s.contains("speed")?SEMAFORO_RADAR:SEMAFORO_CAMERA;
        if(s.contains("pedagio")||s.contains("toll"))return PEDAGIO;
        if(s.contains("lombada")||s.contains("quebra mola")||s.contains("bump")||s.contains("traffic calming"))return LOMBADA;
        if(s.contains("policia")||s.contains("police")||s.contains("posto policial"))return POLICIA;
        if(s.contains("camera")||s.contains("cctv"))return SEMAFORO_CAMERA;
        if(s.contains("radar")||s.contains("fixo")||s.contains("speed camera")||s.contains("maxspeed")
                ||s.contains("controlador de velocidade")||s.contains("redutor de velocidade"))return FIXO;
        return -1;
    }
    private static String[] csvSplit(String line,char delimiter){
        ArrayList<String> cols=new ArrayList<>();StringBuilder buf=new StringBuilder();boolean quote=false;
        for(int i=0;i<line.length();i++){
            char c=line.charAt(i);
            if(c=='"'){
                if(quote&&i+1<line.length()&&line.charAt(i+1)=='"'){buf.append('"');i++;}
                else quote=!quote;
            }else if(c==delimiter&&!quote){cols.add(buf.toString().trim());buf.setLength(0);}
            else buf.append(c);
        }
        cols.add(buf.toString().trim());return cols.toArray(new String[0]);
    }
    private static int find(String[] cols,String... terms){
        for(int i=0;i<cols.length;i++){
            String c=norm(cols[i]);
            for(String term:terms)if(c.equals(term))return i;
        }
        return -1;
    }
    private static double coord(String s){
        if(s==null)return Double.NaN;
        s=s.trim().replace("°","").replace(" ","").replace(',', '.');
        if(s.isEmpty())return Double.NaN;
        try{return Double.parseDouble(s);}catch(Exception ignored){return Double.NaN;}
    }
    private static int speed(String s){
        if(s==null)return 0;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|\\D)(\\d{2,3})(?:\\D|$)").matcher(s);
        while(m.find()){int k=Integer.parseInt(m.group(1));if(k>=20&&k<=140)return k;}
        return 0;
    }
    public static final class ParseResult{
        public final ArrayList<Point> points=new ArrayList<>();
        public int skipped=0, lines=0;
        public String file="";
    }
    /** Lê o formato Tracksource/7Ways IDX,X,Y,Type,Speed,DirType,Direction.
     * Todos os números ficam na origem e as coordenadas X/Y são LONGITUDE/LATITUDE. */
    public static ParseResult parseTracksource(Reader source,String filename,int limitRows)throws IOException {
        ParseResult out=new ParseResult();out.file=filename;
        String lower=norm(filename);
        int category=lower.contains("pedagio")?PEDAGIO:lower.contains("lombada")?LOMBADA:
                lower.contains("policial")?POLICIA:lower.contains("risco")?RISCO:
                lower.contains("perigo")?PERIGO:FIXO;
        BufferedReader in=new BufferedReader(source);String line;long row=0;
        while((line=in.readLine())!=null){
            if(++row>limitRows)throw new IOException("Muitas linhas em "+filename);
            out.lines++;
            if(line.trim().isEmpty()||line.startsWith("IDX,"))continue;
            String[] a=line.split(",",-1);
            if(a.length<7){out.skipped++;continue;}
            try {
                double lon=Double.parseDouble(a[1].trim()),lat=Double.parseDouble(a[2].trim());
                if(!validCoord(lat,lon)){out.skipped++;continue;}
                int speed=Math.max(0,Math.min(140,Integer.parseInt(a[4].trim())));
                int dir=Integer.parseInt(a[5].trim()),heading=Integer.parseInt(a[6].trim());
                long id=0x3000000000000000L ^ (((long)filename.hashCode()&0xffffffffL)<<24) ^ row;
                out.points.add(new Point(lat,lon,speed,category,id,true,dir,heading));
            } catch(NumberFormatException ex){out.skipped++;}
        }
        return out;
    }
    public static ParseResult parseAuto(Reader in,String filename,int limitRows)throws IOException {
        BufferedReader b=new BufferedReader(in);b.mark(4096);String first=b.readLine();b.reset();
        if(first!=null&&first.replace("\uFEFF", "").startsWith("IDX,X,Y,Type,Speed,DirType,Direction"))
            return parseTracksource(b,filename,limitRows);
        return parseCsv(b,filename,limitRows);
    }
    /** Elimina duplicações entre bases por categoria, coordenada (~11m) e direção. */
    public static ArrayList<Point> dedupe(Collection<Point> points) {
        ArrayList<Point> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(Point p:points){
            String key=p.type+":"+Math.round(p.lat*10000)+":"+Math.round(p.lon*10000)+":"+
                (p.dirType==0?0:(p.heading/30+1));
            if(seen.add(key))out.add(p);
        }
        return out;
    }
    /** Aceita CSV com cabeçalhos lat/lon/tipo/limite ou listas GPS com coordenadas nas primeiras colunas.
     * Linhas sem categoria identificável são ignoradas para evitar falsos radares. */
    public static ParseResult parseCsv(Reader input,String filename,int limitRows)throws IOException{
        ParseResult out=new ParseResult();out.file=filename;
        String base=norm(filename);int filenameType=category(base);
        BufferedReader reader=new BufferedReader(input);String line;
        String[] headers=null;char delim=',';boolean picked=false;
        while((line=reader.readLine())!=null){
            out.lines++;if(out.lines>limitRows)throw new IOException("Arquivo excede o limite de linhas");
            if(out.lines==1 && line.startsWith("\uFEFF"))line=line.substring(1);
            if(line.trim().isEmpty()||line.startsWith("#"))continue;
            if(!picked){
                delim=line.indexOf(';')>=0?';':line.indexOf('\t')>=0?'\t':',';
                String[] cols=csvSplit(line,delim);
                if(find(cols,"latitude","lat","y")>=0&&find(cols,"longitude","lon","lng","long","x")>=0){
                    headers=cols;picked=true;continue;
                }
                picked=true;
            }
            String[] cols=csvSplit(line,delim);
            int lai=headers==null?-1:find(headers,"latitude","lat","y");
            int loi=headers==null?-1:find(headers,"longitude","lon","lng","long","x");
            int ti=headers==null?-1:find(headers,"tipo","type","categoria","category","alerta","tipo de radar","tipo_de_radar");
            int limi=headers==null?-1:find(headers,"limite","velocidade","speed","maxspeed","speed limit","velocidade leve","velocidade_leve");
            double la=Double.NaN,lo=Double.NaN;
            if(lai>=0&&loi>=0&&lai<cols.length&&loi<cols.length){
                la=coord(cols[lai]);lo=coord(cols[loi]);
            }else if(cols.length>=2){
                double x=coord(cols[0]),y=coord(cols[1]);
                if(validCoord(x,y)){
                    // No Brasil, longitude geralmente fica entre -75 e -32 e latitude entre -34 e +6.
                    boolean xLon=x<-33&&x>-76, yLon=y<-33&&y>-76;
                    if(xLon&&!yLon){lo=x;la=y;}else{la=x;lo=y;}
                }
            }
            if(!validCoord(la,lo)){out.skipped++;continue;}
            StringBuilder detail=new StringBuilder(filename);
            for(int j=0;j<cols.length;j++)if(j!=lai&&j!=loi&&(headers!=null||j>1))detail.append(' ').append(cols[j]);
            int type=(ti>=0&&ti<cols.length)?category(cols[ti]):-1;
            // O tipo escrito em cada linha prevalece sobre um nome genérico do CSV.
            if(type<0)type=category(detail.toString().replace(filename,""));
            if(type<0)type=filenameType;
            if(type<0){out.skipped++;continue;}
            int max=limi>=0&&limi<cols.length?speed(cols[limi]):0;
            if(max==0&&type<=SEMAFORO_RADAR)max=speed(detail.toString());
            if(out.points.size()>=MAX)throw new IOException("Base excede 150 mil pontos");
            long id=0x4000000000000000L+out.points.size()+((long)filename.hashCode()<<20);
            out.points.add(new Point(la,lo,max,type,id,true));
        }
        return out;
    }
}
