package edu.cit.caaway.supplier;

/** LegacySupply understood the request and refused it. Sending it again unchanged will not help. */
class SupplierRejectedException extends RuntimeException {

    private final String code;

    SupplierRejectedException(String code, String message) {
        super(code + " " + message);
        this.code = code;
    }

    String getCode() {
        return code;
    }
}
