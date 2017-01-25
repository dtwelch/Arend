package com.jetbrains.jetpad.vclang.typechecking.typeclass;

import com.jetbrains.jetpad.vclang.core.definition.Definition;
import com.jetbrains.jetpad.vclang.core.expr.DefCallExpression;
import com.jetbrains.jetpad.vclang.core.expr.Expression;
import com.jetbrains.jetpad.vclang.core.expr.visitor.NormalizeVisitor;
import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.util.Pair;

import java.util.HashMap;
import java.util.Map;

public class GlobalInstancePool implements ClassViewInstancePool {
  private final Map<Pair<Definition, Abstract.ClassView>, Expression> myViewPool = new HashMap<>();

  @Override
  public Expression getInstance(Expression classifyingExpression, Abstract.ClassView classView) {
    DefCallExpression defCall = classifyingExpression.normalize(NormalizeVisitor.Mode.WHNF).toDefCall();
    if (defCall == null) {
      return null;
    }
    return myViewPool.get(new Pair<>(defCall.getDefinition(), classView));
  }

  public Expression getInstance(Definition definition, Abstract.ClassView classView) {
    return myViewPool.get(new Pair<>(definition, classView));
  }

  public void addInstance(Definition classifyingDefinition, Abstract.ClassView classView, Expression instance) {
    myViewPool.put(new Pair<>(classifyingDefinition, classView), instance);
  }
}
