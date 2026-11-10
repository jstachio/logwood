/** A runnable application demonstrating the Balsa SPI and both logging APIs. */
module io.jstach.balsa.example {
  requires static org.jspecify;
  requires io.jstach.balsa.slf4j;
  provides io.jstach.balsa.spi.BalsaService with
    io.jstach.balsa.example.Main.ApplicationFields,
    io.jstach.balsa.example.Main.Config,
    io.jstach.balsa.example.Main.DefaultFields;
}
