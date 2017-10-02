package com.jetbrains.jetpad.vclang.typechecking;

import com.jetbrains.jetpad.vclang.core.context.binding.Binding;
import com.jetbrains.jetpad.vclang.core.context.binding.LevelVariable;
import com.jetbrains.jetpad.vclang.core.context.binding.inference.InferenceLevelVariable;
import com.jetbrains.jetpad.vclang.core.context.param.DependentLink;
import com.jetbrains.jetpad.vclang.core.definition.*;
import com.jetbrains.jetpad.vclang.core.expr.*;
import com.jetbrains.jetpad.vclang.core.expr.visitor.NormalizeVisitor;
import com.jetbrains.jetpad.vclang.core.sort.Level;
import com.jetbrains.jetpad.vclang.core.sort.Sort;
import com.jetbrains.jetpad.vclang.error.Error;
import com.jetbrains.jetpad.vclang.error.doc.DocFactory;
import com.jetbrains.jetpad.vclang.naming.reference.GlobalReferable;
import com.jetbrains.jetpad.vclang.naming.reference.Referable;
import com.jetbrains.jetpad.vclang.naming.scope.MergeScope;
import com.jetbrains.jetpad.vclang.naming.scope.NamespaceScope;
import com.jetbrains.jetpad.vclang.naming.scope.Scope;
import com.jetbrains.jetpad.vclang.term.concrete.Concrete;
import com.jetbrains.jetpad.vclang.typechecking.error.local.*;
import com.jetbrains.jetpad.vclang.typechecking.visitor.CheckTypeVisitor;

import java.util.List;

public class TypeCheckingDefCall {
  private final CheckTypeVisitor myVisitor;
  private ClassDefinition myThisClass;
  private Binding myThisBinding;

  public TypeCheckingDefCall(CheckTypeVisitor visitor) {
    myVisitor = visitor;
  }

  public ClassDefinition getThisClass() {
    return myThisClass;
  }

  public Binding getThisBinding() {
    return myThisBinding;
  }

  public void setThis(ClassDefinition thisClass, Binding thisBinding) {
    myThisClass = thisClass;
    myThisBinding = thisBinding;
  }

  private Definition getTypeCheckedDefinition(GlobalReferable definition, Concrete.Expression expr) {
    /* TODO[abstract]: I'm not sure what to do with this. Maybe eliminate class views and their fields during name resolving
    while (definition instanceof Concrete.ClassView) {
      definition = (GlobalReferable) ((Concrete.ClassView) definition).getUnderlyingClass().getReferent();
    }
    if (definition instanceof Concrete.ClassViewField) {
      definition = (GlobalReferable) ((Concrete.ClassViewField) definition).getUnderlyingField();
    }
    */
    Definition typeCheckedDefinition = myVisitor.getTypecheckingState().getTypechecked(definition);
    if (typeCheckedDefinition == null) {
      throw new IllegalStateException("Internal error: definition " + definition.textRepresentation() + " was not type checked");
    }
    if (!typeCheckedDefinition.status().headerIsOK()) {
      myVisitor.getErrorReporter().report(new HasErrors(Error.Level.ERROR, definition, expr));
      return null;
    } else {
      if (typeCheckedDefinition.status() == Definition.TypeCheckingStatus.BODY_HAS_ERRORS) {
        myVisitor.getErrorReporter().report(new HasErrors(Error.Level.WARNING, definition, expr));
      }
      return typeCheckedDefinition;
    }
  }

