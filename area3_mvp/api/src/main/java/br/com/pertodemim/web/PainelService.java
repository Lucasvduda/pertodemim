package br.com.pertodemim.web;

import br.com.pertodemim.config.AppProperties;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.ServicoUnidade;
import br.com.pertodemim.domain.StatusAgendamento;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.repo.AgendamentoRepository;
import br.com.pertodemim.repo.ServicoUnidadeRepository;
import br.com.pertodemim.repo.TriagemRepository;
import br.com.pertodemim.repo.UnidadeEspecialidadeRepository;
import br.com.pertodemim.repo.UnidadeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Painel da unidade (simulado): fila, profissionais presentes e demanda recebida. */
@Service
public class PainelService {

    private final UnidadeRepository unidades;
    private final UnidadeEspecialidadeRepository especialidades;
    private final ServicoUnidadeRepository servicos;
    private final TriagemRepository triagens;
    private final AgendamentoRepository agendamentos;
    private final AppProperties props;
    private final Clock clock;

    public PainelService(UnidadeRepository unidades, UnidadeEspecialidadeRepository especialidades,
                         ServicoUnidadeRepository servicos, TriagemRepository triagens,
                         AgendamentoRepository agendamentos, AppProperties props, Clock clock) {
        this.unidades = unidades;
        this.especialidades = especialidades;
        this.servicos = servicos;
        this.triagens = triagens;
        this.agendamentos = agendamentos;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> montar(Long id) {
        Unidade u = unidades.findById(id).orElseThrow(() -> ApiException.naoEncontrado("Unidade não encontrada"));
        Instant agora = clock.instant();
        boolean online = u.getUltimoHeartbeat() != null && u.isOnlineInformado()
                && Duration.between(u.getUltimoHeartbeat(), agora)
                .compareTo(Duration.ofMinutes(props.getIot().getHeartbeatMaxMinutos())) < 0;

        List<Map<String, Object>> profissionais = new ArrayList<>();
        for (UnidadeEspecialidade ue : especialidades.findPorUnidade(id)) {
            boolean presente = ue.isPresente() && ue.getUltimoCheckin() != null
                    && Duration.between(ue.getUltimoCheckin(), agora)
                    .compareTo(Duration.ofHours(props.getIot().getPresencaMaxHoras())) < 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cbo", ue.getCbo());
            m.put("especialidade", ue.getEspecialidade());
            m.put("qtdProfissionais", ue.getQtdProfissionais());
            m.put("presenteAgora", presente);
            m.put("agendaAberta", ue.isAgendaAberta());
            if (ue.getUltimoCheckin() != null) m.put("ultimoCheckin", ue.getUltimoCheckin().toString());
            profissionais.add(m);
        }
        List<String> servicosAtivos = new ArrayList<>();
        for (Servico s : new Servico[]{Servico.DOCUMENTO, Servico.ASSISTENCIA_SOCIAL}) {
            if (servicos.existeAtivo(id, s)) servicosAtivos.add(s.name().toLowerCase());
        }

        LocalDate hoje = clock.instant().atZone(clock.getZone()).toLocalDate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("nome", u.getNome());
        m.put("tipo", u.getTipo());
        m.put("bairro", u.getBairro());
        m.put("online", online);
        if (u.getUltimoHeartbeat() != null) m.put("ultimoHeartbeat", u.getUltimoHeartbeat().toString());
        if (u.getLatenciaMs() != null) m.put("latenciaMs", u.getLatenciaMs());
        m.put("filaPessoas", u.getFilaPessoas());
        m.put("profissionais", profissionais);
        m.put("servicos", servicosAtivos);
        m.put("indicacoesUltimas24h", triagens.contarIndicacoes(id, agora.minus(Duration.ofHours(24))));
        m.put("agendamentosHoje", agendamentos.contarDoDia(id, StatusAgendamento.CONFIRMADO,
                hoje.atStartOfDay(), hoje.plusDays(1).atStartOfDay()));
        return m;
    }
}
