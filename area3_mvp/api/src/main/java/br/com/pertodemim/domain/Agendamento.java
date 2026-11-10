package br.com.pertodemim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "agendamentos")
public class Agendamento {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unidade_id")
    private Unidade unidade;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Servico servico;
    @Column(length = 10)
    private String cbo;
    @Column(name = "data_hora", nullable = false)
    private LocalDateTime dataHora;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private StatusAgendamento status = StatusAgendamento.CONFIRMADO;
    @Column(nullable = false, unique = true, length = 20)
    private String protocolo;
    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    protected Agendamento() {}

    public Agendamento(Usuario usuario, Unidade unidade, Servico servico, String cbo, LocalDateTime dataHora,
                       String protocolo, Instant criadoEm) {
        this.usuario = usuario;
        this.unidade = unidade;
        this.servico = servico;
        this.cbo = cbo;
        this.dataHora = dataHora;
        this.protocolo = protocolo;
        this.criadoEm = criadoEm;
    }

    public Long getId() { return id; }
    public Usuario getUsuario() { return usuario; }
    public Unidade getUnidade() { return unidade; }
    public Servico getServico() { return servico; }
    public String getCbo() { return cbo; }
    public LocalDateTime getDataHora() { return dataHora; }
    public StatusAgendamento getStatus() { return status; }
    public String getProtocolo() { return protocolo; }
    public Instant getCriadoEm() { return criadoEm; }

    public void cancelar() { this.status = StatusAgendamento.CANCELADO; }
}
