package br.com.pertodemim.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pertodemim.domain.Caminho;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TriagemServiceTest {

    /** LLM de mentira: devolve o que o teste mandar e conta quantas vezes foi chamado. */
    static class LlmFalso implements LlmProvider {
        final AtomicInteger chamadas = new AtomicInteger();
        final Optional<Classificacao> resposta;
        String ultimoTexto;

        LlmFalso(Optional<Classificacao> resposta) { this.resposta = resposta; }

        @Override public String nome() { return "falso"; }
        @Override public boolean ativo() { return true; }
        @Override public Optional<Classificacao> classificar(String t) {
            chamadas.incrementAndGet();
            ultimoTexto = t;
            return resposta;
        }
    }

    private TriagemService servico(LlmProvider p) {
        return new TriagemService(new RedFlagDetector(), new RegrasClassificador(), p, new Anonimizador());
    }

    private static Classificacao llm(Especialidade e, Urgencia u) {
        return new Classificacao(Servico.SAUDE, e, u, Orientacoes.montar(Servico.SAUDE, e, u), null);
    }

    @Test
    void modoRulesNaoUsaLlmECaminhoEhRegras() {
        var r = servico(new NenhumProvider()).triar("quebrei a perna");
        assertThat(r.caminho()).isEqualTo(Caminho.REGRAS);
        assertThat(r.especialidade().cbo()).isEqualTo("225270");
        assertThat(r.emergencia()).isFalse();
        assertThat(r.telefone()).isNull();
    }

    @Test
    void redFlagNuncaChamaOLlm() { // T02
        var llm = new LlmFalso(Optional.of(llm(Especialidades.CLINICO, Urgencia.VERDE)));
        var r = servico(llm).triar("dor forte no peito e falta de ar");
        assertThat(r.urgencia()).isEqualTo(Urgencia.VERMELHO);
        assertThat(r.caminho()).isEqualTo(Caminho.RED_FLAG);
        assertThat(r.telefone()).isEqualTo("192");
        assertThat(r.orientacao()).contains("192");
        assertThat(llm.chamadas.get()).isZero();
    }

    @Test
    void anonimizacaoNaoPodeEsconderEmergencia() {
        // Regressão: "rua e está desacordado" era apagado como endereço e o caso virava verde.
        for (String texto : new String[]{"meu vizinho caiu na rua e está desacordado",
                "sou o Pedro e meu pai desmaiou na avenida",
                "moro na Rua das Flores 12 e minha mãe está desacordada"}) {
            var r = servico(new NenhumProvider()).triar(texto);
            assertThat(r.urgencia()).as(texto).isEqualTo(Urgencia.VERMELHO);
            assertThat(r.caminho()).as(texto).isEqualTo(Caminho.RED_FLAG);
            assertThat(r.textoAnonimizado()).as(texto).doesNotContain("Flores");
        }
    }

    @Test
    void negacaoDeDorNoPeitoNaoViraEmergencia() {
        var r = servico(new NenhumProvider()).triar("não tenho dor no peito nem falta de ar só quero fazer um check-up");
        assertThat(r.urgencia()).isNotEqualTo(Urgencia.VERMELHO);
        assertThat(r.caminho()).isEqualTo(Caminho.REGRAS);
    }

    @Test
    void ideacaoSuicidaEncaminhaParaCvvSemLlm() {
        var llm = new LlmFalso(Optional.empty());
        var r = servico(llm).triar("quero me matar");
        assertThat(r.urgencia()).isEqualTo(Urgencia.VERMELHO);
        assertThat(r.telefone()).isEqualTo("188");
        assertThat(llm.chamadas.get()).isZero();
    }

    @Test
    void llmValidoViraCaminhoLlm() {
        var llm = new LlmFalso(Optional.of(llm(Especialidades.DERMATOLOGIA, Urgencia.VERDE)));
        var r = servico(llm).triar("tenho umas manchas estranhas");
        assertThat(r.caminho()).isEqualTo(Caminho.LLM);
        assertThat(r.especialidade().cbo()).isEqualTo("225135");
        assertThat(llm.chamadas.get()).isEqualTo(1);
    }

    @Test
    void llmIndisponivelCaiNoFallbackPorRegras() { // T09
        var llm = new LlmFalso(Optional.empty());
        var r = servico(llm).triar("quebrei a perna");
        assertThat(r.caminho()).isEqualTo(Caminho.FALLBACK);
        assertThat(r.especialidade().cbo()).isEqualTo("225270");
    }

    @Test
    void llmNaoPodeRebaixarUrgenciaAltaReconhecidaPelasRegras() {
        var llm = new LlmFalso(Optional.of(llm(Especialidades.ORTOPEDIA, Urgencia.AZUL)));
        var r = servico(llm).triar("quebrei a perna");
        assertThat(r.caminho()).isEqualTo(Caminho.LLM);
        assertThat(r.urgencia()).isEqualTo(Urgencia.LARANJA); // as regras viram fratura: laranja prevalece
    }

    @Test
    void llmPodeSubirUrgencia() {
        var llm = new LlmFalso(Optional.of(llm(Especialidades.CLINICO, Urgencia.LARANJA)));
        var r = servico(llm).triar("estou com gripe");
        assertThat(r.urgencia()).isEqualTo(Urgencia.LARANJA);
    }

    @Test
    void llmApontandoVermelhoViraEmergencia() {
        var llm = new LlmFalso(Optional.of(llm(Especialidades.CLINICO, Urgencia.VERMELHO)));
        var r = servico(llm).triar("estou me sentindo muito estranho");
        assertThat(r.emergencia()).isTrue();
        assertThat(r.telefone()).isEqualTo("192");
    }

    @Test
    void textoEnviadoAoLlmEstaAnonimizado() {
        var llm = new LlmFalso(Optional.empty());
        servico(llm).triar("meu nome é Carlos Souza, cpf 123.456.789-09, telefone (11) 91234-5678, quebrei a perna");
        assertThat(llm.ultimoTexto).doesNotContain("Carlos").doesNotContain("123.456.789-09").doesNotContain("91234-5678");
        assertThat(llm.ultimoTexto).contains("quebrei a perna");
    }

    @Test
    void textoGravadoNoResultadoEstaAnonimizado() {
        var r = servico(new NenhumProvider()).triar("cpf 123.456.789-09 quebrei a perna");
        assertThat(r.textoAnonimizado()).doesNotContain("123.456.789-09").contains("[CPF]");
    }

    @Test
    void documentoPelasRegras() {
        var r = servico(new NenhumProvider()).triar("preciso tirar o RG");
        assertThat(r.servico()).isEqualTo(Servico.DOCUMENTO);
        assertThat(r.especialidade()).isNull();
    }
}