  public CheckTypeVisitor.TResult typeCheckDefCall(Concrete.ReferenceExpression expr) {
    Concrete.Expression left = expr.getExpression();
    GlobalReferable resolvedDefinition = expr.getReferent() instanceof GlobalReferable ? (GlobalReferable) expr.getReferent() : null;
    Definition typeCheckedDefinition = null;
    if (resolvedDefinition != null) {
      typeCheckedDefinition = getTypeCheckedDefinition(resolvedDefinition, expr);
      if (typeCheckedDefinition == null) {
        return null;
      }
    }

    CheckTypeVisitor.Result result = null;
    if (left != null && (typeCheckedDefinition == null || (!(left instanceof Concrete.ReferenceExpression) && !(left instanceof Concrete.ModuleCallExpression)))) {
      result = left.accept(myVisitor, null);
      if (result == null) {
        return null;
      }
    }

    // No left-hand side
    if (result == null && typeCheckedDefinition != null) {
      Expression thisExpr = null;
      if (typeCheckedDefinition.getThisClass() != null) {
        if (myThisClass != null) {
          thisExpr = findParent(myThisClass, typeCheckedDefinition, new ReferenceExpression(myThisBinding));
        }

        if (thisExpr == null) {
          /* TODO[abstract]
          if (resolvedDefinition instanceof Concrete.ClassViewField) {
            assert typeCheckedDefinition instanceof ClassField;
            Concrete.ClassView ownClassView = ((Concrete.ClassViewField) resolvedDefinition).getOwnView();
            ClassCallExpression classCall = new ClassCallExpression(typeCheckedDefinition.getThisClass(), Sort.generateInferVars(myVisitor.getEquations(), expr));
            thisExpr = new InferenceReferenceExpression(new TypeClassInferenceVariable<>(typeCheckedDefinition.getThisClass().getName() + "-inst", classCall, ownClassView, true, expr, myVisitor.getAllBindings()), myVisitor.getEquations());
          } else { */
            LocalTypeCheckingError error;
            if (myThisClass != null) {
              error = new NotAvailableDefinitionError(typeCheckedDefinition, expr);
            } else {
              error = new LocalTypeCheckingError("Non-static definitions are not allowed in a static context", expr);
            }
            myVisitor.getErrorReporter().report(error);
            return null;
          // }
        }
      }

      return makeResult(typeCheckedDefinition, thisExpr, expr);
    }

    if (left == null) {
      // TODO: Create a separate expression for local variables
      throw new IllegalStateException();
    }

    String name = expr.getReferent().textRepresentation();

    // Field call
    Expression type = result.type.normalize(NormalizeVisitor.Mode.WHNF);
    if (type.isInstance(ClassCallExpression.class)) {
      ClassDefinition classDefinition = type.cast(ClassCallExpression.class).getDefinition();

      if (typeCheckedDefinition == null) {
        GlobalReferable member = myVisitor.getDynamicNamespaceProvider().forReferable(classDefinition.getReferable()).resolveName(name);
        if (member == null) {
          myVisitor.getErrorReporter().report(new MemberNotFoundError(classDefinition, name, false, expr));
          return null;
        }
        typeCheckedDefinition = getTypeCheckedDefinition(member, expr);
        if (typeCheckedDefinition == null) {
          return null;
        }
      } else {
        if (!(typeCheckedDefinition instanceof ClassField && classDefinition.getFields().contains(typeCheckedDefinition))) {
          throw new IllegalStateException("Internal error: field " + typeCheckedDefinition + " does not belong to class " + classDefinition);
        }
      }

      if (typeCheckedDefinition.getThisClass() == null) {
        myVisitor.getErrorReporter().report(new LocalTypeCheckingError("Static definitions are not allowed in a non-static context", expr));
        return null;
      }
      if (!classDefinition.isSubClassOf(typeCheckedDefinition.getThisClass())) {
        if (!type.isInstance(ErrorExpression.class)) {ClassCallExpression classCall = new ClassCallExpression(typeCheckedDefinition.getThisClass(), Sort.generateInferVars(myVisitor.getEquations(), expr));
        myVisitor.getErrorReporter().report( new TypeMismatchError(DocFactory.termDoc(classCall), DocFactory.termDoc(type), left));}
        return null;
      }

      return makeResult(typeCheckedDefinition, result.expression, expr);
    }

    int lamSize = 0;
    Expression lamExpr = result.expression;
    while (lamExpr.isInstance(LamExpression.class)) {
      lamSize += DependentLink.Helper.size(lamExpr.cast(LamExpression.class).getParameters());
      lamExpr = lamExpr.cast(LamExpression.class).getBody();
    }

    // Constructor call
    DataCallExpression dataCall = lamExpr.checkedCast(DataCallExpression.class);
    if (dataCall != null) {
      DataDefinition dataDefinition = dataCall.getDefinition();
      List<? extends Expression> args = dataCall.getDefCallArguments();
      if (result.expression.isInstance(LamExpression.class)) {
        args = args.subList(0, args.size() - lamSize);
      }

      Constructor constructor;
      if (typeCheckedDefinition == null) {
        constructor = dataDefinition.getConstructor(name);
        if (constructor == null && !args.isEmpty()) {
          myVisitor.getErrorReporter().report(new MissingConstructorError(name, dataDefinition, expr));
          return null;
        }
        if (constructor != null && !constructor.status().headerIsOK()) {
          myVisitor.getErrorReporter().report(new HasErrors(Error.Level.ERROR, constructor.getReferable(), expr));
          return null;
        }
        if (constructor != null && constructor.status() == Definition.TypeCheckingStatus.BODY_HAS_ERRORS) {
          myVisitor.getErrorReporter().report(new HasErrors(Error.Level.WARNING, constructor.getReferable(), expr));
        }
      } else {
        if (typeCheckedDefinition instanceof Constructor && dataDefinition.getConstructors().contains(typeCheckedDefinition)) {
          constructor = (Constructor) typeCheckedDefinition;
        } else {
          throw new IllegalStateException("Internal error: " + typeCheckedDefinition + " is not a constructor of " + dataDefinition);
        }
      }

      if (constructor != null) {
        CheckTypeVisitor.TResult result1 = CheckTypeVisitor.DefCallResult.makeTResult(expr, constructor, dataCall.getSortArgument(), null);
        return args.isEmpty() ? result1 : ((CheckTypeVisitor.DefCallResult) result1).applyExpressions(args);
      }
    }

    Expression thisExpr = null;
    final Definition leftDefinition;
    Referable member = null;
    ClassCallExpression classCall = result.expression.checkedCast(ClassCallExpression.class);
    if (classCall != null) {
      // Static call
      leftDefinition = classCall.getDefinition();
      ClassField parentField = classCall.getDefinition().getEnclosingThisField();
      if (parentField != null) {
        thisExpr = classCall.getImplementation(parentField, null /* it should be OK */);
      }
      if (typeCheckedDefinition == null) {
        member = myVisitor.getStaticNamespaceProvider().forReferable(leftDefinition.getReferable()).resolveName(name);
        if (member == null) {
          myVisitor.getErrorReporter().report(new MemberNotFoundError(leftDefinition, name, true, expr));
          return null;
        }
      }
    } else {
      // Dynamic call
      if (result.expression.isInstance(DefCallExpression.class)) {
        DefCallExpression defCall = result.expression.cast(DefCallExpression.class);
        thisExpr = defCall.getDefCallArguments().size() == 1 ? defCall.getDefCallArguments().get(0) : null;
        leftDefinition = defCall.getDefinition();
      } else {
        myVisitor.getErrorReporter().report(new LocalTypeCheckingError("Expected a definition", expr));
        return null;
      }

      if (typeCheckedDefinition == null) {
        if (!(leftDefinition instanceof ClassField)) { // Some class fields do not have abstract definitions
          Scope scope = new NamespaceScope(myVisitor.getStaticNamespaceProvider().forReferable(leftDefinition.getReferable()));
          if (leftDefinition instanceof ClassDefinition) {
            scope = new MergeScope(scope, new NamespaceScope(myVisitor.getDynamicNamespaceProvider().forReferable(leftDefinition.getReferable())));
          }
          member = scope.resolveName(name);
        }
        if (!(member instanceof GlobalReferable)) {
          myVisitor.getErrorReporter().report(new MemberNotFoundError(leftDefinition, name, expr));
          return null;
        }
      }
    }

    if (member != null) {
      typeCheckedDefinition = getTypeCheckedDefinition((GlobalReferable) member, expr);
      if (typeCheckedDefinition == null) {
        return null;
      }
    }

    return makeResult(typeCheckedDefinition, thisExpr, expr);
  }

