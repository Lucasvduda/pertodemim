package br.com.pertodemim.seed;

import br.com.pertodemim.config.AppProperties;
import br.com.pertodemim.domain.Dispositivo;
import br.com.pertodemim.domain.IotTipo;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.ServicoUnidade;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.iot.IotKeys;
import br.com.pertodemim.repo.DispositivoRepository;
import br.com.pertodemim.repo.ServicoUnidadeRepository;
import br.com.pertodemim.repo.UnidadeEspecialidadeRepository;
import br.com.pertodemim.repo.UnidadeRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carrega unidades (amostra real do CNES), especialidades e serviços (sintéticos) a partir dos CSVs de
 * {@code resources/seed}, cria um dispositivo por unidade e tipo de sensor e, opcionalmente, deixa as
 * unidades "frescas" (online, com fila e profissionais presentes) para a demo funcionar antes do simulador.
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final UnidadeRepository unidades;
    private final UnidadeEspecialidadeRepository especialidades;
    private final ServicoUnidadeRepository servicos;
    private final DispositivoRepository dispositivos;
    private final AppProperties props;
    private final Clock clock;

    public SeedService(UnidadeRepository unidades, UnidadeEspecialidadeRepository especialidades,
                       ServicoUnidadeRepository servicos, DispositivoRepository dispositivos, AppProperties props,
                       Clock clock) {
        this.unidades = unidades;
        this.especialidades = especialidades;
        this.servicos = servicos;
        this.dispositivos = dispositivos;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public int carregar() throws IOException {
        if (unidades.count() > 0) return 0;
        Map<String, Unidade> porCnes = new HashMap<>();
        for (Map<String, String> l : ler("seed/unidades.csv")) {
            Unidade u = new Unidade(Long.parseLong(l.get("cnes")), l.get("nome"), l.get("tipo"), l.get("bairro"),
                    l.get("regiao"), Double.parseDouble(l.get("lat")), Double.parseDouble(l.get("lon")),
                    Boolean.parseBoolean(l.get("atende_sus")), l.get("turno"));
            porCnes.put(l.get("cnes"), unidades.save(u));
        }
        Random rnd = new Random(2026);
        Instant agora = clock.instant();
        boolean fresco = props.getSeed().isEstadoInicialFresco();

        for (Map<String, String> l : ler("seed/especialidades.csv")) {
            Unidade u = porCnes.get(l.get("cnes"));
            UnidadeEspecialidade ue = new UnidadeEspecialidade(u, l.get("cbo"), l.get("especialidade"),
                    Integer.parseInt(l.get("qtd_profissionais")), Boolean.parseBoolean(l.get("agenda_aberta")));
            if (fresco) ue.registrarPresenca(rnd.nextDouble() < 0.75, agora);
            especialidades.save(ue);
        }
        for (Map<String, String> l : ler("seed/servicos.csv")) {
            servicos.save(new ServicoUnidade(porCnes.get(l.get("cnes")), Servico.valueOf(l.get("tipo")), true));
        }
        for (Unidade u : porCnes.values()) {
            for (IotTipo tipo : IotTipo.values()) {
                String chave = IotKeys.derivar(props.getIot().getChaveSemente(), u.getId(), tipo);
                dispositivos.save(new Dispositivo(u, tipo, IotKeys.hash(chave)));
            }
            if (fresco) {
                u.registrarHeartbeat(agora, true, 80 + rnd.nextInt(220));
                u.registrarFila(agora, rnd.nextInt(15));
            }
        }
        log.info("Seed carregado: {} unidades, estado inicial {}", porCnes.size(), fresco ? "fresco" : "vazio");
        return porCnes.size();
    }

    private List<Map<String, String>> ler(String caminho) throws IOException {
        try (InputStream in = new ClassPathResource(caminho).getInputStream()) {
            return CsvSimples.ler(in);
        }
    }
}
