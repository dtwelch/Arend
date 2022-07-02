package org.arend.naming.reference;

import org.arend.ext.reference.DataContainer;
import org.arend.ext.reference.Precedence;
import org.arend.module.ModuleLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// NOTE (D.W): used to encompass/capture both MetaReferable and TCDefReferable
// (we don't have metas the way arend does -- for now at least,
//  so I'm just using TCDefReferable in place of this as an equivalent substitute)
public interface TCReferable extends LocatedReferable, DataContainer {
  @Override
  default @NotNull TCReferable getTypecheckable() {
    return this;
  }

  boolean isTypechecked();

  default boolean isLocalFunction() {
    return false;
  }

  default @NotNull String getDescription() {
    return "";
  }
}
