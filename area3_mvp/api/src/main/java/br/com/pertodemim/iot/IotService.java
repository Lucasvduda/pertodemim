package br.com.pertodemim.iot;

import br.com.pertodemim.domain.Dispositivo;
import br.com.pertodemim.domain.EventoIot;
import br.com.pertodemim.domain.IotTipo;
import br.com.pertodemim.domain.Unidade;
import br.com.pertodemim.domain.UnidadeEspecialidade;
import br.com.pertodemim.repo.DispositivoRepository;
import br.com.pertodemim.repo.EventoIotRepository;
import br.com.pertodemim.repo.UnidadeEspecialidadeRepository;
import br.com.pertodemim.triagem.Especialidades;
import br.com.pertodemim.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ingestão dos eventos dos sensores (mock): autentica o dispositivo, valida, aplica o estado e grava no bronze. */
@Service
public class IotService {

    public record Resultado(String eventId, boolean duplicado) {}

    private final DispositivoRepository dispositivos;
    private final EventoIotRepository eventos;
    private final UnidadeEspecialidadeRepository especialidades;
    private final BronzeWriter bronze;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    /** Saída da transação: o resultado e o registro a gravar no bronze depois do commit. */
    private record Processado(Resultado resultado, Map<String, Object> registro, java.time.LocalDate dia) {}

    public IotService(DispositivoRepository dispositivos, EventoIotRepository eventos,
                      UnidadeEspecialidadeRepository especialidades, BronzeWriter bronze, ObjectMapper mapper,
                      Clock clock, PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.dispositivos = dispositivos;
        this.eventos = eventos;
        this.especialidades = especialidades;
        this.bronze = bronze;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * A transação é programática para tratar a corrida entre duas entregas do mesmo evento: o perdedor falha
     * na chave primária, a transação dele é desfeita e a resposta é "duplicado" (sem erro 500). O bronze só é
     * gravado depois do commit.
     */
    public Resultado registrar(String chave, EventoIotRequest req) {
        Processado p;
        try {
            p = tx.execute(status -> processar(chave, req));
        } catch (DataIntegrityViolationException e) {
            return new Resultado(req.eventId(), true);
        }
        if (p.registro() != null) bronze.anexar("iot", p.dia(), p.registro());
        return p.resultado();
    }

    private Processado processar(String chave, EventoIotRequest req) {
        if (chave == null || chave.isBlank()) throw ApiException.naoAutorizado("Chave do dispositivo ausente");
        Dispositivo disp = dispositivos.findPorChaveHash(IotKeys.hash(chave.trim()))
                .orElseThrow(() -> ApiException.naoAutorizado("Chave do dispositivo inválida"));

        IotTipo tipo;
        try {
            tipo = IotTipo.de(req.tipo());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalida(e.getMessage());
        }
        Unidade unidade = disp.getUnidade();
        if (!unidade.getId().equals(req.unidadeId()) || disp.getTipo() != tipo) {
            throw ApiException.proibido("Esta chave não pertence a esta unidade/tipo de sensor");
        }

        Instant agora = clock.instant();
        Instant ts = req.ts() == null ? agora : req.ts();
        if (ts.isAfter(agora.plus(Duration.ofMinutes(5)))) throw ApiException.invalida("ts no futuro");

        if (eventos.existsById(req.eventId())) return new Processado(new Resultado(req.eventId(), true), null, null);

        Map<String, Object> valor = new LinkedHashMap<>();
        switch (tipo) {
            case FILA -> {
                if (req.pessoas() == null) throw ApiException.invalida("pessoas é obrigatório para fila");
                unidade.registrarFila(agora, req.pessoas());
                valor.put("pessoas", req.pessoas());
            }
            case PRESENCA -> {
                if (req.cbo() == null || !Especialidades.contemCbo(req.cbo()))
                    throw ApiException.invalida("cbo inválido para presença");
                if (req.presente() == null) throw ApiException.invalida("presente é obrigatório para presença");
                UnidadeEspecialidade ue = especialidades.findPorUnidadeECbo(unidade.getId(), req.cbo())
                        .orElseThrow(() -> ApiException.invalida("A unidade não tem essa especialidade"));
                ue.registrarPresenca(req.presente(), agora);
                valor.put("cbo", req.cbo());
                valor.put("presente", req.presente());
            }
            case HEARTBEAT -> {
                if (req.online() == null) throw ApiException.invalida("online é obrigatório para heartbeat");
                unidade.registrarHeartbeat(agora, req.online(), req.latenciaMs());
                valor.put("online", req.online());
                if (req.latenciaMs() != null) valor.put("latenciaMs", req.latenciaMs());
            }
        }

        String json;
        try {
            json = mapper.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw ApiException.invalida("Payload inválido");
        }
        // se outra entrega do mesmo evento chegou junto, isto lança DataIntegrityViolationException (tratada em registrar)
        eventos.saveAndFlush(new EventoIot(req.eventId(), disp, tipo, json, ts, agora));

        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("eventId", req.eventId());
        registro.put("unidadeId", unidade.getId());
        registro.put("tipo", tipo.name().toLowerCase());
        registro.put("ts", ts.toString());
        registro.putAll(valor);
        return new Processado(new Resultado(req.eventId(), false), registro, ts.atZone(clock.getZone()).toLocalDate());
    }
}
