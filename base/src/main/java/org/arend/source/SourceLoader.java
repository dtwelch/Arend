package org.arend.source;

import org.arend.ext.error.ErrorReporter;
import org.arend.ext.module.ModulePath;
import org.arend.ext.typechecking.DefinitionListener;
import org.arend.extImpl.SerializableKeyRegistryImpl;
import org.arend.library.LibraryManager;
import org.arend.library.SourceLibrary;
import org.arend.module.error.ModuleNotFoundError;
import org.arend.module.scopeprovider.CachingModuleScopeProvider;
import org.arend.module.scopeprovider.ModuleScopeProvider;
import org.arend.naming.reference.converter.ReferableConverter;
import org.arend.naming.scope.Scope;
import org.arend.typechecking.instance.provider.InstanceProviderSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * Contains all necessary information for source loading.
 */
public final class SourceLoader {
  private final SourceLibrary myLibrary;
  private final ReferableConverter myReferableConverter;
  private final LibraryManager myLibraryManager;
  private final Map<ModulePath, SourceType> myLoadedModules = new HashMap<>();
  private final Map<ModulePath, BinarySource> myLoadingBinaryModules = new HashMap<>();
  private final Map<ModulePath, Source> myLoadingRawModules = new HashMap<>();
  private ModuleScopeProvider myModuleScopeProvider;
  private ModuleScopeProvider myTestsModuleScopeProvider;
  private final boolean myPreviewBinariesMode;
  private final Map<ModulePath, HashSet<ModulePath>> myModuleDependencies = new HashMap<>();

  private enum SourceType { RAW, BINARY, BINARY_FAIL }

  public SourceLoader(SourceLibrary library, LibraryManager libraryManager, Boolean previewBinariesMode) {
    myLibrary = library;
    myLibraryManager = libraryManager;
    myReferableConverter = myLibrary.getReferableConverter();
    myPreviewBinariesMode = previewBinariesMode;
  }

  public SourceLoader(SourceLibrary library, LibraryManager libraryManager) {
    this(library, libraryManager, false);
  }

  public boolean isInPreviewBinariesMode() {
    return myPreviewBinariesMode;
  }

  public HashSet<ModulePath> getFailingBinaries() {
    HashSet<ModulePath> diff = new HashSet<>();
    for (Map.Entry<ModulePath, SourceType> entry : myLoadedModules.entrySet()) if (entry.getValue() == SourceType.BINARY_FAIL) diff.add(entry.getKey());

    HashSet<ModulePath> result = new HashSet<>(diff);

    while (!diff.isEmpty()) {
      HashSet<ModulePath> newDiff = new HashSet<>();
      for (ModulePath path : diff) {
        HashSet<ModulePath> dependents = myModuleDependencies.get(path);
        if (dependents != null) for (ModulePath dependent : dependents) if (!result.contains(dependent)) newDiff.add(dependent);
      }
      result.addAll(newDiff);
      diff = newDiff;
    }

    return result;
  }

  public void initializeLoader(SourceLoader previewLoader) {
    myModuleScopeProvider = previewLoader.myModuleScopeProvider;

    for (ModulePath module : previewLoader.myLoadedModules.keySet())
      if (previewLoader.myLoadedModules.get(module) != SourceType.BINARY_FAIL)
        myLoadedModules.put(module, SourceType.RAW);

    for (ModulePath module : previewLoader.getFailingBinaries())
      myLoadedModules.put(module, SourceType.BINARY_FAIL);
  }

  public void markDependency(ModulePath dependent, ModulePath dependency) {
    if (myPreviewBinariesMode) {
      HashSet<ModulePath> dependents = myModuleDependencies.computeIfAbsent(dependency, k -> new HashSet<>());
      dependents.add(dependent);
    }
  }

  public SourceLibrary getLibrary() {
    return myLibrary;
  }

  public ReferableConverter getReferableConverter() {
    return myReferableConverter;
  }

  public ModuleScopeProvider getModuleScopeProvider(boolean withTests) {
    if (myModuleScopeProvider == null) {
      myModuleScopeProvider = myLibraryManager.getAvailableModuleScopeProvider(myLibrary);
    }
    if (withTests) {
      if (myTestsModuleScopeProvider == null) {
        ModuleScopeProvider testsProvider = new CachingModuleScopeProvider(myLibrary.getTestsModuleScopeProvider());
        myTestsModuleScopeProvider = module -> {
          Scope scope = myModuleScopeProvider.forModule(module);
          return scope != null ? scope : testsProvider.forModule(module);
        };
      }
      return myTestsModuleScopeProvider;
    } else {
      return myModuleScopeProvider;
    }
  }

