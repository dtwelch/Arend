package org.arend.term.abs;

import org.arend.error.CountingErrorReporter;
import org.arend.error.DummyErrorReporter;
import org.arend.error.ParsingError;
import org.arend.ext.error.ErrorReporter;
import org.arend.ext.error.GeneralError;
import org.arend.naming.reference.*;
import org.arend.naming.reference.converter.ReferableConverter;
import org.arend.term.ClassFieldKind;
import org.arend.term.Fixity;
import org.arend.term.FunctionKind;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.error.local.LocalErrorReporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;

@SuppressWarnings("WeakerAccess")
public class ConcreteBuilder implements AbstractDefinitionVisitor<Concrete.Definition>, AbstractExpressionVisitor<Void, Concrete.Expression>, AbstractLevelExpressionVisitor<Void, Concrete.LevelExpression> {
  private final ReferableConverter myReferableConverter;
  private final LocalErrorReporter myErrorReporter;
  private final TCReferable myDefinition;
  private GeneralError.Level myErrorLevel;

  private ConcreteBuilder(ReferableConverter referableConverter, ErrorReporter errorReporter, TCReferable definition) {
    myReferableConverter = referableConverter;
    myDefinition = definition;
    myErrorReporter = new LocalErrorReporter(myDefinition, errorReporter) {
      @Override
      public void report(GeneralError error) {
        if (myErrorLevel == null || error.level.ordinal() > myErrorLevel.ordinal()) {
          myErrorLevel = error.level;
        }
        errorReporter.report(error);
      }
    };
  }

  public static @NotNull Concrete.Definition convert(ReferableConverter referableConverter, Abstract.Definition definition, ErrorReporter errorReporter) {
    ConcreteBuilder builder = new ConcreteBuilder(referableConverter, errorReporter, referableConverter.toDataLocatedReferable(definition.getReferable()));
    Concrete.Definition result = definition.accept(builder);
    if (builder.myErrorLevel != null) {
      result.setStatus(builder.myErrorLevel);
    }
    return result;
  }

  public static @Nullable Concrete.Expression convertWithoutErrors(Abstract.Expression expression) {
    CountingErrorReporter errorReporter = new CountingErrorReporter(DummyErrorReporter.INSTANCE);
    Concrete.Expression result = expression.accept(new ConcreteBuilder(null, errorReporter, null), null);
    return errorReporter.getErrorsNumber() == 0 ? result : null;
  }

  public static @NotNull Concrete.Expression convertExpression(Abstract.Expression expression, ErrorReporter errorReporter) {
    return expression.accept(new ConcreteBuilder(null, errorReporter, null), null);
  }

  public static @NotNull Concrete.Expression convertExpression(Abstract.Expression expression) {
    return convertExpression(expression, DummyErrorReporter.INSTANCE);
  }

  // Definition

  private void setEnclosingClass(Concrete.Definition definition, Abstract.Definition abstractDef) {
    TCReferable enclosingClass = myReferableConverter.toDataLocatedReferable(abstractDef.getEnclosingClass());
    if (!(definition instanceof Concrete.ClassDefinition)) {
      definition.enclosingClass = enclosingClass;
    }
  }

