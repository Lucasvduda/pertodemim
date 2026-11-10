package br.com.pertodemim.domain;

import jakarta.persistence.*;

/** Dispositivo (mock) de uma unidade. Só o hash SHA-256 da chave fica no banco. */
@Entity
@Table(name = "dispositivos")
public class Dispositivo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unidade_id")
    private Unidade unidade;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private IotTipo tipo;
    @Column(name = "chave_hash", nullable = false, unique = true, length = 64)
    private String chaveHash;

    protected Dispositivo() {}

    public Dispositivo(Unidade unidade, IotTipo tipo, String chaveHash) {
        this.unidade = unidade;
        this.tipo = tipo;
        this.chaveHash = chaveHash;
    }

    public Long getId() { return id; }
    public Unidade getUnidade() { return unidade; }
    public IotTipo getTipo() { return tipo; }
    public String getChaveHash() { return chaveHash; }
}
