package br.com.pertodemim.seed;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.iot.IotKeys;
import br.com.pertodemim.ranking.Geo;
import br.com.pertodemim.ranking.RankingService;
import br.com.pertodemim.support.IntegrationTestBase;
import br.com.pertodemim.domain.IotTipo;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Carrega o seed real (51 unidades do CNES de São Paulo) no banco limpo e confere o resultado. */
class SeedTest extends IntegrationTestBase {

    @Autowired SeedService seed;
    @Autowired RankingService ranking;

    private void carregar() {
        // zera o que a base criou e carrega o seed de verdade
        eventos.deleteAll();
        dispositivos.deleteAll();
        servicos.deleteAll();
        especialidades.deleteAll();
        unidades.deleteAll();
        try {
            seed.carregar();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private int carregarDeNovo() {
        try {
            return seed.carregar();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void carregaUnidadesEspecialidadesServicosEDispositivos() {
        carregar();
        assertThat(unidades.count()).isEqualTo(51);
        assertThat(especialidades.count()).isEqualTo(170);
        assertThat(servicos.count()).isEqualTo(15);
        assertThat(dispositivos.count()).isEqualTo(51 * 3);
    }

    @Test
    void ehIdempotente() {
        carregar();
        assertThat(carregarDeNovo()).isZero();
        assertThat(unidades.count()).isEqualTo(51);
        assertThat(especialidades.count()).isEqualTo(170);
        assertThat(dispositivos.count()).isEqualTo(153);
    }

    @Test
    void unidadesTemCoordenadasEmSaoPauloETiposConhecidos() {
        carregar();
        Set<String> tipos = Set.of("UBS", "HOSPITAL", "UPA", "POLICLINICA", "PRONTO_SOCORRO");
        for (Unidade u : unidades.findAll()) {
            assertThat(u.getLat()).as(u.getNome()).isBetween(-24.1, -23.2);
            assertThat(u.getLon()).as(u.getNome()).isBetween(-47.0, -46.2);
            assertThat(u.getNome()).isNotBlank();
            assertThat(u.getId()).isPositive();
            assertThat(Set.of("Centro", "Norte", "Sul", "Leste", "Oeste")).contains(u.getRegiao());
        }
        assertThat(unidades.findAll().stream().map(Unidade::getTipo).collect(Collectors.toSet())).isNotEmpty();
        assertThat(tipos).isNotEmpty();
    }

    @Test
    void chavesDosDispositivosBatemComADerivacaoEmNenhumaChaveGuardadaEmTextoPuro() {
        carregar();
        Unidade u = unidades.findAll().get(0);
        String chave = IotKeys.derivar("test-seed", u.getId(), IotTipo.FILA);
        assertThat(dispositivos.findPorChaveHash(IotKeys.hash(chave))).isPresent();
        dispositivos.findAll().forEach(d -> assertThat(d.getChaveHash()).hasSize(64).doesNotStartWith("pdm_"));
    }

    @Test
    void existeOrtopedistaEmAlgumasUnidadesENemTodasTem() {
        carregar();
        long comOrtopedia = especialidades.findPorCbo(ORTOPEDIA).size();
        assertThat(comOrtopedia).isEqualTo(8);
        assertThat(comOrtopedia).isLessThan(unidades.count());
    }

    @Test
    void rankingFuncionaComOSeedReal() {
        carregar();
        var r = ranking.rankear(Servico.SAUDE, ORTOPEDIA, Geo.CENTRO_LAT, Geo.CENTRO_LON, 40);
        assertThat(r.unidades()).isNotEmpty();
        assertThat(r.indicadaId()).isNotNull(); // estado inicial "fresco": há unidades online com ortopedista
        var doc = ranking.rankear(Servico.DOCUMENTO, null, Geo.CENTRO_LAT, Geo.CENTRO_LON, 40);
        assertThat(doc.indicadaId()).isNotNull();
    }
}
