/**
 * Framework-agnostic ABAC model and OPA client abstractions.
 *
 * <p>No Spring dependency lives here — this module defines the portable core
 * ({@link dev.dmitriikonovalov.opaabac.core.AbacContext},
 * {@link dev.dmitriikonovalov.opaabac.core.AbacDataObject},
 * {@link dev.dmitriikonovalov.opaabac.core.OpaClient}) that the Spring modules build on.
 */
package dev.dmitriikonovalov.opaabac.core;
