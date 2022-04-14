package org.arend.ext.core.expr;

import org.jetbrains.annotations.NotNull;

public interface CoreBoxExpression extends CoreExpression {
  @NotNull CoreExpression getExpression();
}
