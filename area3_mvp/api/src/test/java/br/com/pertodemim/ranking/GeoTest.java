package br.com.pertodemim.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoTest {

    @Test
    void distanciaDeUmPontoParaEleMesmoEhZero() {
        assertThat(Geo.haversineKm(-23.55, -46.63, -23.55, -46.63)).isZero();
    }

    @Test
    void distanciaConhecidaSaoPauloRio() {
        // Praça da Sé (SP) até a Candelária (RJ): cerca de 357 km em linha reta
        double km = Geo.haversineKm(-23.5505, -46.6333, -22.9027, -43.1763);
        assertThat(km).isCloseTo(357, within(8.0));
    }

    @Test
    void distanciaEhSimetrica() {
        assertThat(Geo.haversineKm(-23.5, -46.6, -23.7, -46.8))
                .isEqualTo(Geo.haversineKm(-23.7, -46.8, -23.5, -46.6), within(1e-9));
    }

    @Test
    void umGrauDeLatitudeTemCercaDe111Km() {
        assertThat(Geo.haversineKm(0, 0, 1, 0)).isCloseTo(111.2, within(0.3));
    }

    @Test
    void regioesDeSaoPaulo() {
        assertThat(Geo.regiao(-23.5505, -46.6333)).isEqualTo("Centro");
        assertThat(Geo.regiao(-23.75, -46.69)).isEqualTo("Sul");
        assertThat(Geo.regiao(-23.40, -46.63)).isEqualTo("Norte");
        assertThat(Geo.regiao(-23.55, -46.42)).isEqualTo("Leste");
        assertThat(Geo.regiao(-23.55, -46.82)).isEqualTo("Oeste");
    }
}
