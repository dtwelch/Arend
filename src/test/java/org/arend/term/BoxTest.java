package org.arend.term;

import org.arend.Matchers;
import org.arend.typechecking.TypeCheckingTestCase;
import org.arend.typechecking.error.local.NotEqualExpressionsError;
import org.junit.Test;

public class BoxTest extends TypeCheckingTestCase {
  @Test
  public void notProp() {
    typeCheckDef("\\func test (A : \\Type) (a : A) => \\box a", 1);
  }

  @Test
  public void comparisonTest() {
    typeCheckDef("\\func test (A : \\Prop) (a a' : A) : (\\box a) = (\\box a') => idp");
  }

  @Test
  public void comparisonTest2() {
    typeCheckModule(
      "\\lemma f {A : \\Prop} (a : A) => a\n" +
      "\\func test (A : \\Prop) (a a' : A) : f a = \\box a' => idp");
  }

  @Test
  public void comparisonTest3() {
    typeCheckDef("\\func test (A : \\Prop) (a : A) : a = \\box a => idp", 1);
    assertThatErrorsAre(Matchers.typecheckingError(NotEqualExpressionsError.class));
  }
}
