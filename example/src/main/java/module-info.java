/** A runnable application demonstrating the Logwood SPI and both logging APIs. */
module io.jstach.logwood.example {
  requires static org.jspecify;
  requires io.jstach.logwood.slf4j;
  provides io.jstach.logwood.spi.LogwoodService with
    io.jstach.logwood.example.Main.ApplicationFields,
    io.jstach.logwood.example.Main.Config,
    io.jstach.logwood.example.Main.DefaultFields;
}