  @Override
  public Concrete.BaseFunctionDefinition visitFunction(Abstract.FunctionDefinition def) {
    Concrete.FunctionBody body;
    Abstract.Expression term = def.getTerm();
    if (def.withTerm()) {
      if (term == null) {
        myErrorLevel = GeneralError.Level.ERROR;
        LocatedReferable ref = def.getReferable();
        body = new Concrete.TermFunctionBody(ref, new Concrete.ErrorHoleExpression(ref, null));
      } else {
        body = new Concrete.TermFunctionBody(term.getData(), term.accept(this, null));
      }
    } else if (def.isCowith()) {
      List<Concrete.CoClauseElement> elements = new ArrayList<>();
      for (Abstract.CoClauseElement element : def.getCoClauseElements()) {
        if (element instanceof Abstract.CoClauseFunctionReference) {
          LocatedReferable functionRef = ((Abstract.CoClauseFunctionReference) element).getFunctionReference();
          if (functionRef != null) {
            Abstract.Reference implementedField = element.getImplementedField();
            if (implementedField != null) {
              elements.add(new Concrete.CoClauseFunctionReference(implementedField.getReferent(), myReferableConverter.toDataLocatedReferable(functionRef)));
            }
            continue;
          }
        }
        if (element instanceof Abstract.ClassFieldImpl) {
          buildImplementation(def, (Abstract.ClassFieldImpl) element, elements);
        } else {
          myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Unknown coclause element", element));
        }
      }
      body = new Concrete.CoelimFunctionBody(myDefinition, elements);
    } else {
      body = new Concrete.ElimFunctionBody(myDefinition, buildReferences(def.getEliminatedExpressions()), buildClauses(def.getClauses()));
    }

    List<Concrete.Parameter> parameters = buildParameters(def.getParameters(), true);
    Abstract.Expression resultType = def.getResultType();
    Abstract.Expression resultTypeLevel = checkResultTypeLevel(resultType, def.getResultTypeLevel());
    Concrete.Expression type = resultType == null ? null : resultType.accept(this, null);
    Concrete.Expression typeLevel = resultTypeLevel == null ? null : resultTypeLevel.accept(this, null);

    FunctionKind kind = def.getFunctionKind();

    TCReferable parentRef = myReferableConverter.toDataLocatedReferable(def.getReferable().getLocatedReferableParent());
    Concrete.BaseFunctionDefinition result;
    if (kind == FunctionKind.COCLAUSE_FUNC) {
      Abstract.Reference implementedField = def.getImplementedField();
      result = new Concrete.CoClauseFunctionDefinition(myDefinition, parentRef, implementedField == null ? null : implementedField.getReferent(), parameters, type, typeLevel, body);
    } else {
      result = Concrete.UseDefinition.make(def.getFunctionKind(), myDefinition, parameters, type, typeLevel, body, parentRef);
    }
    setEnclosingClass(result, def);
    if (result instanceof Concrete.FunctionDefinition) {
      ((Concrete.FunctionDefinition) result).setUsedDefinitions(visitUsedDefinitions(def.getUsedDefinitions()));
    }
    return result;
  }

  @Override
  public Concrete.DataDefinition visitData(Abstract.DataDefinition def) {
    Abstract.Expression absUniverse = def.getUniverse();
    Concrete.Expression universe = absUniverse == null ? null : absUniverse.accept(this, null);
    if (universe != null && !(universe instanceof Concrete.UniverseExpression)) {
      myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Expected a universe", universe.getData()));
    }

    List<Concrete.TypeParameter> typeParameters = buildTypeParameters(def.getParameters(), true);
    Collection<? extends Abstract.ConstructorClause> absClauses = def.getClauses();
    List<Concrete.ConstructorClause> clauses = new ArrayList<>(absClauses.size());
    Collection<? extends Abstract.Reference> elimExpressions = def.getEliminatedExpressions();
    Concrete.DataDefinition data = new Concrete.DataDefinition(myDefinition, typeParameters, elimExpressions == null ? null : buildReferences(elimExpressions), def.isTruncated(), universe instanceof Concrete.UniverseExpression ? (Concrete.UniverseExpression) universe : null, clauses);
    setEnclosingClass(data, def);

    for (Abstract.ConstructorClause clause : absClauses) {
      Collection<? extends Abstract.Constructor> absConstructors = clause.getConstructors();
      if (absConstructors.isEmpty()) {
        myErrorLevel = GeneralError.Level.ERROR;
        continue;
      }

      List<Concrete.Constructor> constructors = new ArrayList<>(absConstructors.size());
      for (Abstract.Constructor constructor : absConstructors) {
        TCReferable constructorRef = myReferableConverter.toDataLocatedReferable(constructor.getReferable());
        if (constructorRef != null) {
          Concrete.Constructor cons = new Concrete.Constructor(constructorRef, data, buildTypeParameters(constructor.getParameters(), true), buildReferences(constructor.getEliminatedExpressions()), buildClauses(constructor.getClauses()));
          Abstract.Expression resultType = constructor.getResultType();
          if (resultType != null) {
            cons.setResultType(resultType.accept(this, null));
          }
          constructors.add(cons);
        } else {
          myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Internal error: cannot locate constructor", constructor));
        }
      }

      Collection<? extends Abstract.Pattern> patterns = clause.getPatterns();
      clauses.add(new Concrete.ConstructorClause(clause.getData(), patterns.isEmpty() ? null : buildPatterns(patterns), constructors));
    }

    data.setUsedDefinitions(visitUsedDefinitions(def.getUsedDefinitions()));
    return data;
  }

