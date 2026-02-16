package pl.kejlo.mzutv2;

import java.io.IOException;

public class SessionExpiredException extends IOException {
    public SessionExpiredException(String message) {
        super(message);
    }
}

