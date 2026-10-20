package ru.ba6kir.tradelab.fundamentals.application.port;

import java.util.Optional;
import ru.ba6kir.tradelab.fundamentals.domain.model.AssetIdentity;

public interface AssetIdentityResolver {
    Optional<AssetIdentity> resolve(String instrumentUid);
}
