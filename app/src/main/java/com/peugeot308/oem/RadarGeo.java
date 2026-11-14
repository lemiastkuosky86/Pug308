package com.peugeot308.oem;

/** Filtro conservador baseado somente em posição e rumo GPS (não faz map matching). */
final class RadarGeo {
    private RadarGeo(){}
    static float angle(float a,float b){return (a-b+540f)%360f-180f;}
    static boolean candidate(float distanceMeters,float vehicleBearing,float targetBearing,
                             float accuracyMeters,int cameraDirection){
        if(distanceMeters<10f||distanceMeters>350f||accuracyMeters<0||accuracyMeters>35f)return false;
        float diff=Math.abs(angle(vehicleBearing,targetBearing));
        if(diff>38f)return false;
        float lateral=(float)(distanceMeters*Math.sin(Math.toRadians(diff)));
        float tolerance=Math.max(25f,Math.min(65f,accuracyMeters*1.5f+18f+distanceMeters*.09f));
        if(lateral>tolerance)return false;
        return cameraDirection<0||Math.abs(angle(vehicleBearing,cameraDirection))<=60f;
    }
}
