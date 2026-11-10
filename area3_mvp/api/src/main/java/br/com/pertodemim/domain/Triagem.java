package br.com.pertodemim.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Registro de uma triagem. O texto é guardado já anonimizado (LGPD: minimização). */
@Entity
@Table(name = "triagens")
public class Triagem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "texto_anonimizado", nullable = false, length = 600)
    private String textoAnonimizado;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Servico servico;
    @Column(length = 10)
    private String cbo;
    @Column(length = 80)
    private String especialidade;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Urgencia urgencia;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Caminho caminho;
    @Column(length = 20)
    private String regiao;
    @Column(name = "unidade_indicada_id")
    private Long unidadeIndicadaId;
    @Column(name = "km_mais_proxima")
    private Double kmMaisProxima;
    @Column(name = "km_indicada")
    private Double kmIndicada;
    @Column(name = "mais_proxima_tinha")
    private Boolean maisProximaTinha;
    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    protected Triagem() {}

    public Triagem(Long usuarioId, String textoAnonimizado, Servico servico, String cbo, String especialidade,
                   Urgencia urgencia, Caminho caminho, String regiao, Instant criadoEm) {
        this.usuarioId = usuarioId;
        this.textoAnonimizado = textoAnonimizado;
        this.servico = servico;
        this.cbo = cbo;
        this.especialidade = especialidade;
        this.urgencia = urgencia;
        this.caminho = caminho;
        this.regiao = regiao;
        this.criadoEm = criadoEm;
    }

    public void registrarRanking(Long unidadeIndicadaId, Double kmMaisProxima, Double kmIndicada,
                                 Boolean maisProximaTinha) {
        this.unidadeIndicadaId = unidadeIndicadaId;
        this.kmMaisProxima = kmMaisProxima;
        this.kmIndicada = kmIndicada;
        this.maisProximaTinha = maisProximaTinha;
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public String getTextoAnonimizado() { return textoAnonimizado; }
    public Servico getServico() { return servico; }
    public String getCbo() { return cbo; }
    public String getEspecialidade() { return especialidade; }
    public Urgencia getUrgencia() { return urgencia; }
    public Caminho getCaminho() { return caminho; }
    public String getRegiao() { return regiao; }
    public Long getUnidadeIndicadaId() { return unidadeIndicadaId; }
    public Double getKmMaisProxima() { return kmMaisProxima; }
    public Double getKmIndicada() { return kmIndicada; }
    public Boolean getMaisProximaTinha() { return maisProximaTinha; }
    public Instant getCriadoEm() { return criadoEm; }
}
