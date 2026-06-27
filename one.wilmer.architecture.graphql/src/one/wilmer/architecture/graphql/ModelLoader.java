package one.wilmer.architecture.graphql;

import com.google.inject.Injector;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.emf.common.util.URI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ModelLoader {

    private final XtextResourceSet resourceSet;

    public ModelLoader(Injector injector) {
        // Use the injector to get a perfectly configured Xtext ResourceSet
        this.resourceSet = injector.getInstance(XtextResourceSet.class);
    }

    /**
     * Loads all .aspec files from the directory and resolves cross-references.
     */
    public void loadAllModels(String rootDir) throws IOException {
        Path startPath = Paths.get(rootDir);

        try (Stream<Path> stream = Files.walk(startPath)) {
            stream.filter(Files::isRegularFile)
                  .filter(path -> path.toString().endsWith(".aspec"))
                  .forEach(path -> {
                      URI uri = URI.createFileURI(path.toAbsolutePath().toString());
                      resourceSet.getResource(uri, true);
                  });
        }
        
        // CRITICAL: Resolves all EMF proxies across the loaded resources
        EcoreUtil.resolveAll(resourceSet);
        
        
        
        System.out.println("Resources loaded: " + resourceSet.getResources().size());
    }

    /**
     * Generic query to find all EObjects matching a specific EClass.
     * This iterates through every loaded resource.
     */
    public List<EObject> getAllObjectsOfType(EClassifier classifier) {
        List<EObject> results = new ArrayList<>();
        
        if (!(classifier instanceof EClass eClass)) return results;

        for (Resource resource : resourceSet.getResources()) {
            var iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                // Check if the object is an instance of the requested EClass
                if (eClass.isInstance(obj)) {
                    results.add(obj);
                }
            }
        }
        return results;
    }
}