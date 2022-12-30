package org.aspectix.selfoptim.stepped;

public enum RequestType {
    UNDEFINED(0),
    BYTI(0.2f),
    LU(0.4f),
    LULU(0.6f),
    LUCLU(0.8f),
    LUCLUCLU(1.0f);

    private final float requestTypeCode;

    RequestType(float requestTypeCode) {
        this.requestTypeCode = requestTypeCode;
    }

    public float getRequestTypeCode() {
        return requestTypeCode;
    }

}