  public void buildClassParameters(Collection<? extends Abstract.FieldParameter> absParameters, Concrete.ClassDefinition classDef, List<Concrete.ClassElement> elements) {
    TCFieldReferable coercingField = null;
    boolean isForced = false;

    for (Abstract.FieldParameter absParameter : absParameters) {
      boolean forced = absParameter.isClassifying();
      Concrete.Parameter parameter = buildParameter(absParameter, false, false);
      if (parameter.getType() != null) {
        for (Referable referable : parameter.getReferableList()) {
          if (referable instanceof TCFieldReferable) {
            if (forced) {
              if (isForced) {
                myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Class can have at most one classifying field", parameter));
              } else {
                coercingField = (TCFieldReferable) referable;
                isForced = true;
              }
            } else if (coercingField == null && parameter.isExplicit()) {
              coercingField = (TCFieldReferable) referable;
            }
            elements.add(new Concrete.ClassField((TCFieldReferable) referable, classDef, parameter.isExplicit(), ClassFieldKind.ANY, new ArrayList<>(), parameter.getType(), null));
          } else {
            myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Incorrect field parameter", referable));
          }
        }
      } else {
        myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Expected a typed parameter", parameter.getData()));
      }
    }

    if (coercingField != null) {
      classDef.setCoercingField(coercingField, isForced);
    }
  }

  @Override
  public Concrete.Definition visitClass(Abstract.ClassDefinition def) {
    List<Concrete.ClassElement> elements = new ArrayList<>();
    Concrete.ClassDefinition classDef = new Concrete.ClassDefinition(myDefinition, def.isRecord(), def.withoutClassifying(), buildReferences(def.getSuperClasses()), elements);
    buildClassParameters(def.getParameters(), classDef, elements);

    for (Abstract.ClassElement element : def.getClassElements()) {
      if (element instanceof Abstract.ClassField) {
        Abstract.ClassField field = (Abstract.ClassField) element;
        Abstract.Expression resultType = field.getResultType();
        LocatedReferable fieldRefOrig = field.getReferable();
        TCReferable fieldRef = myReferableConverter.toDataLocatedReferable(fieldRefOrig);
        if (resultType == null || !(fieldRef instanceof TCFieldReferable)) {
          myErrorLevel = GeneralError.Level.ERROR;
          if (fieldRef != null && !(fieldRef instanceof TCFieldReferable)) {
            myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Incorrect field", fieldRef));
          }
        } else {
          List<? extends Abstract.Parameter> parameters = field.getParameters();
          Concrete.Expression type = resultType.accept(this, null);
          Abstract.Expression resultTypeLevel = field.getResultTypeLevel();
          Concrete.Expression typeLevel = resultTypeLevel == null ? null : resultTypeLevel.accept(this, null);
          elements.add(new Concrete.ClassField((TCFieldReferable) fieldRef, classDef, true, field.getClassFieldKind(), buildTypeParameters(parameters, false), type, typeLevel));
        }
      } else if (element instanceof Abstract.ClassFieldImpl) {
        buildImplementation(def, (Abstract.ClassFieldImpl) element, elements);
      } else if (element instanceof Abstract.OverriddenField) {
        Abstract.OverriddenField field = (Abstract.OverriddenField) element;
        Abstract.Reference ref = field.getOverriddenField();
        Abstract.Expression type = field.getResultType();
        if (ref == null || type == null) {
          continue;
        }
        Abstract.Expression typeLevel = field.getResultTypeLevel();
        elements.add(new Concrete.OverriddenField(field.getData(), ref.getReferent(), buildTypeParameters(field.getParameters(), false), type.accept(this, null), typeLevel == null ? null : typeLevel.accept(this, null)));
      } else {
        myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Unknown class element", element));
      }
    }

    classDef.setUsedDefinitions(visitUsedDefinitions(def.getUsedDefinitions()));
    return classDef;
  }

  private List<TCReferable> visitUsedDefinitions(Collection<? extends LocatedReferable> usedDefinitions) {
    if (!usedDefinitions.isEmpty()) {
      List<TCReferable> tcUsedDefinitions = new ArrayList<>(usedDefinitions.size());
      for (LocatedReferable coercingFunction : usedDefinitions) {
        tcUsedDefinitions.add(myReferableConverter.toDataLocatedReferable(coercingFunction));
      }
      return tcUsedDefinitions;
    } else {
      return Collections.emptyList();
    }
  }

  public Concrete.ReferenceExpression buildReference(Abstract.Reference reference) {
    return new Concrete.ReferenceExpression(reference.getData(), reference.getReferent());
  }

  public List<Concrete.ReferenceExpression> buildReferences(Collection<? extends Abstract.Reference> absElimExpressions) {
    List<Concrete.ReferenceExpression> elimExpressions = new ArrayList<>(absElimExpressions.size());
    for (Abstract.Reference reference : absElimExpressions) {
      elimExpressions.add(buildReference(reference));
    }
    return elimExpressions;
  }

