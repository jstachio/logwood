/**
 * Optional named-module registration for SLF4J. The core Balsa module can run
 * without SLF4J because its descriptor does not declare an SLF4J service type.
 */
module io.jstach.balsa.slf4j {
  requires transitive io.jstach.balsa;
  requires transitive org.slf4j;
  provides org.slf4j.spi.SLF4JServiceProvider with io.jstach.balsa.slf4j.Slf4jProvider;
}
