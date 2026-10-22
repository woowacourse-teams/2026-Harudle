package com.harudle.auth.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleUserInfo(String sub, String email, String name) {

}
