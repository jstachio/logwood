/**
 * Optional named-module registration for SLF4J. The core Logwood module can run
 * without SLF4J because its descriptor does not declare an SLF4J service type.
 */
module io.jstach.logwood.slf4j {
  requires static org.jspecify;
  requires transitive io.jstach.logwood;
  requires transitive org.slf4j;
  provides org.slf4j.spi.SLF4JServiceProvider with io.jstach.logwood.slf4j.Slf4jProvider;
}
