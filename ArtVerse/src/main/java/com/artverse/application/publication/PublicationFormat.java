package com.artverse.application.publication;

import com.artverse.common.BusinessException;

public enum PublicationFormat {
    MANGA("manga"),
    NOVEL("novel");

    private final String apiValue;

    PublicationFormat(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static PublicationFormat fromApiValue(String value) {
        String resolved = value == null ? MANGA.apiValue : value;
        for (PublicationFormat format : values()) {
            if (format.apiValue.equals(resolved)) {
                return format;
            }
        }
        throw new BusinessException(400, "Invalid publication format");
    }
}
