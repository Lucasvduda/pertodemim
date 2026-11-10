package br.com.pertodemim.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.support.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RankingServiceTest extends IntegrationTestBase {

    @Autowired RankingService ranking;

    private static final double LAT = Geo.CENTRO_LAT;
    private static final double LON = Geo.CENTRO_LON;

    private ResultadoRanking ortopedia() {
        return ranking.rankear(Servico.SAUDE, ORTOPEDIA, LAT, LON, 40);
    }

    private List<Long> ids(ResultadoRanking r) {
        return r.unidades().stream().map(UnidadeRanking::id).toList();
    }

    private UnidadeRanking item(ResultadoRanking r, Unidade u) {
        return r.unidades().stream().filter(x -> x.id().equals(u.getId())).findFirst().orElseThrow();
    }

    @Test
    void unidadeMaisProximaSemOrtopedistaNaoFicaNoTopo() { // T03
        ResultadoRanking r = ortopedia();
        assertThat(r.unidades().get(0).id()).isEqualTo(b.getId());
        assertThat(r.unidades().get(0).categoria()).isEqualTo("AGORA");
        assertThat(r.indicadaId()).isEqualTo(b.getId());
        UnidadeRanking ubs = item(r, a);
        assertThat(ubs.categoria()).isEqualTo("SEM_CONFIRMACAO");
        assertThat(ubs.aviso()).contains("ausente");
        assertThat(ids(r).indexOf(a.getId())).isGreaterThan(ids(r).indexOf(b.getId()));
    }

    @Test
    void mostraAMaisProximaQueNaoTinhaParaMedirOGanho() {
        ResultadoRanking r = ortopedia();
        assertThat(r.maisProximaTinha()).isFalse(); // A é a mais próxima e está sem ortopedista
        assertThat(r.kmMaisProxima()).isLessThan(r.kmIndicada());
    }

    @Test
    void unidadeComHeartbeatExpiradoEhRebaixadaComAviso() { // T04
        // C está offline: não aparece como elegível, nem mesmo com ortopedista presente
        ResultadoRanking r = ortopedia();
        assertThat(r.unidades().stream().filter(u -> u.categoria().equals("AGORA")).map(UnidadeRanking::id))
                .doesNotContain(c.getId());
        // aproxima a cidadã da unidade C para ela aparecer entre as rebaixadas e conferir o aviso
        ResultadoRanking perto = ranking.rankear(Servico.SAUDE, ORTOPEDIA, c.getLat(), c.getLon(), 40);
        assertThat(perto.unidades().get(0).id()).isNotEqualTo(c.getId());
        UnidadeRanking uc = item(perto, c);
        assertThat(uc.categoria()).isEqualTo("SEM_CONFIRMACAO");
        assertThat(uc.online()).isFalse();
        assertThat(uc.aviso()).containsIgnoringCase("sem conexão");
    }

    @Test
    void heartbeatFicaExpiradoDepoisDeDoisMinutos() {
        assertThat(ortopedia().indicadaId()).isEqualTo(b.getId());
        clock.avancar(Duration.ofMinutes(3)); // ninguém mandou novo heartbeat
        ResultadoRanking r = ortopedia();
        assertThat(r.indicadaId()).isNull();
        assertThat(r.unidades()).isNotEmpty();
        assertThat(r.unidades()).allMatch(u -> u.categoria().equals("SEM_CONFIRMACAO") && u.score() == null);
    }

    @Test
    void checkinComMaisDeQuatroHorasNaoValePresencaAgora() {
        // mantém a unidade online, mas o check-in do ortopedista de B envelhece
        clock.avancar(Duration.ofHours(5));
        for (Unidade u : new Unidade[]{a, b, d}) {
            unidades.findById(u.getId()).ifPresent(x -> { x.registrarHeartbeat(clock.instant(), true, 100); unidades.save(x); });
        }
        ResultadoRanking r = ortopedia();
        assertThat(r.indicadaId()).isNull();
    }

    @Test
    void agendaAbertaTornaElegivelComoAgendaMasDepoisDeQuemTemProfissionalAgora() {
        UnidadeEspecialidade ueA = especialidades.findPorUnidadeECbo(a.getId(), ORTOPEDIA).orElseThrow();
        ueA.setAgendaAberta(true);
        especialidades.save(ueA);
        ResultadoRanking r = ortopedia();
        UnidadeRanking ubs = item(r, a);
        assertThat(ubs.categoria()).isEqualTo("AGENDA");
        assertThat(ubs.agendaAberta()).isTrue();
        // B tem profissional AGORA e vem antes, mesmo A estando mais perto
        assertThat(ids(r).indexOf(b.getId())).isLessThan(ids(r).indexOf(a.getId()));
        assertThat(r.unidades().get(0).categoria()).isEqualTo("AGORA");
    }

    @Test
    void entreAsQueTemOProfissionalOrdenaPorScore() {
        ResultadoRanking r = ortopedia();
        List<UnidadeRanking> agora = r.unidades().stream().filter(u -> u.categoria().equals("AGORA")).toList();
        assertThat(agora).extracting(UnidadeRanking::id).containsExactly(b.getId(), d.getId());
        assertThat(agora.get(0).score()).isLessThan(agora.get(1).score());
        assertThat(agora.get(0).distanciaKm()).isLessThan(agora.get(1).distanciaKm());
    }

    @Test
    void esperaEntraNoScoreMasADistanciaTemMaisPeso() {
        // B perto mas com fila enorme; D longe e sem fila
        b = unidades.findById(b.getId()).orElseThrow();
        b.registrarFila(clock.instant(), 200);
        unidades.save(b);
        Unidade dd = unidades.findById(d.getId()).orElseThrow();
        dd.registrarFila(clock.instant(), 0);
        unidades.save(dd);
        ResultadoRanking r = ortopedia();
        UnidadeRanking ub = item(r, b);
        UnidadeRanking ud = item(r, d);
        assertThat(ub.esperaMin()).isEqualTo(1000);
        assertThat(ud.esperaMin()).isZero();
        // dist(B)=4,3 km vs dist(D)=19 km: 0,5·0,22 + 0,3·1 + 0,2·0,06 = 0,42 (B) contra 0,5·1 + 0 + 0,2·0,06 = 0,51 (D)
        assertThat(ub.score()).isLessThan(ud.score());
    }

    @Test
    void filaDesatualizadaViraDesconhecida() {
        clock.avancar(Duration.ofMinutes(30));
        for (Unidade u : new Unidade[]{a, b, d}) {
            unidades.findById(u.getId()).ifPresent(x -> { x.registrarHeartbeat(clock.instant(), true, 100); unidades.save(x); });
        }
        UnidadeEspecialidade ue = especialidades.findPorUnidadeECbo(b.getId(), ORTOPEDIA).orElseThrow();
        ue.registrarPresenca(true, clock.instant());
        especialidades.save(ue);
        UnidadeRanking ub = item(ortopedia(), b);
        assertThat(ub.filaPessoas()).isNull();
        assertThat(ub.esperaMin()).isNull();
    }

    @Test
    void respeitaOLimiteDeRaio() {
        ResultadoRanking r = ranking.rankear(Servico.SAUDE, ORTOPEDIA, LAT, LON, 6);
        assertThat(ids(r)).contains(b.getId()).doesNotContain(d.getId());
    }

    @Test
    void semNenhumaElegivelMostraAsMaisProximasComAviso() {
        ResultadoRanking r = ranking.rankear(Servico.SAUDE, "225120" /* cardiologia: ninguém tem */, LAT, LON, 40);
        assertThat(r.indicadaId()).isNull();
        assertThat(r.unidades()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(r.unidades()).allMatch(u -> u.categoria().equals("SEM_CONFIRMACAO") && u.aviso() != null);
        assertThat(r.unidades().get(0).distanciaKm()).isLessThanOrEqualTo(r.unidades().get(1).distanciaKm());
    }

    @Test
    void documentoSoListaUnidadesQueOferecemOServico() {
        ResultadoRanking r = ranking.rankear(Servico.DOCUMENTO, null, LAT, LON, 40);
        assertThat(r.indicadaId()).isEqualTo(a.getId());
        assertThat(r.unidades().stream().filter(u -> !u.categoria().equals("SEM_CONFIRMACAO")).map(UnidadeRanking::id))
                .containsExactly(a.getId(), d.getId());
        assertThat(r.maisProximaTinha()).isTrue();
    }

    @Test
    void assistenciaSocialSoNaUnidadeA() {
        ResultadoRanking r = ranking.rankear(Servico.ASSISTENCIA_SOCIAL, null, d.getLat(), d.getLon(), 40);
        assertThat(r.indicadaId()).isEqualTo(a.getId());
        assertThat(r.maisProximaTinha()).isFalse(); // a mais próxima de D é a própria D, que não oferece
    }

    @Test
    void semUnidadesCadastradasDevolveVazio() {
        servicos.deleteAll();
        especialidades.deleteAll();
        dispositivos.deleteAll();
        unidades.deleteAll();
        ResultadoRanking r = ortopedia();
        assertThat(r.unidades()).isEmpty();
        assertThat(r.indicadaId()).isNull();
    }
}
