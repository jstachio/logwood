/**
 * A stdout-only, bounded LTSV and JSON Lines backend for SLF4J 2 and System.Logger.
 * The only application service type is LogwoodService, loaded once with
 * ServiceLoader. No files, sockets, shutdown hooks, output plugins, or format plugins.
 */
module io.jstach.logwood {
  requires static transitive org.jspecify;
  requires static org.slf4j;
  exports io.jstach.logwood.spi;
  uses io.jstach.logwood.spi.LogwoodService;
  provides java.lang.System.LoggerFinder with io.jstach.logwood.LogwoodFinder;
}
