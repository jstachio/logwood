/**
 * Minimal console logging. Fields are escaped, custom labels are namespaced, records and
 * traversal are bounded, and MDC stores nothing by default. Global settings initialize
 * once. Each logger resolves its own threshold at first use and retains it in the
 * caller-owned logger object. See BalsaConfig for the application SPI.
 */
package io.jstach.balsa;
