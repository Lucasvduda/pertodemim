package br.com.pertodemim.repo;

import br.com.pertodemim.domain.EventoIot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoIotRepository extends JpaRepository<EventoIot, String> {
}
