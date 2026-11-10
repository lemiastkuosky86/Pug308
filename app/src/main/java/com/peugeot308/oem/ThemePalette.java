package com.peugeot308.oem;

/** Paleta única para a Home, prévia dos temas e janelas de personalização.
 *  Sem recursos Android: testes locais verificam contraste e paridade de cores.
 */
final class ThemePalette {
    final boolean dark;
    final int bg0, bg1, head, surfTop, surfBot, surf2, hair, text, muted, dim, shadow, track, ok, bad, accent, onAccent;

    private ThemePalette(boolean dark, int[] c, int accent) {
        this.dark = dark;
        bg0=c[0]; bg1=c[1]; head=c[2]; surfTop=c[3]; surfBot=c[4]; surf2=c[5];
        hair=c[6]; text=c[7]; muted=c[8]; dim=c[9]; shadow=c[10]; track=c[11]; ok=c[12]; bad=c[13];
        this.accent=accent;
        int black=0xff101419, white=0xffffffff;
        this.onAccent = contrast(accent, black) >= contrast(accent, white) ? black : white;
    }
    static int baseOf(int preset) { return preset < 5 ? 1 : preset < 12 ? 2 : 0; }
    static ThemePalette create(int preset,int mode,boolean isNight,int rawAccent) {
        int base=baseOf(preset);
        boolean night = mode != 2 && (base != 0 || mode == 1 || isNight);
        int[] c;
        if (night && base == 1) {
            c=new int[]{0xff000000,0xff050505,0xff000000,0xff171717,0xff0e0e0e,0xff202020,
                    0x24ffffff,0xfff1ede5,0xffa8a49d,0xff77746e,0x99000000,0x2affffff,0xff3ddc84,0xffff5c6c};
        } else if (night && base == 2) {
            c=new int[]{0xff16191d,0xff1f2328,0xff13161a,0xff2c3137,0xff24282e,0xff353b43,
                    0x20ffffff,0xffeceef1,0xffa2a8b0,0xff737a83,0x66000000,0x2affffff,0xff3ddc84,0xffff5c6c};
        } else if (night) {
            c=new int[]{0xff080c12,0xff10161f,0xff0d131b,0xff1a222d,0xff121820,0xff202a37,
                    0x1fffffff,0xfff2f5f9,0xffa0acbc,0xff6f7c8d,0x66000000,0x26ffffff,0xff3ddc84,0xffff5c6c};
        } else if (base != 0) {
            c=new int[]{0xffe4e5e7,0xfff1f2f3,0xfff8f8f9,0xffffffff,0xfff3f4f5,0xffe8e9eb,
                    0x1f0f1720,0xff0f1720,0xff566272,0xff8793a2,0x221e2a3c,0x1f0f1720,0xff14a44d,0xffe5384f};
        } else {
            c=new int[]{0xffe3e8ee,0xfff0f3f7,0xfff8fafc,0xffffffff,0xfff3f5f8,0xffe7ebf0,
                    0x1f0f1720,0xff0f1720,0xff566272,0xff8793a2,0x221e2a3c,0x1f0f1720,0xff14a44d,0xffe5384f};
        }
        if (base==0 && preset>=12) {
            // Presets coloridos: as superfícies têm uma tonalidade sutil mas reconhecível.
            float k=night?.115f:.075f;
            for(int i=0;i<6;i++)c[i]=mix(c[i],rawAccent,k);
        }
        int adjusted=night?lightenForContrast(rawAccent,c[3],4.5):darkenForContrast(rawAccent,c[3],4.5);
        return new ThemePalette(night,c,adjusted);
    }
    private static int mix(int a,int b,float f) {
        float k=Math.max(0,Math.min(1,f));
        int r=Math.round(((a>>16)&255)*(1-k)+((b>>16)&255)*k);
        int g=Math.round(((a>>8)&255)*(1-k)+((b>>8)&255)*k);
        int bl=Math.round((a&255)*(1-k)+(b&255)*k);
        return 0xff000000|(r<<16)|(g<<8)|bl;
    }
    static int lightenForContrast(int color,int background,double minRatio){
        int result=color;
        for(int i=0;i<26 && contrast(result,background)<minRatio;i++) result=mix(result,0xffffffff,.10f);
        return result;
    }
    static int darkenForContrast(int color,int background,double minRatio){
        int result=color;
        for(int i=0;i<26 && contrast(result,background)<minRatio;i++) result=mix(result,0xff000000,.10f);
        return result;
    }
    private static double linear(int v) {
        double u=v/255.0;
        return u<=.04045?u/12.92:Math.pow((u+.055)/1.055,2.4);
    }
    static double relativeLuminance(int color) {
        return .2126*linear((color>>16)&255)+.7152*linear((color>>8)&255)+.0722*linear(color&255);
    }
    static double contrast(int a,int b) {
        double x=relativeLuminance(a),y=relativeLuminance(b);
        return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);
    }
}