  private CheckTypeVisitor.TResult makeResult(Definition definition, Expression thisExpr, Concrete.ReferenceExpression expr) {
    Sort sortArgument;
    if (definition instanceof DataDefinition && !definition.getParameters().hasNext()) {
      sortArgument = Sort.PROP;
    } else {
      if (expr.getPLevel() == null && expr.getHLevel() == null) {
        sortArgument = Sort.generateInferVars(myVisitor.getEquations(), expr);
      } else {
        Level pLevel = null;
        if (expr.getPLevel() != null) {
          pLevel = expr.getPLevel().accept(myVisitor, LevelVariable.PVAR);
        }
        if (pLevel == null) {
          InferenceLevelVariable pl = new InferenceLevelVariable(LevelVariable.LvlType.PLVL, expr.getPLevel());
          myVisitor.getEquations().addVariable(pl);
          pLevel = new Level(pl);
        }

        Level hLevel = null;
        if (expr.getHLevel() != null) {
          hLevel = expr.getHLevel().accept(myVisitor, LevelVariable.HVAR);
        }
        if (hLevel == null) {
          InferenceLevelVariable hl = new InferenceLevelVariable(LevelVariable.LvlType.HLVL, expr.getHLevel());
          myVisitor.getEquations().addVariable(hl);
          hLevel = new Level(hl);
        }

        sortArgument = new Sort(pLevel, hLevel);
      }
    }

    if (thisExpr == null && definition instanceof ClassField) {
      myVisitor.getErrorReporter().report(new LocalTypeCheckingError("Field call without a class instance", expr));
      return null;
    }

    if (expr.getPLevel() == null && expr.getHLevel() == null) {
      Level hLevel = null;
      if (definition instanceof DataDefinition && !sortArgument.isProp()) {
        hLevel = ((DataDefinition) definition).getSort().getHLevel();
      } else if (definition instanceof FunctionDefinition && !sortArgument.isProp()) {
        UniverseExpression universe = ((FunctionDefinition) definition).getResultType().getPiParameters(null, false).checkedCast(UniverseExpression.class);
        if (universe != null) {
          hLevel = universe.getSort().getHLevel();
        }
      }
      if (hLevel != null && hLevel.getConstant() == -1 && hLevel.getVar() == LevelVariable.HVAR && hLevel.getMaxConstant() == 0) {
        myVisitor.getEquations().bindVariables((InferenceLevelVariable) sortArgument.getPLevel().getVar(), (InferenceLevelVariable) sortArgument.getHLevel().getVar());
      }
    }

    return CheckTypeVisitor.DefCallResult.makeTResult(expr, definition, sortArgument, thisExpr);
  }

  private Expression findParent(ClassDefinition classDefinition, Definition definition, Expression result) {
    if (classDefinition.isSubClassOf(definition.getThisClass())) {
      return result;
    }
    ClassField parentField = classDefinition.getEnclosingThisField();
    if (parentField == null) {
      return null;
    }
    ClassCallExpression classCall = parentField.getBaseType(Sort.STD).checkedCast(ClassCallExpression.class);
    if (classCall == null) {
      return null;
    }
    return findParent(classCall.getDefinition(), definition, ExpressionFactory.FieldCall(parentField, result));
  }
}
