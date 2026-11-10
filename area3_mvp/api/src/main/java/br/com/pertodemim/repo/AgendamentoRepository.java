package br.com.pertodemim.repo;

import br.com.pertodemim.domain.Agendamento;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.StatusAgendamento;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgendamentoRepository extends JpaRepository<Agendamento, Long> {

    @Query("select a from Agendamento a join fetch a.unidade where a.usuario.id = :usuarioId order by a.dataHora desc")
    List<Agendamento> findDoUsuario(@Param("usuarioId") Long usuarioId);

    @Query("select a from Agendamento a join fetch a.unidade where a.id = :id and a.usuario.id = :usuarioId")
    Optional<Agendamento> findDoUsuario(@Param("id") Long id, @Param("usuarioId") Long usuarioId);

    /** Quantos agendamentos confirmados já ocupam o horário. Use cbo = "" (vazio) para serviços não médicos. */
    @Query("""
            select count(a) from Agendamento a
            where a.unidade.id = :unidadeId and a.servico = :servico
              and coalesce(a.cbo, '') = :cbo
              and a.dataHora = :dataHora and a.status = :status
            """)
    long contarNoHorario(@Param("unidadeId") Long unidadeId, @Param("servico") Servico servico,
                         @Param("cbo") String cbo, @Param("dataHora") LocalDateTime dataHora,
                         @Param("status") StatusAgendamento status);

    @Query("select count(a) > 0 from Agendamento a where a.usuario.id = :usuarioId and a.dataHora = :dataHora and a.status = :status")
    boolean usuarioOcupadoNoHorario(@Param("usuarioId") Long usuarioId, @Param("dataHora") LocalDateTime dataHora,
                                    @Param("status") StatusAgendamento status);

    @Query("""
            select count(a) from Agendamento a
            where a.unidade.id = :unidadeId and a.status = :status
              and a.dataHora >= :inicio and a.dataHora < :fim
            """)
    long contarDoDia(@Param("unidadeId") Long unidadeId, @Param("status") StatusAgendamento status,
                     @Param("inicio") LocalDateTime inicio, @Param("fim") LocalDateTime fim);
}
