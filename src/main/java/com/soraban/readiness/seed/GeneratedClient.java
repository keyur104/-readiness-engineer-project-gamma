package com.soraban.readiness.seed;

import java.util.List;

/**
 * A business client the firm files on behalf of.
 *
 * <p>{@code sourceSystems} is usually a single entry. Roughly two percent of clients carry
 * two, modelling a firm that switched accounting systems mid-year &mdash; realistic, and
 * more importantly it is the case that stresses tombstone scoping in the importer: when a
 * QuickBooks export arrives declaring coverage of this client, it must <b>not</b> delete
 * the client's spreadsheet-sourced rows merely because they are absent from it. A corpus
 * where every client lives in exactly one file would never catch that.
 *
 * @param clientRef     the identifier the bookkeeper uses; unique within a firm
 * @param legalName     the business name
 * @param ein           the payer's own EIN (for a sole proprietor this is an SSN too)
 * @param addressLine1  street
 * @param city          city
 * @param stateCode     two-letter state
 * @param postalCode    ZIP
 * @param sourceSystems which export file(s) this client's rows appear in
 * @param vendors       the vendors this client pays
 * @param targetRows    how many ledger lines to generate for this client
 */
public record GeneratedClient(
        String clientRef,
        String legalName,
        String ein,
        String addressLine1,
        String city,
        String stateCode,
        String postalCode,
        List<String> sourceSystems,
        List<GeneratedVendor> vendors,
        long targetRows
) {

    /** Header row for {@code clients.csv}. */
    public static final String[] HEADERS = {
            "client_ref", "legal_name", "client_ein", "address_line1",
            "city", "state_code", "postal_code", "default_source_system"
    };

    public String[] toCsvRow() {
        return new String[]{
                clientRef, legalName, ein, addressLine1,
                city, stateCode, postalCode, sourceSystems.getFirst()
        };
    }

    /** True when this client's rows are split across more than one export file. */
    public boolean switchedSystems() {
        return sourceSystems.size() > 1;
    }
}
