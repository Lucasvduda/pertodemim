package br.com.pertodemim.ranking;

import br.com.pertodemim.config.AppProperties;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.ServicoUnidade;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.repo.ServicoUnidadeRepository;
import br.com.pertodemim.repo.UnidadeEspecialidadeRepository;
import br.com.pertodemim.repo.UnidadeRepository;
import br.com.pertodemim.triagem.Especialidades;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ranking de unidades.
 * <pre>
 * elegível = unidade online (heartbeat &lt; 2 min) E (profissional com check-in &lt; 4 h OU agenda aberta)
 * score    = 0,5·(dist/dist_máx) + 0,3·(espera/espera_máx) + 0,2·penalidade_instabilidade   (menor é melhor)
 * ordem    = AGORA (por score), depois AGENDA (por score), depois as não elegíveis mais próximas (rebaixadas)
 * </pre>
 */
@Service
public class RankingService {

    static final int LIMITE_ELEGIVEIS = 8;
    static final int LIMITE_REBAIXADAS = 3;
    static final int LIMITE_SEM_CONFIRMACAO = 5;
    private static final Duration FILA_VALIDADE = Duration.ofMinutes(15);

    private final UnidadeRepository unidades;
    private final UnidadeEspecialidadeRepository especialidades;
    private final ServicoUnidadeRepository servicos;
    private final AppProperties props;
    private final Clock clock;

    public RankingService(UnidadeRepository unidades, UnidadeEspecialidadeRepository especialidades,
                          ServicoUnidadeRepository servicos, AppProperties props, Clock clock) {
        this.unidades = unidades;
        this.especialidades = especialidades;
        this.servicos = servicos;
        this.props = props;
        this.clock = clock;
    }

    /** Estado calculado de uma unidade para o alvo pedido. */
    private record Candidata(Unidade u, double dist, boolean online, boolean presenteAgora, boolean agenda,
                             boolean temServico, Integer qtd, Integer fila, Integer espera, String motivo) {
        boolean elegivel() { return online && temServico && (presenteAgora || agenda); }
    }

