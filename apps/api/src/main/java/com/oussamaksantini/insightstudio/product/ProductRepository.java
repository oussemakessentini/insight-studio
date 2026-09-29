package com.oussamaksantini.insightstudio.product;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByBusinessId(Long businessId);

    Optional<Product> findByBusinessIdAndSku(Long businessId, String sku);
}
