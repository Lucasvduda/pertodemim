package br.com.pertodemim.triagem;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Triagem;
import br.com.pertodemim.iot.BronzeWriter;
import br.com.pertodemim.ranking.Geo;
import br.com.pertodemim.ranking.RankingService;
import br.com.pertodemim.ranking.ResultadoRanking;
import br.com.pertodemim.ranking.UnidadeRanking;
import br.com.pertodemim.repo.TriagemRepository;
import br.com.pertodemim.web.TriagemRequest;
import br.com.pertodemim.web.TriagemResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fluxo automático da triagem: classifica → monta o ranking de unidades → grava a triagem (anonimizada)
 * → registra o evento no bronze → devolve o resultado com o resumo para compartilhar.
 */
@Service
public class TriagemOrquestrador {

    static final double RAIO_PADRAO_KM = 40;
    static final String AVISO = "Isto é uma orientação, não é diagnóstico. Em emergência, ligue 192 (SAMU).";

    private final TriagemService triagem;
    private final RankingService ranking;
    private final TriagemRepository repo;
    private final BronzeWriter bronze;
    private final Clock clock;

    public TriagemOrquestrador(TriagemService triagem, RankingService ranking, TriagemRepository repo,
                               BronzeWriter bronze, Clock clock) {
        this.triagem = triagem;
        this.ranking = ranking;
        this.repo = repo;
        this.bronze = bronze;
        this.clock = clock;
    }

    @Transactional
    public TriagemResponse executar(Long usuarioId, TriagemRequest req) {
        ResultadoTriagem r = triagem.triar(req.texto());
        double lat = req.lat() != null ? req.lat() : Geo.CENTRO_LAT;
        double lon = req.lon() != null ? req.lon() : Geo.CENTRO_LON;
        double raio = req.raioKm() != null ? req.raioKm() : RAIO_PADRAO_KM;
        String regiao = Geo.regiao(lat, lon);

        String cbo = r.especialidade() == null ? null : r.especialidade().cbo();
        Instant agora = clock.instant();
        Triagem t = new Triagem(usuarioId, r.textoAnonimizado(), r.servico(), cbo,
                r.especialidade() == null ? null : r.especialidade().nome(), r.urgencia(), r.caminho(), regiao, agora);

        ResultadoRanking rk = ResultadoRanking.vazio();
        if (!r.emergencia()) {
            // emergência: sem ranking. Saúde sem especialidade identificada cai no clínico geral.
            String cboRanking = r.servico() == Servico.SAUDE ? (cbo != null ? cbo : Especialidades.CLINICO.cbo()) : null;
            rk = ranking.rankear(r.servico(), cboRanking, lat, lon, raio);
            t.registrarRanking(rk.indicadaId(), rk.kmMaisProxima(), rk.kmIndicada(), rk.maisProximaTinha());
        }
        t = repo.save(t);

        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("triagemId", t.getId());
        reg.put("ts", agora.toString());
        reg.put("servico", r.servico().name().toLowerCase(Locale.ROOT));
        reg.put("cbo", cbo);
        reg.put("especialidade", t.getEspecialidade());
        reg.put("urgencia", r.urgencia().name().toLowerCase(Locale.ROOT));
        reg.put("caminho", r.caminho().name().toLowerCase(Locale.ROOT));
        reg.put("regiao", regiao);
        reg.put("unidadeIndicadaId", rk.indicadaId());
        reg.put("kmMaisProxima", rk.kmMaisProxima());
        reg.put("kmIndicada", rk.kmIndicada());
        reg.put("maisProximaTinha", rk.maisProximaTinha());
        reg.put("textoAnonimizado", r.textoAnonimizado());
        bronze.anexar("triagens", agora.atZone(clock.getZone()).toLocalDate(), reg);

        List<UnidadeRanking> unidades = rk.unidades();
        return new TriagemResponse(t.getId(), r.servico().name().toLowerCase(Locale.ROOT), t.getEspecialidade(), cbo,
                r.urgencia().name().toLowerCase(Locale.ROOT), r.urgencia().tempoAlvoMin(), r.orientacao(),
                r.perguntaAdicional(), r.emergencia(), "192".equals(r.telefone()), r.telefone(), r.caminho().name().toLowerCase(Locale.ROOT),
                resumo(r, unidades), AVISO, rk.indicadaId(), unidades, rk.kmIndicada(), rk.kmMaisProxima(), rk.maisProximaTinha());
    }

    private String resumo(ResultadoTriagem r, List<UnidadeRanking> unidades) {
        if (r.emergencia()) return "PertoDeMim: emergência. Ligue " + r.telefone() + " agora.";
        StringBuilder sb = new StringBuilder("PertoDeMim: ");
        sb.append(r.especialidade() != null ? r.especialidade().nome() : rotuloServico(r.servico()));
        sb.append(" (urgência ").append(r.urgencia().name().toLowerCase(Locale.ROOT)).append("). ");
        unidades.stream().filter(u -> !"SEM_CONFIRMACAO".equals(u.categoria())).findFirst().ifPresentOrElse(u -> {
            sb.append("Unidade indicada: ").append(u.nome()).append(" (").append(u.distanciaKm()).append(" km");
            if (u.esperaMin() != null) sb.append(", espera estimada ").append(u.esperaMin()).append(" min");
            sb.append(").");
        }, () -> sb.append("Nenhuma unidade com confirmação agora; veja as mais próximas e confirme por telefone."));
        return sb.toString();
    }

    private static String rotuloServico(Servico s) {
        return s == Servico.DOCUMENTO ? "Emissão de documento" : "Assistência social";
    }
}