    @Transactional(readOnly = true)
    public ResultadoRanking rankear(Servico servico, String cbo, double lat, double lon, double raioKm) {
        Instant agora = clock.instant();
        List<Unidade> todas = unidades.findAll();
        if (todas.isEmpty()) return ResultadoRanking.vazio();

        Map<Long, UnidadeEspecialidade> porUnidade = new HashMap<>();
        Set<Long> comServico = new HashSet<>();
        String nomeEsp = "o profissional";
        if (servico == Servico.SAUDE) {
            for (UnidadeEspecialidade ue : especialidades.findPorCbo(cbo)) porUnidade.put(ue.getUnidade().getId(), ue);
            nomeEsp = Especialidades.porCbo(cbo).map(e -> e.nome()).orElse(nomeEsp);
        } else {
            for (ServicoUnidade s : servicos.findAtivosPorTipo(servico)) comServico.add(s.getUnidade().getId());
        }

        List<Candidata> candidatas = new ArrayList<>();
        for (Unidade u : todas) {
            double dist = Geo.haversineKm(lat, lon, u.getLat(), u.getLon());
            boolean online = estaOnline(u, agora);
            boolean presente = false;
            boolean agenda = false;
            boolean temServico;
            Integer qtd = null;
            String motivo = null;
            if (servico == Servico.SAUDE) {
                UnidadeEspecialidade ue = porUnidade.get(u.getId());
                temServico = ue != null;
                if (ue != null) {
                    qtd = ue.getQtdProfissionais();
                    presente = ue.isPresente() && ue.getUltimoCheckin() != null
                            && Duration.between(ue.getUltimoCheckin(), agora)
                            .compareTo(Duration.ofHours(props.getIot().getPresencaMaxHoras())) < 0;
                    agenda = ue.isAgendaAberta();
                }
                if (!online) motivo = "Unidade sem conexão agora: não foi possível confirmar o atendimento";
                else if (!temServico) motivo = "Esta unidade não tem " + nomeEsp;
                else if (!presente && !agenda) motivo = nomeEsp + " ausente agora";
            } else {
                temServico = comServico.contains(u.getId());
                presente = temServico;
                if (!online) motivo = "Unidade sem conexão agora: não foi possível confirmar o atendimento";
                else if (!temServico) motivo = "Esta unidade não oferece esse serviço";
            }
            Integer fila = filaValida(u, agora);
            Integer espera = fila == null ? null : fila * props.getIot().getMinutosPorPessoaNaFila();
            candidatas.add(new Candidata(u, dist, online, presente, agenda, temServico, qtd, fila, espera, motivo));
        }

        Candidata maisProxima = candidatas.stream().min(Comparator.comparingDouble(Candidata::dist)).orElseThrow();

        List<Candidata> elegiveis = candidatas.stream()
                .filter(Candidata::elegivel).filter(c -> c.dist() <= raioKm).toList();
        List<UnidadeRanking> saida = new ArrayList<>();

        double distMax = elegiveis.stream().mapToDouble(Candidata::dist).max().orElse(1);
        double esperaMax = elegiveis.stream().mapToDouble(c -> c.espera() == null ? 0 : c.espera()).max().orElse(1);
        if (distMax <= 0) distMax = 1;
        if (esperaMax <= 0) esperaMax = 1;

        Map<Long, Double> scores = new HashMap<>();
        for (Candidata c : elegiveis) {
            double penal = c.u().getLatenciaMs() == null ? 0.5 : Math.min(1.0, c.u().getLatenciaMs() / 2000.0);
            double espera = c.espera() == null ? 0 : c.espera();
            scores.put(c.u().getId(), 0.5 * (c.dist() / distMax) + 0.3 * (espera / esperaMax) + 0.2 * penal);
        }
        Comparator<Candidata> porScore = Comparator.comparingDouble(c -> scores.get(c.u().getId()));
        List<Candidata> agoraOrd = elegiveis.stream().filter(Candidata::presenteAgora).sorted(porScore).toList();
        List<Candidata> agendaOrd = elegiveis.stream().filter(c -> !c.presenteAgora()).sorted(porScore).toList();

        List<Candidata> ordenadas = new ArrayList<>(agoraOrd);
        ordenadas.addAll(agendaOrd);
        for (Candidata c : ordenadas.stream().limit(LIMITE_ELEGIVEIS).toList()) {
            String categoria = c.presenteAgora() ? "AGORA" : "AGENDA";
            String aviso = c.presenteAgora() ? null : "Sem confirmação de presença agora; agenda aberta para marcar horário";
            saida.add(dto(c, categoria, scores.get(c.u().getId()), aviso));
        }

        List<Candidata> naoElegiveis = candidatas.stream().filter(c -> !c.elegivel())
                .sorted(Comparator.comparingDouble(Candidata::dist)).toList();
        if (ordenadas.isEmpty()) {
            for (Candidata c : naoElegiveis.stream().filter(c -> c.dist() <= raioKm).limit(LIMITE_SEM_CONFIRMACAO).toList()) {
                saida.add(dto(c, "SEM_CONFIRMACAO", null, c.motivo()));
            }
        } else {
            double distMelhor = ordenadas.get(0).dist();
            for (Candidata c : naoElegiveis.stream().filter(c -> c.dist() < distMelhor).limit(LIMITE_REBAIXADAS).toList()) {
                saida.add(dto(c, "SEM_CONFIRMACAO", null, c.motivo()));
            }
        }

        Candidata indicada = ordenadas.isEmpty() ? null : ordenadas.get(0);
        return new ResultadoRanking(saida,
                indicada == null ? null : indicada.u().getId(),
                indicada == null ? null : arredondar(indicada.dist()),
                arredondar(maisProxima.dist()),
                maisProxima.elegivel());
    }

    private boolean estaOnline(Unidade u, Instant agora) {
        return u.getUltimoHeartbeat() != null && u.isOnlineInformado()
                && Duration.between(u.getUltimoHeartbeat(), agora)
                .compareTo(Duration.ofMinutes(props.getIot().getHeartbeatMaxMinutos())) < 0;
    }

    private Integer filaValida(Unidade u, Instant agora) {
        if (u.getFilaPessoas() == null || u.getFilaAtualizadaEm() == null) return null;
        return Duration.between(u.getFilaAtualizadaEm(), agora).compareTo(FILA_VALIDADE) < 0 ? u.getFilaPessoas() : null;
    }

    private UnidadeRanking dto(Candidata c, String categoria, Double score, String aviso) {
        Unidade u = c.u();
        return new UnidadeRanking(u.getId(), u.getNome(), u.getTipo(), u.getBairro(), u.getRegiao(), u.getLat(),
                u.getLon(), arredondar(c.dist()), c.fila(), c.espera(), c.online(), c.presenteAgora(), c.agenda(),
                c.qtd(), categoria, score == null ? null : Math.round(score * 1000.0) / 1000.0, aviso);
    }

    private static double arredondar(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
