package br.com.pertodemim.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pertodemim.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Testes ponta a ponta da API (T01, T02, T05, T06, T08 do plano de testes) e de segurança. */
class ApiIntegrationTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AMANHA = "2026-11-13";

    // ---------- helpers ----------

    private ResultActions postJson(String url, String token, String corpo) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(corpo);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    private String email() { return "u" + UUID.randomUUID().toString().substring(0, 8) + "@teste.com"; }

    private void registrar(String email, String senha) throws Exception {
        postJson("/api/auth/register", null, "{\"nome\":\"Maria Teste\",\"email\":\"" + email + "\",\"senha\":\"" + senha
                + "\",\"consentimento\":true}").andExpect(status().isCreated());
    }

    private String token() throws Exception {
        String e = email();
        registrar(e, "senha12345");
        return login(e, "senha12345");
    }

    private String login(String email, String senha) throws Exception {
        String corpo = postJson("/api/auth/login", null, "{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(corpo).get("token").asText();
    }

    private String triagemJson(String texto) {
        return "{\"texto\":\"" + texto + "\",\"consentimento\":true}";
    }

    private JsonNode triar(String token, String texto) throws Exception {
        String corpo = postJson("/api/triagem", token, triagemJson(texto)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(corpo);
    }

    private String agendar(String token, long unidadeId, String cbo, String dataHora) {
        return "{\"unidadeId\":" + unidadeId + ",\"servico\":\"saude\",\"cbo\":\"" + cbo + "\",\"dataHora\":\"" + dataHora + "\"}";
    }

    // ---------- T05: autenticação ----------

    @Test
    void cadastroELoginFuncionam() throws Exception {
        String e = email();
        postJson("/api/auth/register", null, "{\"nome\":\"Ana\",\"email\":\"" + e.toUpperCase()
                + "\",\"senha\":\"senha12345\",\"consentimento\":true}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value(e))
                .andExpect(jsonPath("$.senhaHash").doesNotExist()).andExpect(jsonPath("$.senha").doesNotExist());
        postJson("/api/auth/login", null, "{\"email\":\"" + e + "\",\"senha\":\"senha12345\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tipo").value("Bearer"));
    }

    @Test
    void senhaFicaGuardadaComBcryptNuncaEmTextoPuro() throws Exception {
        String e = email();
        registrar(e, "senha12345");
        String hash = usuarios.findByEmail(e).orElseThrow().getSenhaHash();
        assertThat(hash).startsWith("$2").doesNotContain("senha12345");
    }

    @Test
    void emailDuplicadoRetorna409() throws Exception {
        String e = email();
        registrar(e, "senha12345");
        postJson("/api/auth/register", null, "{\"nome\":\"Outra\",\"email\":\"" + e
                + "\",\"senha\":\"outrasenha1\",\"consentimento\":true}").andExpect(status().isConflict());
    }

    @Test
    void cadastroValidaOsCampos() throws Exception { // T06
        postJson("/api/auth/register", null, "{\"nome\":\"A\",\"email\":\"x@y.com\",\"senha\":\"senha12345\",\"consentimento\":true}")
                .andExpect(status().isBadRequest());
        postJson("/api/auth/register", null, "{\"nome\":\"Ana\",\"email\":\"nao-e-email\",\"senha\":\"senha12345\",\"consentimento\":true}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.email").exists());
        postJson("/api/auth/register", null, "{\"nome\":\"Ana\",\"email\":\"a@b.com\",\"senha\":\"curta\",\"consentimento\":true}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.senha").exists());
        postJson("/api/auth/register", null, "{\"nome\":\"Ana\",\"email\":\"a@b.com\",\"senha\":\"senha12345\",\"consentimento\":false}")
                .andExpect(status().isBadRequest());
        postJson("/api/auth/register", null, "{\"nome\":\"Ana\",\"email\":\"a@b.com\",\"senha\":\"senha12345\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/auth/register", null, "{}").andExpect(status().isBadRequest());
        assertThat(usuarios.count()).isZero();
    }

    @Test
    void loginComCredenciaisErradasRetorna401ComMesmaMensagem() throws Exception {
        String e = email();
        registrar(e, "senha12345");
        String m1 = postJson("/api/auth/login", null, "{\"email\":\"" + e + "\",\"senha\":\"errada12345\"}")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String m2 = postJson("/api/auth/login", null, "{\"email\":\"naoexiste@x.com\",\"senha\":\"senha12345\"}")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(m1).isEqualTo(m2); // não revela se o e-mail existe
        assertThat(m1).doesNotContain("senha12345").doesNotContain("$2");
    }

    @Test
    void loginComCorpoInvalidoRetorna400() throws Exception {
        postJson("/api/auth/login", null, "{}").andExpect(status().isBadRequest());
        postJson("/api/auth/login", null, "isso não é json").andExpect(status().isBadRequest());
    }

    // ---------- segurança ----------

    @Test
    void rotasProtegidasExigemToken() throws Exception {
        mvc.perform(get("/api/unidades?especialidade=225270")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/especialidades")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/unidades/1001/painel")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agendamentos/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agendamentos/horarios?unidadeId=1001&data=" + AMANHA)).andExpect(status().isUnauthorized());
        postJson("/api/triagem", null, triagemJson("febre")).andExpect(status().isUnauthorized());
        postJson("/api/agendamentos", null, "{}").andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/agendamentos/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenInvalidoAdulteradoOuExpiradoRetorna401() throws Exception {
        mvc.perform(get("/api/especialidades").header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
        String t = token();
        mvc.perform(get("/api/especialidades").header("Authorization", "Bearer " + t + "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/especialidades").header("Authorization", "Basic " + t)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/especialidades").header("Authorization", "Bearer " + t)).andExpect(status().isOk());
        clock.avancar(Duration.ofDays(2));
        mvc.perform(get("/api/especialidades").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
    }

    @Test
    void cabecalhosDeSegurancaPresentes() throws Exception {
        mvc.perform(get("/api/especialidades").header("Authorization", "Bearer " + token()))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void healthEhPublicoMasOutrosEndpointsDoActuatorNao() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/beans")).andExpect(status().is4xxClientError());
        mvc.perform(get("/h2-console")).andExpect(status().is4xxClientError());
    }

    @Test
    void erroNaoVazaStackTraceNemDetalhesInternos() throws Exception {
        String t = token();
        String corpo = mvc.perform(get("/api/unidades?especialidade=225270&lat=abc").header("Authorization", "Bearer " + t))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("Exception").doesNotContain("at br.com").doesNotContain("org.springframework");
        String corpo404 = mvc.perform(get("/api/nao-existe").header("Authorization", "Bearer " + t))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(corpo404).doesNotContain("Exception").doesNotContain("org.springframework");
    }

    @Test
    void injecaoSqlNoTextoEntraComoTextoComum() throws Exception {
        String t = token();
        JsonNode r = triar(t, "'; DROP TABLE usuario; -- dor de cabeça");
        assertThat(r.get("triagemId").asLong()).isPositive();
        assertThat(usuarios.count()).isEqualTo(1);
        mvc.perform(get("/api/unidades?especialidade=225270' OR '1'='1").header("Authorization", "Bearer " + t))
                .andExpect(status().isBadRequest());
    }

    // ---------- T01 / T02: triagem + ranking ----------

    @Test
    void pernaQuebradaIndicaOrtopediaELevaAoHospitalComOrtopedista() throws Exception { // T01 + T03 ponta a ponta
        JsonNode r = triar(token(), "quebrei a perna");
        assertThat(r.get("servico").asText()).isEqualTo("saude");
        assertThat(r.get("especialidade").asText()).containsIgnoringCase("ortop");
        assertThat(r.get("cbo").asText()).isEqualTo(ORTOPEDIA);
        assertThat(r.get("urgencia").asText()).isEqualTo("laranja");
        assertThat(r.get("emergencia").asBoolean()).isFalse();
        assertThat(r.get("caminho").asText()).isEqualTo("regras");
        assertThat(r.get("aviso").asText()).contains("não é diagnóstico").contains("192");
        // a UBS mais perto não tem ortopedista agora: o hospital B vem na frente
        assertThat(r.get("unidadeIndicadaId").asLong()).isEqualTo(b.getId());
        assertThat(r.get("unidades").get(0).get("id").asLong()).isEqualTo(b.getId());
        assertThat(r.get("unidades").get(0).get("categoria").asText()).isEqualTo("AGORA");
        assertThat(r.get("resumo").asText()).contains("Hospital B");
        // o ganho aparece na resposta: a mais próxima não tinha o profissional e a indicada é mais longe
        assertThat(r.get("maisProximaTinha").asBoolean()).isFalse();
        assertThat(r.get("kmIndicada").asDouble()).isGreaterThan(r.get("kmMaisProxima").asDouble());
        assertThat(triagens.count()).isEqualTo(1);
    }

    @Test
    void emergenciaNaoMostraUnidadesEMostraOSamu() throws Exception { // T02
        JsonNode r = triar(token(), "meu pai está com dor no peito e falta de ar");
        assertThat(r.get("urgencia").asText()).isEqualTo("vermelho");
        assertThat(r.get("emergencia").asBoolean()).isTrue();
        assertThat(r.get("samu").asBoolean()).isTrue();
        assertThat(r.get("telefoneEmergencia").asText()).isEqualTo("192");
        assertThat(r.get("caminho").asText()).isEqualTo("red_flag");
        assertThat(r.get("unidades")).isEmpty();
        assertThat(r.has("unidadeIndicadaId")).isFalse(); // sem indicação: o app só mostra o 192
    }

    @Test
    void semConsentimentoOuTextoVazioOuGrandeDemais() throws Exception { // T06
        String t = token();
        postJson("/api/triagem", t, "{\"texto\":\"febre\",\"consentimento\":false}").andExpect(status().isBadRequest());
        postJson("/api/triagem", t, "{\"texto\":\"febre\"}").andExpect(status().isBadRequest());
        postJson("/api/triagem", t, "{\"texto\":\"   \",\"consentimento\":true}").andExpect(status().isBadRequest());
        postJson("/api/triagem", t, "{\"consentimento\":true}").andExpect(status().isBadRequest());
        postJson("/api/triagem", t, triagemJson("a".repeat(501))).andExpect(status().isBadRequest());
        postJson("/api/triagem", t, triagemJson("a".repeat(500))).andExpect(status().isOk());
        postJson("/api/triagem", t, "{\"texto\":\"febre\",\"lat\":120,\"consentimento\":true}").andExpect(status().isBadRequest());
        postJson("/api/triagem", t, "{\"texto\":\"febre\",\"raioKm\":0,\"consentimento\":true}").andExpect(status().isBadRequest());
        postJson("/api/triagem", t, "não é json").andExpect(status().isBadRequest());
        assertThat(triagens.count()).isEqualTo(1);
    }

    @Test
    void triagemGuardaSoOTextoAnonimizado() throws Exception {
        String t = token();
        triar(t, "meu nome é João Silva, CPF 123.456.789-09, telefone (11) 91234-5678, estou com febre");
        var gravada = triagens.findAll().get(0);
        assertThat(gravada.getTextoAnonimizado()).doesNotContain("123.456.789-09").doesNotContain("91234-5678")
                .doesNotContain("João Silva");
        String bronze = Files.readString(BRONZE.resolve("triagens/2026-11-12/api.jsonl"));
        assertThat(bronze).doesNotContain("123.456.789-09").doesNotContain("91234").doesNotContain("João Silva");
    }

    @Test
    void documentoEAssistenciaSocialUsamOsServicosDasUnidades() throws Exception {
        String t = token();
        JsonNode doc = triar(t, "preciso tirar a segunda via do meu RG");
        assertThat(doc.get("servico").asText()).isEqualTo("documento");
        assertThat(doc.get("unidadeIndicadaId").asLong()).isEqualTo(a.getId());
        JsonNode soc = triar(t, "preciso de ajuda para pedir cesta básica e Bolsa Família");
        assertThat(soc.get("servico").asText()).isEqualTo("assistencia_social");
        assertThat(soc.get("unidadeIndicadaId").asLong()).isEqualTo(a.getId());
    }

    @Test
    void listaDeUnidadesComFiltros() throws Exception {
        String t = token();
        mvc.perform(get("/api/unidades?especialidade=ortopedia&raioKm=40").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indicadaId").value(b.getId()))
                .andExpect(jsonPath("$.unidades[0].categoria").value("AGORA"));
        mvc.perform(get("/api/unidades?servico=documento").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indicadaId").value(a.getId()));
        mvc.perform(get("/api/unidades").header("Authorization", "Bearer " + t)).andExpect(status().isBadRequest()); // saúde sem especialidade
        mvc.perform(get("/api/unidades?especialidade=astrologia").header("Authorization", "Bearer " + t)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/unidades?servico=pizza").header("Authorization", "Bearer " + t)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/unidades?especialidade=225270&raioKm=9999").header("Authorization", "Bearer " + t)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/especialidades").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(12)));
    }

    @Test
    void painelDaUnidadeMostraFilaProfissionaisEDemanda() throws Exception {
        String t = token();
        triar(t, "quebrei a perna");
        mvc.perform(get("/api/unidades/" + b.getId() + "/painel").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Hospital B"))
                .andExpect(jsonPath("$.online").value(true)).andExpect(jsonPath("$.filaPessoas").value(4))
                .andExpect(jsonPath("$.indicacoesUltimas24h").value(1))
                .andExpect(jsonPath("$.profissionais[?(@.cbo=='" + ORTOPEDIA + "')].presenteAgora").value(true));
        mvc.perform(get("/api/unidades/999999/painel").header("Authorization", "Bearer " + t)).andExpect(status().isNotFound());
    }

    // ---------- T08: agendamento ----------

    @Test
    void horariosMostramAsVagasRestantes() throws Exception {
        String t = token();
        mvc.perform(get("/api/agendamentos/horarios?unidadeId=" + a.getId() + "&cbo=" + ORTOPEDIA + "&data=" + AMANHA)
                        .header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(18)))
                .andExpect(jsonPath("$[0].dataHora").value(startsWith(AMANHA + "T08:00")))
                .andExpect(jsonPath("$[0].vagas").value(1))
                .andExpect(jsonPath("$[17].dataHora").value(startsWith(AMANHA + "T16:30")));
        // hoje só aparecem horários depois das 12:00 (hora local)
        mvc.perform(get("/api/agendamentos/horarios?unidadeId=" + a.getId() + "&cbo=" + ORTOPEDIA + "&data=2026-11-12")
                        .header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].dataHora").value(startsWith("2026-11-12T12:30")));
        // data passada e data além de 90 dias
        mvc.perform(get("/api/agendamentos/horarios?unidadeId=" + a.getId() + "&cbo=" + ORTOPEDIA + "&data=2026-11-01")
                .header("Authorization", "Bearer " + t)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agendamentos/horarios?unidadeId=" + a.getId() + "&cbo=" + ORTOPEDIA + "&data=2027-06-01")
                .header("Authorization", "Bearer " + t)).andExpect(status().isBadRequest());
    }

    @Test
    void agendarCancelarEHorarioLotado() throws Exception {
        String t1 = token();
        String t2 = token();
        String horario = AMANHA + "T09:00:00";

        // usuário 1 agenda no ortopedista da unidade A (capacidade 1)
        String resp = postJson("/api/agendamentos", t1, agendar(t1, a.getId(), ORTOPEDIA, horario))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.protocolo").value(startsWith("PDM-20261112-")))
                .andExpect(jsonPath("$.status").value("confirmado")).andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(resp).get("id").asLong();

        // usuário 2 no mesmo horário: lotado (409)
        postJson("/api/agendamentos", t2, agendar(t2, a.getId(), ORTOPEDIA, horario)).andExpect(status().isConflict());
        // o mesmo usuário em outra unidade no mesmo horário: conflito (409)
        postJson("/api/agendamentos", t1, agendar(t1, b.getId(), ORTOPEDIA, horario)).andExpect(status().isConflict());

        // vagas caem para zero
        mvc.perform(get("/api/agendamentos/horarios?unidadeId=" + a.getId() + "&cbo=" + ORTOPEDIA + "&data=" + AMANHA)
                        .header("Authorization", "Bearer " + t2))
                .andExpect(jsonPath("$[2].dataHora").value(startsWith(horario.substring(0, 16))))
                .andExpect(jsonPath("$[2].vagas").value(0));

        // outro usuário não consegue cancelar (404, sem revelar que existe)
        mvc.perform(delete("/api/agendamentos/" + id).header("Authorization", "Bearer " + t2)).andExpect(status().isNotFound());
        mvc.perform(get("/api/agendamentos/me").header("Authorization", "Bearer " + t2))
                .andExpect(jsonPath("$", hasSize(0)));

        // o dono cancela; o horário volta a ficar livre
        mvc.perform(delete("/api/agendamentos/" + id).header("Authorization", "Bearer " + t1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelado"));
        postJson("/api/agendamentos", t2, agendar(t2, a.getId(), ORTOPEDIA, horario)).andExpect(status().isCreated());
        mvc.perform(get("/api/agendamentos/me").header("Authorization", "Bearer " + t1))
                .andExpect(jsonPath("$[0].status").value("cancelado"));
    }

    @Test
    void agendamentoComCapacidadeDoisAceitaDoisEBloqueiaOTerceiro() throws Exception {
        String h = AMANHA + "T10:30:00";
        String t1 = token(), t2 = token(), t3 = token();
        postJson("/api/agendamentos", t1, agendar(t1, b.getId(), ORTOPEDIA, h)).andExpect(status().isCreated());
        postJson("/api/agendamentos", t2, agendar(t2, b.getId(), ORTOPEDIA, h)).andExpect(status().isCreated());
        postJson("/api/agendamentos", t3, agendar(t3, b.getId(), ORTOPEDIA, h)).andExpect(status().isConflict());
        // outra especialidade no mesmo horário tem a própria capacidade
        postJson("/api/agendamentos", t3, agendar(t3, b.getId(), CLINICO, h)).andExpect(status().isCreated());
    }

    @Test
    void documentoTemDoisGuichesEAssistenciaUm() throws Exception {
        String t1 = token(), t2 = token(), t3 = token();
        String h = AMANHA + "T14:00:00";
        for (String t : new String[]{t1, t2}) {
            postJson("/api/agendamentos", t, "{\"unidadeId\":" + a.getId() + ",\"servico\":\"documento\",\"dataHora\":\"" + h + "\"}")
                    .andExpect(status().isCreated());
        }
        postJson("/api/agendamentos", t3, "{\"unidadeId\":" + a.getId() + ",\"servico\":\"documento\",\"dataHora\":\"" + h + "\"}")
                .andExpect(status().isConflict());
        postJson("/api/agendamentos", t3, "{\"unidadeId\":" + a.getId() + ",\"servico\":\"assistencia_social\",\"dataHora\":\"" + h + "\"}")
                .andExpect(status().isCreated());
        // unidade B não oferece documento
        postJson("/api/agendamentos", t3, "{\"unidadeId\":" + b.getId() + ",\"servico\":\"documento\",\"dataHora\":\"" + AMANHA + "T15:00:00\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void agendamentoValidaEntradas() throws Exception { // T06
        String t = token();
        postJson("/api/agendamentos", t, agendar(t, a.getId(), ORTOPEDIA, "2026-11-12T09:00:00")).andExpect(status().isBadRequest());  // passado
        postJson("/api/agendamentos", t, agendar(t, a.getId(), ORTOPEDIA, AMANHA + "T09:15:00")).andExpect(status().isBadRequest());   // fora da grade
        postJson("/api/agendamentos", t, agendar(t, a.getId(), ORTOPEDIA, AMANHA + "T07:30:00")).andExpect(status().isBadRequest());   // antes das 08:00
        postJson("/api/agendamentos", t, agendar(t, a.getId(), ORTOPEDIA, AMANHA + "T17:00:00")).andExpect(status().isBadRequest());   // depois das 16:30
        postJson("/api/agendamentos", t, agendar(t, a.getId(), ORTOPEDIA, "2027-06-01T09:00:00")).andExpect(status().isBadRequest()); // > 90 dias
        postJson("/api/agendamentos", t, agendar(t, 999999, ORTOPEDIA, AMANHA + "T09:00:00")).andExpect(status().isNotFound());
        postJson("/api/agendamentos", t, agendar(t, a.getId(), "999999", AMANHA + "T09:00:00")).andExpect(status().isBadRequest());
        postJson("/api/agendamentos", t, agendar(t, c.getId(), "225112", AMANHA + "T09:00:00")).andExpect(status().isBadRequest());     // unidade sem a especialidade
        postJson("/api/agendamentos", t, "{\"servico\":\"saude\",\"cbo\":\"" + ORTOPEDIA + "\"}").andExpect(status().isBadRequest());
        postJson("/api/agendamentos", t, "{\"unidadeId\":" + a.getId() + ",\"servico\":\"saude\",\"cbo\":\"" + ORTOPEDIA
                + "\",\"dataHora\":\"ontem\"}").andExpect(status().isBadRequest());
        mvc.perform(delete("/api/agendamentos/999999").header("Authorization", "Bearer " + t)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/agendamentos/abc").header("Authorization", "Bearer " + t)).andExpect(status().isBadRequest());
        assertThat(agendamentos.count()).isZero();
    }

    @Test
    void agendamentoVaiParaOBronze() throws Exception {
        String t = token();
        postJson("/api/agendamentos", t, agendar(t, b.getId(), ORTOPEDIA, AMANHA + "T11:00:00")).andExpect(status().isCreated());
        String bronze = Files.readString(BRONZE.resolve("agendamentos/2026-11-12/api.jsonl"));
        assertThat(bronze).contains("\"evento\":\"criado\"").contains("\"origem\":\"api\"");
    }

    // ---------- diversos ----------

    @Test
    void respostaDeErroTemFormatoPadronizado() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erro").value(not(equalTo(""))))
                .andExpect(jsonPath("$.campos").exists());
        mvc.perform(post("/api/auth/register").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
