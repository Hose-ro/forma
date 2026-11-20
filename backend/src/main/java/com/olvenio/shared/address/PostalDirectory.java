package com.olvenio.shared.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.olvenio.shared.domain.DomainException;
import com.olvenio.shared.domain.InvalidRequestException;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class PostalDirectory {
    private static final int CACHE_SIZE = 4096;
    private final RestClient client;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    public PostalDirectory(RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = builder.baseUrl("https://postali.app").requestFactory(factory).build();
    }

    public record Neighborhood(String name, String type, String locality) {}
    public record PostalCode(String postalCode, String state, String municipality, List<Neighborhood> neighborhoods) {}
    private record Cached(PostalCode value, Instant expiresAt) {}

    /** Only the five-digit code leaves the store. Customer data stays in Olvenio. */
    public PostalCode lookup(String code) {
        if (code == null || !code.matches("[0-9]{5}"))
            throw new InvalidRequestException("Escribe un código postal de 5 dígitos.");
        Instant now = Instant.now();
        synchronized (cache) {
            Cached hit = cache.get(code);
            if (hit != null && now.isBefore(hit.expiresAt())) return hit.value();
        }
        PostalCode value = fetch(code);
        synchronized (cache) {
            cache.put(code, new Cached(value, now.plus(value == null ? Duration.ofMinutes(5) : Duration.ofHours(24))));
            if (cache.size() > CACHE_SIZE) cache.pollFirstEntry();
        }
        return value;
    }

    private PostalCode fetch(String code) {
        try {
            JsonNode body = client.get().uri("/api/v1/mx/cp/{code}", code).retrieve().body(JsonNode.class);
            if (body == null || !code.equals(body.path("cp").asText()) || !body.path("asentamientos").isArray())
                throw unavailable();
            String state = canonicalState(body.path("estado").asText());
            String municipality = body.path("municipio").asText().trim();
            if (municipality.isEmpty() || state.isEmpty() || municipality.length() > 100 || state.length() > 100)
                throw unavailable();
            List<Neighborhood> neighborhoods = body.path("asentamientos").valueStream()
                    .map(n -> new Neighborhood(n.path("nombre").asText().trim(), n.path("tipo").asText(),
                            n.path("ciudad").asText("").trim()))
                    .filter(n -> !n.name().isEmpty() && n.name().length() <= 160).distinct().toList();
            if (neighborhoods.isEmpty()) throw unavailable();
            return new PostalCode(code, state, municipality, neighborhoods);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw unavailable();
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    public PostalCode validate(String code, String state, String municipality, String neighborhood) {
        PostalCode address = lookup(code);
        if (address == null) throw new InvalidRequestException("No encontramos ese código postal.");
        if (!same(address.state(), canonicalState(state)))
            throw new InvalidRequestException("El estado no coincide con el código postal.");
        boolean matches = address.neighborhoods().stream().anyMatch(n ->
                (neighborhood == null || same(n.name(), neighborhood))
                && (same(address.municipality(), municipality) || same(n.locality(), municipality)));
        if (!matches) throw new InvalidRequestException("La colonia o el municipio no coincide con el código postal.");
        return address;
    }

    public static String canonicalState(String state) {
        return switch (normalize(state)) {
            case "mexico", "estado de mexico", "edomex" -> "Estado de México";
            case "ciudad de mexico", "distrito federal", "cdmx" -> "Ciudad de México";
            case "coahuila de zaragoza" -> "Coahuila";
            case "michoacan de ocampo" -> "Michoacán";
            case "veracruz de ignacio de la llave" -> "Veracruz";
            default -> Objects.toString(state, "").trim();
        };
    }

    public static boolean same(String a, String b) { return normalize(a).equals(normalize(b)); }

    private static String normalize(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().toLowerCase(Locale.ROOT);
    }

    private static DomainException unavailable() {
        return new DomainException("No pudimos verificar el código postal. Intenta de nuevo.") {
            @Override public int status() { return 503; }
            @Override public String title() { return "Postal directory unavailable"; }
        };
    }
}
