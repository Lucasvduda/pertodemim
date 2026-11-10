package br.com.pertodemim.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "eventos_iot")
public class EventoIot {
    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispositivo_id")
    private Dispositivo dispositivo;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private IotTipo tipo;
    @Column(name = "valor_json", nullable = false, length = 1000)
    private String valorJson;
    @Column(nullable = false)
    private Instant ts;
    @Column(name = "recebido_em", nullable = false)
    private Instant recebidoEm;

    protected EventoIot() {}

    public EventoIot(String eventId, Dispositivo dispositivo, IotTipo tipo, String valorJson, Instant ts,
                     Instant recebidoEm) {
        this.eventId = eventId;
        this.dispositivo = dispositivo;
        this.tipo = tipo;
        this.valorJson = valorJson;
        this.ts = ts;
        this.recebidoEm = recebidoEm;
    }

    public String getEventId() { return eventId; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public IotTipo getTipo() { return tipo; }
    public String getValorJson() { return valorJson; }
    public Instant getTs() { return ts; }
    public Instant getRecebidoEm() { return recebidoEm; }
}
