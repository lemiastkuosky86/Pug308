package com.peugeot308.oem;

/**
 * Matemática do equalizador (sem dependência do Android, para poder ser testada).
 * A tela trabalha com 10 bandas (as mesmas frequências do equalizador original da central);
 * o Android costuma expor só 5 bandas, então a curva é "amostrada" nas faixas reais.
 */
final class EqMath {
    private EqMath() {}

    static final int N = 10;
    static final float[] FREQ = {60f, 230f, 910f, 3600f, 5000f, 11000f, 13000f, 14000f, 16000f, 19900f};
    static final String[] FLABEL = {"60", "230", "910", "3,6k", "5k", "11k", "13k", "14k", "16k", "19,9k"};
    static final float RANGE = 12f;        // ±12 dB na tela
    static final float LOUD_LOW_DB = 7f;   // reforço máximo de graves do loudness (intensidade 100%, volume mínimo)
    static final float LOUD_HIGH_DB = 9f;  // reforço máximo de agudos

    /** Predefinições de fábrica (dB por banda). "Usuário" é a curva manual e não está aqui. */
    static final String[] PRESET_NAMES = {"Plano", "Rock", "Pop", "Ao vivo", "Eletrônica", "Clássica", "Suave", "Jazz", "Voz", "Graves+", "Agudos+"};
    static final float[][] PRESETS = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {5, 3, -1, -2, 0, 3, 4, 4, 3, 2},
        {-1, 1, 3, 4, 3, 1, 0, 0, 0, 0},
        {-3, 0, 2, 3, 3, 3, 2, 2, 2, 2},
        {6, 5, 1, -1, -2, 2, 3, 3, 4, 4},
        {0, 0, 0, 0, 0, 1, 2, 2, 3, 3},
        {2, 2, 1, 0, -1, -2, -3, -3, -4, -4},
        {3, 2, 1, 2, -1, -1, 0, 1, 2, 3},
        {-3, -1, 2, 4, 3, 1, 0, -1, -2, -2},
        {7, 5, 2, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 1, 2, 4, 5, 5, 5, 4},
    };

    /** Pré-ganho automático de uma curva: segura o nível quando as bandas sobem (evita distorção). */
    static float autoPre(float[] g) {
        float mx = 0; for (float v : g) mx = Math.max(mx, v);
        return -Math.round(mx * 0.6f);
    }

    static float snap(float v) { return Math.round(clamp(v, -RANGE, RANGE) * 2f) / 2f; } // passos de 0,5 dB
    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Interpolação cúbica monótona (Fritsch–Carlson): suave e sem "ondas" fora dos pontos. */
    static float mono(float[] xs, float[] ys, float x) {
        int n = xs.length;
        if (x <= xs[0]) return ys[0];
        if (x >= xs[n - 1]) return ys[n - 1];
        float[] d = new float[n - 1], m = new float[n];
        for (int i = 0; i < n - 1; i++) d[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]);
        m[0] = d[0]; m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2f;
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) { m[i] = 0; m[i + 1] = 0; continue; }
            float a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b;
            if (s > 9f) { float t = 3f / (float) Math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
        }
        int k = 0; while (k < n - 2 && x > xs[k + 1]) k++;
        float h = xs[k + 1] - xs[k], t = (x - xs[k]) / h, t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1];
    }

    private static float[] LN;
    private static float[] lnFreq() {
        if (LN == null) { float[] a = new float[N]; for (int i = 0; i < N; i++) a[i] = (float) Math.log(FREQ[i]); LN = a; }
        return LN;
    }
    /** Ganho (dB) da curva das 10 bandas na frequência f (interpolação em escala logarítmica). */
    static float curveDb(float[] g, float f) { return mono(lnFreq(), g, (float) Math.log(f)); }

    private static float sig(float x) { return 1f / (1f + (float) Math.exp(-x)); }
    /** Forma do loudness: prateleira de graves (< ~150 Hz) e de agudos (> ~4,5 kHz). */
    static float loudShape(float f, float lowDb, float highDb) {
        float l = (float) Math.log(f);
        return lowDb * sig(-(l - (float) Math.log(150.0)) * 2.2f) + highDb * sig((l - (float) Math.log(4500.0)) * 2.2f);
    }

    /** Fração do efeito de loudness (0..1) para a fração de volume vf (0..1); 'below' = volume onde o efeito começa. */
    static float loudFactor(float vf, float below) {
        float lo = 0.08f, hi = Math.max(lo + 0.05f, below);
        float t = clamp((hi - vf) / (hi - lo), 0f, 1f);
        return t * t * (3f - 2f * t); // suaviza o começo e o fim
    }

    /**
     * Nível (dB) pedido para uma faixa real do Android [loHz..hiHz]: média da curva dentro da faixa,
     * somada ao loudness e ao pré-ganho.
     */
    static float bandDb(float[] g, float loHz, float hiHz, float pre, float loudLowDb, float loudHighDb) {
        float lo = Math.max(25f, loHz), hi = Math.min(20000f, hiHz);
        if (hi <= lo) hi = lo * 1.2f;
        int K = 12; double a = Math.log(lo), b = Math.log(hi), s = 0;
        for (int i = 0; i < K; i++) {
            float f = (float) Math.exp(a + (b - a) * (i + .5) / K);
            s += curveDb(g, f) + loudShape(f, loudLowDb, loudHighDb);
        }
        return (float) (s / K) + pre;
    }

    /** Aproxima a faixa de cada banda quando o aparelho não informa: pontos médios (geométricos) entre os centros. */
    static float[][] edgesFromCenters(float[] centers) {
        int n = centers.length; float[][] e = new float[n][2];
        for (int i = 0; i < n; i++) {
            e[i][0] = i == 0 ? centers[0] / 2f : (float) Math.sqrt(centers[i - 1] * centers[i]);
            e[i][1] = i == n - 1 ? Math.min(20000f, centers[n - 1] * 1.6f) : (float) Math.sqrt(centers[i] * centers[i + 1]);
        }
        return e;
    }

    static String db(float v) {
        float r = Math.round(v * 2f) / 2f;
        if (r == 0) return "0";
        String s = (r == (int) r) ? String.valueOf((int) Math.abs(r)) : String.valueOf(Math.abs(r)).replace('.', ',');
        return (r > 0 ? "+" : "−") + s;
    }
}
