package org.arend;

import org.arend.ext.error.GeneralError;
import org.arend.ext.error.ListErrorReporter;
import org.arend.ext.prettyprinting.PrettyPrinterConfig;
import org.arend.ext.prettyprinting.doc.Doc;
import org.arend.extImpl.DefinitionRequester;
import org.arend.frontend.ConcreteReferableProvider;
import org.arend.frontend.PositionComparator;
import org.arend.frontend.library.PreludeFileLibrary;
import org.arend.library.Library;
import org.arend.library.LibraryManager;
import org.arend.library.SourceLibrary;
import org.arend.module.scopeprovider.ModuleScopeProvider;
import org.arend.naming.reference.converter.IdReferableConverter;
import org.arend.prelude.Prelude;
import org.arend.typechecking.instance.provider.InstanceProviderSet;
import org.arend.typechecking.order.listener.TypecheckingOrderingListener;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BasicRunTest {
    private LibraryManager libraryManager;
    private Library preludeLibrary;
    private ModuleScopeProvider moduleScopeProvider;

    private final List<GeneralError> errorList = new ArrayList<>();
    private final ListErrorReporter errorReporter =
            new ListErrorReporter(errorList);
    private final TypecheckingOrderingListener typechecking =
            new TypecheckingOrderingListener(new InstanceProviderSet(),
                    ConcreteReferableProvider.INSTANCE,
                    IdReferableConverter.INSTANCE, errorReporter,
                    PositionComparator.INSTANCE, ref -> null);

    @Test public void testStartup() {
        LibraryManager libraryManager = new LibraryManager((lib, name) -> {
            throw new IllegalStateException();
        }, new InstanceProviderSet(),
                errorReporter,
                errorReporter,
                DefinitionRequester.INSTANCE, null);
        SourceLibrary preludeLibrary = new PreludeFileLibrary(null);
        preludeLibrary.addFlag(SourceLibrary.Flag.RECOMPILE);

        // NOTE (Dtw): loadLibrary does not initialize the consts from prelude
        //  in the InitialCC/Prelude class. You have to call typecheckLibrary.
        //  The static fields like InitialCC.ARRAY reference 'core' abstract syntax
        //  Definition objects
        //  (i.e.: typechecked definitions) -- loading doesn't do this
        //  (again, have to call typecheckLibrary on the succesfully loaded library)
        // Q: so what happens if I comment out the libMgr.loadLibrary(..) stmt
        //      and just call typeCheck library anyways?
        // A: it fails (scope will be uninitialized as the raw structure wasn't
        //      loaded into memory -- either from a file or a binary stream, e.g.,
        //      from an existing .arc file)
       // libraryManager.loadLibrary(preludeLibrary, null);

        new Prelude.PreludeTypechecking(new InstanceProviderSet(),
                ConcreteReferableProvider.INSTANCE,
                IdReferableConverter.INSTANCE,
                PositionComparator.INSTANCE).typecheckLibrary(preludeLibrary);

        Object x = Prelude.ARRAY;

        for (GeneralError err : errorReporter.getErrorList()) {
            Doc d = err.getDoc(PrettyPrinterConfig.DEFAULT);
            System.out.println(err);
        }
        //basicErrorReporter.errors().clear();
    }

}
