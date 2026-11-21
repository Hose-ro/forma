package com.olvenio.storefront.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.shared.domain.BusinessRuleException;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.security.CurrentUser;
import com.olvenio.storefront.application.HomeHeroDtos.HeroA;
import com.olvenio.storefront.application.HomeHeroDtos.HeroC;
import com.olvenio.storefront.application.HomeHeroDtos.HeroD;
import com.olvenio.storefront.application.HomeHeroDtos.HeroView;
import com.olvenio.storefront.application.HomeHeroDtos.Moment;
import com.olvenio.storefront.application.HomeHeroDtos.Tile;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and replaces the home hero content. The storefront decides the rotation from the enabled rows. */
@Service
public class HomeHeroService {

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public HomeHeroService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** Every variant, enabled or not, for the admin editor. */
    @Transactional(readOnly = true)
    public List<HeroView> all() {
        return db.query("select variant, enabled, content, updated_at from home_heroes order by variant", this::view);
    }

    /** Only what the storefront may show: disabled drafts stay private. */
    @Transactional(readOnly = true)
    public List<HeroView> published() {
        return db.query("select variant, enabled, content, updated_at from home_heroes where enabled order by variant",
                this::view);
    }

    @Transactional
    public HeroView replace(String variant, boolean enabled, Object content) {
        // Serialises concurrent edits so two admins cannot each disable the last enabled hero.
        db.query("select variant from home_heroes order by variant for update", rs -> { });
        checkReferences(content);
        int updated = db.update("""
                update home_heroes set enabled=?, content=?::jsonb, updated_at=now(), updated_by=? where variant=?""",
                enabled, write(content), CurrentUser.id().orElse(null), variant);
        if (updated == 0) throw new NotFoundException("Unknown hero " + variant);
        Integer active = db.queryForObject("select count(*) from home_heroes where enabled", Integer.class);
        if (active == null || active == 0) throw new BusinessRuleException("At least one hero must stay enabled");
        return db.queryForObject("select variant, enabled, content, updated_at from home_heroes where variant=?",
                this::view, variant);
    }

    /** Links must point at something the storefront can show: existing categories and active products. */
    private void checkReferences(Object content) {
        switch (content) {
            case HeroA a -> requireCategories(Stream.concat(Stream.of(a.main()), a.tiles().stream())
                    .map(Tile::categorySlug).toList());
            case HeroC c -> requireProducts(c.campaigns().stream().map(HomeHeroDtos.Campaign::productSlug).toList());
            case HeroD d -> {
                Set<String> ids = new LinkedHashSet<>();
                for (Moment m : d.moments()) {
                    if (!ids.add(m.id())) throw new BusinessRuleException("Moment " + m.id() + " appears twice");
                }
                requireProducts(d.moments().stream().flatMap(m -> m.productSlugs().stream()).toList());
            }
            default -> { }
        }
    }

    private void requireCategories(Collection<String> slugs) {
        for (String slug : new LinkedHashSet<>(slugs)) {
            Integer found = db.queryForObject("select count(*) from categories where slug=?", Integer.class, slug);
            if (found == null || found == 0) throw new BusinessRuleException("Unknown category: " + slug);
        }
    }

    private void requireProducts(Collection<String> slugs) {
        for (String slug : new LinkedHashSet<>(slugs)) {
            Integer found = db.queryForObject("select count(*) from products where slug=? and status='ACTIVE'",
                    Integer.class, slug);
            if (found == null || found == 0) throw new BusinessRuleException("Unknown or inactive product: " + slug);
        }
    }

    private HeroView view(ResultSet rs, int row) throws SQLException {
        try {
            JsonNode content = json.readTree(rs.getString("content"));
            return new HeroView(rs.getString("variant"), rs.getBoolean("enabled"), content,
                    rs.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String write(Object content) {
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
