package com.harudle.notification.presentation;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** read 필드에서만 문자열이나 숫자의 Boolean 변환을 금지한다. */
public final class StrictNotificationReadDeserializer extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.hasToken(JsonToken.VALUE_TRUE)) {
            return true;
        }
        if (parser.hasToken(JsonToken.VALUE_FALSE)) {
            return false;
        }
        return context.reportInputMismatch(Boolean.class, "읽음 상태는 JSON boolean이어야 합니다.");
    }
}
