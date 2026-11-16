package com.peugeot308.oem;

/**
 * Geometria conservadora para radar. Distância exibida = projeção adiante no
 * rumo GPS, não distância em linha reta ao poste (que pode estar fora da pista).
 * Não faz map matching: a precisão do GPS e da base ainda limitam o resultado.
 */
final class RadarGeo {
    private RadarGeo(){}
    static float angle(float a,float b){return (a-b+540f)%360f-180f;}

    static float forwardMeters(float distanceMeters,float vehicleBearing,float targetBearing){
        return (float)(distanceMeters*Math.cos(Math.toRadians(angle(vehicleBearing,targetBearing))));
    }
    static float lateralMeters(float distanceMeters,float vehicleBearing,float targetBearing){
        return (float)Math.abs(distanceMeters*Math.sin(Math.toRadians(angle(vehicleBearing,targetBearing))));
    }
    static float lateralTolerance(float distanceMeters,float accuracyMeters){
        return Math.max(42f,Math.min(90f,accuracyMeters*1.4f+15f+distanceMeters*.08f));
    }
    static boolean candidate(float distanceMeters,float vehicleBearing,float targetBearing,
                             float accuracyMeters,int cameraDirection){
        if(!Float.isFinite(distanceMeters)||distanceMeters<0f||distanceMeters>350f
                ||!Float.isFinite(vehicleBearing)||!Float.isFinite(targetBearing)
                ||accuracyMeters<0f||accuracyMeters>60f)return false;
        // A passagem se encerra quando o radar fica para trás do rumo do carro,
        // mesmo que sua coordenada esteja 10-40 m ao lado da faixa de rolamento.
        if(forwardMeters(distanceMeters,vehicleBearing,targetBearing)<=0.8f)return false;
        if(lateralMeters(distanceMeters,vehicleBearing,targetBearing)
                >lateralTolerance(distanceMeters,accuracyMeters))return false;
        return cameraDirection<0||Math.abs(angle(vehicleBearing,cameraDirection))<=60f;
    }
    static boolean passed(float distanceMeters,float vehicleBearing,float targetBearing,
                          float accuracyMeters){
        return distanceMeters<=110f
                &&forwardMeters(distanceMeters,vehicleBearing,targetBearing)<=0.8f
                &&lateralMeters(distanceMeters,vehicleBearing,targetBearing)
                    <=lateralTolerance(distanceMeters,accuracyMeters);
    }
}
