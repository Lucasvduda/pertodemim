package br.com.pertodemim.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EspecialidadesTest {

    @Test
    void listaFechadaTem12ComCboUnicos() {
        assertThat(Especialidades.todas()).hasSize(12);
        assertThat(Especialidades.todas().stream().map(Especialidade::cbo).distinct()).hasSize(12);
    }

    @Test
    void buscaPorNomeEhEstritaSemAcentoEMaiusculas() {
        assertThat(Especialidades.porNome("ORTOPEDIA E TRAUMATOLOGIA")).contains(Especialidades.ORTOPEDIA);
        assertThat(Especialidades.porNome("clinico geral")).contains(Especialidades.CLINICO);
        assertThat(Especialidades.porNome("ortopedia")).isEmpty(); // estrita: usada para validar o LLM
        assertThat(Especialidades.porNome(null)).isEmpty();
    }

    @Test
    void buscaAproximadaServeParaOFiltroDigitado() {
        assertThat(Especialidades.porNomeAproximado("ortopedia")).contains(Especialidades.ORTOPEDIA);
        assertThat(Especialidades.porNomeAproximado("Ginecologia")).contains(Especialidades.GINECOLOGIA);
        assertThat(Especialidades.porNomeAproximado("oft")).contains(Especialidades.OFTALMOLOGIA);
    }

    @Test
    void buscaAproximadaRecusaAmbiguoCurtoOuDesconhecido() {
        assertThat(Especialidades.porNomeAproximado("o")).isEmpty();
        assertThat(Especialidades.porNomeAproximado("ot")).isEmpty();
        assertThat(Especialidades.porNomeAproximado("astrologia")).isEmpty();
        assertThat(Especialidades.porNomeAproximado("")).isEmpty();
        assertThat(Especialidades.porNomeAproximado(null)).isEmpty();
    }

    @Test
    void cboConhecidoEDesconhecido() {
        assertThat(Especialidades.contemCbo("225270")).isTrue();
        assertThat(Especialidades.contemCbo("000000")).isFalse();
        assertThat(Especialidades.contemCbo(null)).isFalse();
    }
}
