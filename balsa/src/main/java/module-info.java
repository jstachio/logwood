/**
 * A stdout-only, bounded LTSV backend for SLF4J 2 and System.Logger.
 * The only application service type is BalsaService, loaded once with
 * ServiceLoader. No files, sockets, shutdown hooks, output plugins, or format plugins.
 */
module io.jstach.balsa {
  requires static transitive org.jspecify;
  requires static org.slf4j;
  exports io.jstach.balsa.spi;
  uses io.jstach.balsa.spi.BalsaService;
  provides java.lang.System.LoggerFinder with io.jstach.balsa.BalsaFinder;
}
