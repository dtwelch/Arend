package org.arend.repl;

import org.arend.core.expr.Expression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.error.GeneralError;
import org.arend.ext.error.ListErrorReporter;
import org.arend.ext.module.ModulePath;
import org.arend.ext.prettyprinting.DefinitionRenamer;
import org.arend.ext.prettyprinting.PrettyPrinterConfig;
import org.arend.ext.prettyprinting.PrettyPrinterFlag;
import org.arend.ext.reference.Precedence;
import org.arend.extImpl.definitionRenamer.CachingDefinitionRenamer;
import org.arend.extImpl.definitionRenamer.ScopeDefinitionRenamer;
import org.arend.library.Library;
import org.arend.library.LibraryManager;
import org.arend.module.ModuleLocation;
import org.arend.module.scopeprovider.ModuleScopeProvider;
import org.arend.naming.reference.*;
import org.arend.naming.resolving.visitor.DefinitionResolveNameVisitor;
import org.arend.naming.resolving.visitor.ExpressionResolveNameVisitor;
import org.arend.naming.scope.Scope;
import org.arend.naming.scope.ScopeFactory;
import org.arend.repl.action.*;
import org.arend.term.NamespaceCommand;
import org.arend.term.concrete.Concrete;
import org.arend.term.group.Group;
import org.arend.term.prettyprint.PrettyPrintVisitor;
import org.arend.term.prettyprint.ToAbstractVisitor;
import org.arend.typechecking.instance.pool.GlobalInstancePool;
import org.arend.typechecking.order.listener.TypecheckingOrderingListener;
import org.arend.typechecking.result.TypecheckingResult;
import org.arend.typechecking.visitor.CheckTypeVisitor;
import org.arend.typechecking.visitor.DesugarVisitor;
import org.arend.typechecking.visitor.SyntacticDesugarVisitor;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class Repl {
  public static final @NotNull ModuleLocation replModulePath = new ModuleLocation("Repl", true, ModuleLocation.LocationKind.SOURCE, ModulePath.fromString("Repl"));

  protected final List<Scope> myMergedScopes = new LinkedList<>();
  private final List<ReplHandler> myHandlers = new ArrayList<>();
  private final TCDefReferable myModuleReferable;
  protected final ReplScope myReplScope = new ReplScope(null, myMergedScopes);
  protected @NotNull Scope myScope = myReplScope;
  protected @NotNull TypecheckingOrderingListener typechecking;
  protected final @NotNull PrettyPrinterConfig myPpConfig = new PrettyPrinterConfig() {
    @Contract(" -> new")
    @Override
    public @NotNull DefinitionRenamer getDefinitionRenamer() {
      return new CachingDefinitionRenamer(new ScopeDefinitionRenamer(myScope));
    }

    @Override
    public @NotNull EnumSet<PrettyPrinterFlag> getExpressionFlags() {
      return getPrettyPrinterFlags();
    }

    @Override
    public @Nullable NormalizationMode getNormalizationMode() {
      return Repl.this.getNormalizationMode();
    }
  };
  protected final @NotNull ListErrorReporter myErrorReporter;
  protected final @NotNull LibraryManager myLibraryManager;

  public Repl(@NotNull ListErrorReporter listErrorReporter,
              @NotNull LibraryManager libraryManager,
              @NotNull TypecheckingOrderingListener typecheckingOrderingListener) {
    myErrorReporter = listErrorReporter;
    myLibraryManager = libraryManager;
    typechecking = typecheckingOrderingListener;
    myModuleReferable = new LocatedReferableImpl(Precedence.DEFAULT, replModulePath.getLibraryName(), new FullModuleReferable(replModulePath), GlobalReferable.Kind.OTHER);
  }

  protected abstract void loadLibraries();

  protected final @NotNull List<Referable> getInScopeElements() {
    return myReplScope.getElements();
  }

  public final void initialize() {
    loadLibraries();
    loadCommands();
    checkErrors();
  }

  public @NotNull PrettyPrinterConfig getPrettyPrinterConfig() {
    return myPpConfig;
  }

  public abstract @NotNull EnumSet<PrettyPrinterFlag> getPrettyPrinterFlags();

  public abstract @Nullable NormalizationMode getNormalizationMode();

  public abstract void setNormalizationMode(@Nullable NormalizationMode mode);

  /**
   * The function executed per main-loop of the REPL.
   *
   * @param currentLine  the current user input
   * @param lineSupplier in case the command requires more user input,
   *                     use this to acquire more lines
   * @return true if the REPL wants to quit
   */
  public final boolean repl(@NotNull String currentLine, @NotNull Supplier<@NotNull String> lineSupplier) {
    boolean quit = false;
    for (var action : myHandlers)
      if (action.isApplicable(currentLine)) {
        try {
          action.invoke(currentLine, this, lineSupplier);
        } catch (QuitReplException e) {
          quit = true;
        }
        checkErrors();
      }
    return quit;
  }

  protected final boolean typecheckLibrary(@NotNull Library library) {
    return typechecking.typecheckLibrary(library);
  }

  @Contract(pure = true)
  public final @NotNull ModuleScopeProvider getAvailableModuleScopeProvider() {
    return module -> {
      for (Library registeredLibrary : myLibraryManager.getRegisteredLibraries()) {
        Scope scope = registeredLibrary.getModuleScopeProvider().forModule(module);
        if (scope != null) return scope;
        scope = registeredLibrary.getTestsModuleScopeProvider().forModule(module);
        if (scope != null) return scope;
      }
      return null;
    };
  }

  public @NotNull String prompt() {
    return ">";
  }

  protected abstract @Nullable Group parseStatements(@NotNull String line);

  protected abstract @Nullable Concrete.Expression parseExpr(@NotNull String text);

  protected void loadPotentialUnloadedModules(Collection<? extends NamespaceCommand> namespaceCommands) {
  }

  public final void checkStatements(@NotNull String line) {
    var group = parseStatements(line);
    if (group == null) return;
    var moduleScopeProvider = getAvailableModuleScopeProvider();
    loadPotentialUnloadedModules(group.getNamespaceCommands());
    var scope = ScopeFactory.forGroup(group, moduleScopeProvider);
    myReplScope.addScope(scope);
    myReplScope.setCurrentLineScope(null);
    new DefinitionResolveNameVisitor(typechecking.getConcreteProvider(), null, myErrorReporter)
        .resolveGroupWithTypes(group, myScope);
    if (checkErrors()) {
      myMergedScopes.remove(scope);
    } else {
      typecheckStatements(group, scope);
    }
  }

  protected void typecheckStatements(@NotNull Group group, @NotNull Scope scope) {
    if (!typechecking.typecheckModules(Collections.singletonList(group), null)) {
      checkErrors();
      var isRemoved = removeScope(scope);
      assert isRemoved;
    }
    onScopeAdded(group);
  }

  protected void onScopeAdded(Group group) {
    typechecking.getInstanceProviderSet().collectInstances(
      group,
      myScope,
      myModuleReferable,
      typechecking.getReferableConverter()
    );
  }

  @MustBeInvokedByOverriders
  protected void loadCommands() {
    myHandlers.add(CodeParsingHandler.INSTANCE);
    myHandlers.add(CommandHandler.INSTANCE);
    registerAction("quit", QuitCommand.INSTANCE);
    registerAction("type", ShowTypeCommand.INSTANCE);
    registerAction("print", PrintCommand.INSTANCE);
    registerAction("print_flags", PrettyPrintFlagCommand.INSTANCE);
    registerAction("size", SizeCommand.INSTANCE);
    registerAction("normalize_mode", NormalizeCommand.INSTANCE);
    registerAction("libraries", ShowLoadedLibrariesCommand.INSTANCE);
    registerAction("?", CommandHandler.HELP_COMMAND_INSTANCE);
    registerAction("help", CommandHandler.HELP_COMMAND_INSTANCE);
  }

  public final @Nullable ReplCommand registerAction(@NotNull String name, @NotNull ReplCommand action) {
    return CommandHandler.INSTANCE.commandMap.put(name, action);
  }

  public final @Nullable ReplCommand registerAction(@NotNull String name, @NotNull AliasableCommand action) {
    action.aliases.add(name);
    return registerAction(name, (ReplCommand) action);
  }

  public final @Nullable ReplCommand unregisterAction(@NotNull String name) {
    return CommandHandler.INSTANCE.commandMap.remove(name);
  }

  public final void clearActions() {
    CommandHandler.INSTANCE.commandMap.clear();
  }

  /**
   * Multiplex the scope into the current REPL scope.
   */
  public void addScope(@NotNull Scope scope) {
    myReplScope.addScope(scope);
  }

  /**
   * Remove a multiplexed scope from the current REPL scope.
   *
   * @return true if there is indeed a scope removed
   */
  public final boolean removeScope(@NotNull Scope scope) {
    var isRemoved = removeScopeImpl(scope.getGlobalSubscopeWithoutOpens());
    var isRemoved2 = removeScopeImpl(scope);
    return isRemoved || isRemoved2;
  }

  private boolean removeScopeImpl(Scope scope) {
    for (Referable element : scope.getElements())
      if (element instanceof TCDefReferable)
        ((TCDefReferable) element).setTypechecked(null);
    return myMergedScopes.remove(scope);
  }

  /**
   * A replacement of {@link System#out#println(Object)} where it uses the
   * output stream of the REPL.
   *
   * @param anything whose {@link Object#toString()} is invoked.
   */
  public void println(Object anything) {
    print(anything);
    print(System.lineSeparator());
  }

  public void println() {
    print(System.lineSeparator());
  }

  public abstract void print(Object anything);

  /**
   * @param toError if true, print to stderr. Otherwise print to stdout
   */
  public void printlnOpt(Object anything, boolean toError) {
    if (toError) eprintln(anything);
    else println(anything);
  }

  /**
   * A replacement of {@link System#err#println(Object)} where it uses the
   * error output stream of the REPL.
   *
   * @param anything whose {@link Object#toString()} is invoked.
   */
  public abstract void eprintln(Object anything);

  public Expression normalize(Expression expr) {
    NormalizationMode mode = getNormalizationMode();
    return mode == null ? expr : expr.normalize(mode);
  }

  @Contract("_, _ -> param1")
  public @NotNull StringBuilder prettyExpr(@NotNull StringBuilder builder, @NotNull Expression expression) {
    var abs = ToAbstractVisitor.convert(expression, myPpConfig);
    abs.accept(new PrettyPrintVisitor(builder, 0), new Precedence(Concrete.Expression.PREC));
    return builder;
  }

  /**
   * @param expr input concrete expression.
   * @see Repl#preprocessExpr(String)
   */
  public void checkExpr(@NotNull Concrete.Expression expr, @Nullable Expression expectedType, @NotNull Consumer<TypecheckingResult> continuation) {
    expr = DesugarVisitor.desugar(expr, myErrorReporter);
    if (checkErrors()) return;
    var typechecker = new CheckTypeVisitor(myErrorReporter, null, null);
    var instanceProvider = typechecking.getInstanceProviderSet().get(myModuleReferable);
    var instancePool = new GlobalInstancePool(instanceProvider, typechecker);
    typechecker.setInstancePool(instancePool);
    var result = typechecker.finalCheckExpr(expr, expectedType);
    if (!checkErrors()) {
      continuation.accept(result);
    }
  }

  /**
   * @see Repl#checkExpr
   */
  public final @Nullable Concrete.Expression preprocessExpr(@NotNull String text) {
    var expr = parseExpr(text);
    if (expr == null || checkErrors()) return null;
    expr = expr
        .accept(new ExpressionResolveNameVisitor(typechecking.getReferableConverter(),
            myScope, new ArrayList<>(), myErrorReporter, null), null)
        .accept(new SyntacticDesugarVisitor(myErrorReporter), null);
    if (checkErrors()) return null;
    return expr;
  }

  private static final List<GeneralError.Level> ERROR_LEVELS = List.of(
      GeneralError.Level.ERROR,
      GeneralError.Level.WARNING,
      GeneralError.Level.GOAL
  );

  /**
   * Check and print errors.
   *
   * @return true if there is error(s).
   */
  public boolean checkErrors() {
    var errorList = myErrorReporter.getErrorList();
    boolean hasErrors = false;
    for (GeneralError error : errorList) {
      printlnOpt(error.getDoc(myPpConfig), ERROR_LEVELS.contains(error.level));
      if (error.level == GeneralError.Level.ERROR) hasErrors = true;
    }
    errorList.clear();
    return hasErrors;
  }

  private static class ShowLoadedLibrariesCommand implements ReplCommand {
    private static final ShowLoadedLibrariesCommand INSTANCE = new ShowLoadedLibrariesCommand();

    private ShowLoadedLibrariesCommand() {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Sentence) @NotNull String description() {
      return "List registered libraries in the REPL";
    }

    @Override
    public void invoke(@NotNull String line, @NotNull Repl api, @NotNull Supplier<@NotNull String> scanner) {
      for (var registeredLibrary : api.myLibraryManager.getRegisteredLibraries()) {
        boolean external = registeredLibrary.isExternal();
        boolean notLoaded = !registeredLibrary.isLoaded();
        var info = external && notLoaded
          ? " (external, not loaded)"
          : external ? " (external)"
          : notLoaded ? " (not loaded)"
          : "";
        api.println(registeredLibrary.getName() + info);
      }
    }
  }
}
