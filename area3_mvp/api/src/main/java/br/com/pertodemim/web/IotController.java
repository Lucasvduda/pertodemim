package br.com.pertodemim.web;

import br.com.pertodemim.iot.EventoIotRequest;
import br.com.pertodemim.iot.IotService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/iot")
public class IotController {

    private final IotService iot;

    public IotController(IotService iot) {
        this.iot = iot;
    }

    /** Eventos dos sensores. Autenticado pela chave do dispositivo (X-Device-Key), não por JWT. */
    @PostMapping("/eventos")
    public ResponseEntity<Map<String, Object>> evento(
            @RequestHeader(value = "X-Device-Key", required = false) String chave,
            @Valid @RequestBody EventoIotRequest req) {
        // O cabeçalho ausente é barrado por DeviceKeyHeaderFilter (401); chave inválida/errada: 401/403 no serviço.
        IotService.Resultado r = iot.registrar(chave, req);
        HttpStatus status = r.duplicado() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(Map.of("eventId", r.eventId(), "duplicado", r.duplicado()));
    }
}
