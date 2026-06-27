package one.wilmer.architecture.graphql;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.google.inject.Injector;

import one.wilmer.architecture.ArchSpecStandaloneSetup;
import one.wilmer.architecture.archSpec.Greeting;
import one.wilmer.architecture.archSpec.Model;
import com.sun.net.httpserver.HttpServer;

/**
 * This class controls all aspects of the application's execution
 */
public class GraphQLServer implements IApplication {
	final int PORT=9000;
	
	
	/**
     * Recursively finds all *.aspec files in the given directory.
     */
    public List<Path> findAllAspecFiles(String rootDirectoryPath) throws IOException {
        Path startPath = Paths.get(rootDirectoryPath);

        // The try-with-resources block is crucial here to ensure the stream 
        // (and underlying file handles) are closed properly.
        try (Stream<Path> stream = Files.walk(startPath)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".aspec"))
                .collect(Collectors.toList());
        }
    }
    private HttpServer server;
    private Injector injector;

	@Override
	public Object start(IApplicationContext context) throws Exception {
		
		// 1. Initialize Xtext and capture the Guice Injector
        // Do this exactly once before any model parsing happens.
        this.injector = new ArchSpecStandaloneSetup().createInjectorAndDoEMFRegistration();
        
        ModelLoader loader=new ModelLoader(injector);
        loader.loadAllModels("/home/thorsten/runtime-ArchIDE/test/");
        
        ArchitectureGraphQLEngine engine = new ArchitectureGraphQLEngine(loader);
        
        
     
      
        
        
     // 3. Boot the embedded HTTP Server
        System.out.println("Starting embedded GraphQL server on port "+PORT+"...");
         server = HttpServer.create(new InetSocketAddress(PORT), 0);
 
        
        
     // Inside GraphQLServer.start()
        server.createContext("/graphql", exchange -> {
            String method = exchange.getRequestMethod();
            String acceptHeader = exchange.getRequestHeaders().getFirst("Accept");

            if ("GET".equals(method) && acceptHeader != null && acceptHeader.contains("text/html")) {
                // SERVE SANDBOX
                Bundle bundle = FrameworkUtil.getBundle(GraphQLServer.class);
                URL url = bundle.getEntry("resources/sandbox.html");
                byte[] html = FileLocator.openStream(bundle, new org.eclipse.core.runtime.Path("resources/sandbox.html"), false).readAllBytes();
                
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, html.length);
                exchange.getResponseBody().write(html);
                exchange.close();
            } else {
                // SERVE GRAPHQL API (Delegate to your existing GraphQLHttpHandler)
                new GraphQLHttpHandler(engine).handle(exchange);
            }
        });
        
        
		
     // Use a basic thread pool to handle concurrent queries
        server.setExecutor(Executors.newFixedThreadPool(10)); 
        server.start();
        
        System.out.println("Server is running. Send POST requests to http://localhost:"+PORT+"/graphql");

        // Keep the OSGi application running
        synchronized (this) {
            wait();
        }
        
        
		return IApplication.EXIT_OK;
	}

	@Override
	public void stop() {
	    if (server != null) {
	        server.stop(0); // Gracefully shut down the port
	    }
	}
}
