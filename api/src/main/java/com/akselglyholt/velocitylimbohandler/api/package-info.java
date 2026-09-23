/**
 * Public Java API for plugins running in the same Velocity proxy as VelocityLimboHandler.
 *
 * <p>Obtain the API through {@link com.akselglyholt.velocitylimbohandler.api.VelocityLimboApi#get}
 * during or after {@code ProxyInitializeEvent}. Mutation methods use typed operational results and
 * owner-scoped leases. Snapshot values are immutable. The API does not guarantee callback or event
 * delivery on a particular thread.</p>
 *
 * <p>See the
 * <a href="https://github.com/AkselGlyholt/velocity-limbo-handler/wiki/Developer-API-v1">Developer API guide</a>
 * for dependency setup, complete examples, event timing, and lifecycle rules.</p>
 */
package com.akselglyholt.velocitylimbohandler.api;
