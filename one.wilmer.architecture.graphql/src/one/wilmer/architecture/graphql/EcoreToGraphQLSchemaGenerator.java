package one.wilmer.architecture.graphql;

import org.eclipse.emf.ecore.EPackage;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.FieldCoordinates;
import graphql.schema.DataFetcher;
import graphql.Scalars;
import graphql.schema.*;
import org.eclipse.emf.ecore.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class EcoreToGraphQLSchemaGenerator {

    private final Map<EClass, GraphQLObjectType> cache = new HashMap<>();

    public GraphQLSchema generate(EPackage ePackage, ModelLoader modelLoader) {
        GraphQLObjectType.Builder queryBuilder = GraphQLObjectType.newObject().name("Query");
        
        // The Code Registry is the programmatic alternative to RuntimeWiring
        GraphQLCodeRegistry.Builder codeRegistry = GraphQLCodeRegistry.newCodeRegistry();

        // 1. Initialize our Generic Fetcher
        DataFetcher<Object> genericDataFetcher = new GenericEMFDataFetcher();

        // 2. Define the Root Fetcher (Moved from the Engine file)
        DataFetcher<Object> rootDataFetcher = env -> {
            String fieldName = env.getField().getName();
            System.out.println("-> [GraphQL Engine] Root query received for field: '" + fieldName + "'");
            
            String targetName = fieldName.endsWith("s") ? fieldName.substring(0, fieldName.length() - 1) : fieldName;
            
            EClassifier requestedClassifier = ePackage.getEClassifiers().stream()
                .filter(c -> c.getName().equalsIgnoreCase(targetName) || c.getName().equalsIgnoreCase(fieldName))
                .findFirst()
                .orElse(null);
                
            if (requestedClassifier == null) {
                System.out.println("-> [GraphQL Engine] ERROR: Failed to find EClass '" + targetName + "'");
                return null;
            }

            System.out.println("-> [GraphQL Engine] Matched GraphQL field to EMF EClass: '" + requestedClassifier.getName() + "'");
            List<EObject> allObjects = modelLoader.getAllObjectsOfType(requestedClassifier);
            System.out.println("-> [GraphQL Engine] ModelLoader returned " + allObjects.size() + " instances.");

            // Argument handling (ID/Name)
            Map<String, Object> arguments = env.getArguments();
            String identifier = null;
            if (arguments.containsKey("id") && arguments.get("id") != null) identifier = arguments.get("id").toString();
            else if (arguments.containsKey("name") && arguments.get("name") != null) identifier = arguments.get("name").toString();

            if (identifier != null) {
                final String targetId = identifier;
                EObject matchedObject = allObjects.stream().filter(obj -> {
                    EStructuralFeature idFeature = obj.eClass().getEStructuralFeature("id");
                    if (idFeature == null) idFeature = obj.eClass().getEStructuralFeature("name");
                    if (idFeature != null) {
                        Object val = obj.eGet(idFeature);
                        return val != null && val.toString().equals(targetId);
                    }
                    return false;
                }).findFirst().orElse(null);
                
                return fieldName.endsWith("s") ? 
                    (matchedObject != null ? List.of(matchedObject) : List.of()) : matchedObject;
            }
            return allObjects;
        };

        // 3. Expose classes and Wire the fetchers dynamically
        for (EClassifier classifier : ePackage.getEClassifiers()) {
            if (classifier instanceof EClass eClass) {
                String baseName = eClass.getName().toLowerCase();
                
                // Plural List Query
                queryBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(baseName + "s")
                    .type(new GraphQLList(convertEClass(eClass, codeRegistry, genericDataFetcher)))
                    .build());
                // Bind the root fetcher
                codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", baseName + "s"), rootDataFetcher);

                // Singular Item Query
                queryBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(baseName)
                    .type(convertEClass(eClass, codeRegistry, genericDataFetcher))
                    .argument(GraphQLArgument.newArgument().name("id").type(Scalars.GraphQLString).build())
                    .argument(GraphQLArgument.newArgument().name("name").type(Scalars.GraphQLString).build())
                    .build());
                // Bind the root fetcher
                codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", baseName), rootDataFetcher);
            }
        }

        // 4. Build Schema WITH the Code Registry attached!
        return GraphQLSchema.newSchema()
            .query(queryBuilder.build())
            .codeRegistry(codeRegistry.build()) 
            .build();
    }

    private GraphQLObjectType convertEClass(EClass eClass, GraphQLCodeRegistry.Builder codeRegistry, DataFetcher<?> genericDataFetcher) {
        if (cache.containsKey(eClass)) return cache.get(eClass);

        GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject().name(eClass.getName());

        for (EAttribute attr : eClass.getEAllAttributes()) {
            typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(attr.getName())
                .type(mapToGraphQLScalar(attr.getEAttributeType()))
                .build());
            // Bind the generic EMF fetcher to this specific attribute
            codeRegistry.dataFetcher(FieldCoordinates.coordinates(eClass.getName(), attr.getName()), genericDataFetcher);
        }

        for (EReference ref : eClass.getEAllContainments()) {
            typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(ref.getName())
                .type(ref.isMany() ? new GraphQLList(convertEClass(ref.getEReferenceType(), codeRegistry, genericDataFetcher)) 
                                  : convertEClass(ref.getEReferenceType(), codeRegistry, genericDataFetcher))
                .build());
            // Bind the generic EMF fetcher to this specific reference
            codeRegistry.dataFetcher(FieldCoordinates.coordinates(eClass.getName(), ref.getName()), genericDataFetcher);
        }

        GraphQLObjectType type = typeBuilder.build();
        cache.put(eClass, type);
        return type;
    }

    private GraphQLScalarType mapToGraphQLScalar(EDataType dataType) {
        return switch (dataType.getInstanceTypeName()) {
            case "int", "java.lang.Integer" -> Scalars.GraphQLInt;
            case "boolean", "java.lang.Boolean" -> Scalars.GraphQLBoolean;
            default -> Scalars.GraphQLString;
        };
    }
}