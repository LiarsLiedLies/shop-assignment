package edu.cit.caaway.supplier;

/** LegacySupply could not be reached or did not answer properly. Trying again later may work. */
class SupplierUnavailableException extends RuntimeException {

    private final boolean rateLimited;

    SupplierUnavailableException(String message, boolean rateLimited) {
        super(message);
        this.rateLimited = rateLimited;
    }

    boolean isRateLimited() {
        return rateLimited;
    }
}
