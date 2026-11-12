package com.heliosdesk.tickets;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
  Optional<Ticket> findByTenantIdAndId(UUID tenantId, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from Ticket t where t.tenantId=:tenant and t.id=:id")
  Optional<Ticket> lock(@Param("tenant") UUID tenant, @Param("id") UUID id);
}
