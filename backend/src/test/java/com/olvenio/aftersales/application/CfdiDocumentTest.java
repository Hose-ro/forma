package com.olvenio.aftersales.application;

import com.olvenio.shared.domain.InvalidRequestException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CfdiDocumentTest {
    private static final UUID UUID_SAMPLE = UUID.fromString("55cc7404-6fb1-47c2-a03d-879709fd4d1a");
    private static final AfterSales.InvoiceView REQUEST = new AfterSales.InvoiceView("AAA010101AAA", "ANA PRUEBA",
            "06600", "612", "G03", null, Instant.EPOCH, null);
    private static final BigDecimal TOTAL = new BigDecimal("88.20");
    private static final String XML = """
            <cfdi:Comprobante xmlns:cfdi="http://www.sat.gob.mx/cfd/4" xmlns:tfd="http://www.sat.gob.mx/TimbreFiscalDigital"
                Version="4.0" TipoDeComprobante="I" Moneda="MXN" Total="88.20">
              <cfdi:Receptor Rfc="AAA010101AAA" Nombre="ANA PRUEBA" DomicilioFiscalReceptor="06600"
                  RegimenFiscalReceptor="612" UsoCFDI="G03"/>
              <cfdi:Complemento><tfd:TimbreFiscalDigital UUID="%s"/></cfdi:Complemento>
            </cfdi:Comprobante>
            """.formatted(UUID_SAMPLE);

    @Test void acceptsTheMatchingDocumentAndRejectsAnotherOrdersCorrelation() {
        assertEquals(UUID_SAMPLE, parse(XML).fiscalId());
        assertThrows(InvalidRequestException.class, () -> parse(XML.replace("88.20", "88.19")));
        assertThrows(InvalidRequestException.class, () -> parse(XML.replace("06600", "06700")));
        assertThrows(InvalidRequestException.class, () -> parse(XML.replace("AAA010101AAA", "BBB010101BBB")));
    }

    @Test void rejectsDoctypes() {
        String doctype = "<!DOCTYPE Comprobante [<!ENTITY x 'ANA PRUEBA'>]>";
        assertThrows(InvalidRequestException.class, () -> parse(doctype + XML.replace("ANA PRUEBA", "&x;")));
    }

    private CfdiDocument parse(String xml) {
        return CfdiDocument.read(new AfterSales.InvoiceUpload(encode("%PDF-test"), encode(xml)), REQUEST, "MXN", TOTAL);
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
