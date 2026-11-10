package br.com.pertodemim.support;

import br.com.pertodemim.domain.IotTipo;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.iot.IotKeys;
import br.com.pertodemim.repo.AgendamentoRepository;
import br.com.pertodemim.repo.DispositivoRepository;
import br.com.pertodemim.repo.EventoIotRepository;
import br.com.pertodemim.repo.ServicoUnidadeRepository;
import br.com.pertodemim.repo.TriagemRepository;
import br.com.pertodemim.repo.UnidadeEspecialidadeRepository;
import br.com.pertodemim.repo.UnidadeRepository;
import br.com.pertodemim.repo.UsuarioRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base dos testes de integração: banco H2 limpo a cada teste, relógio congelado e 4 unidades de teste
 * ao redor da Praça da Sé (A: perto e SEM ortopedista presente; B: com ortopedista; C: com ortopedista
 * mas OFFLINE; D: longe, com ortopedista).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public abstract class IntegrationTestBase {

    public static final String SEMENTE = "test-seed";
    public static final String ORTOPEDIA = "225270";
    public static final String CLINICO = "225125";

    protected static final Path BRONZE;

    static {
        try {
            BRONZE = Files.createTempDirectory("pdm-bronze-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("app.bronze.dir", () -> BRONZE.toString());
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected TestClock clock;
    @Autowired protected UnidadeRepository unidades;
    @Autowired protected UnidadeEspecialidadeRepository especialidades;
    @Autowired protected ServicoUnidadeRepository servicos;
    @Autowired protected DispositivoRepository dispositivos;
    @Autowired protected EventoIotRepository eventos;
    @Autowired protected TriagemRepository triagens;
    @Autowired protected AgendamentoRepository agendamentos;
    @Autowired protected UsuarioRepository usuarios;

    protected Unidade a;
    protected Unidade b;
    protected Unidade c;
    protected Unidade d;

    @BeforeEach
    void limparEPopular() {
        clock.reset();
        eventos.deleteAll();
        agendamentos.deleteAll();
        triagens.deleteAll();
        dispositivos.deleteAll();
        servicos.deleteAll();
        especialidades.deleteAll();
        unidades.deleteAll();
        usuarios.deleteAll();

        Instant agora = clock.instant();
        a = unidade(1001L, "UBS Perto", "UBS", -23.5600, -46.6400, true, agora);
        b = unidade(1002L, "Hospital B", "HOSPITAL", -23.5800, -46.6600, true, agora);
        c = unidade(1003L, "UPA C", "UPA", -23.6000, -46.6800, false, agora);
        d = unidade(1004L, "Hospital D", "HOSPITAL", -23.7000, -46.7500, true, agora);

        especialidade(a, ORTOPEDIA, "Ortopedia e traumatologia", 1, false, agora);   // ausente
        especialidade(a, CLINICO, "Clínico geral", 2, true, agora);
        especialidade(b, ORTOPEDIA, "Ortopedia e traumatologia", 2, true, agora);    // presente
        especialidade(b, CLINICO, "Clínico geral", 3, true, agora);
        especialidade(c, ORTOPEDIA, "Ortopedia e traumatologia", 1, true, agora);    // presente, mas offline
        especialidade(d, ORTOPEDIA, "Ortopedia e traumatologia", 1, true, agora);    // presente, longe

        servicos.save(new br.com.pertodemim.domain.ServicoUnidade(a, Servico.DOCUMENTO, true));
        servicos.save(new br.com.pertodemim.domain.ServicoUnidade(a, Servico.ASSISTENCIA_SOCIAL, true));
        servicos.save(new br.com.pertodemim.domain.ServicoUnidade(d, Servico.DOCUMENTO, true));

        for (Unidade u : new Unidade[]{a, b, c, d}) {
            for (IotTipo t : IotTipo.values()) {
                dispositivos.save(new br.com.pertodemim.domain.Dispositivo(u, t,
                        IotKeys.hash(IotKeys.derivar(SEMENTE, u.getId(), t))));
            }
        }
    }

    private Unidade unidade(Long id, String nome, String tipo, double lat, double lon, boolean online, Instant agora) {
        Unidade u = new Unidade(id, nome, tipo, "Centro", "Centro", lat, lon, true, "diurno");
        if (online) u.registrarHeartbeat(agora, true, 120);
        else u.registrarHeartbeat(agora.minusSeconds(3600), true, 120); // heartbeat de 1 h atrás: expirado
        u.registrarFila(agora, 4);
        return unidades.save(u);
    }

    private void especialidade(Unidade u, String cbo, String nome, int qtd, boolean presente, Instant agora) {
        UnidadeEspecialidade ue = new UnidadeEspecialidade(u, cbo, nome, qtd, false);
        ue.registrarPresenca(presente, agora);
        especialidades.save(ue);
    }

    protected String chave(Unidade u, IotTipo tipo) {
        return IotKeys.derivar(SEMENTE, u.getId(), tipo);
    }
}
