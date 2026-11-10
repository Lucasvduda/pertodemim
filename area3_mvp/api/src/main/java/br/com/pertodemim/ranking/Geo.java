package br.com.pertodemim.ranking;

/** Funções geográficas: distância de Haversine e região (mesma regra do script de seed em Python). */
public final class Geo {

    public static final double CENTRO_LAT = -23.5505; // Praça da Sé
    public static final double CENTRO_LON = -46.6333;
    private static final double RAIO_TERRA_KM = 6371.0088;

    private Geo() {}

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dp / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
        return 2 * RAIO_TERRA_KM * Math.asin(Math.sqrt(a));
    }

    public static String regiao(double lat, double lon) {
        if (haversineKm(lat, lon, CENTRO_LAT, CENTRO_LON) < 3) return "Centro";
        double dlat = lat - CENTRO_LAT;
        double dlon = lon - CENTRO_LON;
        if (Math.abs(dlat) >= Math.abs(dlon)) return dlat > 0 ? "Norte" : "Sul";
        return dlon > 0 ? "Leste" : "Oeste";
    }
}
