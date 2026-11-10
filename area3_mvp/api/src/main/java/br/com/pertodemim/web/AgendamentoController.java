package br.com.pertodemim.web;

import br.com.pertodemim.agenda.AgendamentoService;
import br.com.pertodemim.domain.Agendamento;
import br.com.pertodemim.domain.ServicoTexto;
import br.com.pertodemim.security.UsuarioPrincipal;
import br.com.pertodemim.triagem.Especialidades;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agendamentos")
public class AgendamentoController {

    public record AgendamentoRequest(
            @NotNull Long unidadeId,
            @Size(max = 30) String servico,
            @Size(max = 10) String cbo,
            @NotNull(message = "Informe a data e a hora") LocalDateTime dataHora) {}

    private final AgendamentoService service;

    public AgendamentoController(AgendamentoService service) {
        this.service = service;
    }

    @GetMapping("/horarios")
    public List<AgendamentoService.Horario> horarios(
            @RequestParam Long unidadeId,
            @RequestParam(required = false) String servico,
            @RequestParam(required = false) String cbo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        return service.horarios(unidadeId, servico, cbo, data);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> criar(@AuthenticationPrincipal UsuarioPrincipal usuario,
                                                     @Valid @RequestBody AgendamentoRequest req) {
        Agendamento a = service.agendar(usuario.id(), req.unidadeId(), req.servico(), req.cbo(), req.dataHora());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(a));
    }

    @GetMapping("/me")
    public List<Map<String, Object>> meus(@AuthenticationPrincipal UsuarioPrincipal usuario) {
        return service.doUsuario(usuario.id()).stream().map(AgendamentoController::dto).toList();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> cancelar(@AuthenticationPrincipal UsuarioPrincipal usuario, @PathVariable Long id) {
        return dto(service.cancelar(usuario.id(), id));
    }

    private static Map<String, Object> dto(Agendamento a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("protocolo", a.getProtocolo());
        m.put("unidadeId", a.getUnidade().getId());
        m.put("unidadeNome", a.getUnidade().getNome());
        m.put("servico", ServicoTexto.texto(a.getServico()));
        m.put("cbo", a.getCbo());
        if (a.getCbo() != null) m.put("especialidade", Especialidades.porCbo(a.getCbo()).map(e -> e.nome()).orElse(null));
        m.put("dataHora", a.getDataHora().toString());
        m.put("status", a.getStatus().name().toLowerCase());
        return m;
    }
}
