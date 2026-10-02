package edu.cit.caaway.channel;

/** Tiangge understood the request and refused it (a 4xx answer). Repeating it will not help. */
class TianggeRejectedException extends RuntimeException {

    private final int status;
    private final String error;

    TianggeRejectedException(int status, String error, String message) {
        super("HTTP " + status + " " + error + ": " + message);
        this.status = status;
        this.error = error;
    }

    int getStatus() {
        return status;
    }

    String getError() {
        return error;
    }
}
