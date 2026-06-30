package pl.kejlo.zutnik;

import java.io.IOException;

public class SessionExpiredException extends IOException {
    public SessionExpiredException(String message) {
        super(message);
    }
}

