package br.com.pertodemim.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "unidade_especialidade",
        uniqueConstraints = @UniqueConstraint(columnNames = {"unidade_id", "cbo"}))
public class UnidadeEspecialidade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unidade_id")
    private Unidade unidade;
    @Column(nullable = false, length = 10)
    private String cbo;
    @Column(nullable = false, length = 80)
    private String especialidade;
    @Column(name = "qtd_profissionais", nullable = false)
    private int qtdProfissionais;
    @Column(name = "agenda_aberta", nullable = false)
    private boolean agendaAberta;
    @Column(nullable = false)
    private boolean presente;
    @Column(name = "ultimo_checkin")
    private Instant ultimoCheckin;

    protected UnidadeEspecialidade() {}

    public UnidadeEspecialidade(Unidade unidade, String cbo, String especialidade, int qtdProfissionais,
                                boolean agendaAberta) {
        this.unidade = unidade;
        this.cbo = cbo;
        this.especialidade = especialidade;
        this.qtdProfissionais = qtdProfissionais;
        this.agendaAberta = agendaAberta;
    }

    public Long getId() { return id; }
    public Unidade getUnidade() { return unidade; }
    public String getCbo() { return cbo; }
    public String getEspecialidade() { return especialidade; }
    public int getQtdProfissionais() { return qtdProfissionais; }
    public boolean isAgendaAberta() { return agendaAberta; }
    public boolean isPresente() { return presente; }
    public Instant getUltimoCheckin() { return ultimoCheckin; }

    public void setAgendaAberta(boolean agendaAberta) { this.agendaAberta = agendaAberta; }

    /** Registra um check-in (presente=true, atualiza horário) ou um check-out (presente=false). */
    public void registrarPresenca(boolean presente, Instant agora) {
        this.presente = presente;
        if (presente) this.ultimoCheckin = agora;
    }
}
