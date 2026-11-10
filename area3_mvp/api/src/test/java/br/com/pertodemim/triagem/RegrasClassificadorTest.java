package br.com.pertodemim.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RegrasClassificadorTest {

    private final RegrasClassificador regras = new RegrasClassificador();

    @Test
    void quebreiAPernaEhOrtopediaLaranja() { // caso T01 do plano de testes
        Classificacao c = regras.classificar("quebrei a perna");
        assertThat(c.servico()).isEqualTo(Servico.SAUDE);
        assertThat(c.especialidade().cbo()).isEqualTo("225270");
        assertThat(c.especialidade().nome()).isEqualTo("Ortopedia e traumatologia");
        assertThat(c.urgencia()).isIn(Urgencia.LARANJA, Urgencia.AMARELO);
        assertThat(c.orientacao()).contains("Ortopedia");
        assertThat(c.generica()).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" → {1} ({2})")
    @CsvSource(delimiter = '|', value = {
            "torci o tornozelo jogando bola|225270|AMARELO",
            "dor no joelho há meses|225270|AZUL",
            "meu filho está com febre e vomitando|225124|AMARELO",
            "tenho que levar o bebê para a vacina|225124|AZUL",
            "estou grávida e queria começar o pré-natal|225250|VERDE",
            "minha pressão está alta e sinto palpitação|225120|AMARELO",
            "estou com coceira e manchas na pele|225135|VERDE",
            "tenho ansiedade e não consigo dormir|225133|VERDE",
            "ouço vozes que ninguém mais ouve|225133|LARANJA",
            "dor de ouvido e zumbido|225275|VERDE",
            "ardência ao urinar|225285|AMARELO",
            "meu olho está vermelho e lacrimejando|225265|VERDE",
            "entrou produto químico no meu olho|225265|LARANJA",
            "dor de cabeça forte e tontura|225112|AMARELO",
            "estou com gripe e tosse|225125|VERDE",
            "preciso renovar a receita|225130|AZUL"
    })
    void deveClassificarEspecialidadeEUrgencia(String texto, String cbo, Urgencia urgencia) {
        Classificacao c = regras.classificar(texto);
        assertThat(c.servico()).as(texto).isEqualTo(Servico.SAUDE);
        assertThat(c.especialidade().cbo()).as(texto).isEqualTo(cbo);
        assertThat(c.urgencia()).as(texto).isEqualTo(urgencia);
    }

    @ParameterizedTest(name = "documento: {0}")
    @CsvSource({"preciso tirar o RG", "perdi meu CPF", "quero a segunda via da certidão de nascimento",
            "preciso do cartão do SUS", "carteira de trabalho"})
    void deveReconhecerDocumento(String texto) {
        Classificacao c = regras.classificar(texto);
        assertThat(c.servico()).as(texto).isEqualTo(Servico.DOCUMENTO);
        assertThat(c.especialidade()).isNull();
        assertThat(c.urgencia()).isEqualTo(Urgencia.AZUL);
    }

    @ParameterizedTest(name = "assistência social: {0}")
    @CsvSource({"minha mãe idosa precisa de visita da assistência social",
            "quero me inscrever no CadÚnico", "preciso de uma cesta básica", "quero falar com o CRAS"})
    void deveReconhecerAssistenciaSocial(String texto) {
        Classificacao c = regras.classificar(texto);
        assertThat(c.servico()).as(texto).isEqualTo(Servico.ASSISTENCIA_SOCIAL);
        assertThat(c.especialidade()).isNull();
    }

    @Test
    void situacaoDeFomeSobeParaAmarelo() {
        assertThat(regras.classificar("estamos passando fome em casa").urgencia()).isEqualTo(Urgencia.AMARELO);
    }

    @Test
    void textoSemPistaVaiParaClinicoGeralEPerguntaMais() {
        Classificacao c = regras.classificar("preciso de ajuda");
        assertThat(c.generica()).isTrue();
        assertThat(c.especialidade().cbo()).isEqualTo("225125");
        assertThat(c.perguntaAdicional()).isNotBlank();
    }

    @Test
    void dorMuitoForteSobeUmNivel() {
        Urgencia normal = regras.classificar("dor na coluna").urgencia();
        Urgencia forte = regras.classificar("dor na coluna insuportável").urgencia();
        assertThat(forte.ordinal()).isLessThan(normal.ordinal());
    }

    @Test
    void ortopediaTemPrioridadeSobrePediatriaParaFratura() {
        Classificacao c = regras.classificar("meu filho quebrou o braço");
        assertThat(c.especialidade().cbo()).isEqualTo("225270");
    }

    @Test
    void ignoraAcentoEMaiuscula() {
        assertThat(regras.classificar("QUEBREI A PERNA").especialidade().cbo()).isEqualTo("225270");
        assertThat(regras.classificar("Estou Com Dor De Cabeça Forte").especialidade().cbo()).isEqualTo("225112");
    }

    @Test
    void todaEspecialidadeDevolvidaPertenceAListaFechada() {
        for (String t : new String[]{"quebrei a perna", "febre", "olho vermelho", "coceira", "preciso de ajuda",
                "ansiedade", "pressão alta", "ardência ao urinar"}) {
            Classificacao c = regras.classificar(t);
            if (c.servico() == Servico.SAUDE) {
                assertThat(Especialidades.contemCbo(c.especialidade().cbo())).as(t).isTrue();
            }
        }
    }
}
