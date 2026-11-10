package br.com.pertodemim.agenda;

import br.com.pertodemim.domain.Agendamento;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.ServicoTexto;
import br.com.pertodemim.domain.StatusAgendamento;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.domain.Usuario;
import br.com.pertodemim.iot.BronzeWriter;
import br.com.pertodemim.repo.AgendamentoRepository;
import br.com.pertodemim.repo.ServicoUnidadeRepository;
import br.com.pertodemim.repo.UnidadeEspecialidadeRepository;
import br.com.pertodemim.repo.UnidadeRepository;
import br.com.pertodemim.repo.UsuarioRepository;
import br.com.pertodemim.triagem.Especialidades;
import br.com.pertodemim.web.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agendamento de consultas, documentos e visitas. Horários de 30 em 30 minutos, das 08:00 às 16:30,
 * até 90 dias à frente. Conflito (horário lotado ou o usuário já ocupado) devolve 409.
 */
@Service
public class AgendamentoService {

    static final LocalTime PRIMEIRO = LocalTime.of(8, 0);
    static final LocalTime ULTIMO = LocalTime.of(16, 30);
    static final int DIAS_MAX = 90;
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Horario(LocalDateTime dataHora, int vagas) {}

    private final AgendamentoRepository agendamentos;
    private final UnidadeRepository unidades;
    private final UnidadeEspecialidadeRepository especialidades;
    private final ServicoUnidadeRepository servicos;
    private final UsuarioRepository usuarios;
    private final BronzeWriter bronze;
    private final Clock clock;

    public AgendamentoService(AgendamentoRepository agendamentos, UnidadeRepository unidades,
                              UnidadeEspecialidadeRepository especialidades, ServicoUnidadeRepository servicos,
                              UsuarioRepository usuarios, BronzeWriter bronze, Clock clock) {
        this.agendamentos = agendamentos;
        this.unidades = unidades;
        this.especialidades = especialidades;
        this.servicos = servicos;
        this.usuarios = usuarios;
        this.bronze = bronze;
        this.clock = clock;
    }

    /** Capacidade por horário: nº de profissionais (saúde), 2 guichês (documento), 1 (assistência social). */
    private int capacidade(Unidade u, Servico servico, String cbo) {
        return switch (servico) {
            case SAUDE -> especialidades.findPorUnidadeECbo(u.getId(), cbo)
                    .map(UnidadeEspecialidade::getQtdProfissionais)
                    .orElseThrow(() -> ApiException.invalida("A unidade não tem essa especialidade"));
            case DOCUMENTO -> exigirServico(u, servico, 2);
            case ASSISTENCIA_SOCIAL -> exigirServico(u, servico, 1);
        };
    }

    private int exigirServico(Unidade u, Servico servico, int cap) {
        if (!servicos.existeAtivo(u.getId(), servico)) throw ApiException.invalida("A unidade não oferece esse serviço");
        return cap;
    }

    private String normalizarCbo(Servico servico, String cbo) {
        if (servico != Servico.SAUDE) return "";
        if (cbo == null || !Especialidades.contemCbo(cbo)) throw ApiException.invalida("cbo inválido para saúde");
        return cbo;
    }

    private Unidade unidade(Long id) {
        return unidades.findById(id).orElseThrow(() -> ApiException.naoEncontrado("Unidade não encontrada"));
    }

    private static boolean horarioValido(LocalDateTime dh) {
        LocalTime t = dh.toLocalTime();
        return dh.getSecond() == 0 && dh.getNano() == 0 && (t.getMinute() == 0 || t.getMinute() == 30)
                && !t.isBefore(PRIMEIRO) && !t.isAfter(ULTIMO);
    }

