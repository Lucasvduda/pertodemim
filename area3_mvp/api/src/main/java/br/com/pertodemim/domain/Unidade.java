package br.com.pertodemim.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Unidade de atendimento. O id é o código CNES (chave natural estável). */
@Entity
@Table(name = "unidades")
public class Unidade {
    @Id
    private Long id;
    @Column(nullable = false, length = 200)
    private String nome;
    @Column(nullable = false, length = 30)
    private String tipo;
    @Column(length = 120)
    private String bairro;
    @Column(length = 20)
    private String regiao;
    @Column(nullable = false)
    private double lat;
    @Column(nullable = false)
    private double lon;
    @Column(name = "atende_sus", nullable = false)
    private boolean atendeSus = true;
    @Column(length = 40)
    private String turno;

    // estado dinâmico alimentado pelos sensores (mock)
    @Column(name = "ultimo_heartbeat")
    private Instant ultimoHeartbeat;
    @Column(name = "online_informado", nullable = false)
    private boolean onlineInformado = false;
    @Column(name = "latencia_ms")
    private Integer latenciaMs;
    @Column(name = "fila_pessoas")
    private Integer filaPessoas;
    @Column(name = "fila_atualizada_em")
    private Instant filaAtualizadaEm;

    protected Unidade() {}

    public Unidade(Long id, String nome, String tipo, String bairro, String regiao, double lat, double lon,
                   boolean atendeSus, String turno) {
        this.id = id;
        this.nome = nome;
        this.tipo = tipo;
        this.bairro = bairro;
        this.regiao = regiao;
        this.lat = lat;
        this.lon = lon;
        this.atendeSus = atendeSus;
        this.turno = turno;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getTipo() { return tipo; }
    public String getBairro() { return bairro; }
    public String getRegiao() { return regiao; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public boolean isAtendeSus() { return atendeSus; }
    public String getTurno() { return turno; }
    public Instant getUltimoHeartbeat() { return ultimoHeartbeat; }
    public boolean isOnlineInformado() { return onlineInformado; }
    public Integer getLatenciaMs() { return latenciaMs; }
    public Integer getFilaPessoas() { return filaPessoas; }
    public Instant getFilaAtualizadaEm() { return filaAtualizadaEm; }

    public void registrarHeartbeat(Instant agora, boolean online, Integer latenciaMs) {
        this.ultimoHeartbeat = agora;
        this.onlineInformado = online;
        this.latenciaMs = latenciaMs;
    }

    public void registrarFila(Instant agora, int pessoas) {
        this.filaPessoas = pessoas;
        this.filaAtualizadaEm = agora;
    }
}
