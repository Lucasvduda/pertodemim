package br.com.pertodemim.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pertodemim.domain.IotTipo;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.ranking.Geo;
import br.com.pertodemim.ranking.RankingService;
import br.com.pertodemim.ranking.ResultadoRanking;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.support.IntegrationTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class IotControllerTest extends IntegrationTestBase {

    @Autowired RankingService ranking;

    private ResultActions enviar(String chave, String json) throws Exception {
        var req = post("/api/iot/eventos").contentType(MediaType.APPLICATION_JSON).content(json);
        if (chave != null) req.header("X-Device-Key", chave);
        return mvc.perform(req);
    }

    private static String id() { return "evt-" + UUID.randomUUID(); }

    private String fila(Unidade u, String eventId, int pessoas) {
        return "{\"eventId\":\"" + eventId + "\",\"unidadeId\":" + u.getId() + ",\"tipo\":\"fila\",\"pessoas\":" + pessoas + "}";
    }

    private String presenca(Unidade u, String cbo, boolean presente) {
        return "{\"eventId\":\"" + id() + "\",\"unidadeId\":" + u.getId() + ",\"tipo\":\"presenca\",\"cbo\":\"" + cbo
                + "\",\"presente\":" + presente + "}";
    }

    private String heartbeat(Unidade u, boolean online, int latencia) {
        return "{\"eventId\":\"" + id() + "\",\"unidadeId\":" + u.getId() + ",\"tipo\":\"heartbeat\",\"online\":" + online
                + ",\"latenciaMs\":" + latencia + "}";
    }

    @Test
    void semChaveRetorna401() throws Exception { // T07 (parte 1)
        enviar(null, fila(a, id(), 5)).andExpect(status().isUnauthorized());
        enviar("  ", fila(a, id(), 5)).andExpect(status().isUnauthorized());
        assertThat(eventos.count()).isZero();
    }

    @Test
    void semChaveRetorna401MesmoComCorpoInvalido() throws Exception {
        enviar(null, "{}").andExpect(status().isUnauthorized());
        enviar(null, "isso nem é json").andExpect(status().isUnauthorized());
    }

    @Test
    void chaveInvalidaRetorna401() throws Exception {
        enviar("pdm_chave_que_nao_existe", fila(a, id(), 5)).andExpect(status().isUnauthorized());
    }

    @Test
    void comChaveCorretaRetorna201EGravaNoBanco() throws Exception { // T07 (parte 2)
        String ev = id();
        enviar(chave(a, IotTipo.FILA), fila(a, ev, 14))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(ev))
                .andExpect(jsonPath("$.duplicado").value(false));
        assertThat(eventos.existsById(ev)).isTrue();
        assertThat(unidades.findById(a.getId()).orElseThrow().getFilaPessoas()).isEqualTo(14);
    }

    @Test
    void eventoGravadoNoBronzeEmJsonLinesParticionadoPorData() throws Exception {
        String ev = id();
        enviar(chave(a, IotTipo.FILA), fila(a, ev, 9)).andExpect(status().isCreated());
        Path dir = BRONZE.resolve("iot").resolve("2026-11-12");
        assertThat(dir.resolve("api.jsonl")).exists();
        assertThat(Files.readAllLines(dir.resolve("api.jsonl")))
                .anySatisfy(l -> assertThat(l).contains(ev).contains("\"pessoas\":9").contains("\"origem\":\"api\""));
    }

    @Test
    void eventoRepetidoEhIdempotente() throws Exception {
        String ev = id();
        String corpo = fila(a, ev, 3);
        enviar(chave(a, IotTipo.FILA), corpo).andExpect(status().isCreated());
        enviar(chave(a, IotTipo.FILA), corpo).andExpect(status().isOk()).andExpect(jsonPath("$.duplicado").value(true));
        assertThat(eventos.count()).isEqualTo(1);
        long linhas = Files.readAllLines(BRONZE.resolve("iot/2026-11-12/api.jsonl")).stream().filter(l -> l.contains(ev)).count();
        assertThat(linhas).isEqualTo(1);
    }

    @Test
    void chaveDeOutraUnidadeOuOutroTipoRetorna403() throws Exception {
        enviar(chave(b, IotTipo.FILA), fila(a, id(), 5)).andExpect(status().isForbidden());
        enviar(chave(a, IotTipo.PRESENCA), fila(a, id(), 5)).andExpect(status().isForbidden());
        assertThat(eventos.count()).isZero();
    }

    @Test
    void validaCamposDoContrato() throws Exception {
        String k = chave(a, IotTipo.FILA);
        enviar(k, fila(a, id(), -1)).andExpect(status().isBadRequest());                 // fila >= 0
        enviar(k, "{\"unidadeId\":" + a.getId() + ",\"tipo\":\"fila\",\"pessoas\":1}").andExpect(status().isBadRequest()); // sem eventId
        enviar(k, "{\"eventId\":\"x\",\"unidadeId\":" + a.getId() + ",\"tipo\":\"fila\"}").andExpect(status().isBadRequest()); // sem pessoas
        enviar(k, "{\"eventId\":\"x\",\"unidadeId\":" + a.getId() + ",\"tipo\":\"foguete\",\"pessoas\":1}").andExpect(status().isBadRequest());
        enviar(k, "não é json").andExpect(status().isBadRequest());
        enviar(chave(a, IotTipo.PRESENCA), presenca(a, "999999", true)).andExpect(status().isBadRequest()); // cbo fora da lista
        enviar(chave(a, IotTipo.PRESENCA), presenca(a, "225112", true)).andExpect(status().isBadRequest()); // unidade não tem neurologia
        enviar(chave(a, IotTipo.HEARTBEAT),
                "{\"eventId\":\"x\",\"unidadeId\":" + a.getId() + ",\"tipo\":\"heartbeat\"}").andExpect(status().isBadRequest());
        assertThat(eventos.count()).isZero();
    }

    @Test
    void rejeitaTimestampNoFuturo() throws Exception {
        String futuro = clock.instant().plus(Duration.ofHours(2)).toString();
        enviar(chave(a, IotTipo.FILA), "{\"eventId\":\"f1\",\"unidadeId\":" + a.getId()
                + ",\"tipo\":\"fila\",\"pessoas\":1,\"ts\":\"" + futuro + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void presencaDoOrtopedistaMudaORanking() throws Exception {
        // A (a mais perto) estava sem ortopedista; ao receber o check-in, passa a ser a indicada
        ResultadoRanking antes = ranking.rankear(Servico.SAUDE, ORTOPEDIA, Geo.CENTRO_LAT, Geo.CENTRO_LON, 40);
        assertThat(antes.indicadaId()).isEqualTo(b.getId());

        enviar(chave(a, IotTipo.PRESENCA), presenca(a, ORTOPEDIA, true)).andExpect(status().isCreated());
        ResultadoRanking depois = ranking.rankear(Servico.SAUDE, ORTOPEDIA, Geo.CENTRO_LAT, Geo.CENTRO_LON, 40);
        assertThat(depois.indicadaId()).isEqualTo(a.getId());

        // e o inverso: B perde o ortopedista (cenário "ortopedista ausente")
        enviar(chave(a, IotTipo.PRESENCA), presenca(a, ORTOPEDIA, false)).andExpect(status().isCreated());
        enviar(chave(b, IotTipo.PRESENCA), presenca(b, ORTOPEDIA, false)).andExpect(status().isCreated());
        ResultadoRanking sem = ranking.rankear(Servico.SAUDE, ORTOPEDIA, Geo.CENTRO_LAT, Geo.CENTRO_LON, 40);
        assertThat(sem.indicadaId()).isEqualTo(d.getId());
    }

    @Test
    void heartbeatOfflineTiraAUnidadeDoRanking() throws Exception {
        enviar(chave(b, IotTipo.HEARTBEAT), heartbeat(b, false, 0)).andExpect(status().isCreated());
        ResultadoRanking r = ranking.rankear(Servico.SAUDE, ORTOPEDIA, Geo.CENTRO_LAT, Geo.CENTRO_LON, 40);
        assertThat(r.indicadaId()).isEqualTo(d.getId());
    }

    @Test
    void heartbeatOnlineRecolocaUnidadeOffline() throws Exception {
        enviar(chave(c, IotTipo.HEARTBEAT), heartbeat(c, true, 150)).andExpect(status().isCreated());
        UnidadeEspecialidade ue = especialidades.findPorUnidadeECbo(c.getId(), ORTOPEDIA).orElseThrow();
        assertThat(ue.isPresente()).isTrue();
        ResultadoRanking r = ranking.rankear(Servico.SAUDE, ORTOPEDIA, c.getLat(), c.getLon(), 40);
        assertThat(r.indicadaId()).isEqualTo(c.getId());
    }

    @Test
    void filaAtualizaAEsperaEstimada() throws Exception {
        enviar(chave(b, IotTipo.FILA), fila(b, id(), 11)).andExpect(status().isCreated());
        var item = ranking.rankear(Servico.SAUDE, ORTOPEDIA, Geo.CENTRO_LAT, Geo.CENTRO_LON, 40).unidades().stream()
                .filter(u -> u.id().equals(b.getId())).findFirst().orElseThrow();
        assertThat(item.filaPessoas()).isEqualTo(11);
        assertThat(item.esperaMin()).isEqualTo(55);
    }

    @Test
    void concorrenciaMesmoEventoGravaUmaVezSo() throws Exception {
        String ev = id();
        String corpo = fila(a, ev, 7);
        String k = chave(a, IotTipo.FILA);
        List<Integer> status = Stream.generate(() -> corpo).limit(8).parallel().map(c -> {
            try {
                return enviar(k, c).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(status).allMatch(s -> s == 200 || s == 201);
        assertThat(status.stream().filter(s -> s == 201).count()).isGreaterThanOrEqualTo(1);
        assertThat(eventos.count()).isEqualTo(1);
    }
}
