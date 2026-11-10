package br.com.pertodemim.iot;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Contrato do evento IoT (idempotente por eventId).
 * fila: {eventId, unidadeId, tipo:"fila", ts, pessoas}
 * presenca: {eventId, unidadeId, tipo:"presenca", ts, cbo, presente}
 * heartbeat: {eventId, unidadeId, tipo:"heartbeat", ts, online, latenciaMs}
 */
public record EventoIotRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotNull Long unidadeId,
        @NotBlank @Size(max = 20) String tipo,
        Instant ts,
        @Min(0) Integer pessoas,
        @Size(max = 10) String cbo,
        Boolean presente,
        @Min(0) Integer latenciaMs,
        Boolean online) {
}
