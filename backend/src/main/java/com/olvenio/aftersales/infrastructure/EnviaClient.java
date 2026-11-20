package com.olvenio.aftersales.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.orders.domain.OrderAddress;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.DomainException;
import com.olvenio.shared.domain.InvalidRequestException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class EnviaClient {
    private final RestClient shipping;
    private final RestClient geocodes;
    private final List<String> carriers;
    private final JsonNode origin;
    private final boolean live;

    public record Parcel(BigDecimal weight, int length, int width, int height, String content) {}
    public record Destination(String name, String street, String details, String district, String municipality,
                              String postalCode, String phone, String email) {
        public static Destination from(OrderAddress address, String customerPhone, String email) {
            String phone = address.getPhone() == null ? customerPhone : address.getPhone();
            if (!"MX".equals(address.getCountry()) || !address.getPostalCode().matches("[0-9]{5}")
                    || phone == null || phone.isBlank() || address.getNeighborhood() == null)
                throw new ConflictException("Se requiere un domicilio mexicano con colonia y teléfono. Para este pedido puedes registrar una guía manual.");
            return new Destination(address.getRecipientName(), address.getLine1(), address.getLine2(),
                    address.getNeighborhood(), address.getCity(), address.getPostalCode(), phone, email);
        }
    }
    public record Rate(String carrier, String service, String description, BigDecimal amount, String currency) {}
    public record Label(String shipmentId, String trackingNumber, String url, String trackingUrl) {}

    public EnviaClient(RestClient.Builder builder, ObjectMapper mapper, Environment environment,
                       @Value("${app.envia.token:}") String token,
                       @Value("${app.envia.origin:}") String originJson,
                       @Value("${app.envia.carriers:estafeta}") String carriers,
                       @Value("${app.envia.environment:sandbox}") String mode) {
        this.live = "production".equals(mode);
        if (!token.isBlank() && (!List.of("sandbox", "production").contains(mode)
                || (live && !environment.matchesProfiles("prod"))))
            throw new IllegalStateException("Envia production requires the prod profile");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.geocodes = builder.clone().baseUrl("https://geocodes.envia.com").requestFactory(factory).build();
        this.shipping = token.isBlank() ? null : builder.clone()
                .baseUrl(live ? "https://api.envia.com" : "https://api-test.envia.com")
                .requestFactory(factory).defaultHeaders(headers -> headers.setBearerAuth(token)).build();
        this.carriers = java.util.Arrays.stream(carriers.split(",")).map(String::trim).distinct().toList();
        if (shipping != null && (this.carriers.isEmpty() || this.carriers.size() > 4
                || this.carriers.stream().anyMatch(n -> !n.matches("[a-z0-9_-]{2,40}"))))
            throw new IllegalStateException("Configure up to four valid Envia carrier codes");
        try {
            origin = shipping == null ? null : mapper.readTree(originJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("APP_ENVIA_ORIGIN_JSON must contain an Envia origin address");
        }
        if (shipping != null && (origin == null || !origin.isObject() || !"MX".equals(origin.path("country").asText())
                || List.of("name", "street", "city", "state", "postalCode", "phone")
                .stream().anyMatch(key -> origin.path(key).asText().isBlank())))
            throw new IllegalStateException("APP_ENVIA_ORIGIN_JSON is missing shipping origin fields");
    }

    public boolean enabled() { return shipping != null; }
    public boolean live() { return live; }

    public Map<String, Object> destination(Destination address) {
        requireEnabled();
        try {
            JsonNode result = geocodes.get().uri("/zipcode/MX/{code}", address.postalCode()).retrieve().body(JsonNode.class);
            JsonNode postal = result != null && result.isArray() ? result.path(0) : null;
            if (postal == null || !address.postalCode().equals(postal.path("zip_code").asText()))
                throw new InvalidRequestException("Envia no reconoce el código postal. Registra la guía manualmente.");
            String state = postal.path("state").path("code").path("2digit").asText("");
            if (state.isBlank()) throw unavailable("No pudimos verificar el estado con Envia.");
            String city = postal.path("locality").asText("");
            var destination = new LinkedHashMap<String, Object>();
            destination.put("name", address.name());
            destination.put("email", address.email());
            destination.put("phone", address.phone());
            destination.put("street", address.street());
            destination.put("district", address.district());
            destination.put("city", city.isBlank() ? address.municipality() : city);
            destination.put("state", state);
            destination.put("country", "MX");
            destination.put("postalCode", address.postalCode());
            if (address.details() != null) destination.put("reference", address.details());
            return destination;
        } catch (RestClientException e) {
            throw unavailable("No pudimos verificar el destino con Envia.");
        }
    }

    public List<Rate> rates(Map<String, Object> destination, Parcel parcel, BigDecimal value) {
        requireEnabled();
        List<Rate> rates = new ArrayList<>();
        boolean unavailable = false;
        for (String carrier : carriers) {
            try {
                var body = post("/ship/rate/", payload(destination, parcel, value, carrier, null));
                for (JsonNode row : body.path("data")) {
                    String service = row.path("service").asText();
                    String currency = row.path("currency").asText();
                    if (!service.matches("[A-Za-z0-9_-]{1,100}") || !"MXN".equals(currency)) continue;
                    BigDecimal amount;
                    try { amount = new BigDecimal(row.path("totalPrice").asText()); }
                    catch (NumberFormatException e) { continue; }
                    if (amount.signum() <= 0 || amount.scale() > 4) continue;
                    rates.add(new Rate(carrier, service, row.path("serviceDescription").asText(service), amount, currency));
                }
            } catch (RestClientException e) {
                unavailable = true;
            }
        }
        if (rates.isEmpty() && unavailable) throw unavailable("No pudimos cotizar el envío con Envia.");
        return List.copyOf(rates);
    }

    /** A single call. The caller retains an uncertain claim instead of automatically purchasing again. */
    public Label generate(Map<String, Object> destination, Parcel parcel, BigDecimal value, Rate rate, String reference) {
        var payload = payload(destination, parcel, value, rate.carrier(), rate.service());
        payload.put("settings", Map.of("printFormat", "PDF", "printSize", "PAPER_4X6", "currency", "MXN", "comments", reference));
        JsonNode data = post("/ship/generate/", payload).path("data");
        JsonNode row = data.path(0);
        String tracking = row.path("trackingNumber").asText();
        String shipment = row.path("shipmentId").asText();
        String url = httpsUrl(row.path("label").asText(row.path("labelUrl").asText()));
        if (!data.isArray() || data.size() != 1 || tracking.isBlank() || tracking.length() > 120
                || shipment.isBlank() || shipment.length() > 100 || url == null
                || !rate.carrier().equalsIgnoreCase(row.path("carrier").asText(rate.carrier())))
            throw unavailable("Envia no confirmó la guía. Revisa el panel de Envia antes de volver a intentar.");
        return new Label(shipment, tracking, url, httpsUrl(row.path("trackUrl").asText()));
    }

    private Map<String, Object> payload(Map<String, Object> destination, Parcel parcel, BigDecimal value, String carrier, String service) {
        var shipment = new LinkedHashMap<String, Object>();
        shipment.put("type", 1);
        shipment.put("carrier", carrier);
        if (service != null) shipment.put("service", service);
        var box = Map.of("type", "box", "content", parcel.content(), "amount", 1, "declaredValue", value,
                "weight", parcel.weight(), "weightUnit", "KG", "lengthUnit", "CM",
                "dimensions", Map.of("length", parcel.length(), "width", parcel.width(), "height", parcel.height()));
        var body = new LinkedHashMap<String, Object>();
        body.put("origin", origin);
        body.put("destination", destination);
        body.put("packages", List.of(box));
        body.put("shipment", shipment);
        body.put("settings", Map.of("currency", "MXN"));
        return body;
    }

    private JsonNode post(String path, Map<String, Object> payload) {
        JsonNode response = shipping.post().uri(path).body(payload).retrieve().body(JsonNode.class);
        return response == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : response;
    }

    private void requireEnabled() {
        if (!enabled()) throw new ConflictException("La generación de guías aún no está configurada.");
    }

    public static String httpsUrl(String value) {
        try {
            var uri = URI.create(value);
            return value.length() <= 2048 && "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getPort() == -1 ? uri.toASCIIString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static DomainException unavailable(String message) {
        return new DomainException(message) {
            @Override public int status() { return 503; }
            @Override public String title() { return "Shipping service unavailable"; }
        };
    }
}
