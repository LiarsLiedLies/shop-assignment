package edu.cit.caaway.supplier;

import edu.cit.caaway.supplier.LegacySupplyXml.PurchaseOrderDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplierTranslationTest {

    private final SupplierCatalog catalog = new SupplierCatalog();

    @Test
    void unitsAreRoundedUpToWholeCases() {
        SupplierCatalog.Item cable = catalog.find("PROD-001").orElseThrow(); // 24 per case
        SupplierCatalog.Item mouse = catalog.find("PROD-002").orElseThrow(); // 6 per case

        assertEquals(1, cable.casesFor(10));
        assertEquals(1, cable.casesFor(24));
        assertEquals(2, cable.casesFor(25));
        assertEquals(2, mouse.casesFor(10));
        assertEquals(12, mouse.unitsIn(mouse.casesFor(10)));
    }

    @Test
    void casesNeverExceedWhatLegacySupplyAccepts() {
        SupplierCatalog.Item mouse = catalog.find("PROD-002").orElseThrow();
        assertEquals(99, mouse.casesFor(10_000));
    }

    @Test
    void unknownProductHasNoSupplierItem() {
        assertTrue(catalog.find("PROD-999").isEmpty());
    }

    @Test
    void statusCodesMapToOurOwnStatus() {
        assertEquals(ReorderStatus.PLACED, LegacySupplyStatus.toReorderStatus(10));
        assertEquals(ReorderStatus.PICKING, LegacySupplyStatus.toReorderStatus(20));
        assertEquals(ReorderStatus.SHIPPED, LegacySupplyStatus.toReorderStatus(30));
        assertEquals(ReorderStatus.DELIVERED, LegacySupplyStatus.toReorderStatus(40));
        assertEquals(ReorderStatus.CANCELLED, LegacySupplyStatus.toReorderStatus(90));
        assertEquals(ReorderStatus.NEEDS_REVIEW, LegacySupplyStatus.toReorderStatus(55));
        assertEquals(ReorderStatus.NEEDS_REVIEW, LegacySupplyStatus.toReorderStatus(-1));
    }

    @Test
    void readsStatusDocument() {
        PurchaseOrderDoc doc = LegacySupplyXml.purchaseOrderDoc("""
                <?xml version="1.0" encoding="UTF-8"?>
                <PurchaseOrderStatus><PoNumber>PO-100036</PoNumber><StatusCode>90</StatusCode>\
                <SupplierSku>LPB-9626</SupplierSku><Qty>1</Qty><Uom>CS</Uom><BuyerRef>RO-7</BuyerRef>\
                <CreatedAt>2026-09-24T11:35:27.044Z</CreatedAt></PurchaseOrderStatus>""");

        assertEquals("PO-100036", doc.poNumber());
        assertEquals(90, doc.statusCode());
        assertEquals("CS", doc.uom());
        assertEquals("RO-7", doc.buyerRef());
    }

    @Test
    void readsOrderListAndErrors() {
        List<PurchaseOrderDoc> two = LegacySupplyXml.purchaseOrderList("""
                <PurchaseOrderList><Count>2</Count>\
                <PurchaseOrder><PoNumber>PO-1</PoNumber><StatusCode>40</StatusCode><Qty>1</Qty></PurchaseOrder>\
                <PurchaseOrder><PoNumber>PO-2</PoNumber><StatusCode>10</StatusCode><Qty>1</Qty></PurchaseOrder>\
                </PurchaseOrderList>""");
        assertEquals(List.of("PO-1", "PO-2"), two.stream().map(PurchaseOrderDoc::poNumber).toList());
        assertTrue(LegacySupplyXml.purchaseOrderList("<PurchaseOrderList><Count>0</Count></PurchaseOrderList>").isEmpty());

        assertEquals("E-AUTH-07", LegacySupplyXml.error(
                "<LSError><Code>E-AUTH-07</Code><Message>Session not valid.</Message></LSError>").code());
        assertEquals(null, LegacySupplyXml.error("<html>Bad gateway").code());
    }

    @Test
    void buyerRefIsEscapedInTheOrderDocument() {
        assertEquals("<PurchaseOrder><SupplierSku>LPB-1517</SupplierSku><Qty>2</Qty><BuyerRef>RO-1&amp;2</BuyerRef></PurchaseOrder>",
                LegacySupplyXml.purchaseOrder("LPB-1517", 2, "RO-1&2"));
    }
}
