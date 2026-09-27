# -*- coding: utf-8 -*-
"""AdminTokenGuardInterceptorTest: 관리자 세션 브릿징 회귀 테스트 2건을 디스크에 직접 삽입."""
p = r'src/test/java/com/example/lms/security/AdminTokenGuardInterceptorTest.java'
d = open(p, 'rb').read()

anchor = b'    @Test\n    void adminTokenHeaderAccepts()'
assert d.count(anchor) == 1, d.count(anchor)

tests = '''    @Test
    void authenticatedAdminSessionPassesWithoutToken() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", true, false, "prod");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpServletRequest request = request("/api/settings/model");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void authenticatedNonAdminSessionStillDenied() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", true, false, "prod");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        MockHttpServletRequest request = request("/api/settings/model");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

'''.encode('utf-8')

if b'authenticatedAdminSessionPassesWithoutToken' in d:
    print('already present')
else:
    d = d.replace(anchor, tests + anchor, 1)
    open(p, 'wb').write(d)
    d2 = open(p, 'rb').read()
    print('inserted:', b'authenticatedAdminSessionPassesWithoutToken' in d2, 'len:', len(d2))
