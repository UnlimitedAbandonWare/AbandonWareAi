package com.example.lms.uaw.presence;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserPresenceFilterTest {

    @Test
    void asyncRequestStartedBeforeDownstreamFailureEndsOnlyOnCompletion() {
        UserPresenceTracker tracker = new UserPresenceTracker();
        UserAbsenceGate absenceGate = absenceGate(tracker);
        UserPresenceFilter filter = userTrafficFilter(tracker);
        MockHttpServletRequest request = asyncRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilterInternal(request, response, (req, res) -> {
            req.startAsync(req, res);
            throw new ServletException("synthetic downstream failure");
        }));

        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        try {
            assertAll(
                    () -> assertEquals(1, tracker.inflight()),
                    () -> assertEquals(1, context.getListeners().size()),
                    () -> assertFalse(absenceGate.isUserAbsentNow()));

            context.complete();

            assertAll(
                    () -> assertEquals(0, tracker.inflight()),
                    () -> assertTrue(absenceGate.isUserAbsentNow()));
        } finally {
            if (request.isAsyncStarted()) {
                context.complete();
            }
        }
    }

    @Test
    void asyncErrorWaitsForCompletionAndEndsTheSameRequestOnlyOnce() throws IOException, ServletException {
        UserPresenceTracker tracker = new UserPresenceTracker();
        UserAbsenceGate absenceGate = absenceGate(tracker);
        UserPresenceFilter filter = userTrafficFilter(tracker);
        MockHttpServletRequest request = asyncRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        tracker.onUserRequestStart(); // A second request remains active throughout this async lifecycle.
        try {
            filter.doFilterInternal(request, response, (req, res) -> req.startAsync(req, res));
            MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
            assertEquals(2, tracker.inflight());
            assertEquals(1, context.getListeners().size());

            AsyncListener listener = context.getListeners().get(0);
            listener.onError(new AsyncEvent(
                    context,
                    request,
                    response,
                    new IOException("synthetic async failure")));
            assertEquals(2, tracker.inflight());

            context.complete();

            assertAll(
                    () -> assertEquals(1, tracker.inflight()),
                    () -> assertFalse(absenceGate.isUserAbsentNow()));
        } finally {
            if (request.isAsyncStarted()) {
                ((MockAsyncContext) request.getAsyncContext()).complete();
            }
            tracker.onUserRequestEnd();
        }
    }

    @Test
    void asyncTimeoutWaitsForCompletionAndEndsTheSameRequestOnlyOnce() throws IOException, ServletException {
        UserPresenceTracker tracker = new UserPresenceTracker();
        UserAbsenceGate absenceGate = absenceGate(tracker);
        UserPresenceFilter filter = userTrafficFilter(tracker);
        MockHttpServletRequest request = asyncRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        tracker.onUserRequestStart(); // A second request remains active throughout this async lifecycle.
        try {
            filter.doFilterInternal(request, response, (req, res) -> req.startAsync(req, res));
            MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
            AsyncListener listener = context.getListeners().get(0);
            assertEquals(2, tracker.inflight());

            listener.onTimeout(new AsyncEvent(context, request, response));
            assertEquals(2, tracker.inflight());

            context.complete();

            assertAll(
                    () -> assertEquals(1, tracker.inflight()),
                    () -> assertFalse(absenceGate.isUserAbsentNow()));
        } finally {
            if (request.isAsyncStarted()) {
                ((MockAsyncContext) request.getAsyncContext()).complete();
            }
            tracker.onUserRequestEnd();
        }
    }

    @Test
    void listenerTransfersToRestartedAsyncContextBeforeItsCompletion() throws IOException, ServletException {
        UserPresenceTracker tracker = new UserPresenceTracker();
        UserPresenceFilter filter = userTrafficFilter(tracker);
        MockHttpServletRequest request = asyncRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        LifecycleAsyncContext first = new LifecycleAsyncContext(request, response);
        LifecycleAsyncContext second = new LifecycleAsyncContext(request, response);

        filter.doFilterInternal(request, response, (req, res) -> {
            req.startAsync(req, res);
            request.setAsyncContext(first);
        });

        request.setAsyncContext(second);
        request.setAsyncStarted(true);
        first.fireStartAsync(second, request, response);

        try {
            assertAll(
                    () -> assertEquals(1, tracker.inflight()),
                    () -> assertEquals(1, second.getListeners().size()));

            second.complete();

            assertEquals(0, tracker.inflight());
        } finally {
            if (request.isAsyncStarted()) {
                second.complete();
            }
        }
    }

    @Test
    void synchronousUserRequestStillEndsWhenTheChainReturns() throws IOException, ServletException {
        UserPresenceTracker tracker = new UserPresenceTracker();
        UserPresenceFilter filter = userTrafficFilter(tracker);
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilterInternal(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> invoked.set(true));

        assertAll(
                () -> assertTrue(invoked.get()),
                    () -> assertEquals(0, tracker.inflight()));
    }

    @Test
    void synchronousDownstreamFailureEndsExactlyOnceAndPreservesOriginalException() {
        UserPresenceTracker tracker = mock(UserPresenceTracker.class);
        UserPresenceFilter filter = userTrafficFilter(tracker);
        ServletException original = new ServletException("synthetic synchronous failure");

        ServletException thrown = assertThrows(ServletException.class, () ->
                filter.doFilterInternal(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        (req, res) -> { throw original; }));

        assertSame(original, thrown);
        verify(tracker).onUserRequestStart();
        verify(tracker, times(1)).onUserRequestEnd();
    }

    @Test
    void nonUserTrafficBypassesPresenceAccountingAndPreservesChainFailure() {
        UserPresenceTracker tracker = mock(UserPresenceTracker.class);
        UserTrafficClassifier classifier = mock(UserTrafficClassifier.class);
        UserPresenceFilter filter = new UserPresenceFilter(tracker, classifier);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ServletException original = new ServletException("synthetic non-user failure");
        when(classifier.isUserTraffic(request)).thenReturn(false);

        ServletException thrown = assertThrows(ServletException.class, () ->
                filter.doFilterInternal(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> { throw original; }));

        assertSame(original, thrown);
        verifyNoInteractions(tracker);
    }

    private static UserPresenceFilter userTrafficFilter(UserPresenceTracker tracker) {
        UserTrafficClassifier classifier = mock(UserTrafficClassifier.class);
        when(classifier.isUserTraffic(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        return new UserPresenceFilter(tracker, classifier);
    }

    private static UserAbsenceGate absenceGate(UserPresenceTracker tracker) {
        UserPresenceProperties properties = new UserPresenceProperties();
        properties.setQuietSeconds(0);
        return new UserAbsenceGate(tracker, properties);
    }

    private static MockHttpServletRequest asyncRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        return request;
    }

    private static final class LifecycleAsyncContext extends MockAsyncContext {

        private LifecycleAsyncContext(
                MockHttpServletRequest request,
                MockHttpServletResponse response) {
            super(request, response);
        }

        private void fireStartAsync(
                AsyncContext next,
                MockHttpServletRequest request,
                MockHttpServletResponse response) throws IOException {
            for (AsyncListener listener : List.copyOf(getListeners())) {
                listener.onStartAsync(new AsyncEvent(next, request, response));
            }
        }
    }

}
