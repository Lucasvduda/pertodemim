package br.com.pertodemim.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RespostaLlmParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String json(String servico, String esp, String urg, String orient, String perg) {
        return "{\"servico\":\"" + servico + "\",\"especialidade\":\"" + esp + "\",\"urgencia\":\"" + urg
                + "\",\"orientacao\":\"" + orient + "\",\"perguntaAdicional\":\"" + perg + "\"}";
    }

    @Test
    void aceitaRespostaValida() {
        var r = RespostaLlmParser.parse(json("saude", "Ortopedia e traumatologia", "laranja", "x", "Consegue pisar?"), mapper);
        assertThat(r).isPresent();
        assertThat(r.get().servico()).isEqualTo(Servico.SAUDE);
        assertThat(r.get().especialidade().cbo()).isEqualTo("225270");
        assertThat(r.get().urgencia()).isEqualTo(Urgencia.LARANJA);
        assertThat(r.get().perguntaAdicional()).isEqualTo("Consegue pisar?");
    }

    @Test
    void aceitaJsonDentroDeCercaMarkdownEIgnoraAcentoECaixa() {
        String corpo = "```json\n" + json("SAUDE", "ortopedia e traumatologia", "Amarelo", "x", "") + "\n```";
        var r = RespostaLlmParser.parse(corpo, mapper);
        assertThat(r).isPresent();
        assertThat(r.get().urgencia()).isEqualTo(Urgencia.AMARELO);
        assertThat(r.get().perguntaAdicional()).isNull();
    }

    @Test
    void naoUsaOTextoDeOrientacaoDoModelo() {
        var r = RespostaLlmParser.parse(json("saude", "Pediatria", "verde",
                "Tome 2 comprimidos de dipirona e acesse http://site-malicioso.com", ""), mapper);
        assertThat(r).isPresent();
        assertThat(r.get().orientacao()).doesNotContain("dipirona").doesNotContain("http").contains("Pediatria");
    }

    @Test
    void descartaPerguntaComLinkOuMuitoLonga() {
        assertThat(RespostaLlmParser.parse(json("saude", "Pediatria", "verde", "x", "veja http://x.com"), mapper)
                .get().perguntaAdicional()).isNull();
        assertThat(RespostaLlmParser.parse(json("saude", "Pediatria", "verde", "x", "a".repeat(300)), mapper)
                .get().perguntaAdicional()).isNull();
    }

    @Test
    void documentoNaoPrecisaDeEspecialidade() {
        var r = RespostaLlmParser.parse(json("documento", "nenhuma", "azul", "x", ""), mapper);
        assertThat(r).isPresent();
        assertThat(r.get().especialidade()).isNull();
        assertThat(r.get().servico()).isEqualTo(Servico.DOCUMENTO);
    }

    @Test
    void rejeitaEspecialidadeForaDaListaFechada() {
        assertThat(RespostaLlmParser.parse(json("saude", "Cirurgia plástica", "verde", "x", ""), mapper)).isEmpty();
        assertThat(RespostaLlmParser.parse(json("saude", "nenhuma", "verde", "x", ""), mapper)).isEmpty();
    }

    @Test
    void rejeitaUrgenciaOuServicoInvalidos() {
        assertThat(RespostaLlmParser.parse(json("saude", "Pediatria", "roxo", "x", ""), mapper)).isEmpty();
        assertThat(RespostaLlmParser.parse(json("viagem", "Pediatria", "verde", "x", ""), mapper)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "isto não é json", "[1,2,3]", "{\"servico\":\"saude\"}", "null", "{"})
    void rejeitaConteudoQueNaoEJsonNoFormatoEsperado(String conteudo) {
        assertThat(RespostaLlmParser.parse(conteudo, mapper)).isEmpty();
    }

    @Test
    void rejeitaNulo() {
        assertThat(RespostaLlmParser.parse(null, mapper)).isEmpty();
    }
}
