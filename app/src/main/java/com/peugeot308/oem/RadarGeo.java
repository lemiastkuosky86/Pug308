package com.peugeot308.oem;

/** Filtro conservador baseado somente em posição e rumo GPS (não faz map matching). */
final class RadarGeo {
    private RadarGeo(){}
    static float angle(float a,float b){return (a-b+540f)%360f-180f;}
    static boolean candidate(float distanceMeters,float vehicleBearing,float targetBearing,
                             float accuracyMeters,int cameraDirection){
        if(distanceMeters<10f||distanceMeters>350f||accuracyMeters<0||accuracyMeters>60f)return false;
        float diff=Math.abs(angle(vehicleBearing,targetBearing));
        if(diff>48f)return false;
        // Aceita pequenos desvios em curvas e nas coordenadas aproximadas de radares,
        // mas ainda exige que o ponto esteja à frente e na mesma direção de tráfego.
        float lateral=(float)(distanceMeters*Math.sin(Math.toRadians(diff)));
        float tolerance=Math.max(35f,Math.min(95f,accuracyMeters*1.5f+18f+distanceMeters*.12f));
        if(lateral>tolerance)return false;
        return cameraDirection<0||Math.abs(angle(vehicleBearing,cameraDirection))<=60f;
    }
}