  private void buildImplementation(Object data, Abstract.ClassFieldImpl implementation, List<? super Concrete.ClassFieldImpl> implementations) {
    Object prec = implementation.getPrec();
    if (prec != null) {
      myErrorReporter.report(new ParsingError(ParsingError.Kind.PRECEDENCE_IGNORED, prec));
    }

    Abstract.Reference implementedField = implementation.getImplementedField();
    if (implementedField == null) {
      return;
    }

    Abstract.Expression impl = implementation.getImplementation();
    if (impl != null) {
      List<? extends Abstract.Parameter> parameters = implementation.getParameters();
      Concrete.Expression term = impl.accept(this, null);
      if (!parameters.isEmpty()) {
        term = new Concrete.LamExpression(parameters.get(0).getData(), buildParameters(parameters, false), term);
      }

      implementations.add(new Concrete.ClassFieldImpl(implementation.getData(), implementedField.getReferent(), term, Collections.emptyList()));
    } else {
      boolean hasImpl = implementation.hasImplementation();
      if (hasImpl) {
        myErrorLevel = GeneralError.Level.ERROR;
      }
      implementations.add(new Concrete.ClassFieldImpl(implementation.getData(), implementedField.getReferent(), hasImpl ? new Concrete.ErrorHoleExpression(data, null) : null, buildImplementations(implementation.getData(), implementation.getCoClauseElements())));
    }
  }

  private List<Concrete.ClassFieldImpl> buildImplementations(Object data, Collection<? extends Abstract.ClassFieldImpl> absImplementations) {
    List<Concrete.ClassFieldImpl> implementations = new ArrayList<>();
    for (Abstract.ClassFieldImpl implementation : absImplementations) {
      buildImplementation(data, implementation, implementations);
    }
    return implementations;
  }

