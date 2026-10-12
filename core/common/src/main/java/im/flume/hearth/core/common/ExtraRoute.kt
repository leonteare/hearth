// The intent extra a notification uses to open a page; here so every module that posts notifications can name it.
package im.flume.hearth.core.common

/** Intent extra naming a page to open (see `DomainContainer.pendingRoute`), e.g. from a notification. */
const val EXTRA_ROUTE = "im.flume.hearth.ROUTE" // fqn-ok