    @Transactional(readOnly = true)
    public List<Horario> horarios(Long unidadeId, String servicoTexto, String cbo, LocalDate data) {
        Servico servico = servico(servicoTexto);
        Unidade u = unidade(unidadeId);
        String chaveCbo = normalizarCbo(servico, cbo);
        int cap = capacidade(u, servico, cbo);
        LocalDateTime agora = LocalDateTime.now(clock);
        if (data.isBefore(agora.toLocalDate()) || data.isAfter(agora.toLocalDate().plusDays(DIAS_MAX))) {
            throw ApiException.invalida("Data fora do período disponível (até " + DIAS_MAX + " dias)");
        }
        List<Horario> lista = new ArrayList<>();
        for (LocalTime t = PRIMEIRO; !t.isAfter(ULTIMO); t = t.plusMinutes(30)) {
            LocalDateTime dh = data.atTime(t);
            if (!dh.isAfter(agora)) continue;
            long ocupados = agendamentos.contarNoHorario(u.getId(), servico, chaveCbo, dh, StatusAgendamento.CONFIRMADO);
            lista.add(new Horario(dh, (int) Math.max(0, cap - ocupados)));
        }
        return lista;
    }

    @Transactional
    public Agendamento agendar(Long usuarioId, Long unidadeId, String servicoTexto, String cbo, LocalDateTime dataHora) {
        Servico servico = servico(servicoTexto);
        Unidade u = unidade(unidadeId);
        String chaveCbo = normalizarCbo(servico, cbo);
        int cap = capacidade(u, servico, cbo);

        LocalDateTime agora = LocalDateTime.now(clock);
        if (dataHora == null || !dataHora.isAfter(agora)) throw ApiException.invalida("Escolha um horário futuro");
        if (dataHora.isAfter(agora.plusDays(DIAS_MAX))) throw ApiException.invalida("Máximo de " + DIAS_MAX + " dias à frente");
        if (!horarioValido(dataHora)) {
            throw ApiException.invalida("Horário inválido: use horas cheias ou meia hora, das 08:00 às 16:30");
        }
        if (agendamentos.usuarioOcupadoNoHorario(usuarioId, dataHora, StatusAgendamento.CONFIRMADO)) {
            throw ApiException.conflito("Você já tem um agendamento neste horário");
        }
        long ocupados = agendamentos.contarNoHorario(u.getId(), servico, chaveCbo, dataHora, StatusAgendamento.CONFIRMADO);
        if (ocupados >= cap) throw ApiException.conflito("Horário lotado: escolha outro horário");

        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> ApiException.naoAutorizado("Usuário não encontrado"));
        Instant agoraInstant = clock.instant();
        Agendamento a;
        try {
            a = agendamentos.saveAndFlush(new Agendamento(usuario, u, servico, servico == Servico.SAUDE ? cbo : null,
                    dataHora, protocolo(), agoraInstant));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflito("Não foi possível confirmar o agendamento. Tente novamente.");
        }
        registrarBronze(a, "criado", agoraInstant);
        return a;
    }

    @Transactional(readOnly = true)
    public List<Agendamento> doUsuario(Long usuarioId) {
        return agendamentos.findDoUsuario(usuarioId);
    }

    @Transactional
    public Agendamento cancelar(Long usuarioId, Long id) {
        Agendamento a = agendamentos.findDoUsuario(id, usuarioId)
                .orElseThrow(() -> ApiException.naoEncontrado("Agendamento não encontrado"));
        if (a.getStatus() == StatusAgendamento.CONFIRMADO) {
            a.cancelar();
            registrarBronze(a, "cancelado", clock.instant());
        }
        return a;
    }

    private Servico servico(String texto) {
        try {
            return ServicoTexto.parse(texto);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalida(e.getMessage());
        }
    }

    private String protocolo() {
        StringBuilder sb = new StringBuilder("PDM-");
        sb.append(LocalDate.now(clock).toString().replace("-", "")).append('-');
        for (int i = 0; i < 6; i++) sb.append(ALFABETO.charAt(RANDOM.nextInt(ALFABETO.length())));
        return sb.toString();
    }

    private void registrarBronze(Agendamento a, String evento, Instant ts) {
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("agendamentoId", a.getId());
        reg.put("protocolo", a.getProtocolo());
        reg.put("evento", evento);
        reg.put("ts", ts.toString());
        reg.put("unidadeId", a.getUnidade().getId());
        reg.put("servico", ServicoTexto.texto(a.getServico()));
        reg.put("cbo", a.getCbo());
        reg.put("dataHora", a.getDataHora().toString());
        reg.put("status", a.getStatus().name().toLowerCase(Locale.ROOT));
        bronze.anexar("agendamentos", ts.atZone(clock.getZone()).toLocalDate(), reg);
    }
}
