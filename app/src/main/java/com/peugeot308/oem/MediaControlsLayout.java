package com.peugeot308.oem;
/** Geometria calculada usando apenas a largura real do card; nunca invade o vizinho. */
final class MediaControlsLayout {
    final float sideRadius, playRadius, separation, centerX;
    private MediaControlsLayout(float side,float play,float sep){this(side,play,sep,Float.NaN);}
    private MediaControlsLayout(float side,float play,float sep,float center){
        sideRadius=side;playRadius=play;separation=sep;centerX=center;
    }
    /** Centraliza Anterior/Play/Próxima somente na coluna reservada a controles. */
    static MediaControlsLayout forRegion(float left,float right,boolean compact,boolean idle){
        float width=Math.max(1f,right-left);
        float side=compact?(idle?35f:34f):40f;
        float play=compact?46f:(idle?52f:53f);
        float separation=compact?(idle?91f:94f):(idle?117f:124f);
        // 10 unidades de margem em cada extremidade; escala os três botões juntos.
        float fit=Math.min(1f,Math.max(.08f,(width/2f-10f)/(separation+side)));
        return new MediaControlsLayout(side*fit,play*fit,separation*fit,(left+right)/2f);
    }
    /** Faixa inferior dedicada aos controles quando Spotify divide a Home com o veiculo.
     *  A faixa fica ABAIXO da capa, entao os botoes podem usar a largura do card.
     *  Mantem alvos de toque confortaveis e distancias proporcionais.
     */
    static MediaControlsLayout forFooter(float left,float right,boolean idle){
        float width=Math.max(1f,right-left);
        float side=idle?39f:37f;
        float play=idle?51f:48f;
        float separation=idle?112f:108f;
        float radiusNeed=separation+side;
        float fit=Math.min(1f,Math.max(.1f,(width/2f-12f)/radiusNeed));
        return new MediaControlsLayout(side*fit,play*fit,separation*fit,(left+right)/2f);
    }
    static MediaControlsLayout forWidth(float w,boolean compact,boolean idle){
        float side=compact?(idle?35:34):40;
        float play=compact?46:(idle?52:53);
        float margin=14;
        // Quatro margens internas e intervalo entre círculos mesmo com card estreito.
        float limit=Math.max(0,(w/2)-margin);
        float need=2*side+play+8;
        if(need>limit){float factor=Math.max(.42f,(limit-8)/Math.max(1,2*side+play));side*=factor;play*=factor;}
        float minimum=side+play+8;
        float sep=Math.min(compact?(idle?91:94):(idle?117:124),limit-side);
        sep=Math.max(minimum,sep);
        // Reposicionamento final sem extravasar a área do card.
        if(sep+side>limit)sep=Math.max(0,limit-side);
        return new MediaControlsLayout(side,play,sep);
    }
}
