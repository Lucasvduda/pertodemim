package br.com.pertodemim.iot;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pertodemim.domain.IotTipo;
import org.junit.jupiter.api.Test;

class IotKeysTest {

    /** Valores de referência calculados com o simulador Python (hmac.new(semente, "id:tipo", sha256)[:32]). */
    @Test
    void chaveDerivadaBateComOSimuladorPython() {
        assertThat(IotKeys.derivar("test-seed", 466611, IotTipo.FILA)).isEqualTo("pdm_4a31547e537a501780c6df0141b47e13");
        assertThat(IotKeys.derivar("test-seed", 466611, IotTipo.PRESENCA)).isEqualTo("pdm_7e625be49f0ccd62faa1f2bb75725286");
        assertThat(IotKeys.derivar("test-seed", 466611, IotTipo.HEARTBEAT)).isEqualTo("pdm_25e97cee1d6c17c08e910ea732ff9e1c");
        assertThat(IotKeys.derivar("dev-only-iot-seed", 466611, IotTipo.FILA)).isEqualTo("pdm_1c8896dfe134121fb6b4be53283db20c");
    }

    @Test
    void chavesDiferemPorUnidadeTipoESemente() {
        String base = IotKeys.derivar("s", 1, IotTipo.FILA);
        assertThat(base).isNotEqualTo(IotKeys.derivar("s", 2, IotTipo.FILA));
        assertThat(base).isNotEqualTo(IotKeys.derivar("s", 1, IotTipo.PRESENCA));
        assertThat(base).isNotEqualTo(IotKeys.derivar("outra", 1, IotTipo.FILA));
    }

    @Test
    void hashEhSha256EmHexadecimal() {
        assertThat(IotKeys.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(IotKeys.hash("pdm_x")).hasSize(64).isNotEqualTo("pdm_x");
    }
}
