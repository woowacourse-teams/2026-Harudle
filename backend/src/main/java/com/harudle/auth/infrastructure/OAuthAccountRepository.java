package com.harudle.auth.infrastructure;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderSubject(OAuthProvider provider, String providerSubject);

    Optional<OAuthAccount> findByUser_Id(UUID userId);

}
