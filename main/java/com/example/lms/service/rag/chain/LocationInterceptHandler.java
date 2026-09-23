package com.example.lms.service.rag.chain;

import com.example.lms.location.LocationService;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Intercepts queries that appear to ask for the user's current location
 * or address and attempts to provide a deterministic answer without
 * invoking the language model.  When no location can be determined
 * the request is passed through to the next link in the chain.
 */
public class LocationInterceptHandler implements ChainLink {
    private static final Logger log = LoggerFactory.getLogger(LocationInterceptHandler.class);

    private final LocationService locationService;

    public LocationInterceptHandler(LocationService locationService) {
        this.locationService = locationService;
    }


    @Override
    public ChainOutcome handle(ChainContext ctx, Chain next) {
        // 위치 인터셉트 기능 비활성화: 항상 다음 체인으로 위임
        return next.proceed(ctx);
    }

}