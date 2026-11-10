package br.com.pertodemim.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnonimizadorTest {

    private final Anonimizador anon = new Anonimizador();

    @Test
    void mascaraCpfComESemPontuacao() {
        assertThat(anon.anonimizar("meu cpf é 123.456.789-09 e preciso de médico")).doesNotContain("123.456.789-09").contains("[CPF]");
        assertThat(anon.anonimizar("cpf 12345678909")).doesNotContain("12345678909").contains("[CPF]");
    }

    @Test
    void mascaraTelefoneEmailECep() {
        String t = anon.anonimizar("ligue (11) 91234-5678 ou escreva maria@exemplo.com, cep 01001-000");
        assertThat(t).doesNotContain("91234-5678").doesNotContain("maria@exemplo.com").doesNotContain("01001-000");
        assertThat(t).contains("[TEL]").contains("[EMAIL]").contains("[CEP]");
    }

    @Test
    void mascaraNomeDitoNaFrase() {
        assertThat(anon.anonimizar("meu nome é José da Silva e quebrei a perna"))
                .doesNotContain("José").doesNotContain("Silva").contains("quebrei a perna");
        assertThat(anon.anonimizar("me chamo Ana e estou com febre")).doesNotContain("Ana ").contains("febre");
    }

    @Test
    void mascaraEndereco() {
        assertThat(anon.anonimizar("moro na Rua das Flores, 123 e estou doente"))
                .doesNotContain("Flores").contains("[ENDERECO]").contains("doente");
    }

    @Test
    void enderecoNaoEngoleORestoDaFrase() {
        assertThat(anon.anonimizar("meu vizinho caiu na rua e está desacordado")).contains("desacordado");
        assertThat(anon.anonimizar("caí na rua e quebrei a perna")).contains("quebrei a perna");
        assertThat(anon.anonimizar("moro na rua das flores e estou com febre"))
                .doesNotContain("flores").contains("estou com febre");
        assertThat(anon.anonimizar("moro na avenida Paulista 1000 e estou com tosse"))
                .doesNotContain("Paulista").doesNotContain("1000").contains("estou com tosse");
        assertThat(anon.anonimizar("moro na rua tal 45, tenho dor de cabeça"))
                .doesNotContain("45").contains("dor de cabeça");
    }

    @Test
    void mascaraNumerosLongosEPreservaTextoClinico() {
        assertThat(anon.anonimizar("cartão sus 898001234567890 febre há 3 dias"))
                .doesNotContain("898001234567890").contains("febre há 3 dias");
    }

    @Test
    void naoAlteraTextoSemIdentificadores() {
        String t = "quebrei a perna ontem, dor forte e inchaço há 2 dias";
        assertThat(anon.anonimizar(t)).isEqualTo(t);
    }

    @Test
    void nuloViraVazio() {
        assertThat(anon.anonimizar(null)).isEmpty();
    }
}
