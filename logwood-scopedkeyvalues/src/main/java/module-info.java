/**
 * Experimental ScopedValue storage for Rainbow Gum 0.17.2 ScopedKeyValues.
 * Requires Java 25. No Rainbow Gum backend or SLF4J facade is initialized.
 * Context is contributed automatically through LogwoodService, independently of configuration.
 */
module io.jstach.logwood.scopedkeyvalues {
  requires static org.jspecify;
  requires transitive io.jstach.logwood;
  requires transitive io.jstach.rainbowgum.scopedkeyvalues;
  provides io.jstach.logwood.spi.LogwoodService
    with io.jstach.logwood.scopedkeyvalues.LogwoodScopedKeyValues;
  provides io.jstach.rainbowgum.scopedkeyvalues.spi.ScopedKeyValuesProviderFactory
    with io.jstach.logwood.scopedkeyvalues.LogwoodScopedKeyValues;
}
