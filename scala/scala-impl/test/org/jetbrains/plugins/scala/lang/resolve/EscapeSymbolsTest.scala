package org.jetbrains.plugins.scala.lang.resolve

/**
  * @author Anton Yalyshev
  * @since 07.09.18.
  */
class EscapeSymbolsTest extends SimpleResolveTest("escapeSymbols") {
  def testSCL7704() = doTest()
}
