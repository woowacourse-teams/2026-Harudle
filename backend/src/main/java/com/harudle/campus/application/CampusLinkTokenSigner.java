package com.harudle.campus.application;

public interface CampusLinkTokenSigner {

    String sign(CampusLinkClaims claims);
}
