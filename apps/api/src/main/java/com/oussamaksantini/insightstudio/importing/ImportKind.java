package com.oussamaksantini.insightstudio.importing;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a file imports (docs/catalog-imports-contract.md §1). The fields are listed in the
 * template's column order; their names are the template's headers.
 */
public enum ImportKind {

    SALES(ImportService.MAX_ROWS, List.of(
            ImportField.required("store_code", "Store code",
                    "Code of an existing store of the business, e.g. BOS (case-sensitive).",
                    "store", "storeid", "storeno", "storenumber", "shop", "shopcode", "location", "locationcode",
                    "branch", "branchcode"),
            ImportField.required("receipt_number", "Receipt number",
                    "Receipt (order) number, 1 to 40 characters; rows with the same store and receipt form one sale.",
                    "receipt", "receiptno", "receiptid", "order", "orderid", "orderno", "ordernumber", "transaction",
                    "transactionid", "invoice", "invoiceno", "invoicenumber", "ticket", "ticketnumber"),
            ImportField.required("sold_at", "Sold at",
                    "ISO-8601 date-time, e.g. 2026-09-01T14:30:00-04:00; without an offset it is in the business's time zone.",
                    "date", "datetime", "time", "timestamp", "saledate", "saletime", "orderdate", "solddate", "soldon",
                    "transactiondate"),
            ImportField.required("sku", "SKU",
                    "SKU of an existing product of the business, e.g. TOP-001 (case-sensitive).",
                    "productsku", "productcode", "productid", "itemcode", "itemid", "item", "product", "article",
                    "articlenumber"),
            ImportField.required("quantity", "Quantity", "Whole number greater than 0.",
                    "qty", "units", "unitssold", "count"),
            ImportField.required("unit_price", "Unit price",
                    "Price actually charged per unit, at least 0 with at most 2 decimals, e.g. 24.50.",
                    "price", "priceeach", "saleprice", "sellingprice", "pricecharged"))),

    STORES(ImportService.MAX_CATALOG_ROWS, List.of(
            ImportField.required("code", "Store code",
                    "1 to 50 letters, digits, '.', '_' or '-', starting with a letter or digit; identifies the store.",
                    "storecode", "storeid", "store", "storeno", "storenumber", "id", "shopcode", "shopid",
                    "locationcode", "locationid", "branchcode", "branchid"),
            ImportField.required("name", "Store name", "At most 200 characters.",
                    "storename", "shopname", "locationname", "branchname", "title"),
            ImportField.optional("city", "City",
                    "At most 100 characters. Mapped and empty clears it; not mapped leaves it unchanged on update.",
                    "town", "storecity", "location", "municipality"))),

    PRODUCTS(ImportService.MAX_CATALOG_ROWS, List.of(
            ImportField.required("sku", "SKU",
                    "1 to 50 letters, digits, '.', '_' or '-', starting with a letter or digit; identifies the product.",
                    "productsku", "productcode", "productid", "itemcode", "itemid", "code", "id", "article",
                    "articlenumber", "reference", "ref"),
            ImportField.required("name", "Product name", "At most 200 characters.",
                    "productname", "itemname", "product", "item", "title"),
            ImportField.required("category", "Category", "At most 100 characters.",
                    "productcategory", "categoryname", "department", "group", "productgroup", "type", "producttype",
                    "family"),
            ImportField.required("list_price", "List price",
                    "Current catalogue price, at least 0 with at most 2 decimals, e.g. 24.50. Past sales keep the price charged.",
                    "price", "listprice", "retailprice", "unitprice", "msrp", "rrp", "sellingprice", "saleprice")));

    private final int maxRows;
    private final List<ImportField> fields;

    ImportKind(int maxRows, List<ImportField> fields) {
        this.maxRows = maxRows;
        this.fields = fields;
    }

    /** {@code sales}, {@code stores} or {@code products}, as in paths, JSON and {@code import_batches.kind}. */
    @JsonValue
    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ImportKind> fromParam(String value) {
        return Arrays.stream(values()).filter(k -> k.param().equals(value)).findFirst();
    }

    /** Data rows accepted in one file. */
    int maxRows() {
        return maxRows;
    }

    List<ImportField> fields() {
        return fields;
    }

    List<String> fieldNames() {
        return fields.stream().map(ImportField::name).toList();
    }

    Optional<ImportField> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
