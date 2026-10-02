package edu.cit.caaway.supplier;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes LegacySupply's XML documents. Nothing outside this package sees them. */
final class LegacySupplyXml {

    /** A purchase order as LegacySupply describes it (acknowledgement, status and list entries share these fields). */
    record PurchaseOrderDoc(String poNumber, int statusCode, String supplierSku, int qty, String uom, String buyerRef) {
    }

    record ErrorDoc(String code, String message) {
    }

    private LegacySupplyXml() {
    }

    static String authRequest(String clientId, String apiKey) {
        return "<AuthRequest><ClientId>" + escape(clientId) + "</ClientId><ApiKey>" + escape(apiKey)
                + "</ApiKey></AuthRequest>";
    }

    static String purchaseOrder(String supplierSku, int cases, String buyerRef) {
        return "<PurchaseOrder><SupplierSku>" + escape(supplierSku) + "</SupplierSku><Qty>" + cases
                + "</Qty><BuyerRef>" + escape(buyerRef) + "</BuyerRef></PurchaseOrder>";
    }

    static String sessionToken(String xml) {
        String token = text(parse(xml).getDocumentElement(), "SessionToken");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("No SessionToken in response");
        }
        return token;
    }

    /** Parses a PurchaseOrderAck or PurchaseOrderStatus document. */
    static PurchaseOrderDoc purchaseOrderDoc(String xml) {
        return toPurchaseOrder(parse(xml).getDocumentElement());
    }

    /** Parses a PurchaseOrderList document. */
    static List<PurchaseOrderDoc> purchaseOrderList(String xml) {
        NodeList poNumbers = parse(xml).getElementsByTagName("PoNumber");
        List<PurchaseOrderDoc> orders = new ArrayList<>();
        for (int i = 0; i < poNumbers.getLength(); i++) {
            orders.add(toPurchaseOrder((Element) poNumbers.item(i).getParentNode()));
        }
        return orders;
    }

    /** Parses an LSError document; never throws, because error bodies are not always XML. */
    static ErrorDoc error(String xml) {
        try {
            Element root = parse(xml).getDocumentElement();
            return new ErrorDoc(text(root, "Code"), text(root, "Message"));
        } catch (RuntimeException e) {
            return new ErrorDoc(null, null);
        }
    }

    private static PurchaseOrderDoc toPurchaseOrder(Element element) {
        String poNumber = text(element, "PoNumber");
        if (poNumber == null || poNumber.isBlank()) {
            throw new IllegalArgumentException("No PoNumber in response");
        }
        return new PurchaseOrderDoc(poNumber, number(text(element, "StatusCode")), text(element, "SupplierSku"),
                number(text(element, "Qty")), text(element, "Uom"), text(element, "BuyerRef"));
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null); // unreadable bodies are reported by exception, not on stderr
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Response is not a readable XML document", e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    // -1 stands for "missing or not a number", which the status translator treats as unexpected.
    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
