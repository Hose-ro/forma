package com.olvenio.aftersales.application;

import com.olvenio.shared.domain.InvalidRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

record CfdiDocument(UUID fiscalId, byte[] pdf, byte[] xml) {
    private static final int MAX_FILE = 1024 * 1024;
    private static final String CFDI = "http://www.sat.gob.mx/cfd/4";
    private static final String TIMBRE = "http://www.sat.gob.mx/TimbreFiscalDigital";

    static CfdiDocument read(AfterSales.InvoiceUpload upload, AfterSales.InvoiceView invoice,
                             String currency, BigDecimal total) {
        byte[] pdf = decode(upload.pdf());
        byte[] xml = decode(upload.xml());
        if (pdf.length < 5 || !"%PDF-".equals(new String(pdf, 0, 5, StandardCharsets.US_ASCII)))
            throw new InvalidRequestException("El archivo PDF no tiene un formato válido.");
        try {
            var factory = DocumentBuilderFactory.newDefaultNSInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Element document = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            var receptor = (Element) document.getElementsByTagNameNS(CFDI, "Receptor").item(0);
            var stamp = (Element) document.getElementsByTagNameNS(TIMBRE, "TimbreFiscalDigital").item(0);
            if (!CFDI.equals(document.getNamespaceURI()) || !"Comprobante".equals(document.getLocalName())
                    || !"4.0".equals(document.getAttribute("Version")) || !"I".equals(document.getAttribute("TipoDeComprobante"))
                    || receptor == null || stamp == null)
                throw new InvalidRequestException("Adjunta un XML de ingreso CFDI 4.0 timbrado.");
            if (!invoice.taxId().equals(receptor.getAttribute("Rfc"))
                    || !invoice.legalName().equalsIgnoreCase(receptor.getAttribute("Nombre"))
                    || !invoice.postalCode().equals(receptor.getAttribute("DomicilioFiscalReceptor"))
                    || !invoice.taxRegime().equals(receptor.getAttribute("RegimenFiscalReceptor"))
                    || !invoice.cfdiUse().equals(receptor.getAttribute("UsoCFDI"))
                    || !currency.equals(document.getAttribute("Moneda"))
                    || total.compareTo(new BigDecimal(document.getAttribute("Total"))) != 0)
                throw new InvalidRequestException("Los datos fiscales o el total del XML no coinciden con esta solicitud.");
            return new CfdiDocument(UUID.fromString(stamp.getAttribute("UUID")), pdf, xml);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Unable to configure CFDI parser", e);
        } catch (SAXException | IOException | IllegalArgumentException e) {
            throw new InvalidRequestException("El XML no contiene un comprobante válido.");
        }
    }

    private static byte[] decode(String encoded) {
        try {
            byte[] file = Base64.getDecoder().decode(encoded);
            if (file.length > MAX_FILE || file.length == 0)
                throw new InvalidRequestException("Cada archivo debe pesar hasta 1 MB.");
            return file;
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("No pudimos leer los archivos de la factura.");
        }
    }
}
