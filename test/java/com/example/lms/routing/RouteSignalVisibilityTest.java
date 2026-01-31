package com.example.lms.routing;

import com.example.lms.service.routing.RouteSignal;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test verifying that the {@link RouteSignal} record is publicly
 * accessible from outside its package.  The test attempts to construct
 * a new instance of the record using its canonical constructor.  If
 * compilation succeeds and the instance is non-null, the record is
 * deemed visible.
 */
public class RouteSignalVisibilityTest {

    @Test
    public void canInstantiateRouteSignal() {
        // Attempt to create a new RouteSignal from another package
        RouteSignal sig = new RouteSignal(
                0.0,
                0.0,
                0.0,
                0.0,
                null,
                null,
                0,
                null,
                null,
                false,
                false
        );
        assertNotNull(sig);
    }
}