package br.com.pertodemim.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedFlagDetectorTest {

    private final RedFlagDetector detector = new RedFlagDetector();

    @ParameterizedTest(name = "emergência: {0}")
    @ValueSource(strings = {
            "estou com dor forte no peito e falta de ar",
            "meu pai está com aperto no peito",
            "acho que é infarto",
            "não consigo respirar direito",
            "minha filha está engasgada",
            "ele desmaiou agora há pouco",
            "minha mãe está desacordada",
            "meu filho teve uma convulsão",
            "está com sangramento que não para",
            "hemorragia depois do parto",
            "ela vomitou sangue",
            "boca torta e fala enrolada",
            "acho que é um AVC",
            "fraqueza em um lado do corpo",
            "foi atropelado na avenida",
            "sofreu acidente de moto",
            "caiu de um prédio",
            "levou um tiro",
            "foi esfaqueado",
            "osso aparecendo na perna",
            "queimadura extensa no braço",
            "estou grávida e com sangramento",
            "gestante com bolsa rompeu",
            "criança engoliu veneno",
            "overdose de remédio",
            "garganta fechando depois de comer camarão",
            "a pior dor de cabeça da minha vida",
            "lábios roxos no bebê"
    })
    void deveDetectarEmergencia(String texto) {
        var alerta = detector.detectar(texto);
        assertThat(alerta).as(texto).isPresent();
        assertThat(alerta.get().telefone()).isEqualTo("192");
        assertThat(alerta.get().tipo()).isEqualTo(RedFlagDetector.Tipo.EMERGENCIA);
    }

    @ParameterizedTest(name = "negação explícita não alerta: {0}")
    @ValueSource(strings = {
            "não tenho dor no peito nem falta de ar só quero fazer um check-up",
            "não tenho dor no peito, só quero marcar consulta",
            "nunca senti falta de ar, quero um exame de rotina",
            "não tenho nenhuma dor no peito"
    })
    void negacaoExplicitaDeDorNoPeitoOuFaltaDeArNaoAlerta(String texto) {
        assertThat(detector.detectar(texto)).as(texto).isEmpty();
    }

    @ParameterizedTest(name = "negação não pode esconder outra emergência: {0}")
    @ValueSource(strings = {
            "não tenho dor no peito mas desmaiei",
            "não tenho dor no peito e meu pai desmaiou",
            "não tenho falta de ar, só que levei um tiro",
            "não consigo respirar",
            "não tenho como respirar direito, falta de ar",
            "não tenho dor no peito nem falta de ar mas quero me matar"
    })
    void negacaoNaoEscondeOutraEmergencia(String texto) {
        assertThat(detector.detectar(texto)).as(texto).isPresent();
    }

    @ParameterizedTest(name = "saúde mental: {0}")
    @ValueSource(strings = {
            "quero me matar",
            "estou pensando em acabar com a minha vida",
            "não quero mais viver",
            "tenho vontade de morrer"
    })
    void deveEncaminharParaCvv(String texto) {
        var alerta = detector.detectar(texto);
        assertThat(alerta).as(texto).isPresent();
        assertThat(alerta.get().tipo()).isEqualTo(RedFlagDetector.Tipo.SAUDE_MENTAL);
        assertThat(alerta.get().telefone()).isEqualTo("188");
        assertThat(alerta.get().mensagem()).contains("188").contains("192");
    }

    @ParameterizedTest(name = "NÃO é emergência: {0}")
    @ValueSource(strings = {
            "quebrei a perna",
            "preciso tirar o RG",
            "estou com tosse e febre há dois dias",
            "dor no joelho há meses",
            "minha mãe idosa precisa de visita da assistência social",
            "quero renovar a receita da pressão",
            "tenho consulta com o dermatologista",
            "estou sem ar condicionado em casa",
            "tirei o gesso ontem"
    })
    void naoDeveGerarFalsoAlarme(String texto) {
        assertThat(detector.detectar(texto)).as(texto).isEmpty();
    }

    @Test
    void ignoraAcentoMaiusculaEEspacosExtras() {
        assertThat(detector.detectar("  DOR   FORTE   NO   PEITO  ")).isPresent();
        assertThat(detector.detectar("Dor No Peito")).isPresent();
    }

    @Test
    void textoNuloOuVazioNaoQuebra() {
        assertThat(detector.detectar(null)).isEmpty();
        assertThat(detector.detectar("")).isEmpty();
    }
}
