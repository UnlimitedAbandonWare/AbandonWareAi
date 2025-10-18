// login-delegate.js — Delegated login trigger handler for mobile/desktop
// This script registers a single delegated click handler on the document
// to handle any current or future login triggers (elements with
// id="login-popover-trigger" or the [data-login-trigger] attribute).
// When a trigger is clicked, the script attempts to render the login
// popover by cloning the #login-form-template element.  If the template
// is missing it falls back to navigating to /login.  Successful form
// submissions reload the page; failures redirect to the standard login
// page.  A guard ensures the handler is only registered once.

(function() {
  // Do not register more than once
  if (window.__awLoginDelegated) return;
  window.__awLoginDelegated = true;
  // Capture phase allows this handler to run before any other click handler
  document.addEventListener('click', function(ev) {
    var trigger = ev.target && ev.target.closest && ev.target.closest('#login-popover-trigger, [data-login-trigger]');
    if (!trigger) return;
    ev.preventDefault();
    var template = document.getElementById('login-form-template');
    if (!template) {
      // Template missing → navigate to standard login page
      window.location.assign('/login');
      return;
    }
    // Build overlay element and clone template content
    var overlay = document.createElement('div');
    overlay.className = 'aw-login-overlay';
    overlay.appendChild(template.content.cloneNode(true));
    document.body.appendChild(overlay);
    // Cancel button hides overlay
    var cancelBtn = overlay.querySelector('[data-login-cancel]');
    if (cancelBtn) {
      cancelBtn.addEventListener('click', function() {
        overlay.remove();
      });
    }
    // Handle form submission via fetch
    var form = overlay.querySelector('#popoverLoginForm');
    if (form) {
      form.addEventListener('submit', function(e) {
        e.preventDefault();
        var fd = new FormData(form);
        var params = new URLSearchParams(fd);
        fetch('/login', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: params.toString()
        }).then(function(res) {
          // Redirects or OK indicate success
          if (res.redirected) {
            window.location.href = res.url;
            return;
          }
          if (res.ok) {
            window.location.reload();
            return;
          }
          if (res.status === 401 || res.status === 403) {
            window.location.assign('/login?from=overlay');
            return;
          }
          // Unexpected responses fallback to login page
          window.location.assign('/login');
        }).catch(function() {
          window.location.assign('/login');
        });
      });
    }
  }, true);
})();