  public Concrete.Parameter buildParameter(Abstract.Parameter parameter, boolean isNamed, boolean isDefinition) {
    List<? extends Referable> referableList = parameter.getReferableList();
    Abstract.Expression type = parameter.getType();
    Concrete.Expression cType;
    if (type == null) {
      if (referableList.size() == 1) {
        return new Concrete.NameParameter(parameter.getData(), parameter.isExplicit(), DataLocalReferable.make(referableList.get(0)));
      } else {
        myErrorLevel = GeneralError.Level.ERROR;
        cType = new Concrete.ErrorHoleExpression(parameter.getData(), null);
      }
    } else {
      cType = type.accept(this, null);
    }

    boolean isStrict = parameter.isStrict();
    if (!isNamed && (referableList.isEmpty() || referableList.size() == 1 && referableList.get(0) == null)) {
      if (isDefinition && isStrict) {
        return new Concrete.DefinitionTypeParameter(parameter.getData(), parameter.isExplicit(), true, cType);
      } else {
        if (isStrict) {
          myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "\\strict is not allowed here", parameter.getData()));
        }
        return new Concrete.TypeParameter(parameter.getData(), parameter.isExplicit(), cType);
      }
    } else {
      List<Referable> dataReferableList = new ArrayList<>(referableList.size());
      for (Referable referable : referableList) {
        dataReferableList.add(referable instanceof LocatedReferable && myReferableConverter != null ? myReferableConverter.toDataLocatedReferable((LocatedReferable) referable) : DataLocalReferable.make(referable));
      }
      if (isDefinition && isStrict) {
        return new Concrete.DefinitionTelescopeParameter(parameter.getData(), parameter.isExplicit(), true, dataReferableList, cType);
      } else {
        if (isStrict) {
          myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "\\strict is not allowed here", parameter.getData()));
        }
        return new Concrete.TelescopeParameter(parameter.getData(), parameter.isExplicit(), dataReferableList, cType);
      }
    }
  }

  public List<Concrete.Parameter> buildParameters(Collection<? extends Abstract.Parameter> absParameters, boolean isDefinition) {
    List<Concrete.Parameter> parameters = new ArrayList<>(absParameters.size());
    for (Abstract.Parameter absParameter : absParameters) {
      parameters.add(buildParameter(absParameter, true, isDefinition));
    }
    return parameters;
  }

  public List<Concrete.TypeParameter> buildTypeParameters(Collection<? extends Abstract.Parameter> absParameters, boolean isDefinition) {
    List<Concrete.TypeParameter> parameters = new ArrayList<>(absParameters.size());
    for (Abstract.Parameter absParameter : absParameters) {
      Concrete.Parameter parameter = buildParameter(absParameter, false, isDefinition);
      if (parameter instanceof Concrete.TypeParameter) {
        parameters.add((Concrete.TypeParameter) parameter);
      } else {
        myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Expected a typed parameter", parameter.getData()));
      }
    }
    return parameters;
  }

  public List<Concrete.TypedReferable> buildTypedReferables(List<? extends Abstract.TypedReferable> typedReferables) {
    List<Concrete.TypedReferable> result = new ArrayList<>();
    for (Abstract.TypedReferable typedReferable : typedReferables) {
      Referable referable = typedReferable.getReferable();
      if (referable != null) {
        Abstract.Expression type = typedReferable.getType();
        result.add(new Concrete.TypedReferable(typedReferable.getData(), DataLocalReferable.make(referable), type == null ? null : type.accept(this, null)));
      }
    }
    return result;
  }

  public Concrete.Pattern buildPattern(Abstract.Pattern pattern) {
    Referable reference = pattern.getHeadReference();
    if (reference == null) {
      Integer number = pattern.getNumber();
      if (number != null) {
        Concrete.Pattern cPattern = new Concrete.NumberPattern(pattern.getData(), number, buildTypedReferables(pattern.getAsPatterns()));
        cPattern.setExplicit(pattern.isExplicit());
        return cPattern;
      }
    }

    if (reference == null && !pattern.isUnnamed()) {
      return new Concrete.TuplePattern(pattern.getData(), pattern.isExplicit(), buildPatterns(pattern.getArguments()), buildTypedReferables(pattern.getAsPatterns()));
    } else {
      Abstract.Expression type = pattern.getType();
      List<? extends Abstract.Pattern> args = pattern.getArguments();
      if (reference instanceof GlobalReferable || reference instanceof LongUnresolvedReference || !args.isEmpty()) {
        if (type != null) {
          myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "Type annotation is allowed only for variables", type.getData()));
        }
        return new Concrete.ConstructorPattern(pattern.getData(), pattern.isExplicit(), reference, buildPatterns(args), buildTypedReferables(pattern.getAsPatterns()));
      } else {
        return new Concrete.NamePattern(pattern.getData(), pattern.isExplicit(), DataLocalReferable.make(reference), type == null ? null : type.accept(this, null));
      }
    }
  }

  public List<Concrete.Pattern> buildPatterns(Collection<? extends Abstract.Pattern> absPatterns) {
    List<Concrete.Pattern> patterns = new ArrayList<>(absPatterns.size());
    for (Abstract.Pattern pattern : absPatterns) {
      patterns.add(buildPattern(pattern));
    }
    return patterns;
  }

  public List<Concrete.FunctionClause> buildClauses(Collection<? extends Abstract.FunctionClause> absClauses) {
    List<Concrete.FunctionClause> clauses = new ArrayList<>(absClauses.size());
    for (Abstract.FunctionClause clause : absClauses) {
      Collection<? extends Abstract.Pattern> patterns = clause.getPatterns();
      if (patterns.isEmpty()) {
        myErrorLevel = GeneralError.Level.ERROR;
        continue;
      }
      Abstract.Expression expr = clause.getExpression();
      clauses.add(new Concrete.FunctionClause(clause.getData(), buildPatterns(patterns), expr == null ? null : expr.accept(this, null)));
    }
    return clauses;
  }

  // Expression

  @Override
  public Concrete.ReferenceExpression visitReference(@Nullable Object data, @NotNull Referable referent, @Nullable Fixity fixity, @Nullable Abstract.LevelExpression level1, @Nullable Abstract.LevelExpression level2, Void params) {
    return Concrete.FixityReferenceExpression.make(data, referent, fixity, level1 == null ? null : level1.accept(this, null), level2 == null ? null : level2.accept(this, null));
  }

  @Override
  public Concrete.ReferenceExpression visitReference(@Nullable Object data, @NotNull Referable referent, int lp, int lh, Void params) {
    return new Concrete.ReferenceExpression(data, referent, new Concrete.NumberLevelExpression(data, lp), new Concrete.NumberLevelExpression(data, lh));
  }

  @Override
  public Concrete.Expression visitThis(@Nullable Object data, Void params) {
    return new Concrete.ThisExpression(data, null);
  }

  @Override
  public Concrete.Expression visitApplyHole(@Nullable Object data, Void params) {
    return new Concrete.ApplyHoleExpression(data);
  }

  @Override
  public Concrete.Expression visitLam(@Nullable Object data, @NotNull Collection<? extends Abstract.Parameter> parameters, @Nullable Abstract.Expression body, Void params) {
    if (parameters.isEmpty() && body == null) {
      return new Concrete.ErrorHoleExpression(data, null);
    }
    Concrete.Expression cBody = body == null ? new Concrete.IncompleteExpression(data) : body.accept(this, null);
    return parameters.isEmpty() ? cBody : new Concrete.LamExpression(data, buildParameters(parameters, false), cBody);
  }

  @Override
  public Concrete.Expression visitPi(@Nullable Object data, @NotNull Collection<? extends Abstract.Parameter> parameters, @Nullable Abstract.Expression codomain, Void params) {
    if (codomain == null) {
      myErrorLevel = GeneralError.Level.ERROR;
    }
    Concrete.Expression cCodomain = codomain == null ? new Concrete.ErrorHoleExpression(data, null) : codomain.accept(this, null);
    return parameters.isEmpty() ? cCodomain : new Concrete.PiExpression(data, buildTypeParameters(parameters, false), cCodomain);
  }

  @Override
  public Concrete.UniverseExpression visitUniverse(@Nullable Object data, @Nullable Integer pLevelNum, @Nullable Integer hLevelNum, @Nullable Abstract.LevelExpression pLevel, @Nullable Abstract.LevelExpression hLevel, Void params) {
    if (pLevelNum != null && hLevel == null) {
      hLevel = pLevel;
      pLevel = null;
    }
    if (pLevelNum != null && pLevel != null) {
      myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "p-level is already specified", pLevel.getData()));
    }
    if (hLevelNum != null && hLevel != null) {
      myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "h-level is already specified", hLevel.getData()));
    }

    return new Concrete.UniverseExpression(data,
      pLevelNum != null ? new Concrete.NumberLevelExpression(data, pLevelNum) : pLevel != null ? pLevel.accept(this, null) : null,
      hLevelNum != null ? (hLevelNum == Abstract.INFINITY_LEVEL ? new Concrete.InfLevelExpression(data) : new Concrete.NumberLevelExpression(data, hLevelNum)) : hLevel != null ? hLevel.accept(this, null) : null);
  }

  @Override
  public Concrete.HoleExpression visitInferHole(@Nullable Object data, Void params) {
    return new Concrete.HoleExpression(data);
  }

  @Override
  public Concrete.GoalExpression visitGoal(@Nullable Object data, @Nullable String name, @Nullable Abstract.Expression expression, Void params) {
    return new Concrete.GoalExpression(data, name, expression == null ? null : expression.accept(this, null));
  }

  @Override
  public Concrete.TupleExpression visitTuple(@Nullable Object data, @NotNull Collection<? extends Abstract.Expression> absFields, @Nullable Object trailingComma, Void params) {
    List<Concrete.Expression> fields = new ArrayList<>();
    for (Abstract.Expression field : absFields) {
      fields.add(field.accept(this, null));
    }
    if (trailingComma != null) {
      fields.add(new Concrete.IncompleteExpression(trailingComma));
    }

    return new Concrete.TupleExpression(data, fields);
  }

  @Override
  public Concrete.SigmaExpression visitSigma(@Nullable Object data, @NotNull Collection<? extends Abstract.Parameter> parameters, Void params) {
    return new Concrete.SigmaExpression(data, buildTypeParameters(parameters, false));
  }

  private Concrete.Expression makeBinOpSequence(Object data, Concrete.Expression left, Collection<? extends Abstract.BinOpSequenceElem> sequence) {
    if (sequence.isEmpty()) {
      return left;
    }

    List<Concrete.BinOpSequenceElem> elems = new ArrayList<>(sequence.size());
    elems.add(new Concrete.ExplicitBinOpSequenceElem(left));
    for (Abstract.BinOpSequenceElem elem : sequence) {
      Abstract.Expression arg = elem.getExpression();
      if (arg == null) {
        continue;
      }

      elems.add(elem.isExplicit() ? new Concrete.ExplicitBinOpSequenceElem(arg.accept(this, null), elem.isVariable() ? Fixity.UNKNOWN : Fixity.NONFIX) : new Concrete.ImplicitBinOpSequenceElem(arg.accept(this, null)));
    }
    return new Concrete.BinOpSequenceExpression(data, elems);
  }

  @Override
  public Concrete.Expression visitBinOpSequence(@Nullable Object data, @NotNull Abstract.Expression left, @NotNull Collection<? extends Abstract.BinOpSequenceElem> sequence, Void params) {
    return makeBinOpSequence(data, left.accept(this, null), sequence);
  }

  @Override
  public Concrete.Expression visitCase(@Nullable Object data, boolean isSFunc, @Nullable Abstract.EvalKind evalKind, @NotNull Collection<? extends Abstract.CaseArgument> caseArgs, @Nullable Abstract.Expression resultType, @Nullable Abstract.Expression resultTypeLevel, @NotNull Collection<? extends Abstract.FunctionClause> clauses, Void params) {
    if (caseArgs.isEmpty()) {
      myErrorLevel = GeneralError.Level.ERROR;
      return new Concrete.ErrorHoleExpression(data, null);
    }
    List<Concrete.CaseArgument> concreteCaseArgs = new ArrayList<>(caseArgs.size());
    for (Abstract.CaseArgument caseArg : caseArgs) {
      Abstract.Expression type = caseArg.getType();
      Abstract.Reference elimRef = caseArg.getEliminatedReference();
      Concrete.Expression cType = type == null ? null : type.accept(this, null);
      if (elimRef != null) {
        concreteCaseArgs.add(new Concrete.CaseArgument(buildReference(elimRef), cType));
      } else {
        Object applyHoleData = caseArg.getApplyHoleData();
        if (applyHoleData != null) {
          concreteCaseArgs.add(new Concrete.CaseArgument(new Concrete.ApplyHoleExpression(applyHoleData), cType));
        } else {
          Abstract.Expression expr = caseArg.getExpression();
          if (expr == null) {
            myErrorLevel = GeneralError.Level.ERROR;
          }
          Concrete.Expression cExpr = expr == null ? new Concrete.ErrorHoleExpression(data, null) : expr.accept(this, null);
          concreteCaseArgs.add(new Concrete.CaseArgument(cExpr, DataLocalReferable.make(caseArg.getReferable()), cType));
        }
      }
    }

    resultTypeLevel = checkResultTypeLevel(resultType, resultTypeLevel);
    Concrete.Expression result = new Concrete.CaseExpression(data, isSFunc, concreteCaseArgs, resultType == null ? null : resultType.accept(this, null), resultTypeLevel == null ? null : resultTypeLevel.accept(this, null), buildClauses(clauses));
    return evalKind != null ? new Concrete.EvalExpression(data, evalKind == Abstract.EvalKind.PEVAL, result) : result;
  }

  private Abstract.Expression checkResultTypeLevel(Abstract.Expression resultType, Abstract.Expression resultTypeLevel) {
    if (resultType == null && resultTypeLevel != null) {
      myErrorReporter.report(new AbstractExpressionError(GeneralError.Level.ERROR, "The level of a type can be specified only if the type is also specified", resultTypeLevel));
      return null;
    } else {
      return resultTypeLevel;
    }
  }

  @Override
  public Concrete.Expression visitFieldAccs(@Nullable Object data, @NotNull Abstract.Expression expression, @NotNull Collection<Integer> fieldAccs, Void params) {
    Concrete.Expression result = expression.accept(this, null);
    for (Integer fieldAcc : fieldAccs) {
      result = new Concrete.ProjExpression(data, result, fieldAcc - 1);
    }

    return result;
  }

  @Override
  public Concrete.Expression visitClassExt(@Nullable Object data, boolean isNew, @Nullable Abstract.EvalKind evalKind, @Nullable Abstract.Expression baseClass, @Nullable Collection<? extends Abstract.ClassFieldImpl> implementations, @NotNull Collection<? extends Abstract.BinOpSequenceElem> sequence, Void params) {
    if (baseClass == null) {
      myErrorLevel = GeneralError.Level.ERROR;
      return new Concrete.ErrorHoleExpression(data, null);
    }

    Concrete.Expression result = baseClass.accept(this, null);
    if (implementations != null) {
      result = Concrete.ClassExtExpression.make(data, result, buildImplementations(data, implementations));
    }
    if (evalKind != null) {
      result = new Concrete.EvalExpression(data, evalKind == Abstract.EvalKind.PEVAL, result);
    }
    if (isNew) {
      result = new Concrete.NewExpression(data, result);
    }

    return makeBinOpSequence(data, result, sequence);
  }

  private Concrete.LetClausePattern visitLetClausePattern(Abstract.LetClausePattern pattern) {
    Referable referable = pattern.getReferable();
    if (referable == null && pattern.isIgnored()) {
      return new Concrete.LetClausePattern(pattern);
    }

    if (referable != null) {
      Abstract.Expression type = pattern.getType();
      return new Concrete.LetClausePattern(DataLocalReferable.make(referable), type == null ? null : type.accept(this, null));
    } else {
      List<Concrete.LetClausePattern> concretePatterns = new ArrayList<>();
      for (Abstract.LetClausePattern subPattern : pattern.getPatterns()) {
        concretePatterns.add(visitLetClausePattern(subPattern));
      }
      return new Concrete.LetClausePattern(pattern, concretePatterns);
    }
  }

  @Override
  public Concrete.Expression visitLet(@Nullable Object data, boolean isStrict, @NotNull Collection<? extends Abstract.LetClause> absClauses, @Nullable Abstract.Expression expression, Void params) {
    if (absClauses.isEmpty()) {
      myErrorLevel = GeneralError.Level.ERROR;
      return expression != null ? expression.accept(this, null) : new Concrete.ErrorHoleExpression(data, null);
    }

    List<Concrete.LetClause> clauses = new ArrayList<>(absClauses.size());
    boolean ok = true;
    for (Abstract.LetClause clause : absClauses) {
      Abstract.Expression term = clause.getTerm();
      if (term != null) {
        Referable referable = clause.getReferable();
        List<? extends Abstract.Parameter> parameters = clause.getParameters();
        Abstract.Expression resultType = clause.getResultType();
        if (referable != null) {
          clauses.add(new Concrete.LetClause(DataLocalReferable.make(referable), buildParameters(parameters, false), resultType == null ? null : resultType.accept(this, null), term.accept(this, null)));
        } else {
          Abstract.LetClausePattern pattern = clause.getPattern();
          if (pattern != null) {
            clauses.add(new Concrete.LetClause(visitLetClausePattern(pattern), resultType == null ? null : resultType.accept(this, null), term.accept(this, null)));
          }
        }
      } else {
        myErrorLevel = GeneralError.Level.ERROR;
        ok = false;
      }
    }

    return new Concrete.LetExpression(data, isStrict, clauses, expression != null ? expression.accept(this, null) : ok ? new Concrete.IncompleteExpression(data) : new Concrete.ErrorHoleExpression(data, null));
  }

  @Override
  public Concrete.NumericLiteral visitNumericLiteral(@Nullable Object data, @NotNull BigInteger number, Void params) {
    return new Concrete.NumericLiteral(data, number);
  }

  @Override
  public Concrete.Expression visitStringLiteral(@Nullable Object data, @NotNull String unescapedString, Void params) {
    return new Concrete.StringLiteral(data, unescapedString);
  }

  @Override
  public Concrete.Expression visitTyped(@Nullable Object data, @NotNull Abstract.Expression expr, @NotNull Abstract.Expression type, Void params) {
    return new Concrete.TypedExpression(data, expr.accept(this, null), type.accept(this, null));
  }

  // LevelExpression

  @Override
  public Concrete.InfLevelExpression visitInf(@Nullable Object data, Void param) {
    return new Concrete.InfLevelExpression(data);
  }

  @Override
  public Concrete.PLevelExpression visitLP(@Nullable Object data, Void param) {
    return new Concrete.PLevelExpression(data);
  }

  @Override
  public Concrete.HLevelExpression visitLH(@Nullable Object data, Void param) {
    return new Concrete.HLevelExpression(data);
  }

  @Override
  public Concrete.NumberLevelExpression visitNumber(@Nullable Object data, int number, Void param) {
    return new Concrete.NumberLevelExpression(data, number);
  }

  @Override
  public Concrete.SucLevelExpression visitSuc(@Nullable Object data, @Nullable Abstract.LevelExpression expr, Void param) {
    if (expr == null) {
      myErrorLevel = GeneralError.Level.ERROR;
      return null;
    }
    return new Concrete.SucLevelExpression(data, expr.accept(this, null));
  }

  @Override
  public Concrete.LevelExpression visitMax(@Nullable Object data, @Nullable Abstract.LevelExpression left, @Nullable Abstract.LevelExpression right, Void param) {
    if (left == null || right == null) {
      myErrorLevel = GeneralError.Level.ERROR;
    }
    return left == null && right == null
      ? null
      : left == null
        ? right.accept(this, null)
        : right == null
          ? left.accept(this, null)
          : new Concrete.MaxLevelExpression(data, left.accept(this, null), right.accept(this, null));
  }

  @Override
  public Concrete.LevelExpression visitError(@Nullable Object data) {
    myErrorLevel = GeneralError.Level.ERROR;
    return null;
  }
}
