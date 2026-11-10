package br.com.pertodemim.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "usuarios")
public class Usuario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String nome;
    @Column(nullable = false, unique = true, length = 160)
    private String email;
    @Column(name = "senha_hash", nullable = false, length = 100)
    private String senhaHash;
    @Column(name = "consentimento_em", nullable = false)
    private Instant consentimentoEm;
    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    protected Usuario() {}

    public Usuario(String nome, String email, String senhaHash, Instant consentimentoEm, Instant criadoEm) {
        this.nome = nome;
        this.email = email;
        this.senhaHash = senhaHash;
        this.consentimentoEm = consentimentoEm;
        this.criadoEm = criadoEm;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getEmail() { return email; }
    public String getSenhaHash() { return senhaHash; }
    public Instant getConsentimentoEm() { return consentimentoEm; }
    public Instant getCriadoEm() { return criadoEm; }
}
