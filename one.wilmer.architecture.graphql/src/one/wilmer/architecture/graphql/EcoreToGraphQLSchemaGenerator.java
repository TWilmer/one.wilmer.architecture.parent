package one.wilmer.architecture.graphql;

import org.eclipse.emf.ecore.EPackage;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.FieldCoordinates;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLOutputType;
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
        
        GraphQLCodeRegistry.Builder codeRegistry = GraphQLCodeRegistry.newCodeRegistry();
        DataFetcher<Object> genericDataFetcher = new GenericEMFDataFetcher();

        DataFetcher<Object> rootDataFetcher = env -> {
            String fieldName = env.getField().getName();
            String targetName = fieldName.endsWith("s") ? fieldName.substring(0, fieldName.length() - 1) : fieldName;
            
            EClassifier requestedClassifier = ePackage.getEClassifiers().stream()
                .filter(c -> c.getName().equalsIgnoreCase(targetName) || c.getName().equalsIgnoreCase(fieldName))
                .findFirst()
                .orElse(null);
                
            if (requestedClassifier == null) {
                return null;
            }

            List<EObject> allObjects = modelLoader.getAllObjectsOfType(requestedClassifier);

            // Argument handling (ID/Name)
            Map<String, Object> arguments = env.getArguments();
            String identifier = null;
            if (arguments.containsKey("id") && arguments.get("id") != null) identifier = arguments.get("id").toString();
            else if (arguments.containsKey("name") && arguments.get("name") != null) identifier = arguments.get("name").toString();
            else if (arguments.containsKey("key") && arguments.get("key") != null) identifier = arguments.get("key").toString();

            if (identifier != null) {
                final String targetId = identifier;
                EObject matchedObject = allObjects.stream().filter(obj -> {
                    EStructuralFeature idFeature = obj.eClass().getEStructuralFeature("id");
                    if (idFeature == null) idFeature = obj.eClass().getEStructuralFeature("name");
                    if (idFeature == null) idFeature = obj.eClass().getEStructuralFeature("key");
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

        // Expose classes and Wire the fetchers dynamically
        for (EClassifier classifier : ePackage.getEClassifiers()) {
            if (classifier instanceof EClass eClass) {
                String baseName = eClass.getName().toLowerCase();
                GraphQLObjectType objectType = convertEClass(eClass, codeRegistry, genericDataFetcher);
                
                // Plural List Query
                queryBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(baseName + "s")
                    .type(new GraphQLList(objectType))
                    .build());
                codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", baseName + "s"), rootDataFetcher);

                // Singular Item Query
                queryBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(baseName)
                    .type(objectType)
                    .argument(GraphQLArgument.newArgument().name("id").type(Scalars.GraphQLString).build())
                    .argument(GraphQLArgument.newArgument().name("name").type(Scalars.GraphQLString).build())
                    .argument(GraphQLArgument.newArgument().name("key").type(Scalars.GraphQLString).build())
                    .build());
                codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", baseName), rootDataFetcher);
            }
        }

        return GraphQLSchema.newSchema()
            .query(queryBuilder.build())
            .codeRegistry(codeRegistry.build()) 
            .build();
    }
    

    private GraphQLObjectType convertEClass(EClass eClass, GraphQLCodeRegistry.Builder codeRegistry, DataFetcher<?> genericDataFetcher) {
        // 1. If it's already in the cache, return it (this handles cycles)
        if (cache.containsKey(eClass)) return (GraphQLObjectType) cache.get(eClass);

        // 2. Create the builder
        GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject().name(eClass.getName());
        
        // 3. IMPORTANT: Put a dummy/placeholder in the cache immediately to satisfy references
        // Note: You need to change the cache map to hold GraphQLObjectType.Builder or handle this carefully
        // To simplify: create the object, then populate it.
        
        // Let's perform the population:
        for (EAttribute attr : eClass.getEAllAttributes()) {
            typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(attr.getName())
                .type(mapToGraphQLScalar(attr.getEAttributeType()))
                .build());
            codeRegistry.dataFetcher(FieldCoordinates.coordinates(eClass.getName(), attr.getName()), genericDataFetcher);
        }

        for (EReference ref : eClass.getEAllReferences()) {
            // Use typeRef to handle the cycle
            GraphQLOutputType refType = GraphQLTypeReference.typeRef(ref.getEReferenceType().getName());
            
            typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(ref.getName())
                .type(ref.isMany() ? new GraphQLList(refType) : refType)
                .build());
            codeRegistry.dataFetcher(FieldCoordinates.coordinates(eClass.getName(), ref.getName()), genericDataFetcher);
        }

        GraphQLObjectType type = typeBuilder.build();
        cache.put(eClass, type); // Now store the fully built object
        return type;
    }

    private GraphQLScalarType mapToGraphQLScalar(EDataType dataType) {
        return switch (dataType.getInstanceTypeName()) {
            case "int", "java.lang.Integer" -> Scalars.GraphQLInt;
            case "boolean", "java.lang.Boolean" -> Scalars.GraphQLBoolean;
            default -> Scalars.GraphQLString; 
            // Note: Your Xtext Enum (AnEnum) will fall through to GraphQLString. 
            // The GraphQL engine will automatically call toString() on the EMF Enumerator, serializing it perfectly.
        };
    }
}