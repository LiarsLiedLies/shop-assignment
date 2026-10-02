package edu.cit.caaway.channel;

/** Tiangge could not be reached or kept failing. The same call can be repeated later. */
class TianggeUnavailableException extends RuntimeException {

    TianggeUnavailableException(String message) {
        super(message);
    }
}
