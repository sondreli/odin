/**
 * Odin mobile app entry point.
 * Load the shadow-cljs bundle and register the app with React Native.
 * Build the bundle first: npx shadow-cljs compile mobile (or watch mobile)
 */
const mod = require('./out/index.js');
if (typeof mod.init === 'function') {
  mod.init();
} else {
  console.warn('Odin: init not found on bundle. Run: npx shadow-cljs compile mobile');
}
