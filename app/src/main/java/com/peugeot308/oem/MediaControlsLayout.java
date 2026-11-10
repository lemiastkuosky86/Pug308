package com.peugeot308.oem;
/** Geometria calculada usando apenas a largura real do card; nunca invade o vizinho. */
final class MediaControlsLayout {
    final float sideRadius, playRadius, separation;
    private MediaControlsLayout(float side,float play,float sep){sideRadius=side;playRadius=play;separation=sep;}
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
