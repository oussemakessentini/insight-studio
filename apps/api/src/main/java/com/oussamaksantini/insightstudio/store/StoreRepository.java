package com.oussamaksantini.insightstudio.store;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findAllByBusinessId(Long businessId);

    List<Store> findAllByBusinessIdOrderByNameAsc(Long businessId);

    Optional<Store> findByBusinessIdAndCode(Long businessId, String code);

    boolean existsByIdAndBusinessId(Long id, Long businessId);
}
