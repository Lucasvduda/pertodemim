package br.com.pertodemim.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "servicos_unidade",
        uniqueConstraints = @UniqueConstraint(columnNames = {"unidade_id", "tipo"}))
public class ServicoUnidade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unidade_id")
    private Unidade unidade;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Servico tipo;
    @Column(nullable = false)
    private boolean ativo = true;

    protected ServicoUnidade() {}

    public ServicoUnidade(Unidade unidade, Servico tipo, boolean ativo) {
        this.unidade = unidade;
        this.tipo = tipo;
        this.ativo = ativo;
    }

    public Long getId() { return id; }
    public Unidade getUnidade() { return unidade; }
    public Servico getTipo() { return tipo; }
    public boolean isAtivo() { return ativo; }
}
