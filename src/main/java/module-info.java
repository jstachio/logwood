/**
 * A stdout-only, bounded LTSV backend for SLF4J 2 and System.Logger.
 * The only application extension point is BalsaConfig, loaded once with
 * ServiceLoader. No files, sockets, shutdown hooks, output plugins, or format plugins.
 */
module io.jstach.balsa {
  requires transitive org.slf4j;
  exports io.jstach.balsa;
  uses io.jstach.balsa.BalsaConfig;
  provides org.slf4j.spi.SLF4JServiceProvider with io.jstach.balsa.BalsaProvider;
  provides java.lang.System.LoggerFinder with io.jstach.balsa.BalsaFinder;
}