  public InstanceProviderSet getInstanceProviderSet() {
    return myLibraryManager.getInstanceProviderSet();
  }

  public ErrorReporter getTypecheckingErrorReporter() {
    return myLibraryManager.getTypecheckingErrorReporter();
  }

  public ErrorReporter getLibraryErrorReporter() {
    return myLibraryManager.getLibraryErrorReporter();
  }

  public void setModuleLoaded(ModulePath modulePath) {
    myLoadedModules.put(modulePath, SourceType.RAW);
  }

  /**
   * Loads the structure of the source and its dependencies.
   *
   * @param modulePath  a module to load.
   * @param inTests     true if the module located in the test directory, false otherwise.
   * @return true if a binary source is available or if the raw source was successfully loaded, false otherwise.
   */
  public boolean preloadRaw(ModulePath modulePath, boolean inTests) {
    if (myLoadedModules.containsKey(modulePath)) {
      return true;
    }
    if (myLoadingRawModules.containsKey(modulePath)) {
      return true;
    }

    Source rawSource = inTests ? myLibrary.getTestSource(modulePath) : myLibrary.getRawSource(modulePath);
    boolean rawSourceIsAvailable = rawSource != null && rawSource.isAvailable();

    if (!rawSourceIsAvailable) {
      getLibraryErrorReporter().report(new ModuleNotFoundError(modulePath));
      return false;
    }

    myLoadedModules.put(modulePath, SourceType.RAW);
    myLoadingRawModules.put(modulePath, rawSource);
    if (!rawSource.preload(this)) {
      myLoadingRawModules.remove(modulePath);
      return false;
    }

    return true;
  }

  /**
   * Loads raw sources that were preloaded.
   */
  public void loadRawSources() {
    while (!myLoadingRawModules.isEmpty()) {
      for (Iterator<Source> it = myLoadingRawModules.values().iterator(); it.hasNext(); ) {
        Source source = it.next();
        Source.LoadResult loadResult = source.load(this);
        if (loadResult != Source.LoadResult.CONTINUE) {
          it.remove();
        }
      }
    }
  }

  /**
   * Loads a binary source.
   *
   * @param modulePath  a module to load.
   * @return true if the source was successfully loaded, false otherwise.
   */
  public boolean loadBinary(ModulePath modulePath, SerializableKeyRegistryImpl keyRegistry, DefinitionListener definitionListener) {
    return preloadBinary(modulePath, keyRegistry, definitionListener) && fillInBinary(modulePath);
  }

  boolean fillInBinary(ModulePath modulePath) {
    BinarySource binarySource = myLoadingBinaryModules.remove(modulePath);
    if (binarySource != null) {
      Source.LoadResult result;
      do {
        result = binarySource.load(this);
      } while (result == Source.LoadResult.CONTINUE);

      if (result != Source.LoadResult.SUCCESS) {
        myLoadedModules.put(modulePath, SourceType.BINARY_FAIL);
        return false;
      }
    }

    return true;
  }

  /**
   * Loads the structure of the source and its dependencies without filling in actual data.
   *
   * @param modulePath  a module to load.
   * @return true if the source was successfully loaded, false otherwise.
   */
  public boolean preloadBinary(ModulePath modulePath, SerializableKeyRegistryImpl keyRegistry, DefinitionListener definitionListener) {
    SourceType sourceType = myLoadedModules.get(modulePath);
    if (sourceType == SourceType.BINARY || sourceType == SourceType.BINARY_FAIL) {
      return sourceType == SourceType.BINARY;
    }
    if (myLibrary.hasRawSources() && sourceType != SourceType.RAW) {
      return false;
    }
    if (myLoadingBinaryModules.containsKey(modulePath)) {
      return true;
    }

    BinarySource binarySource = myLibrary.getBinarySource(modulePath);
    if (binarySource == null || !binarySource.isAvailable()) {
      return false;
    }
    binarySource.setKeyRegistry(keyRegistry);
    binarySource.setDefinitionListener(definitionListener);

    if (!myLibrary.isExternal() && myLibrary.hasRawSources()) {
      Source rawSource = myLibrary.getRawSource(modulePath);
      if (rawSource != null && rawSource.isAvailable() && binarySource.getTimeStamp() < rawSource.getTimeStamp()) {
        return false;
      }
    }

    myLoadedModules.put(modulePath, SourceType.BINARY);
    myLoadingBinaryModules.put(modulePath, binarySource);
    if (!binarySource.preload(this)) {
      myLoadedModules.put(modulePath, SourceType.BINARY_FAIL);
      myLoadingBinaryModules.remove(modulePath);
      return false;
    }

    return true;
  }
}
