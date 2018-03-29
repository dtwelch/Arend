package com.jetbrains.jetpad.vclang.term.abs;

import com.jetbrains.jetpad.vclang.naming.reference.LocatedReferable;
import com.jetbrains.jetpad.vclang.naming.reference.Referable;

public class IdReferableConverter implements ReferableConverter {
  public static final IdReferableConverter INSTANCE = new IdReferableConverter();

  private IdReferableConverter() { }

  @Override
  public Referable toDataReferable(Referable referable) {
    return referable;
  }

  @Override
  public LocatedReferable toDataLocatedReferable(LocatedReferable referable, boolean isTypecheckable) {
    return referable;
  }
}
