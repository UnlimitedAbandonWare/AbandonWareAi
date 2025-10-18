/**
 * geo.js – Browser geolocation helper functions
 *
 * This module provides a small wrapper around the browser's Geolocation API
 * to watch the user's position and send updates to the server.  The
 * returned watch ID can be used to cancel watching when the user toggles
 * location off.  When the Geolocation API is unavailable the function
 * simply returns undefined.
 */

export function startWatch(sendFn) {
  if (typeof navigator === 'undefined' || !navigator.geolocation) {
    console.warn('Geolocation API not available');
    return undefined;
  }
  return navigator.geolocation.watchPosition(
    pos => {
      try {
        const payload = {
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
          timestampMs: Date.now(),
          source: 'browser'
        };
        sendFn(payload);
      } catch (err) {
        console.warn('Failed to send location', err);
      }
    },
    err => {
      console.warn('Geolocation error', err);
    },
    { enableHighAccuracy: true, maximumAge: 10000, timeout: 10000 }
  );
}