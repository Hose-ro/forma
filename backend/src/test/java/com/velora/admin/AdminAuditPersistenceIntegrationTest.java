package com.velora.admin;

import com.velora.admin.application.AdminService;
import com.velora.catalog.application.CatalogDtos.ProductCreateRequest;
import com.velora.catalog.application.CatalogDtos.ProductPatchRequest;
import com.velora.catalog.application.CatalogDtos.VariantInput;
import com.velora.catalog.domain.ProductStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in PostgreSQL regression: use the restricted runtime role; all changes roll back. */
@SpringBootTest(properties={"spring.flyway.enabled=false", "app.admin.email=", "app.admin.password=",
        "app.payments.access-token=", "app.payments.webhook-secret=", "app.payments.collector-id="})
@EnabledIfEnvironmentVariable(named="FORMA_DB_REGRESSION", matches="true")
@Transactional
class AdminAuditPersistenceIntegrationTest {
    @Autowired AdminService admin;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate db;

    @Test void savesProductAndAuditWithoutUpdatingAppendOnlyHistory() {
        assertFalse(Boolean.TRUE.equals(db.queryForObject(
                "select has_table_privilege(current_user,'audit_logs','UPDATE')", Boolean.class)));
        UUID category = db.queryForObject("select id from categories order by id limit 1", UUID.class);
        String slug = "audit-regression-" + UUID.randomUUID();
        var variant = new VariantInput(null, "REG-" + UUID.randomUUID(), "Original", null,
                new BigDecimal("250.00"), null, 0, true, 0, null);
        var created = admin.createProduct(new ProductCreateRequest(category, slug, "Audit regression",
                "Temporary regression product", null, ProductStatus.DRAFT, false,
                new BigDecimal("250.00"), null, List.of(variant), List.of()));
        em.flush();
        admin.updateProduct(created.id(), new ProductPatchRequest(null, null, null, null, null,
                null, null, new BigDecimal("251.00"), null, null, null, null));
        em.flush();
        em.clear();
        assertEquals(2, db.queryForObject("select count(*) from audit_logs where entity_id=?",
                Integer.class, created.id()));
        assertEquals(0, new BigDecimal("251.00").compareTo(db.queryForObject(
                "select price from products where id=?", BigDecimal.class, created.id())));
        assertEquals("DRAFT", db.queryForObject("select status from products where id=?",
                String.class, created.id()));
    }
}
