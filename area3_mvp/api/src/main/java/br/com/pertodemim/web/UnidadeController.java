package br.com.pertodemim.web;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.ServicoTexto;
import br.com.pertodemim.ranking.Geo;
import br.com.pertodemim.ranking.RankingService;
import br.com.pertodemim.ranking.ResultadoRanking;
import br.com.pertodemim.triagem.Especialidade;
import br.com.pertodemim.triagem.Especialidades;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Validated
public class UnidadeController {

    private final RankingService ranking;
    private final PainelService painel;

    public UnidadeController(RankingService ranking, PainelService painel) {
        this.ranking = ranking;
        this.painel = painel;
    }

    /** Lista fechada de especialidades (nome e CBO). */
    @GetMapping("/especialidades")
    public List<Especialidade> especialidades() {
        return Especialidades.todas();
    }

    /**
     * Ranking de unidades. Filtros: servico (saude|documento|assistencia_social, padrão saude),
     * especialidade (nome ou CBO; obrigatória para saúde), lat, lon, raioKm.
     */
    @GetMapping("/unidades")
    public ResultadoRanking unidades(
            @RequestParam(required = false) String servico,
            @RequestParam(required = false) String especialidade,
            @RequestParam(required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam(required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @RequestParam(required = false) @DecimalMin("0.5") @DecimalMax("200.0") Double raioKm) {
        Servico s;
        try {
            s = ServicoTexto.parse(servico);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalida(e.getMessage());
        }
        String cbo = null;
        if (s == Servico.SAUDE) {
            if (especialidade == null || especialidade.isBlank())
                throw ApiException.invalida("Informe a especialidade (nome ou CBO)");
            cbo = Especialidades.porCbo(especialidade.trim())
                    .or(() -> Especialidades.porNomeAproximado(especialidade))
                    .map(Especialidade::cbo)
                    .orElseThrow(() -> ApiException.invalida("Especialidade desconhecida: " + especialidade));
        }
        return ranking.rankear(s, cbo, lat != null ? lat : Geo.CENTRO_LAT, lon != null ? lon : Geo.CENTRO_LON,
                raioKm != null ? raioKm : 40);
    }

    /** Painel da unidade (visão simulada do servidor da unidade). */
    @GetMapping("/unidades/{id}/painel")
    public Map<String, Object> painel(@PathVariable Long id) {
        return painel.montar(id);
    }
}
