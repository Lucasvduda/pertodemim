package br.com.pertodemim.repo;

import br.com.pertodemim.domain.Triagem;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TriagemRepository extends JpaRepository<Triagem, Long> {

    @Query("select count(t) from Triagem t where t.unidadeIndicadaId = :unidadeId and t.criadoEm >= :desde")
    long contarIndicacoes(@Param("unidadeId") Long unidadeId, @Param("desde") Instant desde);
}
